#!/usr/bin/env python3
"""Offline unit tests for the real-model observer summary verdict."""
import importlib
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
run_events = importlib.import_module('run_events')
REPLIES = run_events.REAL_MODEL_REPLY_LABELS
FAULTS = run_events.REAL_MODEL_FAULT_LABELS
REQUESTS = run_events.REAL_MODEL_REQUEST_LABELS


def summary(count_overrides=None, pass_overrides=None, failure_overrides=None,
            transport_overrides=None, failure_counter_overrides=None):
    counts = {name: 1 for name in REQUESTS}
    passes = {name: (1 if name in REPLIES else 0) for name in REQUESTS}
    failures = {name: 0 for name in REQUESTS}
    transport = {'forwarded': len(REPLIES), 'upstream_http_error': 0,
                 'network_error': 0, 'controlled_timeout': 1,
                 'controlled_disconnect': 1}
    observer_failures = {name: 0 for name in (
        'proxy_started', 'invalid_json', 'invalid_model', 'invalid_context',
        'context_too_large', 'semantic_response_rejected', 'upstream_http_error',
        'network_error', 'response_too_large', 'summary_write_failed')}
    for name, value in (count_overrides or {}).items():
        counts[name] = value
    for name, value in (pass_overrides or {}).items():
        passes[name] = value
    for name, value in (failure_overrides or {}).items():
        failures[name] = value
    for name, value in (transport_overrides or {}).items():
        transport[name] = value
    for name, value in (failure_counter_overrides or {}).items():
        observer_failures[name] = value
    return {
        'kind': 'm1-real-model-acceptance-observer',
        'request_body_stored': False,
        'response_body_stored': False,
        'counts': counts,
        'semantic_passes': passes,
        'semantic_failures': failures,
        'semantic_failure_reasons': {},
        'transport': transport,
        'failures': observer_failures,
        'human_review_records': len(REPLIES),
        'human_review_completed': False,
    }


class RealModelSummaryTests(unittest.TestCase):
    def setUp(self):
        self.result = summary()
        self.baseline = {name: 0 for name in self.result['counts']}

    def test_complete_automated_summary_passes_without_claiming_human_review(self):
        verdict = run_events.evaluate_real_model_summary(
            self.baseline, self.result, self.result)
        self.assertTrue(verdict['state_matches'])
        self.assertTrue(verdict['counts_passed'])
        self.assertTrue(verdict['failures_clean'])
        self.assertTrue(verdict['semantic_checks_passed'])
        self.assertTrue(verdict['fault_transports_observed'])
        self.assertEqual(verdict['count_delta'], {name: 1 for name in REQUESTS})
        self.assertFalse(self.result['human_review_completed'])

    def test_missing_rejoin_request_fails(self):
        self.result['counts']['REJOIN'] = 0
        verdict = run_events.evaluate_real_model_summary(
            self.baseline, self.result, self.result)
        self.assertFalse(verdict['counts_passed'])
        self.assertEqual(verdict['count_delta']['REJOIN'], 0)

    def test_rejected_real_reply_fails_even_if_game_continues(self):
        self.result['semantic_passes']['FIRST_MEETING'] = 0
        self.result['semantic_failures']['FIRST_MEETING'] = 1
        self.result['semantic_failure_reasons']['event_context_absent'] = 1
        self.result['failures']['semantic_response_rejected'] = 1
        verdict = run_events.evaluate_real_model_summary(
            self.baseline, self.result, self.result)
        self.assertFalse(verdict['semantic_checks_passed'])
        self.assertFalse(verdict['failures_clean'])

    def test_missing_controlled_disconnect_fails(self):
        self.result['transport']['controlled_disconnect'] = 0
        verdict = run_events.evaluate_real_model_summary(
            self.baseline, self.result, self.result)
        self.assertFalse(verdict['fault_transports_observed'])

    def test_live_state_mismatch_fails(self):
        changed = summary()
        changed['counts']['REAL_CHAT'] = 2
        verdict = run_events.evaluate_real_model_summary(
            self.baseline, self.result, changed)
        self.assertFalse(verdict['state_matches'])

    def test_real_model_labels_are_explicit_not_unpacked_into_nameerror(self):
        self.assertEqual(REPLIES, ('FIRST_MEETING', 'INJURED', 'REJOIN',
                                   'DELETE_PROBE', 'REAL_CHAT'))
        self.assertEqual(FAULTS, ('TIMEOUT', 'DISCONNECT'))


if __name__ == '__main__':
    unittest.main()
