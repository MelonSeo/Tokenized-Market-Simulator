import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch,MagicMock
import ai_index_copy_fixture as fixture
from ai_evaluation_broker import prepare

class CopyFixtureTest(unittest.TestCase):
    def test_live_copy_cannot_claim_without_fitting_dry_run(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);old=prepare(root/'parent',59990,mode='live');owner=old['runId']
            c={'Id':'a'*64,'Image':'sha256:'+'a'*64,'State':{'Running':False,'ExitCode':0},'HostConfig':{'NetworkMode':'none'},'Config':{'Labels':{'ai-evaluation.run':owner}},'Mounts':[{'Type':'volume','Destination':'/var/lib/postgresql/data','Name':'parent-owned-volume'}]}
            def info(*args):return [c] if args[0]=='inspect' else [{'Labels':{'ai-evaluation.run':owner}}]
            with patch.object(fixture,'docker_json',side_effect=info),patch.object(fixture,'command',return_value=b''):
                with self.assertRaisesRegex(RuntimeError,'DRY_RUN_BUDGET_BLOCKED'):fixture.create(root/'parent',root/'child',59991)
            self.assertFalse((root/'child').exists())
    def test_running_parent_is_never_stopped_or_copied(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);prepare(root/'parent',59990,mode='live')
            with patch.object(fixture,'docker_json',return_value=[{'State':{'Running':True}}]),patch.object(fixture,'command') as command:
                with self.assertRaisesRegex(RuntimeError,'PARENT_NOT_SAFE_COLD_SOURCE'):fixture.create(root/'parent',root/'child',59991,probe=True)
                command.assert_not_called()
    def test_probe_mount_is_read_only_no_network_no_pull_and_secrets_not_imported(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);old=prepare(root/'parent',59990,mode='live');owner=old['runId'];calls=[]
            c={'Id':'a'*64,'Image':'sha256:'+'a'*64,'State':{'Running':False,'ExitCode':0},'HostConfig':{'NetworkMode':'none'},'Config':{'Labels':{'ai-evaluation.run':owner}},'Mounts':[{'Type':'volume','Destination':'/var/lib/postgresql/data','Name':'parent-owned-volume'}]}
            def info(*args):return [c] if args[0]=='inspect' else [{'Labels':{'ai-evaluation.run':owner}}]
            artifact={'owner':{'run_id':owner,'source_hash':old['sourceHash'],'corpus_hash':old['corpusHash'],'state':'READY'},'indexes':[],'documents':[],'chunks':[]}
            def command(args,**kwargs):
                calls.append(args);return json.dumps(artifact).encode() if args[:2]==['exec','-i'] else b''
            with patch.object(fixture,'docker_json',side_effect=info),patch.object(fixture,'command',side_effect=command),patch.object(fixture.subprocess,'run',return_value=MagicMock(returncode=0)):
                fixture.create(root/'parent',root/'probe',59991,probe=True)
            runs=[x for x in calls if x[0]=='run'];self.assertEqual(len(runs),3)
            for command in runs:self.assertIn('none',command);self.assertIn('never',command)
            self.assertIn('type=volume,src=parent-owned-volume,dst=/source,readonly',runs[0])
            self.assertFalse((root/'probe'/'approval.json').exists());self.assertFalse((root/'probe'/'openai.key').exists())
            targetEnv=(root/'probe'/'db.env').read_text();self.assertNotIn(old['dbUser'],targetEnv)

if __name__=='__main__':unittest.main()
