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
