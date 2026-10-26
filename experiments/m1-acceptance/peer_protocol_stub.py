#!/usr/bin/env python3
"""Loopback 协议模拟，非真实模型；仅对 peer 双客户端验收提供受限响应。"""
import argparse
from collections import Counter
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import threading
import time

MAX_BODY = 131072
MAX_MESSAGES = 64
MAX_CONTENT = 16000
MARKER = re.compile(
    r'MF_PEER_(PRIVATE_A|PRIVATE_B|SHARED_A|SHARED_B|SHARED_AFTER_LEAVE):'
    r'([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})'
)
PRIVATE_A = ('MF_A_ADDRESS', 'MF_A_PRIVATE_FACT', 'MF_A_PRIVATE_HISTORY')
PRIVATE_B = ('MF_B_ADDRESS', 'MF_B_PRIVATE_FACT', 'MF_B_PRIVATE_HISTORY')
SETUP_GREETING = '已确认的游戏事件：'


def reject_constant(value):
    raise ValueError('non-standard JSON constant')


class PeerProtocolState:
    def __init__(self, summary_path, endpoint):
        self.summary_path = summary_path
        self.endpoint = endpoint
        self.lock = threading.Lock()
        self.counts = Counter()
        self.failures = Counter()

    def snapshot(self):
        with self.lock:
            return {
                'kind': '协议模拟，非真实模型',
                'endpoint': self.endpoint,
                'counts': dict(self.counts),
                'failures': dict(self.failures),
                'request_body_stored': False,
            }

    def record(self, label=None, failure=None):
        with self.lock:
            if label:
                self.counts[label] += 1
            if failure:
                self.failures[failure] += 1
            value = {
                'kind': '协议模拟，非真实模型',
                'endpoint': self.endpoint,
                'counts': dict(self.counts),
                'failures': dict(self.failures),
                'request_body_stored': False,
            }
            temporary = self.summary_path.with_suffix('.tmp')
            temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            temporary.replace(self.summary_path)

    def validate(self, body, authorization=False):
        if authorization:
            self.record(failure='unexpected_authorization')
            return 400, '协议模拟拒绝携带凭据的请求。'
        if len(body) > MAX_BODY:
            self.record(failure='body_limit')
            return 413, '协议请求超过上限。'
        try:
            data = json.loads(body.decode('utf-8'), parse_constant=reject_constant)
            if not isinstance(data, dict) or data.get('model') != 'acceptance-protocol-simulation':
                raise ValueError()
            messages = data.get('messages')
            if not isinstance(messages, list) or not 1 <= len(messages) <= MAX_MESSAGES:
                raise ValueError()
            if any(not isinstance(item, dict) or item.get('role') not in ('system', 'user', 'assistant')
                   or not isinstance(item.get('content'), str) for item in messages):
                raise ValueError()
            if sum(len(item['content']) for item in messages) > MAX_CONTENT:
                self.record(failure='content_limit')
                return 413, '协议上下文超过上限。'
            users = [item['content'] for item in messages if item['role'] == 'user']
            if not users:
                raise ValueError()
            marker = MARKER.search(users[-1])
            context = '\n'.join(item['content'] for item in messages)
            if marker is None:
                if SETUP_GREETING in users[-1]:
                    self.record('SETUP_GREETING')
                    return 200, '协议模拟，非真实模型。'
                self.record(failure='unmarked_request')
                return 400, '协议请求缺少验收标记。'

            label, nonce = marker.groups()
            self.record(label)
            systems = '\n'.join(item['content'] for item in messages if item['role'] == 'system')
            if label == 'PRIVATE_A':
                valid = PRIVATE_A[0] in systems and PRIVATE_A[1] in systems and all(
                    private not in context for private in PRIVATE_B
                )
                failure = 'private_a_context_isolation'
            elif label == 'PRIVATE_B':
                valid = PRIVATE_B[0] in systems and PRIVATE_B[1] in systems and all(
                    private not in context for private in PRIVATE_A
                )
                failure = 'private_b_context_isolation'
            else:
                valid = all(private not in context for private in (*PRIVATE_A, *PRIVATE_B))
                failure = 'shared_context_contains_private_data'
            if not valid:
                self.record(failure=failure)
                return 400, '协议上下文隔离断言失败。'
            return 200, '协议模拟，非真实模型。中文伙伴回复 MF_CHAT_REPLY:' + nonce
        except (ValueError, KeyError, TypeError, UnicodeError, RecursionError):
            self.record(failure='invalid_json_or_shape')
            return 400, '协议请求格式错误。'


class PeerProtocolFixture:
    def __init__(self, summary_path):
        self.summary_path = summary_path
        self.endpoint = ''
        self.state = None
        self.thread = None
        self.server = None

        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_POST(self):
                if self.path != '/v1/chat/completions':
                    self.send_error(404)
                    return
                self.connection.settimeout(5)
                try:
                    size = int(self.headers.get('Content-Length', '-1'))
                    if size < 0 or size > MAX_BODY:
                        fixture.state.record(failure='body_limit')
                        self.send_error(413)
                        return
                    body = self.rfile.read(size)
                    if len(body) != size:
                        raise ValueError()
                    status, content = fixture.state.validate(body, 'Authorization' in self.headers)
                except (OSError, ValueError):
                    fixture.state.record(failure='incomplete_body')
                    status, content = 400, '协议请求不完整。'
                response = json.dumps(
                    {'choices': [{'message': {'role': 'assistant', 'content': content}}]},
                    ensure_ascii=False,
                ).encode('utf-8')
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
        self.endpoint = f'http://127.0.0.1:{self.server.server_port}/v1/chat/completions'
        self.state = PeerProtocolState(summary_path, self.endpoint)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self):
        self.thread.start()
        self.state.record('fixture_started')
        return self

    def close(self):
        if self.thread and self.thread.is_alive():
            self.server.shutdown()
            self.server.server_close()
            self.thread.join(timeout=2)

    def configuration(self):
        return (
            'enabled=true\n'
            'diagnostics.enabled=true\n'
            'model.provider=chat-completions\n'
            f'model.endpoint={self.endpoint}\n'
            'model.name=acceptance-protocol-simulation\n'
            'model.api-key-env=\n'
            'model.allow-unauthenticated-local=true\n'
            'model.retries=0\n'
            'model.timeout-seconds=10\n'
            'chat.prefix=@\n'
        )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', action='store_true', required=True,
                        help='只启动 loopback 协议 fixture；不启动或管理 Minecraft')
    parser.parse_args()
    directory = Path(__file__).resolve().parent / 'build/evidence' / f'peer-protocol-fixture-{time.time_ns()}'
    directory.mkdir(parents=True)
    fixture = PeerProtocolFixture(directory / 'protocol-summary.json').start()
    config_path = directory / 'isolated-server-model.properties'
    config_path.write_text(fixture.configuration(), encoding='utf-8')
    print(json.dumps({
        'kind': '协议模拟，非真实模型',
        'endpoint': fixture.endpoint,
        'configuration_snippet': str(config_path),
        'summary': str(fixture.summary_path),
    }, ensure_ascii=False), flush=True)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        fixture.close()


if __name__ == '__main__':
    main()
