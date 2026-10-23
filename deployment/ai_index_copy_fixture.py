"""No provider access. Cold RO volume copy -> temporary PG -> JSON -> new AI DB.

Only a stopped, labelled network-none parent is accepted. No parent start/reset.
All new containers/volumes/evidence are preserved; cleanup only stops owned ones.
"""
import argparse
import hashlib
import getpass
import csv
import re
import shutil
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
from ai_evaluation_broker import prepare,source_hash,corpus_hash,load_run
from ai_evaluation_lineage import prepare_child
from ai_reevaluation_plan import derive
from ai_db_relay import docker_json


def command(args,**kwargs):
    r=subprocess.run(['docker',*args],capture_output=True,timeout=90,**kwargs)
    if r.returncode:raise RuntimeError('DOCKER_COPY_COMMAND_FAILED')
    return r.stdout


def restrict_new_folder(target):
    # Token-derived SID works in the intentionally credential-free child env.
    r=subprocess.run(['whoami','/user','/fo','csv','/nh'],capture_output=True,check=True,timeout=10)
    rows=list(csv.reader(r.stdout.decode().strip().splitlines()))
    if len(rows)!=1 or len(rows[0])!=2 or not re.fullmatch(r'S-1-5-[0-9-]+',rows[0][1]):raise RuntimeError('NEW_FOLDER_OWNER_INVALID')
    subprocess.run(['icacls',str(target),'/inheritance:r','/grant:r','*'+rows[0][1]+':(OI)(CI)F','SYSTEM:(OI)(CI)F'],check=True,capture_output=True,timeout=10)


def stop_copy_owned(meta,suffix):
    if suffix not in ('-cold-copy','-source-snapshot','-ai-postgres-1'):raise RuntimeError('COPY_STOP_TARGET_INVALID')
    r=subprocess.run(['docker','inspect',meta['runId']+suffix],capture_output=True,timeout=20)
    if r.returncode:
        if b'No such object:' in r.stderr or b'No such container:' in r.stderr:return
        raise RuntimeError('COPY_STOP_INSPECT_FAILED')
    c=json.loads(r.stdout)[0]
    if (c.get('Config',{}).get('Labels') or {}).get('ai-evaluation.run')!=meta['runId']:raise RuntimeError('COPY_STOP_OWNER_INVALID')
    command(['stop','--time','30',c['Id']])


def create(parent,target,port,probe=False,dry_run=None,continuation_predecessor=None,evidence=None,legacy_registry=None):
    target=Path(target).absolute();existed=target.exists()
    try:return _create(parent,target,port,probe,dry_run,continuation_predecessor,evidence,legacy_registry)
    except Exception:
        if not existed and (target/'run.json').is_file():
            meta=json.loads((target/'run.json').read_text(encoding='utf-8'))
            if re.fullmatch(r'eval_[0-9a-f]{32}',meta.get('runId','')):
                for suffix in ('-cold-copy','-source-snapshot','-ai-postgres-1'):stop_copy_owned(meta,suffix)
        raise


