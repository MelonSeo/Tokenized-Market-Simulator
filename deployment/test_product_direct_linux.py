import ast,importlib.util,json,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
class DirectLinux(unittest.TestCase):
 def test_read_and_write_bridges_have_separate_fixed_ports_without_changing_read_default(self):
  import product_read_linux_support as support
  self.assertEqual(support.Bridge('10.254.1.1','10.254.1.0/28').port,32171)
  self.assertEqual(support.Bridge('10.254.1.1','10.254.1.0/28',port=32173).port,32173)
  with self.assertRaises(Exception):support.Bridge('10.254.1.1','10.254.1.0/28',port=12345)
  driver=(ROOT/'deployment/ProductDirectDriver.java').read_text(encoding='utf8')
  self.assertIn('http://127.0.0.1:32173',driver);self.assertIn('identity.origin()',driver)
 def test_gradle_source_list_is_explicit_method_call_not_index_access(self):
  s=(ROOT/'deployment/product-direct-ci.gradle').read_text(encoding='utf8')
  self.assertIn("source(['ProductDirectFixture'",s);self.assertNotIn("source ['",s)
 def module(self):
  spec=importlib.util.spec_from_file_location('direct_linux',ROOT/'deployment/product-direct-linux.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
 def test_separate_runtime_and_read_unchanged(self):
  s=(ROOT/'deployment/product-direct-linux.py').read_text();ast.parse(s)
  self.assertIn('deployment/runtime/product-direct-linux',s)
  self.assertIn("'ProductDirectGate'",s);self.assertIn("launch('oracle'",s)
  read=(ROOT/'deployment/product-read-linux.py').read_text()
  self.assertIn("'ProductReadGate'",read);self.assertNotIn('ProductDirectGate',read)
 def test_new_inputs_are_fingerprinted(self):
  m=self.module();names={n.replace('\\','/') for n in m.sources()}
  self.assertIn('backend/src/test/java/com/pricetrack/exchange/custody/authorization/CReportCandidateBuilderTest.java',names)
  for p in ['deployment/ProductDirectFixture.java','deployment/ProductDirectDriver.java','deployment/ProductDirectGate.java','deployment/CDirectOracleServer.java','deployment/product-direct-ci.gradle','deployment/test_product_direct_linux.py','.github/workflows/product-direct-linux.yml','tools/websocket-test-client/scripts/product-direct-browser.mjs']:self.assertIn(p,names)
 def test_public_artifact_redaction_blocks_secrets(self):
  m=self.module()
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);live=p/'live';live.mkdir();(live/'result.json').write_text('{"status":"secret_value"}')
   with self.assertRaises(Exception):m.safe_artifacts(live,p/'public',['secret_value'])
   self.assertFalse((p/'public/manifest.json').exists())
 def test_public_allowlist_excludes_keys(self):
  m=self.module()
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);live=p/'live';live.mkdir();(live/'result.json').write_text('{"status":"PASS"}');(live/'keys.private.json').write_text('SECRET')
   m.safe_artifacts(live,p/'public',['SECRET']);self.assertFalse((p/'public/keys.private.json').exists())
 def test_idle_direct_chain_uses_normal_interval_mining_without_timestamp_overrides(self):
  m=self.module()
  self.assertEqual(m.anvil_parameters(),['--host','0.0.0.0','--port','8545','--accounts','0','--chain-id','31337','--block-time','1','--silent'])
  tree=ast.parse((ROOT/'deployment/product-direct-linux.py').read_text())
  calls=[n for n in ast.walk(tree) if isinstance(n,ast.Call) and isinstance(n.func,ast.Name) and n.func.id=='launch' and n.args and isinstance(n.args[0],ast.Constant) and n.args[0].value=='anvil']
  self.assertEqual(len(calls),1);self.assertEqual(ast.unparse(calls[0].args[3]),'anvil_parameters()')
  self.assertIn("--tests '*CReportCandidateBuilderTest'",(ROOT/'.github/workflows/product-direct-linux.yml').read_text())
 def test_execution_failure_artifact_contains_only_safe_metadata(self):
  m=self.module()
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);live=p/'live';(live/'appEvidence').mkdir(parents=True)
   data={'statusAtFilter':200,'bearerHeaderPresent':True,'causeTypes':['java.lang.IllegalArgumentException'],'rulesReportTimeRejected':True}
   (live/'appEvidence/execution-failure.json').write_text(json.dumps(data));(live/'appEvidence/raw.private.log').write_text('SECRET')
   m.safe_artifacts(live,p/'public',['SECRET']);self.assertEqual(json.loads((p/'public/appEvidence/execution-failure.json').read_text()),data)
   self.assertFalse((p/'public/appEvidence/raw.private.log').exists())
 def test_workflow_runs_read_before_write_on_personal_integration_branches(self):
  w=(ROOT/'.github/workflows/product-direct-linux.yml').read_text()
  self.assertIn('branches: [melon-init, feat/c-owner-direct-integration]',w);self.assertIn("github.repository == 'MelonSeo/Tokenized-Market-Simulator'",w)
  self.assertLess(w.index('product-read-linux.py run'),w.index('product-direct-linux.py run'))
  self.assertIn('contents: read',w);self.assertIn('productDirectCiClasses',w);self.assertNotIn('secrets.',w)
 def test_workflow_job_matches_trigger_without_widening_repository_or_cleanup(self):
  w=(ROOT/'.github/workflows/product-direct-linux.yml').read_text()
  self.assertIn("if: github.repository == 'MelonSeo/Tokenized-Market-Simulator' && (github.ref == 'refs/heads/melon-init' || github.ref == 'refs/heads/feat/c-owner-direct-integration')",w)
  self.assertNotIn('pull_request_target',w);self.assertNotIn('workflow_dispatch',w)
  for required in ['timeout-minutes: 30','cancel-in-progress: false','retention-days: 1','python3 -B deployment/product-read-linux.py cleanup','python3 -B deployment/product-direct-linux.py cleanup']:
   self.assertIn(required,w)
 def test_trial_wallet_separation_and_bounded_flow(self):
  s=(ROOT/'deployment/product-direct-linux.py').read_text(encoding='utf8');b=(ROOT/'tools/websocket-test-client/scripts/product-direct-browser.mjs').read_text(encoding='utf8')
  self.assertIn('DIRECT_WALLET_TOKEN',s);self.assertIn("mount(live/'keys','/keys')",s)
  app=s[s.index("app=launch('app'"):s.index("deadline=time.monotonic()+120")]
  self.assertNotIn("live/'keys'",app);self.assertNotIn("live/'oracle-config'",app);self.assertNotIn('env-file',app)
  for kind in ['APPROVE','DEPOSIT','OPEN','CLOSE','WITHDRAW']:self.assertIn(kind,b)
  self.assertIn('realMetaMask:false',b);self.assertIn('end=Date.now()+150000',b)
if __name__=='__main__':unittest.main()
