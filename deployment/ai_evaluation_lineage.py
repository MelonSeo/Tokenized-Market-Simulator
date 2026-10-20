"""One durable child per parent run; parent evidence is only ever opened read-only.

No key, approval or transport creation. A failed claim is deliberately not reset.
"""
import hashlib
import os
from pathlib import Path
import uuid
import sqlite3
import time
from contextlib import closing
from ai_evaluation_guard import EvaluationGuard, GuardError, POLICY, _canonical, _decode
from ai_reevaluation_plan import derive

REGISTRY = Path(__file__).resolve().parent/'runtime'/'ai-evaluation-lineage'


def _snapshot(parent):
    p=Path(parent).resolve(strict=True)
    if p.is_symlink() or (p/'run.json').is_symlink() or (p/'budget.sqlite').is_symlink():
        raise GuardError('LINEAGE_INVALID')
    try:
        plan=derive(p)
        with closing(sqlite3.connect((p/'budget.sqlite').as_uri()+'?mode=ro',uri=True)) as db:
            stopped=db.execute('select stopped from control where id=1').fetchall()==[(1,)]
        permit=p/'approval.json';permit_sha=None
        if permit.exists():
            raw=permit.read_bytes();a=_decode(raw);permit_sha=hashlib.sha256(raw).hexdigest()
            if (a.get('runId')!=plan['parentRunId'] or a.get('sourceHash')!=plan['parentSourceHash']
                    or a.get('corpusHash')!=plan['corpusHash'] or type(a.get('expiresAt')) is not int):raise ValueError()
            expired=time.time()>=a['expiresAt']
        else:expired=True
        closed='STOPPED' if stopped or (p/'STOP').exists() else 'EXPIRED' if permit.exists() and expired else 'NO_APPROVAL' if not permit.exists() else None
        if closed is None:raise GuardError('PARENT_AUTHORIZATION_STILL_ACTIVE')
        return {'parentDir':str(p),'parentRunId':plan['parentRunId'],
                'parentSourceHash':plan['parentSourceHash'],'parentLedgerSha256':plan['parentLedgerSha256'],
                'corpusHash':plan['corpusHash'],'spent':plan['spent'],
                'parentClosure':closed,'parentApprovalSha256':permit_sha}
    except GuardError:raise
    except Exception:
        raise GuardError('PARENT_INVALID') from None


def verify(folder, meta, registry=REGISTRY):
    try:
        line=meta['lineage']
        if line!=_snapshot(line['parentDir']) or line['corpusHash']!=meta['corpusHash']:
            raise ValueError()
        claim=_decode((Path(registry)/(line['parentRunId']+'.json')).read_bytes())
        expected={'childDir':str(Path(folder).resolve()),'childContextHash':hashlib.sha256(_canonical(meta)).hexdigest()}
        if claim!=expected:raise ValueError()
        return {('reserved_nusd' if k=='cap_nusd' else k):v for k,v in line['spent'].items()}
    except Exception:
        raise GuardError('LINEAGE_INVALID') from None


def prepare_child(parent, folder, port, source, corpus, registry=REGISTRY):
    # Only this dedicated preparation path creates a child, with no paid approval.
    if type(port) is not int or not 49152<=port<=65535:raise GuardError('RUN_TARGET_INVALID')
    line=_snapshot(parent)
    if corpus!=line['corpusHash']:raise GuardError('CORPUS_CHANGED')
    target=Path(folder).absolute()
    target.mkdir(parents=False,exist_ok=False)
    rid='eval_'+uuid.uuid4().hex
    meta={'version':1,'runId':rid,'sourceHash':source,'corpusHash':corpus,'dbPort':port,
          'dbName':'exchange_ai_'+rid,'dbUser':'exchange_ai_'+rid,'mode':'live','fixture':'success',
          'policy':POLICY,'lineage':line,'questions':list(range(2,17))}
    root=Path(registry);root.mkdir(parents=True,exist_ok=True)
    claim={'childDir':str(target.resolve()),'childContextHash':hashlib.sha256(_canonical(meta)).hexdigest()}
    try:
        fd=os.open(root/(line['parentRunId']+'.json'),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    except FileExistsError:
        raise GuardError('CHILD_ALREADY_CLAIMED') from None
    with os.fdopen(fd,'wb') as f:f.write(_canonical(claim));f.flush();os.fsync(f.fileno())
    (target/'run.json').write_bytes(_canonical(meta))
    carry=verify(target,meta,registry)
    EvaluationGuard(target/'budget.sqlite',run_id=rid,source_hash=source,corpus_hash=corpus,
                    context_hash=hashlib.sha256(_canonical(meta)).hexdigest(),create=True,carry=carry,
                    validate_lineage=lambda:verify(target,meta,registry))
    return meta
