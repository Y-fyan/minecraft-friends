import unittest
from run_actions import marker
class ActionsMarkerTests(unittest.TestCase):
    def test_actual_stdout_or_bare_marker(self):
        x='MF_M2_SERVER net.minecraft.class_3176 result PASS'
        self.assertEqual(x,marker('[07:00] [Server thread/INFO]: [STDOUT]: '+x))
        self.assertEqual(x,marker(x))
    def test_chat_spoof_and_arbitrary_prefix_rejected(self):
        x='MF_M2_SERVER Fake result PASS'
        self.assertIsNone(marker('[STDOUT]: [CHAT] '+x))
        self.assertIsNone(marker('someone said '+x))
        self.assertIsNone(marker(x+' injected'))

class SnapshotContractTests(unittest.TestCase):
    def test_documented_vertical_range_and_retained_budget(self):
        from actions_fixture import ActionsFixture
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as d:
            fixture=ActionsFixture(Path(d)/'summary.json')
            value={'ownerId':'a','companionId':'b','companionPosition':{'x':0,'y':-60,'z':0},'blocks':[{'x':0,'y':-62,'z':0}], 'inventory':[]}
            self.assertTrue(fixture.valid(value))
            value['blocks'][0]['y']=-57
            self.assertFalse(fixture.valid(value))
            value['blocks']=[{'x':0,'y':-62,'z':0}]*65
            self.assertFalse(fixture.valid(value))
            fixture.server.server_close()

class DedicatedMarkerTests(unittest.TestCase):
    def test_trusted_console_prompt_only(self):
        x='MF_M2_SERVER net.minecraft.server.dedicated.DedicatedServer result PASS'
        self.assertEqual(x,marker('> '+x,True))
        self.assertIsNone(marker('> '+x))
        self.assertIsNone(marker('[CHAT] > '+x,True))
