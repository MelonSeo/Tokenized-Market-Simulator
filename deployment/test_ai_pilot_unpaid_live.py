"""Pure runner ownership/env checks; no Docker/server/key access."""
import json,os,subprocess,tempfile,unittest
from pathlib import Path
from unittest.mock import patch,Mock
import ai_pilot_unpaid_live as live


class OwnedRunnerTests(unittest.TestCase):
    def test_finish_validates_entire_delete_set_before_any_removal(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);base=root/'deployment/runtime/pilot-v2-test';base.mkdir(parents=True)
            (base/'owned.json').write_text(json.dumps({'run':base.name,'container':base.name+'-pg'}))
            (root/'deployment/product-read-ci-lock.json').write_text(json.dumps({'images':{'anvil':'anvil-test','java':'java-test'}}))
            names=[base.name+'-pg',base.name+'-fcafeaf7f-anvil',base.name+'-extract',base.name+'-unexpected']
            def command(args,**kwargs):
                if args[1:3]==['container','ls']:return '\n'.join(names)
                if args[1]=='inspect':
                    image='tokenized-ai-latest-tools:phase-a-20261029174809-d30067c2'
                    return json.dumps([{'Name':'/'+args[2],'State':{'Running':False},'Config':{'Labels':{live.LABEL:base.name},'Image':image}}])
                raise AssertionError('Must not delete any container when a later target fails validation')
            with patch.object(live,'ROOT',root),patch.object(live,'cmd',side_effect=command) as cmd:
                with self.assertRaisesRegex(RuntimeError,'V2_CLEANUP_IMAGE'):live.finish(base)
                self.assertFalse(any(c.args[0][1]=='rm' for c in cmd.call_args_list))
    def test_cleanup_checks_possibly_committed_container_after_create_timeout(self):
        def item(running):return json.dumps([{'Name':'/trial-pg','State':{'Running':running},'Config':{'Labels':{live.LABEL:'trial'},'Image':live.IMAGE}}])
        with patch.object(live,'cmd',side_effect=['trial-pg',item(True),'stopped',item(False)]) as command:
            self.assertEqual(live.stop_owned_pg('trial-pg','trial'),'STOPPED')
            self.assertEqual(command.call_count,4);self.assertEqual(command.call_args_list[-2].args[0],['docker','stop','--time','10','trial-pg'])
    def test_child_kill_error_does_not_raise_and_pg_cleanup_remains_callable(self):
        process=Mock();process.poll.return_value=None
        with patch.object(live.subprocess,'run',side_effect=subprocess.CalledProcessError(1,['taskkill'])):
            self.assertEqual(live.stop_child(process),'UNCONFIRMED')
        with patch.object(live,'cmd',return_value=''):
            self.assertEqual(live.stop_owned_pg('trial-pg','trial'),'NOT_CREATED')
    def test_wrong_owner_or_inspect_error_is_unconfirmed_not_stopped(self):
        with patch.object(live,'cmd',side_effect=['trial-pg',json.dumps([{'Name':'/trial-pg','Config':{'Labels':{live.LABEL:'foreign'},'Image':live.IMAGE}}])]) as command:
            self.assertEqual(live.stop_owned_pg('trial-pg','trial'),'UNCONFIRMED');self.assertEqual(command.call_count,2)
    def test_provider_credentials_never_enter_child_environment(self):
        with patch.dict(os.environ,{'OPENAI_API_KEY':'FAKE_NOT_A_KEY','AI_API_KEY':'FAKE_NOT_A_KEY','AI_PILOT_V2_APPROVAL':'FAKE_REFERENCE','PATH':'test-only'},clear=True):
            env=live.env()
        self.assertNotIn('OPENAI_API_KEY',env);self.assertNotIn('AI_API_KEY',env);self.assertNotIn('AI_PILOT_V2_APPROVAL',env)
        self.assertEqual(env['AI_ENABLED'],'false')

    def test_owned_pg_requires_exact_name_label_and_image(self):
        for change in ({},{'Name':'/foreign'},{'label':'foreign'},{'image':'foreign'}):
            item={'Name':change.get('Name','/trial-pg'),'Config':{'Labels':{live.LABEL:change.get('label','trial')},'Image':change.get('image',live.IMAGE)}}
            with self.subTest(change=change),patch.object(live,'cmd',return_value=json.dumps([item])):
                if change:
                    with self.assertRaisesRegex(RuntimeError,'V2_OWNERSHIP_MISMATCH'):live.owned('trial-pg','trial')
                else:self.assertEqual(live.owned('trial-pg','trial'),item)


if __name__=='__main__':unittest.main()
