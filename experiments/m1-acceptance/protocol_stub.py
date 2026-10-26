#!/usr/bin/env python3
"""仅 loopback 的协议模拟，非真实模型；不保存请求正文或凭据。"""
import argparse
from collections import Counter
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import threading
import time

MAX_BODY = 131072
ADDRESS = '验收主人'
FACT = 'MF_PRIVATE_FACT'
PRIVATE_HISTORY = 'MF_PRIVATE_HISTORY'
MARKER = re.compile(r'MF_ACCEPT_(PRIVATE|PREFIX|REJOIN|AFTER_CLEAR|SHARED):([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})')


def reject_constant(value):
    raise ValueError('non-standard JSON constant')


class ProtocolState:
    def __init__(self, summary_path=None):
        self.summary_path = summary_path
        self.lock = threading.Lock()
        self.counts = Counter()
        self.failures = Counter()

    def snapshot(self):
        with self.lock:
            return {'kind': '协议模拟，非真实模型', 'counts': dict(self.counts),
                    'failures': dict(self.failures), 'request_body_stored': False}

    def record(self, label, failure=None):
        with self.lock:
            self.counts[label] += 1
            if failure:
                self.failures[failure] += 1
        if self.summary_path:
            temporary = self.summary_path.with_suffix('.tmp')
            # Serialize writes, including snapshot generation, across HTTP workers.
            with self.lock:
                value = {'kind': '协议模拟，非真实模型', 'counts': dict(self.counts),
                         'failures': dict(self.failures), 'request_body_stored': False}
                temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
                temporary.replace(self.summary_path)

    def validate(self, body, authorization=False):
        if authorization:
            self.record('rejected', 'unexpected_authorization')
            return 400, '协议模拟拒绝了带凭据的请求。'
        if len(body) > MAX_BODY:
            self.record('rejected', 'body_limit')
            return 413, '协议请求超过上限。'
        try:
            data = json.loads(body.decode('utf-8'), parse_constant=reject_constant)
            if not isinstance(data, dict):
                raise ValueError()
            if data.get('model') != 'acceptance-protocol-simulation':
                raise ValueError()
            messages = data['messages']
            if not isinstance(messages, list) or not 1 <= len(messages) <= 64:
                raise ValueError()
            if any(not isinstance(m, dict) or m.get('role') not in ('system', 'user', 'assistant')
                   or not isinstance(m.get('content'), str) for m in messages):
                raise ValueError()
            if sum(len(m['content']) for m in messages) > 16000:
                raise ValueError()
            users = [m['content'] for m in messages if m['role'] == 'user']
            marker = MARKER.search(users[-1]) if users else None
            if marker is None:
                self.record('unmarked')
                return 200, '协议模拟，非真实模型。'
            label, nonce = marker.groups()
            context = '\n'.join(m['content'] for m in messages)
            if label in ('PRIVATE', 'PREFIX', 'REJOIN'):
                injected = '\n'.join(m['content'] for m in messages if m['role'] == 'system')
                valid = ADDRESS in injected and FACT in injected
                failure = 'private_context_missing'
            else:
                valid = all(value not in context for value in (ADDRESS, FACT, PRIVATE_HISTORY))
                failure = 'private_context_leaked'
            if not valid:
                self.record(label, failure)
                return 400, '协议上下文断言失败。'
            self.record(label)
            return 200, '协议模拟，非真实模型。中文伙伴回复 MF_CHAT_REPLY:' + nonce
        except (ValueError, KeyError, TypeError, UnicodeError, RecursionError):
            self.record('rejected', 'invalid_json_or_shape')
            return 400, '协议格式错误。'


class ProtocolFixture:
    def __init__(self, summary_path=None):
        self.state = ProtocolState(summary_path)
        state = self.state

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                self.connection.settimeout(5)
                if self.path != '/v1/chat/completions':
                    self.send_error(404)
                    return
                try:
                    size = int(self.headers.get('Content-Length', '-1'))
                    if size < 0 or size > MAX_BODY:
                        state.record('rejected', 'body_limit')
                        self.send_error(413)
                        return
                    body = self.rfile.read(size)
                    if len(body) != size:
                        raise ValueError()
                    status, content = state.validate(body, 'Authorization' in self.headers)
                except (OSError, ValueError):
                    state.record('rejected', 'incomplete_body')
                    status, content = 400, '协议请求不完整。'
                response = json.dumps({'choices': [{'message': {'role': 'assistant', 'content': content}}]},
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
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def configuration(self):
        return ('enabled=true\ndiagnostics.enabled=true\nmodel.provider=chat-completions\n'
                f'model.endpoint={self.endpoint}\nmodel.name=acceptance-protocol-simulation\n'
                'model.api-key-env=\nmodel.allow-unauthenticated-local=true\n'
                'model.retries=0\nmodel.timeout-seconds=10\nchat.prefix=@\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', action='store_true', required=True,
                        help='Create a standalone fixture for an already isolated DedicatedServer; does not start Minecraft')
    parser.parse_args()
    directory = Path(__file__).resolve().parent / 'build/evidence' / f'protocol-fixture-{time.time_ns()}'
    directory.mkdir(parents=True)
    fixture = ProtocolFixture(directory / 'protocol-summary.json').start()
    fixture.state.record('fixture_started')
    config = directory / 'isolated-server-model.properties'
    config.write_text(fixture.configuration(), encoding='utf-8')
    print(json.dumps({'kind': '协议模拟，非真实模型', 'endpoint': fixture.endpoint,
                      'configuration_snippet': str(config), 'summary': str(fixture.state.summary_path)}, ensure_ascii=False), flush=True)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        fixture.close()


if __name__ == '__main__':
    main()
