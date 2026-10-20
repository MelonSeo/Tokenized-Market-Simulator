"""Offline lineage/budget PLAN, not a new run, grant, ledger, or live entry point."""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
from contextlib import closing

from ai_evaluation_guard import POLICY, _canonical


def derive(parent):
    parent=Path(parent).resolve(strict=True)
    meta=json.loads((parent/'run.json').read_text(encoding='utf-8'))
    ledger=parent/'budget.sqlite'
    before=hashlib.sha256(ledger.read_bytes()).hexdigest()
    if meta['mode']!='live' or meta['policy']!=POLICY:raise ValueError('PARENT_POLICY_INVALID')
    identity={'run':meta['runId'],'source':meta['sourceHash'],'corpus':meta['corpusHash'],
              'context':hashlib.sha256(_canonical(meta)).hexdigest(),'policy':POLICY}
    with closing(sqlite3.connect(ledger.as_uri()+'?mode=ro',uri=True)) as c:
        if json.loads(c.execute('select value from identity where id=1').fetchone()[0])!=identity:raise ValueError('PARENT_IDENTITY_INVALID')
        if c.execute("select count(*) from reservations where outcome!='OBSERVED'").fetchone()[0]:raise ValueError('UNCERTAIN_PARENT_BLOCKED')
        values=c.execute("select count(case when kind='llm' then 1 end),count(case when kind='embedding' then 1 end),coalesce(sum(input),0),coalesce(sum(output),0),coalesce(sum(embedding),0),coalesce(sum(cost),0) from reservations").fetchone()
        if c.execute("select count(*) from reservations where kind not in ('llm','embedding')").fetchone()[0]:raise ValueError('PARENT_KIND_INVALID')
    if hashlib.sha256(ledger.read_bytes()).hexdigest()!=before:raise ValueError('PARENT_CHANGED')
    keys=['llm_calls','embedding_calls','input_tokens','output_tokens','embedding_tokens','cap_nusd']
    spent=dict(zip(keys,values));remaining={k:POLICY[k]-spent[k] for k in keys}
    if any(v<0 for v in spent.values()) or any(v<0 for v in remaining.values()):raise ValueError('PARENT_EXCEEDS_POLICY')
    # Q2..16: ten ordinary planning calls, four explicit positive Skills + Q16.
    # All fourteen positive cases may synthesize; Q16 must stop safely pre-model.
    worst={'llm_calls':24,'embedding_calls':15,'output_tokens':26400,
           'embedding_tokens':15*(1024+8)}
    return {'executable':False,'paidApproved':False,'questionNumbers':list(range(2,17)),
            'parentRunId':meta['runId'],'parentSourceHash':meta['sourceHash'],'corpusHash':meta['corpusHash'],
            'parentLedgerSha256':before,'spent':spent,'remaining':remaining,'worstCaseRequestPlan':worst,
            'freshIndexEmbeddingBound':71678,'fullReembeddingFits':71678<=remaining['embedding_tokens'],
            'callsOutputAndQueryEmbeddingFit':all(worst[k]<=remaining[k] for k in worst),
            'inputTokensRequireDryRun':True,'guardLineageEnforcementImplemented':False,
            'indexStrategy':'NEW_DB_COPY_VERIFIED_AI_SCHEMA_WITH_NEW_OWNER_NOT_OLD_LEASE_RESET',
            'runtimePolicy':'DENY_UNTIL_CUMULATIVE_GUARD_AND_USER_APPROVAL'}


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--parent',required=True);a=p.parse_args()
    print(json.dumps(derive(a.parent),ensure_ascii=True,indent=2))
