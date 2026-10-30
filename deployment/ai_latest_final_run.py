"""Fresh final HTTP acceptance with immutable cumulative approval lineage. No retries."""
import argparse
import datetime as dt
import json
import secrets
import subprocess
import time
from pathlib import Path
from ai_latest_fixture_init import phase,resources
from ai_latest_evaluation_broker import prepare,closed_previous
from ai_db_fixture import write_new


def main():
    p=argparse.ArgumentParser();p.add_argument('--predecessor');p.add_argument('--credentials-file');p.add_argument('--clock-diagnostic-session');p.add_argument('--sql-check-session');p.add_argument('--approved-uncertain-carry',action='store_true')
    p.add_argument('--reuse-verified-session')
    p.add_argument('--approved-input-extension',action='store_true');p.add_argument('--residual-only',action='store_true')
    p.add_argument('--scope-followup',action='store_true')
    p.add_argument('--novel-budget-approved',action='store_true')
    p.add_argument('--novel-followup',action='store_true')
    a=p.parse_args()
    if a.clock_diagnostic_session:
        clock_diagnostic(a.clock_diagnostic_session);return
    if a.sql_check_session:
        sql_check(a.sql_check_session);return
    if not a.predecessor or not a.credentials_file:p.error('predecessor and explicit credentials file required')
    previous=Path(a.predecessor).resolve(strict=True)
    old,carry,_=closed_previous(previous,approve_uncertain=a.approved_uncertain_carry)
    if a.novel_followup and not (a.novel_budget_approved and old.get('novelAcceptance')):raise RuntimeError('NOVEL_FOLLOWUP_SCOPE_INVALID')
    resources()
    if a.reuse_verified_session:
        from ai_verified_fixture_copy import validate
        validate(a.reuse_verified_session,phase.ROOT/'runtime')
    old_session=phase.load_session(str(previous.parent))[1]
    digest=old_session['toolsDigest'].strip()
    actual=subprocess.run(['docker','image','inspect',digest,'--format','{{.Id}}'],check=True,capture_output=True,text=True).stdout.strip()
    if actual!=digest:raise RuntimeError('OWNED_TOOLS_IMAGE_MISMATCH')
    subprocess.run(['git','diff','--exit-code','HEAD','--','contracts','deployment/init-chain.sh','deployment/init-futures-fixture.sh','deployment/foundry.toml'],check=True,capture_output=True)
    name='phase-a-'+dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d%H%M%S')+'-'+secrets.token_hex(4)
    session=phase.ROOT/'runtime'/name
    session.mkdir(exist_ok=False)
    who=subprocess.run(['whoami'],check=True,capture_output=True,text=True).stdout.strip()
    subprocess.run(['icacls',str(session),'/inheritance:r','/grant:r',who+':(OI)(CI)F','SYSTEM:(OI)(CI)F'],check=True,capture_output=True)
    for child in ('logs','fixtures','evidence','latest-study'):(session/child).mkdir()
    head=phase.execute(session,['git','rev-parse','HEAD'],'source-head')
    metadata={'session':name,'sourceHead':head,'productHash':phase.product_hash(),'externalProviders':False,
        'toolsImage':digest,'toolsDigest':digest,'toolsRecipe':'Dockerfile.ai-latest-tools',
        'toolsReuse':'SAME_CONTRACT_RECIPE_AS_PRIOR_APPROVED_AI_FIXTURE','budgetPredecessor':str(previous)}
    # Contract/deploy source identity is covered by the final study fingerprint and immutable image.
    phase.private(session/'session.json',json.dumps(metadata))
    port=next(n for n in secrets.SystemRandom().sample(range(49152,65536),100) if phase._port_available(n))
    study=session/'latest-study'
    try:
        prepare(study,port,a.credentials_file,old['dbImage'],approved=True,
            predecessor=previous if a.novel_followup or not a.novel_budget_approved else None,
            novel_reference=old['separateApproval']['historicalReferenceOnly'] if a.novel_followup else previous if a.novel_budget_approved else None,
            uncertain_approved=a.approved_uncertain_carry,input_extension=a.approved_input_extension,residual=a.residual_only,scope_followup=a.scope_followup,
            novel_followup=a.novel_followup)
        print('FINAL_SESSION='+str(session),flush=True)
        print(('NEW_BUDGET_CARRIED_RESERVATIONS=' if a.novel_followup else 'HISTORICAL_REFERENCE_NOT_CHARGED=' if a.novel_budget_approved else 'CARRIED_RESERVATIONS=')+json.dumps(carry),flush=True)
        phase.fixture(session,metadata,'futures-ai-latest',latest_study=study,reuse_fixture=a.reuse_verified_session)
    finally:
        # Only the new run's temporary credential copy. Preserve original .env and all ledgers/data.
        if not (study/'STOP').exists():write_new(study/'STOP','FINAL_ACCEPTANCE_ENDED_NO_AUTOMATIC_RETRY')
        key=study/'openai.key'
        if key.is_symlink():raise RuntimeError('UNSAFE_CREDENTIAL_COPY')
        if key.is_file():key.unlink()


