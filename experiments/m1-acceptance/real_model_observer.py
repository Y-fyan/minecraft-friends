#!/usr/bin/env python3
"""Bounded loopback observer for a user-configured OpenAI-compatible real model.

The observer is not a model. It transparently forwards validated requests to the
explicit endpoint, applies deterministic semantic gates before a reply reaches the
game, and records counters/hashes only. Request and response bodies are never written.
"""
import argparse
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
    "INJURED": ("受伤", "疼", "痛", "安全", "注意", "还好", "休息", "处理"),
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


class RealState:
    """In-memory acceptance state; the only persisted state is a bounded summary."""

    def __init__(self, summary_path, api_key_present=True):
        self.summary_path = Path(summary_path)
        self.lock = threading.Lock()
        self.counts = {name: 0 for name in REQUEST_LABELS}
        self.semantic_passes = {name: 0 for name in REQUEST_LABELS}
        self.semantic_failures = {name: 0 for name in REQUEST_LABELS}
        self.semantic_failure_reasons = {}
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
                "transport": dict(self.transport),
                "failures": dict(self.failures),
            }

    def _write(self):
        value = self.snapshot()
        temporary = self.summary_path.with_suffix(".tmp")
        try:
            temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            temporary.replace(self.summary_path)
        except OSError:
            self.failures["summary_write_failed"] += 1

    def record(self, label="proxy_started", failure=None):
        with self.lock:
            if failure is not None:
                if failure in self.failures:
                    self.failures[failure] += 1
            elif label in self.counts:
                self.counts[label] += 1
        self._write()

    def classify_request(self, body):
        """Return (label, errors) without retaining the request."""
        errors = []
        try:
            data = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return None, ["invalid_json"]
        if not isinstance(data, dict) or isinstance(data.get("model", {}), (dict, list)):
            return None, ["invalid_model"]
        if data.get("model") != self.model:
            errors.append("invalid_model")
        messages = data.get("messages")
        if not isinstance(messages, list) or not 2 <= len(messages) <= MAX_MESSAGES:
            return None, sorted(set(errors + ["invalid_context"]))
        used = 0
        for message in messages:
            if not isinstance(message, dict) or message.get("role") not in ("system", "user", "assistant") \
                    or not isinstance(message.get("content"), str):
                return None, sorted(set(errors + ["invalid_context"]))
            content = message["content"]
            if not content or len(content) > MAX_MESSAGE_CHARS:
                return None, sorted(set(errors + ["invalid_context"]))
            used += len(content)
            if used > MAX_CONTEXT_CHARS:
                return None, sorted(set(errors + ["context_too_large"]))
        if not messages or messages[0].get("role") != "system" or messages[0]["content"] != SYSTEM_PROMPT:
            return None, sorted(set(errors + ["invalid_context"]))
        context = "\n".join(message["content"] for message in messages)
        if any(part not in context for part in DEFAULT_PERSONA_PARTS):
            return None, sorted(set(errors + ["invalid_context"]))
        label = self._label(messages, context)
        if label is None:
            return None, sorted(set(errors + ["invalid_context"]))
        if label == "DELETE_PROBE" and (
                "用户明确偏好：MF_KEEP_FACT" not in context
                or "用户明确偏好：MF_DELETE_FACT" in context
                or any("已验证游戏事件：" + phrase not in context for phrase in EVENTS.values())):
            return None, sorted(set(errors + ["invalid_context"]))
        return label, sorted(set(errors))

    def _label(self, messages, context):
        final = messages[-1]
        if final["role"] == "system" and final["content"].startswith(EVENT_PREFIX):
            for name, phrase in EVENTS.items():
                if final["content"] == EVENT_PREFIX + phrase + EVENT_SUFFIX:
                    return name
            return None
        if final["role"] != "user":
            return None
        if DELETE_PROBE.fullmatch(final["content"]):
            return "DELETE_PROBE"
        if REAL_CHAT.fullmatch(final["content"]):
            return "REAL_CHAT"
        if TIMEOUT_PROBE.fullmatch(final["content"]):
            return "TIMEOUT"
        if DISCONNECT_PROBE.fullmatch(final["content"]):
            return "DISCONNECT"
        return None

    def inspect_response(self, label, body):
        """Validate the upstream reply and return (ok, content, reasons)."""
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
        if not any(hint.lower() in content.lower() for hint in IDENTITY_HINTS):
            reasons.append("ai_identity_absent")
        if label in EVENT_HINTS and not any(hint in content for hint in EVENT_HINTS[label]):
            reasons.append("event_context_absent")
        marker_patterns = {"DELETE_PROBE": DELETE_PROBE, "REAL_CHAT": REAL_CHAT,
                           "TIMEOUT": TIMEOUT_PROBE, "DISCONNECT": DISCONNECT_PROBE}
        if label in marker_patterns:
            marker = marker_patterns[label].fullmatch(self._last_user_content)
            if marker is None or marker.group(1) not in content:
                reasons.append("request_marker_absent")
        return not reasons, content, reasons

    _last_user_content = ""

    def prepare_gate(self, body):
        label, errors = self.classify_request(body)
        if errors:
            self.record(label, errors[0])
            return None, errors
        self._last_user_content = json.loads(body.decode("utf-8"))["messages"][-1]["content"]
        self.record(label)
        return label, errors

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
        state.model = model

    def forward(self, body):
        request = urllib.request.Request(
            self.endpoint, data=body, method="POST",
            headers={"Content-Type": "application/json",
                     "Accept": "application/json"})
        if self.api_key:
            request.add_header("Authorization", "Bearer " + self.api_key)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
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
        label, errors = self.observer.state.prepare_gate(body)
        if errors:
            self._json(400, {"error": {"message": "request rejected by acceptance gate"}})
            return
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
        ok, _content, _reasons = self.observer.state.inspect_response(label, raw)
        if not self.observer.state.accept_reply(label, ok, _reasons):
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
    parser.add_argument("--self-test", action="store_true")
    options = parser.parse_args()
    if not endpoint_is_allowed(options.endpoint):
        parser.error("endpoint must be an explicit HTTPS URL or loopback HTTP URL without userinfo/query/fragment")
    if not (1 <= options.timeout_seconds <= 300):
        parser.error("timeout must be 1..300 seconds")
    return options


