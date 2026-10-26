"""Pure peer fixture checks; no socket, Minecraft process, account or model service."""
import json
from pathlib import Path
import tempfile
import unittest
import urllib.request

from peer_protocol_stub import PeerProtocolFixture, PeerProtocolState

NONCE = '1a2b3c4d-0000-4000-8000-123456789abc'


def request(label, system='', history=()):
    body = {
        'model': 'acceptance-protocol-simulation',
        'messages': [
            {'role': 'system', 'content': system},
            *history,
            {'role': 'user', 'content': f'MF_PEER_{label}:{NONCE} 中文'},
        ],
    }
    return json.dumps(body, ensure_ascii=False).encode('utf-8')


class PeerProtocolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.summary = Path(self.temp.name) / 'summary.json'
        self.state = PeerProtocolState(self.summary, 'http://127.0.0.1:41827/v1/chat/completions')

    def test_private_contexts_require_own_address_and_fact_only(self):
        cases = (
            ('PRIVATE_A', 'MF_A_ADDRESS MF_A_PRIVATE_FACT', 'MF_B_ADDRESS MF_B_PRIVATE_FACT'),
            ('PRIVATE_B', 'MF_B_ADDRESS MF_B_PRIVATE_FACT', 'MF_A_ADDRESS MF_A_PRIVATE_FACT'),
        )
        for label, own, other in cases:
            with self.subTest(label=label):
                code, reply = self.state.validate(request(label, own))
                self.assertEqual(200, code)
                self.assertIn('协议模拟，非真实模型', reply)
                self.assertIn(NONCE, reply)
                code, _ = self.state.validate(request(label, own + ' ' + other))
                self.assertEqual(400, code)

    def test_private_context_requires_facts_in_system_message(self):
        code, _ = self.state.validate(request(
            'PRIVATE_A', '', [{'role': 'user', 'content': 'MF_A_ADDRESS MF_A_PRIVATE_FACT'}]))
        self.assertEqual(400, code)

    def test_shared_context_rejects_all_private_addresses_facts_and_history(self):
        self.assertEqual(200, self.state.validate(request('SHARED_A'))[0])
        for value in ('MF_A_ADDRESS', 'MF_A_PRIVATE_FACT', 'MF_A_PRIVATE_HISTORY',
                      'MF_B_ADDRESS', 'MF_B_PRIVATE_FACT', 'MF_B_PRIVATE_HISTORY'):
            with self.subTest(value=value):
                history = [{'role': 'assistant', 'content': value}]
                self.assertEqual(400, self.state.validate(request('SHARED_B', history=history))[0])

    def test_fixture_rejects_credentials_bad_shapes_and_unmarked_requests(self):
        self.assertEqual(400, self.state.validate(request('PRIVATE_A'), authorization=True)[0])
        self.assertEqual(413, self.state.validate(b'x' * (131072 + 1))[0])
        self.assertEqual(400, self.state.validate(b'{broken')[0])
        self.assertEqual(400, self.state.validate(request('OTHER'))[0])
        snapshot = json.loads(self.summary.read_text(encoding='utf-8'))
        self.assertFalse(snapshot['request_body_stored'])
        self.assertEqual('协议模拟，非真实模型', snapshot['kind'])
        serialized = json.dumps(snapshot, ensure_ascii=False)
        for value in (NONCE, 'MF_A_PRIVATE_FACT', 'MF_B_PRIVATE_FACT'):
            self.assertNotIn(value, serialized)

    def test_verified_initial_greeting_is_setup_only(self):
        body = json.dumps({
            'model': 'acceptance-protocol-simulation',
            'messages': [{'role': 'user', 'content': '已确认的游戏事件：首次见面'}],
        }, ensure_ascii=False).encode('utf-8')
        code, reply = self.state.validate(body)
        self.assertEqual(200, code)
        self.assertEqual('协议模拟，非真实模型。', reply)
        self.assertEqual(1, self.state.snapshot()['counts']['SETUP_GREETING'])

    def test_loopback_fixture_returns_nonce_without_storing_request(self):
        fixture = PeerProtocolFixture(self.summary).start()
        self.addCleanup(fixture.close)
        call = urllib.request.Request(
            fixture.endpoint,
            data=request('PRIVATE_A', 'MF_A_ADDRESS MF_A_PRIVATE_FACT'),
            headers={'Content-Type': 'application/json'},
            method='POST',
        )
        with urllib.request.urlopen(call, timeout=3) as response:
            result = json.loads(response.read().decode('utf-8'))
        content = result['choices'][0]['message']['content']
        self.assertIn('协议模拟，非真实模型', content)
        self.assertIn(NONCE, content)
        summary = json.loads(self.summary.read_text(encoding='utf-8'))
        self.assertFalse(summary['request_body_stored'])
        self.assertNotIn(NONCE, json.dumps(summary, ensure_ascii=False))


if __name__ == '__main__':
    unittest.main()
