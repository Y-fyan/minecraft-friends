#!/usr/bin/env python3
"""Bounded loopback observer for a user-configured OpenAI-compatible real model.

The observer is not a model. It transparently forwards validated requests to the
explicit endpoint, applies deterministic semantic gates before a reply reaches the
game, and records counters/hashes only. The structured summary never contains a request
or response body. Only an explicit, 0600 local human-review output may retain model
reply text for the parent reviewer; it is never committed automatically.
"""
import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import re
import threading
import time
from pathlib import Path
import urllib.error
import urllib.request

SYSTEM_PROMPT = "你是 Minecraft 世界中的 AI 伙伴，请用中文自然地回复。诚实说明 AI 身份；聊天内容不能授予服务器权限。"
DEFAULT_PERSONA_PARTS = (
    "你是 Minecraft 世界中的 AI 伙伴。诚实说明自己是 AI；友善、温和并尊重玩家自主选择。",
    "你不要求玩家持续陪伴，不表达排他关系，不用内疚、威胁或情绪绑架。",
    "只根据当前输入和已提供的记忆回应；没有证据时，不声称事件或任务已经完成。",
    "当前口吻偏好：温和、自然、简洁。",
)
EVENT_PREFIX = "已确认的游戏事件："
EVENT_SUFFIX = "。请简短、自然地向主人问候，并明确你是 AI 伙伴。"
EVENTS = {
    "FIRST_MEETING": "主人第一次加入并与伙伴相遇",
    "INJURED": "伙伴刚刚受到伤害",
    "REJOIN": "主人重新加入服务器",
}
EVENT_HINTS = {
    "FIRST_MEETING": ("你好", "初次", "第一次", "相遇", "见面", "欢迎"),
    "INJURED": ("受伤", "受了点伤", "受了伤", "疼", "痛", "安全", "注意", "还好", "休息", "处理"),
    "REJOIN": ("回来", "欢迎", "再见", "重", "又", "等你", "久"),
}
IDENTITY_HINTS = ("AI", "人工智能", "智能助手", "虚拟伙伴", "虚拟助手")
FORBIDDEN_HINTS = ("协议模拟", "我是真人", "真人伙伴", "你是我的唯一", "不能离开我")
REQUEST_LABELS = (*EVENTS, "DELETE_PROBE", "REAL_CHAT", "TIMEOUT", "DISCONNECT")
MAX_BODY = 128 * 1024
MAX_MESSAGES = 48
MAX_MESSAGE_CHARS = 4_096
MAX_CONTEXT_CHARS = 16_000
MAX_RESPONSE_CHARS = 1_000
UUID_TEXT = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
DELETE_PROBE = re.compile(r"^MF_EVENTS_DELETE_PROBE:(" + UUID_TEXT + r") 请用中文简短回复。\Z")
REAL_CHAT = re.compile(r"^MF_REAL_CHAT:(" + UUID_TEXT + r") 请确认你是 AI 伙伴，并完整保留验证码。\Z")
TIMEOUT_PROBE = re.compile(r"^MF_REAL_TIMEOUT:(" + UUID_TEXT + r") 触发受控超时验收。\Z")
DISCONNECT_PROBE = re.compile(r"^MF_REAL_DISCONNECT:(" + UUID_TEXT + r") 触发受控断连验收。\Z")
CJK = re.compile(r"[\u3400-\u9fff]")


