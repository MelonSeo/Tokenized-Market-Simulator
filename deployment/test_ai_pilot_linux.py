"""Pure topology/source allowlist checks. No Docker/server/key access."""
import json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import ai_pilot_linux as live

class LinuxTopologyTests(unittest.TestCase):
 def test_fresh_genesis_is_sampled_at_process_launch_without_time_travel(self):
  args=live.fresh_anvil_command()
  self.assertEqual(args[:3],['-c','exec anvil --timestamp "$(date +%s)" "$@"','anvil'])
  self.assertNotIn('--load-state',args);self.assertNotIn('evm_setNextBlockTimestamp',args)
  self.assertEqual(args[args.index('--block-time')+1],'1')
  self.assertEqual(args[args.index('--state')+1],'/data/state.json')
  source=Path(live.__file__).read_text(encoding='utf8')
  self.assertNotIn('clock.configure(rpc)',source)
  self.assertIn("save(folder/'clock-ready.json',clock.require_ready(rpc))",source)
 def test_existing_strict_transport_flags_are_kept_in_both_jvm_paths(self):
  for file in [Path(live.__file__),Path(live.__file__).with_name('ai_pilot_linux_inner.py')]:
   source=file.read_text()
   for flag in ['-Djdk.httpclient.disableRetryConnect=true','-Djdk.httpclient.enableAllMethodRetry=false','-Djdk.httpclient.redirects.retrylimit=1']:self.assertIn(flag,source)
 def test_fresh_names_and_internal_services_have_no_host_port_or_privilege_flags(self):
  run='pilot-linux-'+'a'*16;t=live.topology(run)
  for role in ['pg','anvil','app']:
   args=live.container_args(run,t[role],'image',t['network'],role,['--memory=384m'])
   self.assertNotIn('-p',args);self.assertNotIn('--publish',args);self.assertNotIn('--privileged',args);self.assertNotIn('--cap-add',args);self.assertIn(role,args)
 def test_foreign_names_and_security_bypass_options_are_rejected(self):
  run='pilot-linux-'+'b'*16
  with self.assertRaises(ValueError):live.container_args(run,'existing-user-container','image','net','app')
  for flag in ['-p','--publish','--privileged','--cap-add','--security-opt']:
   with self.assertRaises(ValueError):live.container_args(run,run+'-app','image','net','app',[flag])
 def test_staging_never_copies_private_runtime_env_or_original_checkpoint(self):
  source=Path(live.__file__).read_text(encoding='utf8')
  self.assertNotIn("shutil.copytree(ROOT/'deployment/runtime'",source)
  self.assertIn("p.name=='.env'",source)
  self.assertIn("p.is_symlink()",source)
 def test_image_and_browser_preserve_default_sandbox(self):
  root=Path(live.__file__).resolve().parents[1]
  browser=(root/'tools/websocket-test-client/scripts/ai-pilot-v2-browser.mjs').read_text()
  dockerfile=(root/'deployment/pilot-linux-image/Dockerfile').read_text()
  self.assertNotIn('--no-sandbox',browser);self.assertIn('USER 1000:1000',dockerfile);self.assertIn('chromium chromium-sandbox',dockerfile)
 def test_linux_inner_runs_first_rpc_c_agent_before_full_and_denies_other_platforms(self):
  import ai_pilot_linux_inner as inner
  source=Path(live.__file__).with_name('ai_pilot_linux_inner.py').read_text()
  self.assertIn("sys.platform!='linux'",source)
  self.assertEqual([s[0] for s in inner.stages('FULL')],['C_SMOKE','FULL_HTTP_CHROME'])
  self.assertEqual([s[3] for s in inner.stages('FULL')],[160,1500])
 def test_explicit_a2_scope_stops_before_browser_without_becoming_full_pass(self):
  import ai_pilot_linux_inner as inner
  self.assertEqual([s[0] for s in inner.stages('A2_PREFIX')],['A2_PREFIX_HTTP'])
  with self.assertRaisesRegex(RuntimeError,'LINUX_HTTP_SCOPE_INVALID'):inner.stages('UNKNOWN')
  source=Path(inner.__file__).read_text()
  self.assertIn('PASS_A2_HTTP_TRANSPORT_NOT_QUALITY',source)
  self.assertIn("'full30':'NOT_RUN'",source)
 def test_novel30_scope_is_explicit_and_does_not_launch_c_or_browser(self):
  import ai_pilot_linux_inner as inner
  self.assertEqual([s[0] for s in inner.stages('NOVEL30')],['NOVEL30_HTTP'])
  self.assertEqual(inner.stages('NOVEL30')[0][3],1500)
  with self.assertRaisesRegex(ValueError,'LINUX_HTTP_SCOPE_CONFLICT'):live.run(True,True)
 def test_c_latency_scope_keeps_coordinator_prefix_and_stops_before_browser(self):
  import ai_pilot_linux_inner as inner
  self.assertEqual([s[0] for s in inner.stages('C_LATENCY')],['C_LATENCY_HTTP'])
  self.assertEqual(inner.stages('C_LATENCY')[0][3],1500)
  with self.assertRaisesRegex(ValueError,'LINUX_HTTP_SCOPE_CONFLICT'):live.run(False,True,True)
  self.assertIn('str(receipt_probe or receipt_agent or c_latency or native_browser).lower()',Path(live.__file__).read_text())
 def test_receipt_probe_does_not_repeat_full_acceptance(self):
  import ai_pilot_linux_inner as inner
  stage=inner.stages('RECEIPT_PROBE');self.assertEqual(len(stage),1);self.assertTrue(stage[0][1].endswith('PilotReceiptProbeTest'));self.assertEqual(stage[0][3],480)
  with self.assertRaisesRegex(ValueError,'LINUX_HTTP_SCOPE_CONFLICT'):live.run(False,False,True,True)
 def test_receipt_agent_uses_original_guarded_prefix_without_full30(self):
  import ai_pilot_linux_inner as inner
  stage=inner.stages('RECEIPT_AGENT');self.assertEqual(stage[0][0],'RECEIPT_AGENT_PREFIX_HTTP');self.assertTrue(stage[0][1].endswith('PilotV2LiveHttpTest'));self.assertEqual(stage[0][3],1200)
  source=(live.ROOT/'backend/src/test/java/com/pricetrack/exchange/futures/chain/PilotV2LiveHttpTest.java').read_text(encoding='utf8')
  self.assertIn('Thread.sleep(180000)',source);self.assertIn('phase.equals("SAFETY_LEGACY")',source);self.assertIn('PASS_PREFIX_NOT_FULL_ACCEPTANCE',source)
  with self.assertRaisesRegex(ValueError,'LINUX_HTTP_SCOPE_CONFLICT'):live.run(receipt_probe=True,receipt_agent=True)
 def test_local_copy_checks_every_staged_hash_and_rejects_drift(self):
  import hashlib,ai_pilot_linux_inner as inner
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);p=root/'public';p.write_bytes(b'public');m={'public':hashlib.sha256(b'public').hexdigest()}
   self.assertEqual(inner.verify_source(root,m),1)
   p.write_bytes(b'drift')
   with self.assertRaisesRegex(RuntimeError,'COPY_MISMATCH'):inner.verify_source(root,m)
   with self.assertRaisesRegex(RuntimeError,'SOURCE_MANIFEST'):inner.verify_source(root,{'../private':m['public']})
 def test_native_browser_only_uses_explicit_full_local_scope(self):
  with self.assertRaisesRegex(ValueError,'NATIVE_BROWSER_REQUIRES'):live.run(native_browser=True)
  with self.assertRaisesRegex(ValueError,'NATIVE_BROWSER_REQUIRES'):live.run(c_latency=True,local_source=True,native_browser=True)

if __name__=='__main__':unittest.main()
