import json,tempfile,unittest,subprocess
from pathlib import Path
from unittest.mock import patch
import ai_receipt_clone as c
class ReceiptCloneTests(unittest.TestCase):
 def test_invalid_source_never_reaches_docker(self):
  with patch.object(c,'cmd') as command:
   for source in ['existing-user','../pilot-linux-'+'a'*16,'pilot-linux-x']:
    with self.assertRaises(ValueError):c.run(source)
   command.assert_not_called()
 def test_only_unpaid_failed_trial_is_accepted(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);name='pilot-linux-'+'a'*16;base=root/'deployment/runtime'/name;base.mkdir(parents=True)
   (base/'owned.json').write_text(json.dumps({'run':name,'scope':'FULL','paidCalls':0,'apiKeyReads':0}));(base/'result.json').write_text('{"status":"PARTIAL"}')
   with patch.object(c,'ROOT',root),patch.object(c,'cmd') as command:
    with self.assertRaisesRegex(ValueError,'UNPAID_FAILURE'):c.run(name)
    command.assert_not_called()
 def test_fingerprint_is_readonly_and_rejects_arbitrary_database(self):
  with patch.object(c,'cmd',return_value='{}') as command:
   self.assertEqual(c.fingerprint('owned-pg','exchange_ai_pilot_trade_'+'a'*16),{})
   self.assertIn('BEGIN READ ONLY',command.call_args.args[0][-1]);self.assertNotIn('UPDATE ',command.call_args.args[0][-1])
   command.reset_mock()
   with self.assertRaises(ValueError):c.fingerprint('owned-pg','postgres;drop database x')
   command.assert_not_called()
 def test_copy_is_cold_readonly_and_never_reuses_source_sqlite(self):
  text=Path(c.__file__).read_text()
  self.assertIn('target=/from,readonly',text);self.assertIn('test -z',text);self.assertIn("item['State']['Running']",text)
  self.assertNotIn("copyfile(base/'session",text);self.assertNotIn('prune',text)
 def test_app_limit_can_satisfy_unchanged_linux_1920m_guard(self):
  text=Path(c.__file__).read_text();self.assertIn("create('app',image,['--memory=2560m'",text)
  self.assertNotIn('--memory=1536m',text)
 def test_log_timeout_never_skips_owned_cleanup(self):
  with tempfile.TemporaryDirectory() as d,patch.object(c.subprocess,'run',side_effect=subprocess.TimeoutExpired('docker',10)),patch.object(c,'stop_owned_container',return_value='STOPPED') as stop:
   result=c.cleanup_resources(Path(d),[('trial-pg','pg'),('trial-anvil','anvil'),('trial-app','app')],'trial','trial-anvil')
   self.assertEqual(len(result),3);self.assertEqual(stop.call_count,3);self.assertEqual(set(result.values()),{'STOPPED'})
 def test_log_write_failure_and_one_stop_failure_do_not_skip_others(self):
  with tempfile.TemporaryDirectory() as d,patch.object(c.subprocess,'run',return_value=subprocess.CompletedProcess([],0,b'',b'')),patch.object(Path,'write_bytes',side_effect=OSError('disk')),patch.object(c,'stop_owned_container',side_effect=[RuntimeError('diagnostic'), 'STOPPED','STOPPED']) as stop:
   result=c.cleanup_resources(Path(d),[('trial-pg','pg'),('trial-anvil','anvil'),('trial-app','app')],'trial','trial-anvil')
   self.assertEqual(stop.call_count,3);self.assertEqual(result['trial-app'],'UNCONFIRMED');self.assertEqual(result['trial-pg'],'STOPPED')
if __name__=='__main__':unittest.main()
