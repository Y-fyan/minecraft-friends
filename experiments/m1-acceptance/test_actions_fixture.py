"""Exercise the HTTP replies used by game planning acceptance, including the late reply."""
import json
from pathlib import Path
import tempfile
import unittest
from urllib.request import Request, urlopen
from actions_fixture import ActionsFixture

class CapabilityFixtureTests(unittest.TestCase):
    def test_follow_bad_and_late_use_current_capability_protocol(self):
        snapshot = {'ownerId': 'owner', 'companionId': 'bot',
                    'companionPosition': {'x': 0, 'y': 64, 'z': 0}, 'blocks': [], 'inventory': []}
        with tempfile.TemporaryDirectory() as directory:
            fixture = ActionsFixture(Path(directory) / 'summary.json').start()
            try:
                for instruction, name in [('请跟着我', 'follow'), ('非法动作测试', 'op'), ('延迟后跟随', 'follow')]:
                    body = {'messages': [{'role': 'system', 'content': 'fixture test'},
                            {'role': 'user', 'content': '显式任务请求：' + instruction +
                             '\n服务端观测快照：' + json.dumps(snapshot)}]}
                    request = Request('http://127.0.0.1:' + str(fixture.server.server_port) +
                                      '/v1/chat/completions', data=json.dumps(body).encode(),
                                      headers={'Content-Type': 'application/json'})
                    with urlopen(request, timeout=8) as reply:
                        call = json.loads(json.load(reply)['choices'][0]['message']['content'])
                    self.assertEqual({'capability': name, 'arguments': {}}, call)
                self.assertEqual(0, fixture.failures)
                self.assertEqual(1, fixture.counts['late_write_attempt'])
            finally:
                fixture.close()