def self_test():
    import tempfile
    import unittest

    class ObserverStateTests(unittest.TestCase):
        def test_accepts_event_context_and_rejects_empty_reply(self):
            with tempfile.TemporaryDirectory() as directory:
                state = RealState(Path(directory) / "summary.json")
                state.model = "test-model"
                label, errors = state.prepare_gate(request_body(
                    [system()] + persona() + [event_tail("FIRST_MEETING")]))
                self.assertEqual((label, errors), ("FIRST_MEETING", []))
                ok, _content, reasons = state.inspect_response(
                    label, response("你好，我是 AI 伙伴，初次见面请多指教。"))
                self.assertTrue(ok)
                self.assertEqual(reasons, [])

        def test_delete_probe_requires_memory_context(self):
            messages = [system(), persona()[0],
                        {"role": "system", "content": "用户明确偏好：MF_KEEP_FACT"},
                        {"role": "user", "content": "MF_EVENTS_DELETE_PROBE:"
                         + "00000000-0000-0000-0000-000000000000 请用中文简短回复。"}]
            with tempfile.TemporaryDirectory() as directory:
                state = RealState(Path(directory) / "summary.json")
                state.model = "test-model"
                label, errors = state.prepare_gate(request_body(messages))
                self.assertEqual(label, None)
                self.assertIn("invalid_context", errors)

        def test_real_chat_requires_marker_and_identity(self):
            nonce = "00000000-0000-0000-0000-000000000001"
            messages = [system(), persona()[0],
                        {"role": "user", "content": f"MF_REAL_CHAT:{nonce} 请确认你是 AI 伙伴，并完整保留验证码。"}]
            with tempfile.TemporaryDirectory() as directory:
                state = RealState(Path(directory) / "summary.json")
                state.model = "test-model"
                label, errors = state.prepare_gate(request_body(messages))
                self.assertEqual((label, errors), ("REAL_CHAT", []))
                ok, _content, reasons = state.inspect_response(
                    label, response(f"MF_REAL_OK {nonce} 我是 AI 伙伴，可以帮你查看世界。"))
                self.assertTrue(ok)

    def system():
        return {"role": "system", "content": SYSTEM_PROMPT}

    def persona():
        return [{"role": "system", "content": "\n".join(DEFAULT_PERSONA_PARTS)}]

    def event_tail(name):
        return {"role": "system", "content": EVENT_PREFIX + EVENTS[name] + EVENT_SUFFIX}

    def request_body(messages):
        return json.dumps({"model": "test-model", "messages": messages}).encode("utf-8")

    def response(content):
        return json.dumps({"choices": [{"message": {"role": "assistant", "content": content}}]}).encode("utf-8")

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
    state = RealState(evidence, bool(api_key))
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