def clock_diagnostic(session_path):
    resources();session,_=phase.load_session(session_path)
    fixtures=list((session/'fixtures').glob('futures-ai-latest-*'))
    if len(fixtures)!=1:raise RuntimeError('EXACT_NEW_DIAGNOSTIC_FIXTURE_REQUIRED')
    folder=fixtures[0];meta=json.loads((folder/'run.json').read_text(encoding='utf-8'))
    target=meta['name']+'-postgres'
    c=json.loads(phase.execute(session,['docker','inspect',target],'clock-diagnostic-owner'))[0]
    if c['Config']['Labels'].get('phase-a.fixture')!=meta['name'] or c['State']['Running']:raise RuntimeError('STOPPED_OWN_POSTGRES_REQUIRED')
    rows=[]
    try:
        phase.execute(session,['docker','start',target],'clock-diagnostic-start')
        env=phase.clean_env(session);ready_end=time.monotonic()+30
        while True:
            ready=subprocess.run(['docker','exec',target,'psql','-X','-U','reserve','-d',meta['database'],'-tAc','SELECT 1'],env=env,capture_output=True,timeout=5)
            if ready.returncode==0:break
            if time.monotonic()>=ready_end:raise RuntimeError('DIAGNOSTIC_POSTGRES_NOT_READY')
            time.sleep(.5)
        for _ in range(3):
            row=phase.measure_clock(session,meta)
            row['worstSeconds']=abs(row['dbMinusHostSeconds'])+(row['hostEpochAfter']-row['hostEpochBefore'])/2
            rows.append(row)
        phase.private(folder/'precise-clock-diagnostic.json',json.dumps(rows))
        print(json.dumps(rows))
    finally:phase.execute(session,['docker','stop','--time','60',target],'clock-diagnostic-stop')


def sql_check(session_path):
    from ai_db_relay import docker_json,verify_target,Relay
    resources();session,_=phase.load_session(session_path);run=session/'latest-study'
    m=json.loads((run/'run.json').read_text(encoding='utf-8'))
    if not (run/'STOP').is_file() or not m.get('finalAcceptance'):raise RuntimeError('CLOSED_OWN_FINAL_TEST_REQUIRED')
    target=m['runId']+'-ai-postgres-1';c=docker_json('inspect',target)[0]
    if c['State']['Running'] or c['Image']!=m['dbImage'] or c['Config']['Labels'].get('ai-evaluation.run')!=m['runId'] or c['HostConfig']['NetworkMode']!='none':raise RuntimeError('STOPPED_OWN_AI_DB_REQUIRED')
    relay=None
    try:
        phase.execute(session,['docker','start',target],'sql-component-ai-start')
        end=time.monotonic()+45
        while docker_json('inspect',target)[0]['State'].get('Health',{}).get('Status')!='healthy':
            if time.monotonic()>end:raise RuntimeError('AI_DB_HEALTH_TIMEOUT')
            time.sleep(.5)
        verify_target(m);relay=Relay(m,seconds=180,max_connections=32).start()
        env=phase.clean_env(session);env.update({'AI_LATEST_SQL_TESTS':'true','AI_LATEST_STUDY_RUN':str(run)})
        phase.execute(session,[str(phase.REPO/'backend/gradlew.bat'),'test','--offline','--no-daemon','-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m','--tests','*LatestIdentifierSqlTest'],'sql-component-java',phase.REPO/'backend',env,timeout=150)
    finally:
        if relay:relay.close()
        phase.stop_new_ai_probe(session,m,'sql-component')


if __name__=='__main__':main()
