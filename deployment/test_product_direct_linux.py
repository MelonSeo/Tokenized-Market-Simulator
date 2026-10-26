import ast,importlib.util,json,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
class DirectLinux(unittest.TestCase):
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
 def test_workflow_runs_read_before_write_and_is_development_only(self):
  w=(ROOT/'.github/workflows/product-direct-linux.yml').read_text()
  self.assertIn('branches: [feat/c-owner-direct-integration]',w);self.assertIn("github.repository == 'MelonSeo/Tokenized-Market-Simulator'",w)
  self.assertLess(w.index('product-read-linux.py run'),w.index('product-direct-linux.py run'))
  self.assertIn('contents: read',w);self.assertIn('productDirectCiClasses',w);self.assertNotIn('secrets.',w)
 def test_trial_wallet_separation_and_bounded_flow(self):
  s=(ROOT/'deployment/product-direct-linux.py').read_text(encoding='utf8');b=(ROOT/'tools/websocket-test-client/scripts/product-direct-browser.mjs').read_text(encoding='utf8')
  self.assertIn('DIRECT_WALLET_TOKEN',s);self.assertIn("mount(live/'keys','/keys')",s)
  app=s[s.index("app=launch('app'"):s.index("deadline=time.monotonic()+120")]
  self.assertNotIn("live/'keys'",app);self.assertNotIn("live/'oracle-config'",app);self.assertNotIn('env-file',app)
  for kind in ['APPROVE','DEPOSIT','OPEN','CLOSE','WITHDRAW']:self.assertIn(kind,b)
  self.assertIn('realMetaMask:false',b);self.assertIn('end=Date.now()+150000',b)
if __name__=='__main__':unittest.main()
