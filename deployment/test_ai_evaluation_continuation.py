"""Temporary fake ledgers and local senders only. No provider or original DB."""
import concurrent.futures
import base64
import io
from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import uuid
from unittest.mock import patch,MagicMock
from ai_evaluation_guard import EvaluationGuard,GuardError,_canonical
from ai_evaluation_broker import prepare as parent_prepare,source_hash,corpus_hash,live_sender
from ai_evaluation_lineage import prepare_child
from ai_evaluation_continuation import prepare,verify,snapshot,scope,context,assess
from test_ai_evaluation_guard import llm,fake


class ContinuationTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        self.parent=self.root/'parent';parent_prepare(self.parent,59990,mode='live')
        self.legacy=self.root/'legacy';self.registry=self.root/'newclaims';self.child=self.root/'child'
        self.evidence=self.root/'evidence.json';self.evidence.write_bytes(b'{}')
        self.cm=prepare_child(self.parent,self.child,59991,source_hash(),corpus_hash(),self.legacy)
        with closing(sqlite3.connect(self.child/'budget.sqlite',isolation_level=None)) as db:
            db.execute('insert into reservations values(?,?,?,?,?,?,?,?)',(str(uuid.uuid4()),'llm',15490,1600,0,57925000,'RESERVED','a'*64))
        (self.child/'STOP').write_bytes(b'STOPPED')
        self.before={p:p.read_bytes() for f in (self.parent,self.child,self.legacy) for p in f.iterdir() if p.is_file()}
    def new(self,name='continuation',predecessor=None):
        folder=self.root/name
        m=prepare(predecessor or self.child,folder,59992,source_hash(),corpus_hash(),legacy_registry=self.legacy,evidence=self.evidence,registry=self.registry)
        return folder,m
    def guard(self,f,m,approved=True):
        return EvaluationGuard(f/'budget.sqlite',run_id=m['runId'],source_hash=m['sourceHash'],corpus_hash=m['corpusHash'],
            context_hash=context(m),carry=verify(f,m,self.registry),local_limits=m['continuation']['scope'],
            validate_lineage=lambda:verify(f,m,self.registry),execution_approved=approved)
    def test_uncertain_carry_does_not_refund_or_modify_old_evidence(self):
        f,m=self.new();self.assertEqual(self.guard(f,m).summary()['reserved_nusd'],57925000)
        self.assertEqual(m['questions'],[12]);self.assertFalse((f/'approval.json').exists())
        for p,raw in self.before.items():self.assertEqual(p.read_bytes(),raw)
    def test_duplicate_concurrent_claim(self):
        def attempt(i):
            try:self.new('new'+str(i));return 'ok'
            except GuardError:return 'blocked'
        with concurrent.futures.ThreadPoolExecutor(2) as pool:self.assertEqual(sorted(pool.map(attempt,[1,2])),['blocked','ok'])
    def test_local_limit_survives_reopen(self):
        f,m=self.new();self.guard(f,m).dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()))
        with self.assertRaisesRegex(GuardError,'LOCAL_SCOPE_LIMIT'):
            self.guard(f,m).dispatch('responses',llm(),lambda *a:self.fail('must not send'),request_id=str(uuid.uuid4()))
    def test_plain_guard_cannot_omit_scope_or_carry(self):
        f,m=self.new()
        with self.assertRaisesRegex(GuardError,'IDENTITY_MISMATCH'):
            EvaluationGuard(f/'budget.sqlite',run_id=m['runId'],source_hash=m['sourceHash'],corpus_hash=m['corpusHash'],context_hash=context(m))
    def test_stop_and_ancestor_mutation_fail_closed(self):
        f,m=self.new();g=self.guard(f,m);(self.child/'STOP').write_bytes(b'changed')
        with self.assertRaisesRegex(GuardError,'CONTINUATION_INVALID'):g.summary()
    def test_source_mismatch_rejects_guard(self):
        f,m=self.new();m['sourceHash']='b'*64
        with self.assertRaises(GuardError):self.guard(f,m)
    def test_corpus_mismatch_before_claim(self):
        with self.assertRaisesRegex(GuardError,'CORPUS_CHANGED'):
            prepare(self.child,self.root/'bad',59992,source_hash(),'b'*64,legacy_registry=self.legacy,evidence=self.evidence,registry=self.registry)
        self.assertFalse(self.registry.exists())
    def test_uncertainty_in_new_run_blocks_restart_and_successor(self):
        f,m=self.new();g=self.guard(f,m)
        with self.assertRaisesRegex(GuardError,'TRANSPORT_UNCERTAIN'):
            g.dispatch('responses',llm(),lambda *a:(_ for _ in ()).throw(TimeoutError()),request_id=str(uuid.uuid4()))
        with self.assertRaisesRegex(GuardError,'RUN_HALTED'):
            self.guard(f,m).dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()))
        with self.assertRaises(Exception):self.new('later',f)
    def test_quality_gate_required_then_linear_q14_q15(self):
        f,m=self.new();g=self.guard(f,m);g.dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()))
        with self.assertRaises(Exception):self.new('notreviewed',f)
        (f/'question-result.private.json').write_bytes(b'{"number":12,"result":{"status":"ANSWERED"},"verdict":"REVIEW_REQUIRED"}')
        (f/'completion.json').write_bytes(b'{"status":"EVALUATION_EXECUTED_NOT_AUTOMATIC_PASS","questions":1}')
        review={'runId':m['runId'],'sourceHash':m['sourceHash'],'question':12,'resultSha256':hashlib.sha256((f/'question-result.private.json').read_bytes()).hexdigest(),'verdict':'PASS','reviewedBy':'USER'}
        (f/'quality-review.json').write_bytes(_canonical(review))
        later,lm=self.new('q14',f);self.assertEqual(lm['questions'],[14])
        self.assertEqual(self.guard(later,lm).summary()['llm_calls'],2)
    def test_active_ancestor_approval_blocks_before_claim(self):
        import time
        a={k:self.cm[k] for k in ('runId','sourceHash','corpusHash')};a['expiresAt']=int(time.time())+300
        (self.child/'approval.json').write_bytes(_canonical(a))
        with self.assertRaisesRegex(GuardError,'ANCESTOR_AUTHORIZATION_ACTIVE'):self.new()
    def test_claim_crash_never_reclaims_predecessor(self):
        with patch('ai_evaluation_continuation.EvaluationGuard',side_effect=GuardError('SIMULATED_CRASH')):
            with self.assertRaises(GuardError):self.new()
        with self.assertRaisesRegex(GuardError,'CONTINUATION_ALREADY_CLAIMED'):self.new('other')
    def test_bad_reservation_cost_rejected(self):
        with closing(sqlite3.connect(self.child/'budget.sqlite',isolation_level=None)) as db:db.execute('update reservations set cost=0')
        with self.assertRaisesRegex(GuardError,'ANCESTOR_INVALID'):self.new()
    def test_live_sender_rejects_missing_approval_before_key(self):
        f,m=self.new()
        with self.assertRaisesRegex(GuardError,'PAID_APPROVAL_REQUIRED'):live_sender(f,m,f/'approval.json')
    def test_scope_costs_and_total_do_not_expand_global_policy(self):
        self.assertEqual(scope(12)['reserved_nusd'],101140640)
        self.assertEqual(302005400+sum(scope(q)['reserved_nusd'] for q in (12,14,15)),778867320)
        with self.assertRaises(GuardError):scope(13)
    def test_local_cost_and_embedding_limits(self):
        f,m=self.new();g=self.guard(f,m)
        body=_canonical({'model':'text-embedding-3-small','input':['x'*1025],'dimensions':1536,'encoding_format':'float'})
        with self.assertRaisesRegex(GuardError,'LOCAL_SCOPE_LIMIT'):g.dispatch('embeddings',body,fake,request_id=str(uuid.uuid4()))
    def test_stopped_new_guard_cannot_send(self):
        f,m=self.new();self.guard(f,m).stop()
        with self.assertRaisesRegex(GuardError,'RUN_STOPPED'):self.guard(f,m).dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()))
    def test_evidence_mutation_blocks_before_sender(self):
        f,m=self.new();g=self.guard(f,m);self.evidence.write_bytes(b'{"changed":true}')
        with self.assertRaisesRegex(GuardError,'CONTINUATION_INVALID'):g.dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()))
    def test_broker_transmission_boundary_enforces_single_llm(self):
        import ai_evaluation_broker as broker
        import ai_evaluation_continuation as continuation
        f,m=self.new();original=continuation.verify;out=io.BytesIO()
        frames=[{'id':str(uuid.uuid4()),'path':'responses','body':base64.b64encode(llm()).decode(),'timeoutMs':1000} for _ in range(2)]
        with patch.object(continuation,'verify',side_effect=lambda folder,meta:original(folder,meta,self.registry)),patch.object(broker,'live_sender',return_value=lambda path,raw,timeout:fake(path,raw)) as sender:
            broker.broker(f,live=True,approval=f/'FAKE_APPROVAL_NOT_READ',inp=io.BytesIO(b''.join(_canonical(x)+b'\n' for x in frames)),out=out)
        responses=[json.loads(x) for x in out.getvalue().splitlines()]
        self.assertEqual(responses[-1]['code'],'LOCAL_SCOPE_LIMIT');self.assertTrue((f/'STOP').exists())
        self.assertEqual(snapshot(f)['local']['llm_calls'],1)
    def test_assess_checks_question_and_evidence_identity(self):
        f,m=self.new()
        emb=_canonical({'model':'text-embedding-3-small','input':['query'],'dimensions':1536,'encoding_format':'float'})
        rows=[{'question':12,'path':path,'bodyBase64':base64.b64encode(raw).decode()} for path,raw in [('embeddings',emb),('responses',llm())]]
        capture={'providerCalls':0,'sourceHash':m['sourceHash'],'evidenceSha256':m['continuation']['evidence']['sha256'],'processedQuestions':[12],'preservedFuturesEvidence':True,
            'profiles':[{'profile':name,'requests':rows} for name in ['EXPECTED_STATE','MAXIMUM_MIXED']]}
        self.assertEqual(len(assess(m,capture)),2)
        capture['processedQuestions']=[12,14]
        with self.assertRaisesRegex(GuardError,'CONTINUATION_DRY_RUN_INVALID'):assess(m,capture)
    def test_omitting_only_local_scope_is_also_identity_mismatch(self):
        f,m=self.new()
        with self.assertRaisesRegex(GuardError,'IDENTITY_MISMATCH'):
            EvaluationGuard(f/'budget.sqlite',run_id=m['runId'],source_hash=m['sourceHash'],corpus_hash=m['corpusHash'],context_hash=context(m),
                carry=verify(f,m,self.registry),validate_lineage=lambda:verify(f,m,self.registry))
    def test_continuation_index_copy_reuses_only_cold_ro_source_offline(self):
        import ai_index_copy_fixture as fixture
        import ai_evaluation_continuation as continuation
        old=json.loads((self.parent/'run.json').read_text());owner=old['runId'];calls=[]
        c={'Id':'a'*64,'Image':'sha256:'+'a'*64,'State':{'Running':False,'ExitCode':0},'HostConfig':{'NetworkMode':'none'},'Config':{'Labels':{'ai-evaluation.run':owner}},'Mounts':[{'Type':'volume','Destination':'/var/lib/postgresql/data','Name':'owned-parent'}]}
        artifact={'owner':{'run_id':owner,'source_hash':old['sourceHash'],'corpus_hash':old['corpusHash'],'state':'READY'}}
        def info(*args):return [c] if args[0]=='inspect' else [{'Labels':{'ai-evaluation.run':owner}}]
        def command(args,**kwargs):
            calls.append(args);return json.dumps(artifact).encode() if args[:2]==['exec','-i'] else b''
        emb=_canonical({'model':'text-embedding-3-small','input':['query'],'dimensions':1536,'encoding_format':'float'})
        rows=[{'question':12,'path':p,'bodyBase64':base64.b64encode(raw).decode()} for p,raw in [('embeddings',emb),('responses',llm())]]
        capture={'providerCalls':0,'sourceHash':source_hash(),'evidenceSha256':hashlib.sha256(self.evidence.read_bytes()).hexdigest(),'processedQuestions':[12],'preservedFuturesEvidence':True,
            'profiles':[{'profile':name,'requests':rows} for name in ['EXPECTED_STATE','MAXIMUM_MIXED']]}
        dry=self.root/'dry.json';dry.write_bytes(_canonical(capture));target=self.root/'index-continuation';original=continuation.prepare
        # SID/ACL validation has its own boundary tests; this test isolates the
        # continuation/RO-copy protocol and must not run Windows account lookup.
        with patch.object(fixture,'docker_json',side_effect=info),patch.object(fixture,'command',side_effect=command),patch.object(fixture,'restrict_new_folder'),patch.object(fixture.subprocess,'run',return_value=MagicMock(returncode=0)),patch.object(continuation,'prepare',side_effect=lambda *a,**kw:original(*a,**kw,registry=self.registry)):
            fixture.create(self.parent,target,59993,dry_run=dry,continuation_predecessor=self.child,evidence=self.evidence,legacy_registry=self.legacy)
        meta=json.loads((target/'run.json').read_text());self.assertEqual(meta['questions'],[12]);verify(target,meta,self.registry)
        self.assertFalse((target/'approval.json').exists())
        for call in (x for x in calls if x[0]=='run'):
            self.assertIn('none',call);self.assertIn('never',call)
        self.assertIn('type=volume,src=owned-parent,dst=/source,readonly',next(x for x in calls if x[0]=='run'))
        for p,raw in self.before.items():self.assertEqual(p.read_bytes(),raw)


if __name__=='__main__':unittest.main()