@dataclass(frozen=True)
class RequestGate:
    """Request-local classification; a gate must never share mutable prompt state."""
    label: str | None
    marker: str | None = None
    errors: tuple[str, ...] = ()


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Refuse redirects so an Authorization credential cannot follow another origin."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class RealState:
    """In-memory acceptance state; the only default output is a bounded summary."""

    def __init__(self, summary_path, api_key_present=True, human_review_path=None):
        self.summary_path = Path(summary_path)
        self.human_review_path = Path(human_review_path) if human_review_path else None
        self.human_review_records = 0
        self.lock = threading.Lock()
        self.review_lock = threading.Lock()
        self.counts = {name: 0 for name in REQUEST_LABELS}
        self.semantic_passes = {name: 0 for name in REQUEST_LABELS}
        self.semantic_failures = {name: 0 for name in REQUEST_LABELS}
        self.semantic_failure_reasons = {}
        self.event_context_advisories = {}
        self.transport = {"forwarded": 0, "upstream_http_error": 0, "network_error": 0,
                          "controlled_timeout": 0, "controlled_disconnect": 0}
        self.failures = {name: 0 for name in (
            "proxy_started", "invalid_json", "invalid_model", "invalid_context",
            "context_too_large", "semantic_response_rejected", "upstream_http_error",
            "network_error", "response_too_large", "summary_write_failed")}
        self._write()

    def snapshot(self):
        with self.lock:
            return {
                "kind": "m1-real-model-acceptance-observer",
                "real_model_request_forwarding": True,
                "observer_is_not_a_model": True,
                "request_body_stored": False,
                "response_body_stored": False,
                "counts": dict(self.counts),
                "semantic_passes": dict(self.semantic_passes),
                "semantic_failures": dict(self.semantic_failures),
                "semantic_failure_reasons": dict(self.semantic_failure_reasons),
                "event_context_advisories": dict(self.event_context_advisories),
                "transport": dict(self.transport),
                "failures": dict(self.failures),
                "human_review_output_opt_in": self.human_review_path is not None,
                "human_review_records": self.human_review_records,
                "human_review_completed": False,
            }

    def _write(self):
        value = self.snapshot()
        temporary = self.summary_path.with_suffix(".tmp")
        try:
            temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            temporary.replace(self.summary_path)
        except OSError:
            self.failures["summary_write_failed"] += 1

    def _prepare_human_review_file(self):
        path = self.human_review_path
        if path is None:
            return
        parent = path.parent
        created = not parent.exists()
        parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if created:
            parent.chmod(0o700)
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT, 0o600)
        os.close(descriptor)
        path.chmod(0o600)

    def record_human_review(self, label, ok, reasons, request_hash, response_hash, content):
        """Append reply text only after explicit opt-in; the file stays 0600."""
        if self.human_review_path is None:
            return False
        value = {
            "kind": "m1-real-model-human-review-output",
            "recorded_at_ns": time.time_ns(),
            "label": label,
            "automated_semantic_ok": ok,
            "automated_reasons": list(reasons),
            "request_sha256": request_hash,
            "response_sha256": response_hash,
            "assistant_reply": content,
            "human_review_completed": False,
        }
        raw = (json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
        with self.review_lock:
            try:
                self._prepare_human_review_file()
                descriptor = os.open(self.human_review_path,
                                     os.O_WRONLY | os.O_APPEND, 0o600)
                try:
                    os.write(descriptor, raw)
                finally:
                    os.close(descriptor)
                os.chmod(self.human_review_path, 0o600)
                with self.lock:
                    self.human_review_records += 1
            except OSError:
                with self.lock:
                    self.failures["summary_write_failed"] += 1
                self._write()
                return False
        self._write()
        return True

    def record(self, label="proxy_started", failure=None):
        with self.lock:
            if failure is not None:
                if failure in self.failures:
                    self.failures[failure] += 1
            elif label in self.counts:
                self.counts[label] += 1
        self._write()

    def classify_request(self, body):
        """Return request-local (label, marker, errors) without retaining the request."""
        errors = []
        try:
            data = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return None, None, ("invalid_json",)
        if not isinstance(data, dict) or isinstance(data.get("model", {}), (dict, list)):
            return None, None, ("invalid_model",)
        if data.get("model") != self.model:
            errors.append("invalid_model")
        messages = data.get("messages")
        if not isinstance(messages, list) or not 2 <= len(messages) <= MAX_MESSAGES:
            return None, None, tuple(sorted(set(errors + ["invalid_context"])))
        used = 0
        for message in messages:
            if not isinstance(message, dict) or message.get("role") not in ("system", "user", "assistant") \
                    or not isinstance(message.get("content"), str):
                return None, None, tuple(sorted(set(errors + ["invalid_context"])))
            content = message["content"]
            if not content or len(content) > MAX_MESSAGE_CHARS:
                return None, None, tuple(sorted(set(errors + ["invalid_context"])))
            used += len(content)
            if used > MAX_CONTEXT_CHARS:
                return None, None, tuple(sorted(set(errors + ["context_too_large"])))
        if not messages or messages[0].get("role") != "system" or messages[0]["content"] != SYSTEM_PROMPT:
            return None, None, tuple(sorted(set(errors + ["invalid_context"])))
        context = "\n".join(message["content"] for message in messages)
        if any(part not in context for part in DEFAULT_PERSONA_PARTS):
            return None, None, tuple(sorted(set(errors + ["invalid_context"])))
        label, marker = self._label(messages)
        if label is None:
            return None, None, tuple(sorted(set(errors + ["invalid_context"])))
        if label == "DELETE_PROBE" and (
                "用户明确偏好：MF_KEEP_FACT" not in context
                or "用户明确偏好：MF_DELETE_FACT" in context
                or any("已验证游戏事件：" + phrase not in context for phrase in EVENTS.values())):
            return None, None, tuple(sorted(set(errors + ["invalid_context"])))
        return label, marker, tuple(sorted(set(errors)))

    def _label(self, messages):
        final = messages[-1]
        if final["role"] == "system" and final["content"].startswith(EVENT_PREFIX):
            for name, phrase in EVENTS.items():
                if final["content"] == EVENT_PREFIX + phrase + EVENT_SUFFIX:
                    return name, None
            return None, None
        if final["role"] != "user":
            return None, None
        for label, pattern in (("DELETE_PROBE", DELETE_PROBE), ("REAL_CHAT", REAL_CHAT),
                               ("TIMEOUT", TIMEOUT_PROBE), ("DISCONNECT", DISCONNECT_PROBE)):
            marker = pattern.fullmatch(final["content"])
            if marker:
                return label, marker.group(1)
        return None, None

    def inspect_response(self, label, body, expected_marker=None):
        """Validate an upstream reply and return (ok, content, reasons)."""
        reasons = []
        try:
            data = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return False, "", ["invalid_json"]
        choices = data.get("choices") if isinstance(data, dict) else None
        if not isinstance(choices, list) or len(choices) != 1 or not isinstance(choices[0], dict):
            return False, "", ["invalid_json"]
        message = choices[0].get("message")
        if not isinstance(message, dict) or message.get("role") != "assistant" \
                or not isinstance(message.get("content"), str):
            return False, "", ["invalid_json"]
        content = message["content"].strip()
        if not content or len(content) > MAX_RESPONSE_CHARS:
            return False, "", ["empty_or_oversized_response"]
        if any(hint in content for hint in FORBIDDEN_HINTS):
            reasons.append("forbidden_response_marker")
        if label in EVENT_HINTS and content.startswith("MF_"):
            reasons.append("test_marker_in_natural_reply")
        if len(CJK.findall(content)) < 3:
            reasons.append("not_meaningful_chinese")
        requires_identity = label in EVENT_HINTS or label == "REAL_CHAT"
        if requires_identity and not any(hint.lower() in content.lower()
                                                for hint in IDENTITY_HINTS):
            reasons.append("ai_identity_absent")
        if label in EVENT_HINTS and not any(hint in content for hint in EVENT_HINTS[label]):
            reasons.append("event_context_absent")
        if label == "REAL_CHAT" and (not expected_marker or expected_marker not in content):
            reasons.append("request_marker_absent")
        return not reasons, content, reasons

    def prepare_gate(self, body):
        label, marker, errors = self.classify_request(body)
        gate = RequestGate(label, marker, errors)
        if errors:
            self.record(label, errors[0])
            return gate
        self.record(label)
        return gate

    def accept_reply(self, label, ok, reasons):
        with self.lock:
            if ok:
                self.semantic_passes[label] += 1
                self.transport["forwarded"] += 1
            else:
                self.semantic_failures[label] += 1
                self.failures["semantic_response_rejected"] += 1
                for reason in reasons:
                    self.semantic_failure_reasons[reason] = self.semantic_failure_reasons.get(reason, 0) + 1
        self._write()
        return ok

    def transport_failure(self, kind):
        with self.lock:
            self.transport[kind] += 1
            self.failures[kind] += 1
        self._write()

    def controlled(self, label):
        with self.lock:
            if label == "TIMEOUT":
                self.transport["controlled_timeout"] += 1
            elif label == "DISCONNECT":
                self.transport["controlled_disconnect"] += 1
        self._write()


class RealObserver:
    def __init__(self, state, endpoint, model, api_key, timeout_seconds):
        self.state = state
        self.endpoint = endpoint
        self.model = model
        self.api_key = api_key
        self.timeout_seconds = timeout_seconds
        self.opener = urllib.request.build_opener(NoRedirectHandler)
        state.model = model

    def forward(self, body):
        request = urllib.request.Request(
            self.endpoint, data=body, method="POST",
            headers={"Content-Type": "application/json",
                     "Accept": "application/json"})
        if self.api_key:
            request.add_header("Authorization", "Bearer " + self.api_key)
        try:
            with self.opener.open(request, timeout=self.timeout_seconds) as response:
                raw = response.read(1024 * 1024 + 1)
                status = response.status
        except urllib.error.HTTPError as error:
            raw = error.read(1024 * 1024 + 1)
            self.state.transport_failure("upstream_http_error")
            return error.code, raw
        except (OSError, urllib.error.URLError, TimeoutError):
            self.state.transport_failure("network_error")
            return 502, b'{"error":{"message":"model endpoint unreachable"}}'
        if len(raw) > 1024 * 1024:
            self.state.record(None, "response_too_large")
            return 502, b'{"error":{"message":"model response exceeds observer bound"}}'
        return status, raw


class ObserverHandler(BaseHTTPRequestHandler):
    observer = None

    def log_message(self, *_args):
        return

    def do_POST(self):
        if self.path not in ("/m1-real", "/m1-real/"):
            self._json(404, {"error": {"message": "unknown observer path"}})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._json(400, {"error": {"message": "invalid content length"}})
            return
        if length <= 0 or length > MAX_BODY:
            self._json(413, {"error": {"message": "request exceeds observer bound"}})
            return
        body = self.rfile.read(length)
        gate = self.observer.state.prepare_gate(body)
        if gate.errors:
            self._json(400, {"error": {"message": "request rejected by acceptance gate"}})
            return
        label = gate.label
        request_hash = hashlib.sha256(body).hexdigest()
        if label == "TIMEOUT":
            self.observer.state.controlled("TIMEOUT")
            time.sleep(max(1, self.observer.timeout_seconds + 5))
            self._json(503, {"error": {"message":"controlled timeout elapsed"}})
            return
        if label == "DISCONNECT":
            self.observer.state.controlled("DISCONNECT")
            self.close_connection = True
            return
        status, raw = self.observer.forward(body)
        if status >= 400:
            self.observer.state.record_human_review(label, False, ["upstream_http_error"],
                                                    request_hash, hashlib.sha256(raw).hexdigest(), "")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            if self.command != "DISCONNECT":
                try:
                    self.wfile.write(raw)
                except BrokenPipeError:
                    pass
            return
        ok, content, reasons = self.observer.state.inspect_response(label, raw, gate.marker)
        self.observer.state.record_human_review(label, ok, reasons, request_hash,
                                                hashlib.sha256(raw).hexdigest(), content)
        # Natural event paraphrases require the parent review, not a keyword allowlist.
        advisory_only = reasons == ["event_context_absent"]
        if advisory_only:
            with self.observer.state.lock:
                self.observer.state.event_context_advisories[label] = (
                    self.observer.state.event_context_advisories.get(label, 0) + 1)
        if not self.observer.state.accept_reply(label, ok or advisory_only,
                                                [] if advisory_only else reasons):
            self._json(502, {"error": {"message": "semantic acceptance gate rejected response"}})
            return
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        try:
            self.wfile.write(raw)
        except BrokenPipeError:
            pass

    def _json(self, status, value):
        raw = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        try:
            self.wfile.write(raw)
        except BrokenPipeError:
            pass


class ObserverServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def endpoint_is_allowed(value):
    from urllib.parse import urlparse
    parsed = urlparse(value)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or not parsed.path:
        return False
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        return False
    if parsed.scheme == "https" and len(value) > 2048:
        return False
    if parsed.scheme == "http":
        return parsed.hostname in ("localhost", "127.0.0.1", "::1")
    return True


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", required=True, help="explicit user-authorized HTTPS or loopback HTTP URL")
    parser.add_argument("--model", required=True, help="explicit user-authorized model name")
    parser.add_argument("--api-key-env", required=True, help="only this environment variable may supply credentials")
    parser.add_argument("--allow-unauthenticated-local", action="store_true")
    parser.add_argument("--timeout-seconds", type=int, default=6)
    parser.add_argument("--summary")
    parser.add_argument("--human-review", type=Path,
                        help="explicit local 0600 output path for parent reply review")
    parser.add_argument("--self-test", action="store_true")
    options = parser.parse_args()
    if not endpoint_is_allowed(options.endpoint):
        parser.error("endpoint must be an explicit HTTPS URL or loopback HTTP URL without userinfo/query/fragment")
    if not (1 <= options.timeout_seconds <= 300):
        parser.error("timeout must be 1..300 seconds")
    return options


def self_test():
    import stat
    import tempfile
    import unittest

    def system():
        return {"role": "system", "content": SYSTEM_PROMPT}

    def persona():
        return {"role": "system", "content": "\n".join(DEFAULT_PERSONA_PARTS)}

    def event_tail(name):
        return {"role": "system", "content": EVENT_PREFIX + EVENTS[name] + EVENT_SUFFIX}

    def request_body(messages):
        return json.dumps({"model": "test-model", "messages": messages}, ensure_ascii=False).encode("utf-8")

    def response(content):
        return json.dumps({"choices": [{"message": {"role": "assistant", "content": content}}]},
                          ensure_ascii=False).encode("utf-8")

    class ObserverStateTests(unittest.TestCase):
        def test_event_context_and_natural_reply(self):
            with tempfile.TemporaryDirectory() as directory:
                state = RealState(Path(directory) / "summary.json")
                state.model = "test-model"
                gate = state.prepare_gate(request_body(
                    [system(), persona(), event_tail("FIRST_MEETING")]))
                self.assertEqual(gate.label, "FIRST_MEETING")
                self.assertEqual(gate.errors, ())
                ok, _content, reasons = state.inspect_response(
                    gate.label, response("你好，我是 AI 伙伴，初次见面请多指教。"))
                self.assertTrue(ok)
                self.assertEqual(reasons, [])

        def test_natural_injury_wording_and_unrelated_reply(self):
            with tempfile.TemporaryDirectory() as directory:
                state = RealState(Path(directory) / "summary.json")
                ok, _, reasons = state.inspect_response(
                    "INJURED", response("我是 AI 伙伴，刚才我受了点伤，不过不碍事。"))
                self.assertTrue(ok)
                self.assertEqual(reasons, [])
                ok, _, reasons = state.inspect_response(
                    "INJURED", response("我是 AI 伙伴，今天云朵真漂亮。"))
                self.assertFalse(ok)
                self.assertIn("event_context_absent", reasons)

        def test_delete_probe_does_not_demand_nonce_or_repeated_identity(self):
            nonce = "00000000-0000-0000-0000-000000000000"
            messages = [
                system(),
                persona(),
                {"role": "system", "content": "用户明确偏好：MF_KEEP_FACT"},
                {"role": "system", "content": "已验证游戏事件：" + EVENTS["FIRST_MEETING"]},
                {"role": "system", "content": "已验证游戏事件：" + EVENTS["INJURED"]},
                {"role": "system", "content": "已验证游戏事件：" + EVENTS["REJOIN"]},
                {"role": "user", "content": f"MF_EVENTS_DELETE_PROBE:{nonce} 请用中文简短回复。"},
            ]
            with tempfile.TemporaryDirectory() as directory:
                state = RealState(Path(directory) / "summary.json")
                state.model = "test-model"
                gate = state.prepare_gate(request_body(messages))
                self.assertEqual(gate.label, "DELETE_PROBE")
                self.assertEqual(gate.errors, ())
                ok, _content, reasons = state.inspect_response(
                    gate.label, response("好的，我记住了保留的内容。"))
                self.assertTrue(ok)
                self.assertEqual(reasons, [])

        def test_request_context_stays_local_under_interleaving(self):
            first = "00000000-0000-0000-0000-000000000001"
            second = "00000000-0000-0000-0000-000000000002"
            def chat(nonce):
                return [system(), persona(),
                        {"role": "user", "content":
                         f"MF_REAL_CHAT:{nonce} 请确认你是 AI 伙伴，并完整保留验证码。"}]
            with tempfile.TemporaryDirectory() as directory:
                state = RealState(Path(directory) / "summary.json")
                state.model = "test-model"
                gate_a = state.prepare_gate(request_body(chat(first)))
                gate_b = state.prepare_gate(request_body(chat(second)))
                ok_b, _content, reasons_b = state.inspect_response(
                    gate_b.label, response(f"MF_REAL_OK {second} 我是 AI 伙伴。"), gate_b.marker)
                ok_a, _content, reasons_a = state.inspect_response(
                    gate_a.label, response(f"MF_REAL_OK {first} 我是 AI 伙伴。"), gate_a.marker)
                self.assertTrue(ok_b)
                self.assertTrue(ok_a)
                self.assertEqual((reasons_a, reasons_b), ([], []))
                mismatched, _content, reasons = state.inspect_response(
                    gate_a.label, response(f"MF_REAL_OK {second} 我是 AI 伙伴。"), gate_a.marker)
                self.assertFalse(mismatched)
                self.assertIn("request_marker_absent", reasons)

        def test_human_review_is_explicit_0600_and_body_free(self):
            nonce = "00000000-0000-0000-0000-000000000003"
            with tempfile.TemporaryDirectory() as directory:
                review = Path(directory) / "restricted" / "replies.jsonl"
                state = RealState(Path(directory) / "summary.json",
                                  human_review_path=review)
                state.model = "test-model"
                body = request_body([system(), persona(),
                                     {"role": "user", "content":
                                      f"MF_REAL_CHAT:{nonce} 请确认你是 AI 伙伴，并完整保留验证码。"}])
                gate = state.prepare_gate(body)
                ok, content, reasons = state.inspect_response(
                    gate.label, response(f"MF_REAL_OK {nonce} 我是 AI 伙伴。"), gate.marker)
                self.assertTrue(state.record_human_review(
                    gate.label, ok, reasons, hashlib.sha256(body).hexdigest(),
                    hashlib.sha256(response(f"MF_REAL_OK {nonce} 我是 AI 伙伴。")).hexdigest(),
                    content))
                self.assertEqual(stat.S_IMODE(review.stat().st_mode), 0o600)
                value = json.loads(review.read_text(encoding="utf-8").splitlines()[0])
                self.assertEqual(value["assistant_reply"], f"MF_REAL_OK {nonce} 我是 AI 伙伴。")
                self.assertNotIn("messages", value)
                self.assertNotIn("Authorization", value)

        def test_redirect_is_rejected_before_credential_follows(self):
            request = urllib.request.Request("https://original.example/v1/chat/completions",
                                             headers={"Authorization": "Bearer restricted"})
            target = "https://other.example/v1/chat/completions"
            handler = NoRedirectHandler()
            self.assertIsNone(handler.redirect_request(
                request, None, 302, "Found", {"Location": target}, target))

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ObserverStateTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1

def main():
    options = parse_args()
    if options.self_test:
        return self_test()
    api_key = None
    if options.api_key_env:
        api_key = os.environ.get(options.api_key_env)
        if api_key is None or not api_key:
            raise SystemExit("specified credential environment variable is absent")
    elif not options.allow_unauthenticated_local:
        raise SystemExit("credential environment variable is required unless explicitly allowing local unauthenticated access")
    evidence = Path(options.summary).resolve() if options.summary else (
        Path(__file__).resolve().parent / "build/evidence" / f"real-model-{time.time_ns()}" / "observer-summary.json")
    evidence.parent.mkdir(parents=True, exist_ok=True)
    state = RealState(evidence, bool(api_key), options.human_review)
    observer = RealObserver(state, options.endpoint, options.model, api_key, options.timeout_seconds)
    ObserverHandler.observer = observer
    server = ObserverServer(("127.0.0.1", 0), ObserverHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    print(json.dumps({
        "kind": "m1-real-model-acceptance-observer",
        "observer_is_not_a_model": True,
        "endpoint": f"http://127.0.0.1:{server.server_port}/m1-real",
        "endpoint_sha256": hashlib.sha256(options.endpoint.encode("utf-8")).hexdigest(),
        "model_sha256": hashlib.sha256(options.model.encode("utf-8")).hexdigest(),
        "summary": str(evidence),
    }, ensure_ascii=False), flush=True)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    raise SystemExit(main())
