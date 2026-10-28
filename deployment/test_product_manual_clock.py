import copy, json, tempfile, unittest
from pathlib import Path
from unittest.mock import Mock, patch
import product_manual_clock as c
import product_read_resources as resources


class Clock(unittest.TestCase):
    def test_maintenance_exception_is_explicit_and_global_floor_unchanged(self):
        m=Mock()
        c.resource_admission(m,Path('not-created'))
        m.admission.assert_called_once_with(Path('not-created'))
        self.assertEqual(resources.MIN_RAM,1920*1024**2)

    def test_maintenance_floor_disk_and_failure_observations(self):
        for available,disk,expected in [(1024**3,2*1024**3,'SUFFICIENT'),(1024**3-1,3*1024**3,'RAM_LOW'),(1400*1024**2,2*1024**3-1,'DISK_LOW')]:
            with self.subTest(expected=expected),tempfile.TemporaryDirectory() as d:
                m=Mock();m.resources=resources;m.ROOT=Path(d)
                m.memory.return_value={'availableBytes':available}
                m.shutil.disk_usage.return_value=type('Disk',(),{'free':disk})()
                p=Path(d)/'resources.jsonl'
                if expected=='SUFFICIENT':c.resource_admission(m,p,True)
                else:
                    with self.assertRaisesRegex(ValueError,'MAINTENANCE_RESOURCES_LOW'):c.resource_admission(m,p,True)
                row=json.loads(p.read_text().splitlines()[-1]);self.assertEqual(row['requiredRAMBytes'],1920*1024**2)
                self.assertEqual(m.save.call_args.args[1]['decision'],expected)
                self.assertEqual(resources.MIN_RAM,1920*1024**2)
                m.admission.assert_not_called()

    def test_maintenance_measurement_failure_cannot_bypass_check(self):
        with tempfile.TemporaryDirectory() as d:
            m=Mock();m.resources=resources;m.ROOT=Path(d);m.memory.side_effect=OSError()
            m.shutil.disk_usage.return_value=type('Disk',(),{'free':3*1024**3})()
            p=Path(d)/'resources.jsonl'
            with self.assertRaises(resources.ResourceError):c.resource_admission(m,p,True)
            self.assertEqual(json.loads(p.read_text().splitlines()[-1])['decision'],'MEASUREMENT_FAILED')
            m.save.assert_not_called()

    def rpc(self, latest=1000, pending=1001):
        return lambda method, args: {'timestamp':hex(latest if args[0]=='latest' else pending)}

    def test_readiness_uses_latest_and_pending_and_never_mutates(self):
        rpc=Mock(side_effect=self.rpc())
        c.require_ready(rpc, lambda:1002)
        self.assertEqual([x.args[0] for x in rpc.call_args_list], ['eth_getBlockByNumber']*2)

    def test_stale_head_restored_offset_future_and_boundary(self):
        for latest,pending in [(879,1000),(999,800),(1004,1004)]:
            with self.subTest(latest=latest,pending=pending),self.assertRaisesRegex(ValueError,'NOT_WALL_ALIGNED'):
                c.require_ready(self.rpc(latest,pending), lambda:1000)
        c.require_ready(self.rpc(995,1002),lambda:1000)
        with self.assertRaisesRegex(ValueError,'NOT_WALL_ALIGNED'):
            c.require_ready(self.rpc(994,1000),lambda:1000)

    def test_two_calls_use_real_seconds_and_interval_not_timestamp_interval(self):
        rpc=Mock(side_effect=[12,None,1,False])
        c.configure(rpc,lambda:1793126477.9)
        self.assertEqual([x.args for x in rpc.call_args_list], [('evm_setTime',[1793126477]),('evm_setIntervalMining',[1]),('anvil_getIntervalMining',[]),('anvil_getAutomine',[])])

    def test_timeout_is_not_retried_or_rolled_back(self):
        for outcomes,count in [([TimeoutError()],1),([1,TimeoutError()],2)]:
            rpc=Mock(side_effect=outcomes)
            with self.assertRaises(TimeoutError):c.configure(rpc,lambda:1000)
            self.assertEqual(rpc.call_count,count)

    def test_uncertain_or_wrong_mode_fails_closed(self):
        for outcomes in [['0x1'],[True],[1,True],[1,None,2,False],[1,None,1,True]]:
            with self.subTest(outcomes=outcomes),self.assertRaises(ValueError):c.configure(Mock(side_effect=outcomes),lambda:1000)

    def test_old_history_assets_nonces_and_receipts_must_match_not_new_head(self):
        before={'head':{'number':'0x8','hash':'old'},'proofs':{'vault':{'storageHash':'same'}},'nonce':'0x2','history':{'8':'old'},'receiptHashes':{'tx':'receipt'}}
        after=copy.deepcopy(before);after['head']={'number':'0xa','hash':'new'}
        c.verify_preserved(before,after)
        for key in ['proofs','nonce','history','receiptHashes']:
            broken=copy.deepcopy(after);broken[key]='changed'
            with self.subTest(key=key),self.assertRaisesRegex(ValueError,'PRESERVATION'):c.verify_preserved(before,broken)
        with self.assertRaisesRegex(ValueError,'NO_NEW_BLOCK'):c.verify_preserved(before,before)

    def fixture(self, root):
        owner='0x'+'1'*40;run='ccpg-'+'a'*16
        p=root/('session-'+run[5:]);p.mkdir();live=p/('lifecycle-'+'b'*16);live.mkdir()
        record={'run':run,'lifecycle':live.name,'externalOwner':owner,'identity':{'userA':owner},'rpc':'http://127.0.0.1:32176'}
        (p/'owned.json').write_text(json.dumps(record));(root/'deployment').mkdir()
        for name in ['product_manual_clock.py','product-manual.py','test_product_manual_clock.py']:
            (root/'deployment'/name).write_text('test-only-source')
        m=Mock();m.ROOT=root;m.scoped.return_value=p;m.active_path.return_value=live
        states={role:{'Running':True,'Paused':False} for role in ['app','oracle','pg','anvil']}
        m.owned.side_effect=lambda record,role:{'Id':role+'-id','State':dict(states[role])}
        def docker(action, cid):
            states[cid[:-3]]['Paused']=action=='pause'
        m.docker.side_effect=docker
        m.execute.return_value=type('Result',(),{'returncode':0,'stdout':b'BEGIN\n0|0\nCOMMIT\n'})()
        return p,live,m

    def test_resource_failure_precedes_claim_pause_and_clock_mutation(self):
        with tempfile.TemporaryDirectory() as d:
            p,live,m=self.fixture(Path(d));m.admission.side_effect=ValueError('RAM_LOW')
            with patch.object(c,'manual_module',return_value=m),patch.object(c,'configure') as config:
                with self.assertRaisesRegex(ValueError,'RAM_LOW'):c.repair(p)
            m.owned.assert_not_called();m.save.assert_not_called();m.docker.assert_not_called();config.assert_not_called()

    def test_unsigned_execution_failure_unpauses_without_clock_change(self):
        with tempfile.TemporaryDirectory() as d:
            p,live,m=self.fixture(Path(d));m.execute.return_value.stdout=b'1|0\n'
            with patch.object(c,'manual_module',return_value=m),patch.object(c,'configure') as config:
                with self.assertRaisesRegex(ValueError,'UNSIGNED_EXECUTION'):c.repair(p)
            config.assert_not_called()
            self.assertEqual([x.args for x in m.docker.call_args_list],[('pause','app-id'),('pause','oracle-id'),('unpause','oracle-id'),('unpause','app-id')])

    def test_ambiguous_clock_mutation_keeps_admission_paused(self):
        with tempfile.TemporaryDirectory() as d:
            p,live,m=self.fixture(Path(d))
            with patch.object(c,'manual_module',return_value=m),patch.object(c,'snapshot',return_value={}),patch.object(c,'observe',return_value={}),patch.object(c,'configure',side_effect=TimeoutError()) as config:
                with self.assertRaises(TimeoutError):c.repair(p)
            config.assert_called_once()
            self.assertEqual([x.args for x in m.docker.call_args_list],[('pause','app-id'),('pause','oracle-id')])
            failure=[x.args[1] for x in m.save.call_args_list if x.args[0].name=='failure.json'][0]
            self.assertTrue(failure['mutationMayHaveOccurred']);self.assertFalse(failure['automaticRetry'])

    def test_verified_repair_restores_only_owned_services_and_saves_evidence(self):
        with tempfile.TemporaryDirectory() as d:
            p,live,m=self.fixture(Path(d));before={'head':{'number':'0x8'},'history':{'8':'old'}};after={'head':{'number':'0x9'}}
            with patch.object(c,'manual_module',return_value=m),patch.object(c,'snapshot',side_effect=[before,after]),patch.object(c,'observe',return_value={'latest':{'number':'0x9'}}),patch.object(c,'require_ready',return_value={}),patch.object(c,'configure'),patch.object(c,'verify_preserved') as verify:
                result=c.repair(p)
            verify.assert_called_once_with(before,after)
            self.assertEqual([x.args for x in m.docker.call_args_list],[('pause','app-id'),('pause','oracle-id'),('unpause','oracle-id'),('unpause','app-id')])
            self.assertEqual(result['userTransactionsSent'],0)

    def test_pause_applied_then_response_lost_is_observed_and_recovered(self):
        with tempfile.TemporaryDirectory() as d:
            p,live,m=self.fixture(Path(d));real=m.docker.side_effect
            def timeout(action,cid):
                real(action,cid)
                if action=='pause':raise TimeoutError()
            m.docker.side_effect=timeout
            with patch.object(c,'manual_module',return_value=m),patch.object(c,'configure') as config:
                with self.assertRaises(TimeoutError):c.repair(p)
            config.assert_not_called()
            self.assertEqual([x.args for x in m.docker.call_args_list],[('pause','app-id'),('unpause','app-id')])
            failure=[x.args[1] for x in m.save.call_args_list if x.args[0].name=='failure-service-state.json'][0]
            self.assertTrue(failure['app']['paused'])
            recovery=[x.args[1] for x in m.save.call_args_list if x.args[0].name=='pause-recovery.json'][0]
            self.assertFalse(recovery['states']['app']['paused'])

    def test_unpause_timeout_records_all_roles_and_prevents_pass(self):
        with tempfile.TemporaryDirectory() as d:
            p,live,m=self.fixture(Path(d));real=m.docker.side_effect
            def timeout(action,cid):
                real(action,cid)
                if action=='unpause' and cid=='oracle-id':raise TimeoutError()
            m.docker.side_effect=timeout
            before={'head':{'number':'0x8'},'history':{'8':'old'}}
            with patch.object(c,'manual_module',return_value=m),patch.object(c,'snapshot',return_value=before),patch.object(c,'observe',return_value={'latest':{'number':'0x9'}}),patch.object(c,'require_ready',return_value={}),patch.object(c,'configure'),patch.object(c,'verify_preserved'):
                with self.assertRaisesRegex(ValueError,'SERVICE_RESUME_UNCERTAIN'):c.repair(p)
            self.assertEqual([x.args for x in m.docker.call_args_list][-2:],[('unpause','oracle-id'),('unpause','app-id')])
            recovery=[x.args[1] for x in m.save.call_args_list if x.args[0].name=='pause-recovery.json'][0]
            self.assertEqual(len(recovery['errors']),1)
            self.assertFalse(any(x.args[0].name=='result.json' for x in m.save.call_args_list))


if __name__=='__main__':unittest.main()
