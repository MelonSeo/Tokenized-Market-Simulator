"""Synthetic SQLite/controlled suppliers only; no PG, provider, key or Docker."""
import json
import hashlib
import sqlite3
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import Mock, patch
from ai_pilot_coordinator import PilotCoordinator, digest
from ai_pilot_target13 import CAPS, VERSION, target_rows, parent_summary, import_session, TargetCoordinator
from test_ai_pilot_coordinator import fixture, wire, controlled
from ai_pilot_paid_adapter import private_write


class Target13Tests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name);self.parent=self.root/'parent';self.parent.mkdir()
        f=fixture();f['mode']='REAL_ISOLATED'
        self.old=dict(allocation=str(uuid.uuid4()),mode='PAID_EXPLICIT_V2',source='a'*64,
                      fixture=f,fixtureHash=digest(f),approvalId='tms_pilot_v2_'+'d'*32,
                      budgetAuthority='b'*64,folder=str(self.parent))
        c=PilotCoordinator(self.parent/'pilot-v2.sqlite',allocation=self.old['allocation'],source_hash=self.old['source'],fixture=f,create=True,mode='PAID_V2')
        held_ticket=str(uuid.uuid4());held_id=str(uuid.uuid4())
        with c.db() as db:
            for row in c.rows:
                is_b5=row['case']=='B5';ticket=held_ticket if is_b5 else str(uuid.uuid4())
                db.execute('INSERT INTO submissions VALUES(?,?,?,?,?,1,?,?,?)',
                           (ticket,row['phase'],row['slot'],row['uid'],row['bodySha256'],0 if is_b5 else 1,str(uuid.uuid4()),0 if is_b5 else 1))
            db.execute('CREATE TABLE held_terminal(ticket TEXT PRIMARY KEY,disposition TEXT NOT NULL)')
            db.execute('INSERT INTO held_terminal VALUES(?,?)',(held_ticket,'B5_TECHNICAL_FAIL_NO_REPLAY'))
            for i in range(131):
                k='LLM' if i<77 else 'EMBEDDING';unknown=i==130
                cost=29060 if unknown else 3653474240-29060-129 if i==0 else 1
                row=(held_id if unknown else str(uuid.uuid4()),held_ticket,'NOVEL30',k,1500,1600 if k=='LLM' else 0,cost,'f'*64,
                     'UNKNOWN' if unknown else 'OBSERVED',None if unknown else 1,None if unknown else 0)
                db.execute('INSERT INTO reservations VALUES(?,?,?,?,?,?,?,?,?,?,?)',row)
                if unknown:held=list(row)
            db.execute('UPDATE control SET phase=7,stopped=1');db.execute('UPDATE phases SET complete=1')
        self.old['continuation']={'failedTicket':held_ticket,'heldReservation':held}
        private_write(self.parent/'session.json',self.old)
        private_write(self.parent/'STOP',{'preserved':True})
        _,s=parent_summary(self.parent)
        self.folder=self.root/'new';self.folder.mkdir();(self.folder/'anchor').mkdir()
        d=dict(s,version=VERSION,caps=CAPS,rows=target_rows(f),childId='e'*16)
        self.meta={**self.old,'source':'c'*64,'folder':str(self.folder/'session'),'target13':d};del self.meta['continuation']
        claim=dict(version=VERSION,allocation=self.meta['allocation'],source=self.meta['source'],childId=d['childId'],target13Sha256=digest(d),
                   allocationPathSha256=hashlib.sha256(str(Path(self.meta['folder']).resolve()).encode()).hexdigest())
        private_write(self.folder/'anchor/single-target-claim.json',claim)
        private_write(self.parent/'TARGET13_CLAIM.json',claim)
        self.permit=self.folder/'permit.json';private_write(self.permit,{'controlled':True})
        with patch('ai_pilot_paid_sender.authorize'):
            self.meta=import_session(self.meta,self.permit,self.parent)
        self.c=TargetCoordinator(Path(self.meta['folder'])/'pilot-v2.sqlite',meta=self.meta)

    def begin(self):
        row=self.c.rows[0];ticket=self.c.claim(row['phase'],row['slot']);self.c.admit(row['uid'],row['body'],row['corpus'])
        run=str(uuid.uuid4());self.c.bind_worker(row['uid'],run,row['corpus']);return row,run

    def send(self,row,run,raw=None,sender=controlled):
        return self.c.dispatch(path='responses',raw=raw or wire('LLM'),pg_id=str(uuid.uuid4()),actor=row['uid'],server_request=run,corpus=row['corpus'],sender=sender)

    def test_exact13_no_original_replay_and_held_parent_unchanged(self):
        self.assertEqual(13,len(self.c.rows));self.assertEqual(['PF1b']*4+['F3']*2+['C3','C4','C5','D3','E2']+['PF1b']*2,[r['case'] for r in self.c.rows])
        self.assertEqual(['owner','other'],[r['actor'] for r in self.c.rows if r.get('web')])
        old,summary=parent_summary(self.parent)
        self.assertEqual(summary['rowsSha256'],self.meta['target13']['rowsSha256'])
        self.assertEqual(77,self.c.summary()['llmCalls']);self.assertEqual(1,self.c.summary()['unknown'])
        with self.assertRaisesRegex(ValueError,'V2_PHASE_ORDER'):self.c.claim('SAFETY_LEGACY',0)

    def test_new_unknown_stops_without_replay(self):
        row,run=self.begin();sender=Mock(side_effect=TimeoutError)
        with self.assertRaisesRegex(ValueError,'V2_UNKNOWN_USAGE_HALTED'):self.send(row,run,sender=sender)
        with self.assertRaisesRegex(ValueError,'V2_STOPPED'):self.send(row,run,sender=sender)
        self.assertEqual(1,sender.call_count);self.assertEqual(2,self.c.summary()['unknown'])

    def test_second_child_and_duplicate_global_claim_are_rejected(self):
        from ai_pilot_target13 import verify_anchor
        import copy
        second=copy.deepcopy(self.meta);second['target13']['childId']='f'*16
        with self.assertRaisesRegex(ValueError,'V7_SINGLE_CLAIM_REQUIRED'):verify_anchor(second,self.parent)
        second=copy.deepcopy(self.meta);second['folder']=str(self.folder/'different-session')
        with self.assertRaisesRegex(ValueError,'V7_SINGLE_CLAIM_REQUIRED'):verify_anchor(second,self.parent)
        with self.assertRaisesRegex(ValueError,'V2_PRIVATE_EVIDENCE_EXISTS'):
            private_write(self.parent/'TARGET13_CLAIM.json',{'differentChild':True})

    def test_additional_call_limit_is_before_supplier_and_survives_reopen(self):
        row,run=self.begin();sender=Mock(side_effect=controlled)
        for _ in range(14):self.send(row,run,sender=sender)
        self.c=TargetCoordinator(self.c.path,meta=self.meta)
        with self.assertRaisesRegex(ValueError,'V7_ADDITIONAL_CAP'):self.send(row,run,sender=sender)
        self.assertEqual(14,sender.call_count);self.assertEqual(91,self.c.summary()['llmCalls'])

    def test_per_call_token_bound_and_subcap_are_not_new_budget(self):
        row,run=self.begin();sender=Mock(side_effect=controlled)
        with self.assertRaisesRegex(ValueError,'V7_CALL_TOKEN_LIMIT'):self.send(row,run,wire('LLM',input='x'*33000),sender)
        sender.assert_not_called()
        with self.c.db() as db:
            # A stricter USD2 reservation test, independent of the original USD18 check.
            db.execute("INSERT INTO reservations VALUES(?,?,?,?,?,?,?,?,?,?,?)",(str(uuid.uuid4()),'controlled','TARGET_HTTP','LLM',1,1,2000000000,'a'*64,'OBSERVED',1,1))
        with self.assertRaisesRegex(ValueError,'V7_ADDITIONAL_CAP'):self.send(row,run,sender=sender)
        sender.assert_not_called()


if __name__=='__main__':unittest.main()
