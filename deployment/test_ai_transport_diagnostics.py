import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch
import ai_evaluation_broker as b
from ai_evaluation_guard import _canonical, GuardError
from test_ai_evaluation_guard import llm


class TransportDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name) / 'run'
        self.meta = b.prepare(self.folder, 59991)
        key = Path(self.temp.name) / 'fake-key'
        key.write_text('SECRET_FAKE_KEY', encoding='utf-8')
        self.permit = Path(self.temp.name) / 'permit.json'
        self.permit.write_bytes(_canonical(dict(runId=self.meta['runId'], sourceHash=self.meta['sourceHash'],
            corpusHash=self.meta['corpusHash'], paidApproved=True, capUsd='2.00', llmCalls=32,
            embeddingCalls=80, expiresAt=int(time.time())+300, keyFile=str(key))))

    def sender(self):
        return b.live_sender(self.folder, dict(self.meta, mode='live'), self.permit)

    def events(self):
        return [json.loads(line) for p in self.folder.glob('python-transport-*.jsonl') for line in p.read_text(encoding='utf-8').splitlines()]

    def test_timeout_after_request_return_retains_phase_and_no_secrets_or_retries(self):
        connection = MagicMock()
        connection.getresponse.side_effect = TimeoutError('SECRET_RAW_ERROR')
        with patch.object(b.http.client, 'HTTPSConnection', return_value=connection):
            with self.assertRaises(TimeoutError):
                self.sender()('responses', llm(), 1)
        connection.request.assert_called_once()
        connection.getresponse.assert_called_once()
        connection.close.assert_called_once()
        events = self.events()
        self.assertEqual(events[-1]['phase'], 'REQUEST_CALL_RETURNED')
        self.assertEqual(events[-1]['resultCode'], 'UPSTREAM_TIMEOUT')
        self.assertNotIn('SECRET', json.dumps(events))
        self.assertNotIn('Authorization', json.dumps(events))

    def test_http_status_is_recorded_without_error_body_or_following_redirect(self):
        connection = MagicMock()
        connection.getresponse.return_value.status = 429
        with patch.object(b.http.client, 'HTTPSConnection', return_value=connection):
            with self.assertRaises(GuardError):
                self.sender()('responses', llm(), 1)
        self.assertEqual(self.events()[-1]['httpStatus'], 429)
        self.assertEqual(self.events()[-1]['phase'], 'RESPONSE_HEADERS')
        connection.request.assert_called_once()
        connection.getresponse.return_value.read1.assert_not_called()

    def test_body_decode_failure_is_separate_from_missing_headers(self):
        connection = MagicMock()
        connection.getresponse.return_value.status = 200
        connection.getresponse.return_value.read1.side_effect = [b'SECRET_NOT_JSON', b'']
        with patch.object(b.http.client, 'HTTPSConnection', return_value=connection):
            with self.assertRaises(ValueError):
                self.sender()('responses', llm(), 1)
        self.assertEqual(self.events()[-1]['phase'], 'RESPONSE_BODY')
        self.assertEqual(self.events()[-1]['resultCode'], 'UPSTREAM_RESPONSE_INVALID')
        self.assertNotIn('SECRET', json.dumps(self.events()))

    def test_diagnostic_write_failure_does_not_replace_transport_failure(self):
        connection = MagicMock()
        connection.request.side_effect = OSError('SECRET_RAW_ERROR')
        # Build sender before patching all opens: permit/key validation is real, but the data are fake.
        sender = self.sender()
        with patch.object(b.http.client, 'HTTPSConnection', return_value=connection), patch.object(Path, 'open', side_effect=OSError('sink')):
            with self.assertRaisesRegex(OSError, 'SECRET_RAW_ERROR'):
                sender('responses', llm(), 1)
        connection.request.assert_called_once()

    def test_connection_setup_failure_is_not_claimed_as_remote_request(self):
        with patch.object(b.http.client, 'HTTPSConnection', side_effect=OSError('SECRET_SETUP')) as factory:
            with self.assertRaises(OSError):
                self.sender()('responses', llm(), 1)
        factory.assert_called_once()
        self.assertEqual(self.events()[-1]['phase'], 'CONNECTION_SETUP')
        self.assertEqual(self.events()[-1]['resultCode'], 'UPSTREAM_CONNECTION_FAILURE')

    def test_body_timeout_preserves_received_status_but_not_remote_completion(self):
        connection = MagicMock()
        connection.getresponse.return_value.status = 200
        connection.getresponse.return_value.read1.side_effect = TimeoutError('SECRET_TIMEOUT')
        with patch.object(b.http.client, 'HTTPSConnection', return_value=connection):
            with self.assertRaises(TimeoutError):
                self.sender()('responses', llm(), 1)
        self.assertEqual(self.events()[-1]['phase'], 'RESPONSE_BODY')
        self.assertEqual(self.events()[-1]['httpStatus'], 200)
        self.assertEqual(self.events()[-1]['resultCode'], 'UPSTREAM_TIMEOUT')

    def test_decoded_payload_is_not_itself_usage_or_budget_confirmation(self):
        connection = MagicMock()
        payload = {'service_tier': 'default', 'usage': {'input_tokens': 1, 'output_tokens': 1}}
        connection.getresponse.return_value.status = 200
        connection.getresponse.return_value.read1.side_effect = [json.dumps(payload).encode(), b'']
        with patch.object(b.http.client, 'HTTPSConnection', return_value=connection):
            self.assertEqual(self.sender()('responses', llm(), 1), payload)
        self.assertEqual(self.events()[-1]['phase'], 'PAYLOAD_DECODED')
        self.assertNotIn('usage', self.events()[-1])

    def test_forged_diagnostic_labels_are_ignored(self):
        b._transport_event(self.folder, 'a'*32, 'responses', 'SECRET_PHASE', 'OK', time.monotonic(), 1, 200)
        self.assertEqual(self.events(), [])


if __name__ == '__main__':
    unittest.main()
