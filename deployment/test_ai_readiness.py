"""Pure guard, evidence and CI boundary regressions. No Docker/provider execution."""
import copy
import ast
import inspect
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import unittest
import tempfile
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('readiness',Path(__file__).with_name('ai-readiness.py'))
r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)


class ReadinessTests(unittest.TestCase):
    def metrics(self):
        return dict(schema=1,baseCommit=json.loads(r.PLAN.read_text())['baseCommit'],planSha256=r.plan_hash(),
                    mode='CONTROLLED_NO_PAID_PROVIDER',environment={},load=[],cache={},faults=[],security={},resources={},invariants={},verdict='PASS')

    def test_preregistered_plan(self):
        self.assertEqual('f31b4b53d8072ae1fb3382ba873abbd6f370dcc91cc657cf7dd08374edcecfc2',r.plan_hash())

    def test_failure_diagnostics_never_publish_assertion_values(self):
        with tempfile.TemporaryDirectory() as d:
            f=Path(d)/'result.xml';f.write_text('<testsuite><testcase classname="com.pricetrack.exchange.ai.ExampleTest" name="boundary()"><failure type="java.lang.AssertionError">SECRET_VALUE at com.pricetrack.exchange.ai.ExampleTest.boundary(ExampleTest.java:12)</failure></testcase></testsuite>')
            result=r.safe_junit_failures(f)
        self.assertEqual(1,len(result));self.assertNotIn('SECRET_VALUE',json.dumps(result));self.assertIn('ExampleTest.java:12',json.dumps(result))

    def test_pilot_acceptance_and_manifest_are_frozen_separately_from_old_corpus(self):
        for name,expected in [('backend/src/test/resources/ai/pilot-acceptance-v1.json','08c034ac7f50ecffbbc3d0f6ae9da0c7e611b4c948a0849533fddbe21983e3cb'),('docs/ai/ingest-manifest-pilot.json','13d64f3e3cadd7446e30015c2cb2054e2c0046d543df0a3ebbc28fd67e2a5191')]:
            self.assertEqual(expected,hashlib.sha256((r.REPO/name).read_bytes()).hexdigest())

    def test_browser_cdp_timeout_contract_is_numeric(self):
        script=(r.REPO/'tools/websocket-test-client/scripts/ai-pilot-browser.mjs').read_text(encoding='utf8')
        self.assertIn('returnByValue:true,awaitPromise:true},5000)',script)
        self.assertNotIn('awaitPromise:true},{timeoutMs:',script)

    def test_screenshot_loop_cannot_shadow_owned_container_identity(self):
        source=inspect.getsource(r.run);tree=ast.parse(source)
        targets={n.id for loop in ast.walk(tree) if isinstance(loop,(ast.For,ast.comprehension)) for n in ast.walk(loop.target) if isinstance(n,ast.Name)}
        self.assertNotIn('name',targets);self.assertIn('screenshot_name',targets)
        self.assertIn("if not removed:raise RuntimeError('OWN_CONTAINER_CLEANUP_UNCONFIRMED')",source)

    def test_web_artifact_allowlist_and_hash_are_fail_closed(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d);data=b'\x89PNG\r\n\x1a\n'
            for name in ('01-ai-agent.png','02-ai-abnormal.png'):(folder/name).write_bytes(data)
            m={'mode':'OWNED_FIXTURE_CONTROLLED_PROVIDER_NOT_REAL_MODEL','sha256':{name:hashlib.sha256(data).hexdigest() for name in ('01-ai-agent.png','02-ai-abnormal.png')}}
            (folder/'manifest.json').write_text(json.dumps(m));r.verify_web(folder)
            (folder/'private.log').write_text('private')
            with self.assertRaises(ValueError):r.verify_web(folder)
            (folder/'private.log').unlink();(folder/'01-ai-agent.png').write_bytes(b'corrupt')
            with self.assertRaises(ValueError):r.verify_web(folder)

    def test_exact_threshold(self):
        self.assertTrue(r.resource_result(1920*1024**2,2*1024**3)['ready'])

    def test_ram_one_byte_below_blocks_despite_rounding(self):
        self.assertFalse(r.resource_result(1920*1024**2-1,2*1024**3)['ready'])

    def test_disk_one_byte_below_blocks(self):
        self.assertFalse(r.resource_result(1920*1024**2,2*1024**3-1)['ready'])

    def test_no_paid_credentials_in_child_environment(self):
        with patch.dict(os.environ,{'OPENAI_API_KEY':'canary','AI_API_KEY':'canary','AI_DB_PASSWORD':'canary','AI_LATEST_LIVE':'true'},clear=True):
            env=r.clean_env()
        self.assertFalse(any('canary' in v for v in env.values()))
        self.assertNotIn('AI_LATEST_LIVE',env)
        self.assertEqual('false',env['BLOCKCHAIN_ENABLED'])

    def test_numeric_public_evidence(self):
        m=self.metrics();m['load']=[{'samples':32,'p95Ms':12.5}]
        self.assertIs(m,r.validate_metrics(m))

    def test_sensitive_fields_block(self):
        for key in ['password','apiKey','answer','question','jdbcUrl','toolEvidence','accessToken']:
            m=self.metrics();m['resources'][key]='canary'
            with self.subTest(key=key),self.assertRaises(ValueError):r.validate_metrics(m)

    def test_paths_tokens_and_nonfinite_block(self):
        for value in ['C:/Users/local/runtime','Bearer_secret','eyJcanary','sk-canary',float('nan'),float('inf')]:
            m=self.metrics();m['resources']['extra']=value
            with self.subTest(value=str(value)),self.assertRaises(ValueError):r.validate_metrics(m)

    def test_identity_and_schema_block(self):
        for key,value in [('planSha256','0'*64),('baseCommit','0'*40),('unexpected',True)]:
            m=self.metrics();m[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):r.validate_metrics(m)

    def test_owned_resource_required(self):
        obj={'Name':'/owned','Config':{'Labels':{'tms.ai-readiness.run':'run'},'Image':json.loads(r.PLAN.read_text())['image']}}
        with patch.object(r,'command',return_value=json.dumps([obj])):self.assertEqual(obj,r.inspect_owned('owned','run'))
        for bad in ['label','name','image']:
            changed=copy.deepcopy(obj)
            if bad=='label':changed['Config']['Labels']['tms.ai-readiness.run']='foreign'
            elif bad=='name':changed['Name']='/foreign'
            else:changed['Config']['Image']='foreign'
            with patch.object(r,'command',return_value=json.dumps([changed])),self.assertRaises(RuntimeError):r.inspect_owned('owned','run')

    def test_workflow_has_separate_scope_and_fail_closed_artifacts(self):
        workflow=(r.REPO/'.github/workflows/ai-unpaid-readiness.yml').read_text()
        self.assertIn('branches: [main, ci/ai-unpaid-readiness-20261031]',workflow)
        self.assertIn("github.repository == 'MelonSeo/Tokenized-Market-Simulator'",workflow)
        self.assertIn('contents: read',workflow)
        self.assertIn("steps.public_evidence.outcome == 'success'",workflow)
        self.assertNotIn('secrets.',workflow)
        for line in workflow.splitlines():
            if 'uses:' in line:self.assertRegex(line,r'@[a-f0-9]{40}$')


if __name__=='__main__':unittest.main()