def _create(parent,target,port,probe=False,dry_run=None,continuation_predecessor=None,evidence=None,legacy_registry=None):
    parent=Path(parent).resolve();target=Path(target).absolute()
    old=json.loads((parent/'run.json').read_text(encoding='utf-8'));derive(parent)
    c=docker_json('inspect',old['runId']+'-ai-postgres-1')[0]
    if c['State']['Running'] or c['State'].get('ExitCode')!=0 or c['HostConfig']['NetworkMode']!='none' or c['Config']['Labels'].get('ai-evaluation.run')!=old['runId']:
        raise RuntimeError('PARENT_NOT_SAFE_COLD_SOURCE')
    mounts=c['Mounts']
    if len(mounts)!=1 or mounts[0]['Type']!='volume' or mounts[0]['Destination']!='/var/lib/postgresql/data':raise RuntimeError('PARENT_MOUNT_INVALID')
    volume=mounts[0]['Name'];v=docker_json('volume','inspect',volume)[0]
    if (v.get('Labels') or {}).get('ai-evaluation.run')!=old['runId']:raise RuntimeError('PARENT_VOLUME_INVALID')
    users=command(['ps','-aq','--filter','volume='+volume]).decode().split()
    if any(x['State']['Running'] and any(m.get('Name')==volume for m in x.get('Mounts',[])) for x in (docker_json('inspect',*users) if users else [])):
        raise RuntimeError('PARENT_VOLUME_IN_USE')
    image=c['Image']
    if continuation_predecessor is not None:
        if probe or dry_run is None or evidence is None or legacy_registry is None:raise RuntimeError('CONTINUATION_INPUT_REQUIRED')
        capture=json.loads(Path(dry_run).read_text(encoding='utf-8'))
        if capture.get('sourceHash')!=source_hash() or capture.get('processedQuestions') not in ([12],[14],[15]):
            raise RuntimeError('DRY_RUN_IDENTITY_INVALID')
    elif not probe:
        from ai_evaluation_dry_run import assess
        if dry_run is None or not assess(parent,dry_run)['allProfilesFit']:raise RuntimeError('DRY_RUN_BUDGET_BLOCKED')
        capture=json.loads(Path(dry_run).read_text(encoding='utf-8'))
        if capture.get('sourceHash')!=source_hash() or capture.get('processedQuestions')!=list(range(2,17)) or not capture.get('preservedFuturesEvidence'):
            raise RuntimeError('DRY_RUN_IDENTITY_INVALID')
    if continuation_predecessor is not None:
        from ai_evaluation_continuation import prepare as prepare_continuation,assess
        # New claim/ledger only, ancestors remain RO. Do not invoke this CLI
        # until the user approves preparing an actual new evaluation fixture.
        meta=prepare_continuation(continuation_predecessor,target,port,source_hash(),corpus_hash(),
            legacy_registry=legacy_registry,evidence=evidence)
        if meta['continuation']['ancestors'][0]['runId']!=old['runId']:raise RuntimeError('INDEX_LINEAGE_INVALID')
        assess(meta,capture)
    else:
        meta=prepare(target,port,mode='offline') if probe else prepare_child(parent,target,port,source_hash(),corpus_hash())
    if os.name=='nt':
        restrict_new_folder(target)
    rid=meta['runId'];owner='ai-evaluation.run='+rid
    if continuation_predecessor is not None:
        with (target/'dry-run.private.json').open('xb') as f:f.write(Path(dry_run).read_bytes())
    for suffix in ['cold','data']:command(['volume','create','--label',owner,rid+'-'+suffix])
    # Parent mount is hard RO, helper has no network and no inherited credentials.
    helper=rid+'-cold-copy'
    command(['run','--pull','never','--name',helper,'--network','none','--label',owner,'--mount','type=volume,src='+volume+',dst=/source,readonly',
             '--mount','type=volume,src='+rid+'-cold,dst=/target','--entrypoint','cp',image,'-a','/source/.','/target/'])
    scratch=rid+'-source-snapshot'
    command(['run','--pull','never','-d','--name',scratch,'--network','none','--label',owner,'--env-file',str(parent/'db.env'),
             '--mount','type=volume,src='+rid+'-cold,dst=/var/lib/postgresql/data',image,'postgres','-c','default_transaction_read_only=on'])
    for _ in range(30):
        r=subprocess.run(['docker','exec',scratch,'pg_isready','-U',old['dbUser'],'-d',old['dbName']],capture_output=True,timeout=5)
        if r.returncode==0:break
        time.sleep(.5)
    else:raise RuntimeError('SNAPSHOT_SQL_NOT_READY')
    sql="""BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
      SELECT json_build_object('owner',(select row_to_json(x) from public.ai_evaluation_owner x),
      'indexes',(select json_agg(x order by id) from ai.indexes x),
      'documents',(select json_agg(x order by path) from ai.documents x),
      'chunks',(select json_agg(x order by id) from (select index_id,id,path,heading,content,embedding::text as embedding from ai.chunks) x));
      COMMIT;"""
    raw=command(['exec','-i',scratch,'psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U',old['dbUser'],'-d',old['dbName']],input=sql.encode())
    data=json.loads(raw);ownerrow=data['owner']
    if ownerrow!={'run_id':old['runId'],'source_hash':old['sourceHash'],'corpus_hash':old['corpusHash'],'state':'READY'}:raise RuntimeError('PARENT_LEASE_INVALID')
    (target/'source-index.json').write_bytes(raw)
    provenance={'parentRunId':old['runId'],'parentSourceHash':old['sourceHash'],'corpusHash':old['corpusHash'],
                'parentContainerId':c['Id'],'parentVolume':volume,'sourceArtifactSha256':hashlib.sha256(raw).hexdigest(),
                'parentLedgerSha256':hashlib.sha256((parent/'budget.sqlite').read_bytes()).hexdigest(),'parentMountedReadOnly':True}
    (target/'copy-provenance.json').write_text(json.dumps(provenance),encoding='utf-8')
    command(['stop','--time','10',scratch])
    password=secrets.token_urlsafe(36)
    env='POSTGRES_USER='+meta['dbUser']+'\nPOSTGRES_DB='+meta['dbName']+'\nPOSTGRES_PASSWORD='+password+'\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\n'
    fd=os.open(target/'db.env',os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    with os.fdopen(fd,'w',encoding='utf-8') as f:f.write(env)
    name=rid+'-ai-postgres-1'
    command(['run','--pull','never','-d','--name',name,'--network','none','--label',owner,'--env-file',str(target/'db.env'),
             '--mount','type=volume,src='+rid+'-data,dst=/var/lib/postgresql/data',image])
    relay={**meta,'dbImage':image};(target/'relay.json').write_text(json.dumps(relay),encoding='utf-8')
    print(json.dumps({'prepared':True,'runId':rid,'port':port,'paidApprovalCreated':False,'providerCalls':0}))


def check(target):
    from ai_db_relay import Relay
    target,identity=load_run(target);meta=json.loads((target/'relay.json').read_text(encoding='utf-8'))
    if {k:v for k,v in meta.items() if k!='dbImage'}!=identity:raise RuntimeError('RELAY_RUN_IDENTITY_MISMATCH')
    relay=Relay(meta,seconds=900,max_connections=96).start()
    try:
        env=dict(os.environ);env['JAVA_HOME']=r'C:\Program Files\Java\jdk-21.0.10';env['AI_INDEX_COPY_RUN']=str(target)
        # This JDBC harness uses 127.0.0.1 only; avoid Windows dual-stack daemon
        # listen failures without changing host networking or product policy.
        env['JAVA_TOOL_OPTIONS']='-Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false'
        # No external provider gate or inherited API environment is needed.
        for key in list(env):
            if key.startswith(('AI_APPROVED','OPENAI_')) or key in ('AI_LIVE_EVALUATION','AI_API_KEY'):env.pop(key,None)
        report=Path(__file__).resolve().parent.parent/'backend/build/test-results/test/TEST-com.pricetrack.exchange.ai.evaluation.EvaluationIndexCopyTest.xml'
        previous=report.stat().st_mtime_ns if report.exists() else None
        with (target/'index-copy-java.private.log').open('xb') as log:
            result=subprocess.run([str(Path(__file__).resolve().parent.parent/'backend/gradlew.bat'),'test','--offline','--no-daemon','--rerun-tasks','--stacktrace','--tests','*EvaluationIndexCopyTest'],
                cwd=Path(__file__).resolve().parent.parent/'backend',env=env,stdout=log,stderr=subprocess.STDOUT,timeout=600)
        if report.exists() and report.stat().st_mtime_ns!=previous:shutil.copyfile(report,target/'index-copy-execution.xml')
        print(json.dumps({'javaExit':result.returncode,'verified':(target/'index-copy-verification.json').exists(),'providerCalls':0}))
        if result.returncode:raise RuntimeError('INDEX_COPY_JAVA_FAILED')
    finally:relay.close()


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--parent');p.add_argument('--run-dir',required=True);p.add_argument('--port',type=int);p.add_argument('--probe',action='store_true');p.add_argument('--check',action='store_true');p.add_argument('--dry-run-capture');p.add_argument('--continuation-predecessor');p.add_argument('--evidence');p.add_argument('--legacy-registry');a=p.parse_args()
    try:
        if a.check:check(a.run_dir)
        else:create(a.parent,a.run_dir,a.port,a.probe,a.dry_run_capture,a.continuation_predecessor,a.evidence,a.legacy_registry)
    except Exception as e:
        code=str(e) if isinstance(e,RuntimeError) and str(e).isupper() else 'INDEX_FIXTURE_PREPARATION_FAILED'
        raise SystemExit(code) from None
