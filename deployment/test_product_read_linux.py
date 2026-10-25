import importlib.util,json,re,tempfile,unittest
from types import SimpleNamespace
from pathlib import Path
from unittest.mock import Mock
import product_read_linux_support as linux
import product_read_resources as resources

ROOT=Path(__file__).resolve().parents[1]

class LinuxRead(unittest.TestCase):
 def test_direct_test_imports_have_compile_dependencies(self):
  gradle=(ROOT/'backend/build.gradle').read_text(encoding='utf8')
  for dependency in ['com.h2database:h2','org.junit.platform:junit-platform-launcher']:
   self.assertIn("testImplementation '"+dependency+"'",gradle)
   self.assertNotIn("testRuntimeOnly '"+dependency+"'",gradle)
 def mem(self,d,text='MemTotal: 8388608 kB\nMemAvailable: 3145728 kB\n',limit='max',used='0'):
  p=Path(d);(p/'mem').write_text(text);(p/'max').write_text(limit);(p/'current').write_text(used)
  return linux.memory(p/'mem',p/'max',p/'current')
 def test_physical_units(self):
  with tempfile.TemporaryDirectory() as d:self.assertEqual(self.mem(d)['availableBytes'],3*1024**3)
 def test_cgroup_is_not_bypassed(self):
  with tempfile.TemporaryDirectory() as d:self.assertEqual(self.mem(d,limit=str(2*1024**3),used=str(1024**3))['availableBytes'],1024**3)
 def test_unknown_memory_blocks(self):
  with tempfile.TemporaryDirectory() as d:
   with self.assertRaises(Exception):self.mem(d,text='MemFree: 100 kB\n')
 def test_memory_uses_same_threshold(self):
  self.assertEqual(resources.MIN_RAM,1920*1024**2);self.assertEqual(resources.MIN_DISK,2*1024**3)
 def test_bad_cgroup_blocks(self):
  with tempfile.TemporaryDirectory() as d:
   with self.assertRaises(Exception):self.mem(d,limit='unknown')
 def test_network_inventory_uses_actual_linux_routes(self):
  with tempfile.TemporaryDirectory() as d:
   routes=Path(d)/'route';routes.write_text('Iface Destination Gateway Flags RefCnt Use Metric Mask\neth0 0000FE0A 00000000 0001 0 0 0 F0FFFFFF\n')
   calls=[]
   def execute(args,timeout):
    calls.append(args)
    data=b'abcdef123456\n' if args[1:]==['network','ls','-q'] else json.dumps([{'IPAM':{'Config':[{'Subnet':'172.17.0.0/16'}]}}]).encode()
    return SimpleNamespace(returncode=0,stdout=data)
   plan=linux.inventory(execute,routes);self.assertTrue(plan['collisionFree']);self.assertNotEqual(plan['subnet'],'10.254.0.0/28')
   self.assertTrue(all(c[0]=='docker' for c in calls))
 def test_network_failure_never_uses_empty_inventory(self):
  with self.assertRaises(RuntimeError):linux.inventory(lambda *a,**k:SimpleNamespace(returncode=1,stdout=b''))
 def test_bridge_cannot_target_outside_owned_subnet(self):
  with self.assertRaises(RuntimeError):linux.Bridge('8.8.8.8','10.254.1.0/28')
 def test_artifact_allowlist(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);live=p/'live';live.mkdir();(live/'result.json').write_text('{"status":"BLOCKED"}');(live/'keys.private.json').write_text('SECRET')
   linux.safe_artifacts(live,p/'public',['SECRET']);self.assertFalse((p/'public/keys.private.json').exists());self.assertTrue((p/'public/manifest.json').is_file())
 def test_known_secret_blocks_upload(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);live=p/'live';live.mkdir();(live/'result.json').write_text('{"status":"SECRET"}')
   with self.assertRaises(RuntimeError):linux.safe_artifacts(live,p/'public',['SECRET'])
   self.assertFalse((p/'public/manifest.json').exists())
 def test_sanitized_receipt_summary_is_exported_not_raw_receipt(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);live=p/'live';(live/'fixture-public').mkdir(parents=True)
   (live/'fixture-public/public-trade-userA-0.json').write_text('{"txHash":"0x1","canonicalReceiptVerified":true}')
   (live/'fixture-public/receipt.private.json').write_text('SECRET')
   linux.safe_artifacts(live,p/'public',['SECRET'])
   self.assertTrue((p/'public/fixture-public/public-trade-userA-0.json').exists());self.assertFalse((p/'public/fixture-public/receipt.private.json').exists())
 def test_secret_field_blocks_upload(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);live=p/'live';live.mkdir();(live/'result.json').write_text('{"password":"oops"}')
   with self.assertRaises(RuntimeError):linux.safe_artifacts(live,p/'public',[])
 def test_workflow_scope_and_pins(self):
  s=(ROOT/'.github/workflows/product-read-linux.yml').read_text()
  self.assertIn('branches: [ci/linux-product-read]',s);self.assertIn("github.repository == 'MelonSeo/Tokenized-Market-Simulator'",s)
  self.assertIn('contents: read',s);self.assertIn('ubuntu-24.04',s);self.assertIn('retention-days: 1',s)
  uses=re.findall(r'uses: ([^\s]+)',s);self.assertEqual(len(uses),4)
  for action in uses:self.assertRegex(action,r'^actions/[a-z-]+@[0-9a-f]{40}$')
  self.assertNotIn('pull_request_target',s);self.assertNotIn('secrets.',s)
 def test_source_identity_covers_linux_and_workflow(self):
  spec=importlib.util.spec_from_file_location('linux_gate',ROOT/'deployment/product-read-linux.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
  names={n.replace('\\','/') for n in module.sources()}
  for name in ['deployment/ProductReadFixture.java','deployment/product_read_linux_support.py','.github/workflows/product-read-linux.yml']:self.assertIn(name,names)
 def test_no_old_snapshot_in_linux_runner(self):
  s=(ROOT/'deployment/product-read-linux.py').read_text()
  for term in ['anvil-stopped-state','CROOT','C:/Users','--load-state']:self.assertNotIn(term,s)
 def test_fixture_role_and_economics_recipe(self):
  s=(ROOT/'deployment/ProductReadFixture.java').read_text()
  self.assertIn('new SecureRandom()',s);self.assertIn('g.expect(receipt,expected)',s);self.assertIn('g.verifyRuntime(',s)
  self.assertIn('NEW_CI_RECIPE_NOT_C5A2_SNAPSHOT',s);self.assertIn('new Relayed(',s)
  self.assertNotIn('keys.private.json",privateKeys',s)
 def test_runtime_preserves_alias_and_fixed_read_policy(self):
  runner=(ROOT/'deployment/product-read-linux.py').read_text();driver=(ROOT/'deployment/ProductReadDriver.java').read_text()
  self.assertIn("args+=['--network-alias',role]",runner)
  self.assertIn('requireCanonical',driver);self.assertIn('snapshot.finish()',driver)
  self.assertNotIn('--network=host',runner);self.assertNotIn('--publish',runner);self.assertNotIn('docker system prune',runner)
 def test_compiler_metadata_rejects_outside_source(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);p=root/'contracts/out/CMockCollateral.sol';p.mkdir(parents=True);dest=root/'fixture';dest.mkdir()
   meta={'compiler':{'version':'0.8.24+commit.e11b9ed9'},'settings':{'optimizer':{'enabled':True,'runs':200},'compilationTarget':{'src/CMockCollateral.sol':'CMockCollateral'}},'sources':{'../../secret.sol':{'keccak256':'0x'+'1'*64}}}
   (p/'CMockCollateral.json').write_text(json.dumps({'rawMetadata':json.dumps(meta)}))
   with self.assertRaises(RuntimeError):linux.artifact_fixture(root,dest)

if __name__=='__main__':unittest.main()
