"""Pure protocol validator tests: no socket, Minecraft process, account or model service."""
import json
import unittest

from protocol_stub import ADDRESS, FACT, MAX_BODY, PRIVATE_HISTORY, ProtocolState

NONCE = '1a2b3c4d-0000-4000-8000-123456789abc'


def request(label, system='', history=()):
    return json.dumps({'model': 'acceptance-protocol-simulation', 'messages': [
        {'role': 'system', 'content': system}, *history,
        {'role': 'user', 'content': f'MF_ACCEPT_{label}:{NONCE} 中文'}]}, ensure_ascii=False).encode()


class ProtocolTests(unittest.TestCase):
    def test_private_context_and_nonce_reply(self):
        state = ProtocolState()
        for label in ('PRIVATE', 'PREFIX', 'REJOIN'):
            code, reply = state.validate(request(label, ADDRESS + FACT))
            self.assertEqual(200, code)
            self.assertIn('协议模拟，非真实模型', reply)
            self.assertIn(NONCE, reply)
        snapshot = json.dumps(state.snapshot(), ensure_ascii=False)
        self.assertNotIn(NONCE, snapshot)
        self.assertNotIn(ADDRESS, snapshot)
        self.assertNotIn(FACT, snapshot)

    def test_private_facts_must_be_in_injected_system_context(self):
        state = ProtocolState()
        code, _ = state.validate(request('PRIVATE', '', [{'role': 'user', 'content': ADDRESS + FACT}]))
        self.assertEqual(400, code)
        self.assertEqual(1, state.snapshot()['failures']['private_context_missing'])

    def test_clear_and_shared_reject_private_history(self):
        for label in ('AFTER_CLEAR', 'SHARED'):
            state = ProtocolState()
            self.assertEqual(200, state.validate(request(label))[0])
            self.assertEqual(400, state.validate(request(label, ADDRESS))[0])
            self.assertEqual(400, state.validate(request(label, '', [{'role': 'user', 'content': FACT}]))[0])
            self.assertEqual(400, state.validate(request(label, '', [{'role': 'user', 'content': PRIVATE_HISTORY}]))[0])

    def test_credentials_oversize_and_invalid_shape_are_rejected(self):
        state = ProtocolState()
        self.assertEqual(400, state.validate(request('PRIVATE'), authorization=True)[0])
        self.assertEqual(413, state.validate(b'x' * (MAX_BODY + 1))[0])
        self.assertEqual(400, state.validate(b'{broken')[0])
        self.assertEqual(400, state.validate(b'[]')[0])
        self.assertEqual(400, state.validate(b'{"model":NaN}')[0])
        self.assertEqual(400, state.validate(b'{"model":"acceptance-protocol-simulation","messages":[null]}')[0])


if __name__ == '__main__':
    unittest.main()
