#!/usr/bin/env python3
"""Loopback-only event protocol fixture; every accepted reply says it is not a real model."""
import argparse
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import threading
import time
import unittest

MAX_BODY = 65_536
MAX_MESSAGES = 48
MAX_CHARS = 16_000
MODEL = 'acceptance-events-protocol-simulation'
KIND = '协议模拟，非真实模型'
EVENTS = {
    'FIRST_MEETING': '主人第一次加入并与伙伴相遇',
    'REJOIN': '主人重新加入服务器',
    'INJURED': '伙伴刚刚受到伤害',
}
EVENT_PREFIX = '已确认的游戏事件：'
EVENT_SUFFIX = '。请简短、自然地向主人问候，并明确你是 AI 伙伴。'
PREFERENCE_LABEL = '用户明确偏好：'
MEMORY_EVENT_LABEL = '已验证游戏事件：'
RETAINED_FACTS = (PREFERENCE_LABEL + 'MF_KEEP_FACT',
                  MEMORY_EVENT_LABEL + EVENTS['FIRST_MEETING'],
                  MEMORY_EVENT_LABEL + EVENTS['INJURED'],
                  MEMORY_EVENT_LABEL + EVENTS['REJOIN'])
PROBE = re.compile(r'^MF_EVENTS_DELETE_PROBE:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:\s|$)')
EVENT_REPLY = ' | 协议模拟，非真实模型。你好，我是 AI 伙伴。'


def reject_constant(value):
    raise ValueError('invalid_json_constant')


def reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate_json_key')
        result[key] = value
    return result


class ProtocolState:
    def __init__(self, summary_path=None):
        self.summary_path = Path(summary_path) if summary_path else None
        self.lock = threading.Lock()
        self.counts = Counter()
        self.failures = Counter()

    def snapshot(self):
        with self.lock:
            return {'kind': KIND, 'counts': dict(self.counts), 'failures': dict(self.failures),
                    'request_body_stored': False}

    def record(self, label=None, failure=None):
        with self.lock:
            if label in (*EVENTS, 'DELETE_PROBE', 'unmarked'):
                self.counts[label] += 1
            if failure in ('unexpected_authorization', 'body_limit', 'invalid_shape',
                           'unmarked_request', 'system_event_mismatch', 'deleted_fact_present',
                           'retained_memories_missing', 'fixture_write_failed'):
                self.failures[failure] += 1
            snapshot = {'kind': KIND, 'counts': dict(self.counts), 'failures': dict(self.failures),
                        'request_body_stored': False}
            if self.summary_path:
                try:
                    self.summary_path.parent.mkdir(parents=True, exist_ok=True)
                    temporary = self.summary_path.with_suffix('.tmp')
                    temporary.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + '\n',
                                         encoding='utf-8')
                    temporary.replace(self.summary_path)
                except OSError:
                    self.failures['fixture_write_failed'] += 1

    def validate(self, body, authorization=False):
        if authorization:
            self.record(failure='unexpected_authorization')
            return 400, '协议模拟拒绝带凭据的请求。'
        if len(body) > MAX_BODY:
            self.record(failure='body_limit')
            return 413, '协议请求超过上限。'
        try:
            data = json.loads(body.decode('utf-8'), parse_constant=reject_constant,
                              object_pairs_hook=reject_duplicate_keys)
            if not isinstance(data, dict) or data.get('model') != MODEL:
                raise ValueError('invalid_shape')
            messages = data.get('messages')
            if not isinstance(messages, list) or not 1 <= len(messages) <= MAX_MESSAGES:
                raise ValueError('invalid_shape')
            if any(not isinstance(message, dict)
                   or message.get('role') not in ('system', 'user', 'assistant')
                   or not isinstance(message.get('content'), str)
                   or len(message['content']) > 4_096 for message in messages):
                raise ValueError('invalid_shape')
            if sum(len(message['content']) for message in messages) > MAX_CHARS:
                raise ValueError('invalid_shape')
        except (UnicodeError, ValueError, TypeError, RecursionError):
            self.record(failure='invalid_shape')
            return 400, '协议请求格式或长度无效。'

        users = [message['content'] for message in messages if message['role'] == 'user']
        systems = [message['content'] for message in messages if message['role'] == 'system']
        if messages[-1]['role'] == 'system' and systems and systems[-1].startswith(EVENT_PREFIX):
            label = next((name for name, phrase in EVENTS.items()
                          if systems[-1] == EVENT_PREFIX + phrase + EVENT_SUFFIX), None)
            if label is None:
                self.record(failure='system_event_mismatch')
                return 400, '当前系统事件与协议模拟不匹配。'
            self.record(label)
            return 200, self._reply(label)

        if not users:
            self.record('unmarked', 'unmarked_request')
            return 400, '协议模拟只接受明确标记的验收请求。'
        last_user = users[-1]
        marker = PROBE.match(last_user)
        if marker is None or len(PROBE.findall(last_user)) != 1:
            self.record('unmarked', 'unmarked_request')
            return 400, '协议模拟只接受明确标记的验收请求。'
        all_context = '\n'.join(message['content'] for message in messages)
        if 'MF_DELETE_FACT' in all_context:
            self.record('DELETE_PROBE', 'deleted_fact_present')
            return 400, '已删除的事实仍出现在模型上下文。'
        system_context = '\n'.join(systems)
        if any(value not in system_context for value in RETAINED_FACTS):
            self.record('DELETE_PROBE', 'retained_memories_missing')
            return 400, '删除后应保留的记忆未出现在模型上下文。'
        self.record('DELETE_PROBE')
        return 200, self._reply('DELETE_PROBE')

    @staticmethod
    def _reply(label):
        return f'MF_EVENTS_REPLY {label}{EVENT_REPLY}'


class EventsFixture:
    def __init__(self, summary_path=None):
        self.state = ProtocolState(summary_path)
        state = self.state

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_POST(self):
                self.connection.settimeout(5)
                if self.path != '/v1/chat/completions':
                    self.send_error(404)
                    return
                try:
                    size = int(self.headers.get('Content-Length', '-1'))
                    if size < 0 or size > MAX_BODY:
                        state.record(failure='body_limit')
                        self.send_error(413)
                        return
                    body = self.rfile.read(size)
                    if len(body) != size:
                        raise ValueError('incomplete_body')
                    status, content = state.validate(body, 'Authorization' in self.headers)
                except (OSError, ValueError):
                    state.record(failure='invalid_shape')
                    status, content = 400, '协议请求不完整。'
                response = json.dumps(
                    {'choices': [{'message': {'role': 'assistant', 'content': content}}]},
                    ensure_ascii=False).encode('utf-8')
                self.send_response(status)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Content-Length', str(len(response)))
                self.send_header('Connection', 'close')
                self.end_headers()
                try:
                    self.wfile.write(response)
                except OSError:
                    pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def endpoint(self):
        return f'http://127.0.0.1:{self.server.server_port}/v1/chat/completions'

    def start(self):
        self.thread.start()
        return self

    def close(self):
        if self.thread.is_alive():
            self.server.shutdown()
            self.thread.join(timeout=2)
        self.server.server_close()

    def configuration(self):
        return ('enabled=true\ndiagnostics.enabled=true\nmodel.provider=chat-completions\n'
                f'model.endpoint={self.endpoint}\nmodel.name={MODEL}\nmodel.api-key-env=\n'
                'model.allow-unauthenticated-local=true\nmodel.retries=0\n'
                'model.timeout-seconds=10\nchat.prefix=@\n')


class ProtocolFixtureTests(unittest.TestCase):
    def setUp(self):
        self.state = ProtocolState()

    @staticmethod
    def body(messages):
        return json.dumps({'model': MODEL, 'messages': messages}, ensure_ascii=False).encode('utf-8')

    def test_classifies_only_the_tail_system_event(self):
        for label, phrase in EVENTS.items():
            messages = [{'role': 'system', 'content': '历史记忆：' + phrase},
                        {'role': 'system', 'content': EVENT_PREFIX + phrase + EVENT_SUFFIX}]
            status, response = self.state.validate(self.body(messages))
            self.assertEqual(status, 200)
            self.assertIn('协议模拟，非真实模型', response)
        self.assertEqual(self.state.snapshot()['counts'],
                         {'FIRST_MEETING': 1, 'REJOIN': 1, 'INJURED': 1})

    def test_historical_event_is_not_a_current_event(self):
        messages = [{'role': 'system', 'content': EVENT_PREFIX + EVENTS['FIRST_MEETING'] + EVENT_SUFFIX},
                    {'role': 'user', 'content': '无事件标签'}]
        status, _ = self.state.validate(self.body(messages))
        self.assertEqual(status, 400)
        self.assertEqual(self.state.snapshot()['counts'].get('FIRST_MEETING', 0), 0)
        self.assertEqual(self.state.snapshot()['failures']['unmarked_request'], 1)

    def test_delete_probe_requires_retained_and_deleted_facts(self):
        probe = {'role': 'user',
                 'content': 'MF_EVENTS_DELETE_PROBE:00000000-0000-0000-0000-000000000000 测试'}
        messages = [{'role': 'system', 'content': value} for value in RETAINED_FACTS]
        messages.append(probe)
        status, _ = self.state.validate(self.body(messages))
        self.assertEqual(status, 200)
        for missing_index, missing_fact in enumerate(RETAINED_FACTS):
            incomplete = [{'role': 'system', 'content': fact}
                          for index, fact in enumerate(RETAINED_FACTS) if index != missing_index]
            incomplete.extend([{'role': 'user', 'content': missing_fact}, probe])
            status, _ = self.state.validate(self.body(incomplete))
            self.assertEqual(status, 400)
        with_deleted = [*messages[:-1], {'role': 'user', 'content': 'MF_DELETE_FACT'}, probe]
        status, _ = self.state.validate(self.body(with_deleted))
        self.assertEqual(status, 400)
        self.assertEqual(self.state.snapshot()['failures']['deleted_fact_present'], 1)
        self.assertEqual(self.state.snapshot()['failures']['retained_memories_missing'], 4)

    def test_rejects_auth_and_oversized_bodies(self):
        valid = self.body([{'role': 'user', 'content': 'unmarked'}])
        self.assertEqual(self.state.validate(valid, authorization=True)[0], 400)
        self.assertEqual(self.state.validate(b' ' * (MAX_BODY + 1))[0], 413)
        self.assertFalse(self.state.snapshot()['request_body_stored'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--fixture', action='store_true', help='serve a loopback fixture for an already prepared test server')
    mode.add_argument('--self-test', action='store_true', help='run pure in-process fixture assertions')
    options = parser.parse_args()
    if options.self_test:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(ProtocolFixtureTests)
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        return 0 if result.wasSuccessful() else 1
    evidence = Path(__file__).resolve().parent / 'build/evidence' / f'events-fixture-{time.time_ns()}'
    evidence.mkdir(parents=True)
    fixture = EventsFixture(evidence / 'protocol-summary.json').start()
    fixture.state.record('fixture_started')
    config = evidence / 'isolated-server-model.properties'
    config.write_text(fixture.configuration(), encoding='utf-8')
    print(json.dumps({'kind': KIND, 'endpoint': fixture.endpoint,
                      'configuration_snippet': str(config),
                      'summary': str(fixture.state.summary_path)}, ensure_ascii=False), flush=True)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        fixture.close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
