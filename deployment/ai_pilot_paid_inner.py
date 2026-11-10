"""Actual Linux Spring/PG/DOM coordinator. PREPARE/PROBE have no key or WAN path.

PAID is a separate, permit-bound fresh allocation. Never upgrades a probe ledger.
All logs/evidence stay in the owned private trial. No implicit retry/approval.
"""
import hashlib
import json
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path

from ai_pilot_preflight import ROOT,canonical,need,preflight
from ai_pilot_channel import source_fingerprint,prepare_unpaid,load,prepare_paid
from ai_pilot_fixture import bind_fixture
from ai_pilot_http_adapter import PilotAuthenticatedHttp
from ai_pilot_http_plan import PilotHttpPlan
from ai_pilot_paid_adapter import SpringAdapter,BrowserAdapter,ServerControl,FrameReader,private_write
from ai_pilot_paid_schedule import EvidenceAdapter,from_session
from ai_pilot_paid_sender import context
from ai_pilot_linux_inner import verify_source
from product_read_linux_support import memory

RUN=Path('/run/pilot')


def wait_file(path,child,seconds):
    end=time.monotonic()+seconds
    while not path.exists():
        need(child.poll() is None,'V2_PAID_SERVER_EARLY_EXIT')
        need(time.monotonic()<end,'V2_PAID_SERVER_READY_DEADLINE');time.sleep(.1)


def host_admission():
    request=RUN/'browser-host-request.json';reply=RUN/'browser-host-admission.json'
    private_write(request,{'kind':'BROWSER_ADMISSION','at':time.time()})
    end=time.monotonic()+30
    while not reply.exists():
        need(time.monotonic()<end,'V2_PAID_HOST_ADMISSION_REQUIRED');time.sleep(.1)
    need(json.loads(reply.read_text())=={'allowed':True},'V2_PAID_HOST_RESOURCE_BLOCKED')


def java_command():
    cp=':'.join(str(ROOT/'backend'/p) for p in ['build/classes/java/main','build/classes/java/test','build/resources/main','build/resources/test','build/pilot-v2-test/jars/*','build/product-read-ci/helpers'])
    return ['java','-Xmx384m','-XX:MaxMetaspaceSize=256m','-Djdk.httpclient.disableRetryConnect=true',
            '-Djdk.httpclient.enableAllMethodRetry=false','-Djdk.httpclient.redirects.retrylimit=1',
            '--add-opens=java.net.http/jdk.internal.net.http=ALL-UNNAMED','-cp',cp,
            'com.pricetrack.exchange.futures.chain.PilotPaidServer']


def linux_frame_proof():
    # Exact component skipped on Windows anonymous pipes, now exercised on Linux.
    a,b=os.pipe()
    with os.fdopen(a,'rb') as incoming,os.fdopen(b,'wb') as outgoing:
        reader=FrameReader(incoming)
        try:
            outgoing.write(b'{"op":"ready"}\n');outgoing.flush();need(reader.read(.2)=={'op':'ready'},'V2_FRAME_PROOF')
            try:reader.read(.01)
            except ValueError as e:need(str(e)=='V2_BROWSER_PROTOCOL_DEADLINE','V2_FRAME_PROOF')
            else:raise ValueError('V2_FRAME_PROOF')
        finally:reader.close()
    private_write(RUN/'linux-frame-proof.json',{'framing':True,'deadline':True,'apiRequests':0})


def finalize_execution(browser,child,server,session,mode,log,success,*,primary_present):
    """One failed finalizer must not suppress STOP, server shutdown or child reaping."""
    result={}
    def attempt(name,action):
        try:action();result[name]='DONE'
        except BaseException as error:result[name]=type(error).__name__
    if browser is not None:attempt('browser',lambda:browser.close(False))
    if not success and mode!='PREPARE' and session.is_dir() and not (session/'STOP').exists():
        attempt('stop',lambda:private_write(session/'STOP',{'reason':'EXECUTION_ABORTED_NO_REPLAY'}))
    if server is not None and child.poll() is None:attempt('server',lambda:server('shutdown'))
    def reap():
        try:child.wait(timeout=25)
        except subprocess.TimeoutExpired:
            child.terminate()
            try:child.wait(timeout=10)
            except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)
        need(child.returncode==0,'V2_PAID_SERVER_CLEAN_EXIT_REQUIRED')
    attempt('java',reap);attempt('log',log.close)
    attempt('evidence',lambda:private_write(RUN/'inner-cleanup.json',{'steps':dict(result),'primaryFailurePreserved':primary_present}))
    if not primary_present:need(all(v=='DONE' for v in result.values()),'V2_PAID_CLEANUP_FAILED')
    return result


def execute():
    need(sys.platform=='linux','V2_PAID_LINUX_EXECUTION_REQUIRED');preflight()
    need(memory()['availableBytes']>=1920*1024**2,'V2_PAID_LINUX_RAM_LOW')
    verify_source(ROOT,json.loads((RUN/'source-files.json').read_text()))
    artifact=ROOT/'deployment/paid-build-identity.json'
    need(artifact.is_file() and not artifact.is_symlink() and artifact.stat().st_size<=2_000_000,'V2_PAID_BUILD_IDENTITY_REQUIRED')
    identity=json.loads(artifact.read_text());verify_source(ROOT,identity['files'])
    need(identity['images']==json.loads(os.environ['AI_PILOT_IMAGES']),'V2_PAID_IMAGE_IDENTITY')
    tools={name:subprocess.run(args,capture_output=True,check=True,timeout=10) for name,args in
           [('java',['java','-version']),('node',['node','--version']),('python',['python','--version'])]}
    need('21.0.10' in (tools['java'].stdout+tools['java'].stderr).decode() and tools['node'].stdout.decode().strip()=='v22.22.0'
         and tools['python'].stdout.decode().startswith('Python 3.12.'),'V2_PAID_TOOLS_VERSION')
    mode=os.environ['AI_PILOT_EXECUTION'];need(mode in {'PREPARE','PROBE','PAID','AUTHORIZE','CONTINUATION_PLAN','AUTHORIZE_CONTINUATION'},'V2_PAID_EXECUTION_MODE')
    config=json.loads(Path(os.environ['AI_PILOT_V2_CONFIG']).read_text())
    if mode in {'CONTINUATION_PLAN','AUTHORIZE_CONTINUATION'}:
        from ai_pilot_continuation import make_meta,import_ledger
        meta=make_meta(config,RUN)
        if mode=='CONTINUATION_PLAN':
            private_write(RUN/'approval-request.private.json',{'context':context(meta),'approvalId':meta['approvalId'],'status':'CONTINUATION_NOT_ACTIVATED'})
        else:
            need(json.loads((RUN/'approved-permit.json').read_text())['keyFile']=='/run/approved-provider-key','V2_PAID_KEY_REFERENCE')
            import_ledger(meta,RUN/'approved-permit.json')
            private_write(RUN/'authorized.json',{'authorized':True,'source':source_fingerprint(),'sameBudget':True})
        return
    if mode=='AUTHORIZE':
        need(config['mode']=='PAID_EXPLICIT_V2','V2_PAID_APPROVAL_REQUIRED')
        binding=json.loads((RUN/'prepared-binding.private.json').read_text())
        permit=json.loads((RUN/'approved-permit.json').read_text())
        request=json.loads((RUN/'approval-request.private.json').read_text())
        need(all(permit.get(k)==v for k,v in request['context'].items()),'V2_PAID_BINDING_MISMATCH')
        need(permit.get('keyFile')=='/run/approved-provider-key','V2_PAID_KEY_REFERENCE')
        authority=hashlib.sha256((config['budget']['jdbcUrl']+'\n'+config['budget']['dbUser']).encode()).hexdigest()
        session=Path(config['run']);session.mkdir()
        prepare_paid(session,binding,RUN/'approved-permit.json',authority)
        private_write(RUN/'authorized.json',{'authorized':True,'source':source_fingerprint(),
                      'permitHash':hashlib.sha256(canonical(permit)).hexdigest()});return
    need(config['mode']==('PAID_EXPLICIT_V2' if mode=='PAID' else 'UNPAID_CONTROLLED_ONLY'),'V2_PAID_MODE_MISMATCH')
    linux_frame_proof()
    # PAID validity checked before even starting the server. Per-call sender rechecks again.
    runner=from_session(config['run']) if mode=='PAID' else None
    private_write(RUN/'inner-start.json',{'mode':mode,'at':time.time(),'source':source_fingerprint()})
    log=(RUN/'server.private.log').open('xb')
    child=subprocess.Popen(java_command(),cwd=ROOT/'backend',stdout=log,stderr=subprocess.STDOUT)
    server=None;browser=None;success=False
    try:
        wait_file(RUN/'server-ready.json',child,100);server=ServerControl(RUN)
        descriptor=json.loads((RUN/'prepared-descriptor.private.json').read_text())
        fixture_api=PilotAuthenticatedHttp(descriptor['origin'],descriptor['accounts'],private_control=None,fingerprint=None,review=None,budget_snapshot=None)
        binding=bind_fixture(descriptor['recipe'],fixture_api.request)
        private_write(RUN/'observed-binding.private.json',binding)
        if mode=='PREPARE':
            private_write(RUN/'prepared-binding.private.json',binding)
            authority=hashlib.sha256((json.loads((RUN/'paid-config.private.json').read_text())['budget']['jdbcUrl']+'\npilot').encode()).hexdigest()
            folder=json.loads((RUN/'paid-config.private.json').read_text())['run']
            meta={'allocation':str(uuid.uuid4()),'source':source_fingerprint(),'fixtureHash':hashlib.sha256(canonical(binding)).hexdigest(),
                  'fixture':binding,'budgetAuthority':authority,'folder':folder,'mode':'PAID_EXPLICIT_V2'}
            private_write(RUN/'approval-request.private.json',{'context':context(meta),'status':'NOT_APPROVED','activationAllowed':False})
            private_write(RUN/'inner-result.json',{'status':'PREPARED_NOT_APPROVED','financialUnchanged':True,'apiRequests':0,'keyReads':0})
        else:
            if mode=='PROBE':
                session=Path(config['run']);session.mkdir();prepare_unpaid(session,binding);c=load(session)
            else:
                c=runner.c;need(binding==runner.meta['fixture'],'V2_PAID_FIXTURE_DRIFT')
            rows=c.rows
            selected=[r for r in rows if r['phase']=='SAFETY_LEGACY' and r['case']=='PF1b'] if mode=='PROBE' else [r for r in rows if r['phase']=='WEB_PILOT']
            browser=BrowserAdapter(RUN,{'origin':descriptor['origin'],'mode':config['mode'],'accounts':{k:descriptor['accounts'][k] for k in ('owner','other')},
                       'rows':selected,**({'scope':'SINGLE_CONNECTION_PROBE'} if mode=='PROBE' else {})},host_admission=host_admission)
            adapter=SpringAdapter(c,binding,descriptor,RUN,browser=browser,mode=config['mode'])
            if mode=='PAID' and 'continuation' in runner.meta:
                need(c.reconcile(adapter.budget_snapshot())['matching'],'V2_CARRY_BUDGET_MISMATCH')
            else:
                initialized=adapter.request('admin','POST','/api/ai/provider-budget/initialize',{})
                need(initialized[0]==200,'V2_PAID_PG_BUDGET_INITIALIZE_FAILED')
            if mode=='PROBE':
                plan=PilotHttpPlan(c,binding);safe=EvidenceAdapter(adapter,RUN)
                plan.run_index('INDEX_LEGACY',safe)
                row=selected[0];ticket=c.claim(row['phase'],row['slot'])
                status,reply=safe.web_submit(row['actor'],row['body'])
                need(status==row['expectedHttp'],'V2_PAID_PROBE_HTTP_FAILED')
                c.complete_request(ticket,reply.get('runId'));need(safe.review(row,reply) is True,'V2_PAID_PROBE_TRANSPORT_FAILED')
                need(c.reconcile(adapter.budget_snapshot())['matching'],'V2_PAID_PROBE_BUDGET_MISMATCH')
                need(adapter.financial_fingerprint()==binding['financialFingerprint'],'V2_FINANCIAL_FINGERPRINT_CHANGED')
                browser.close(True);browser=None;c.stop()
                private_write(RUN/'probe-budget.json',c.summary())
                private_write(RUN/'inner-result.json',{'status':'PASS_PAID_ADAPTER_CONTROLLED_PROBE_NOT_QUALITY','domRequests':1,'source':'ACTUAL_SPRING_PG_ANVIL_CHROME',
                             'financialUnchanged':True,'cStateUnchanged':True,'budgetMatching':True,'realModelQuality':'NOT_RUN','apiRequests':0,'keyReads':0})
            else:
                result=runner.run(adapter);browser.close(True);browser=None
                private_write(RUN/'paid-results.private.json',result)
                private_write(RUN/'inner-result.json',{'status':'PAID_SCHEDULE_COMPLETED_REQUIRES_FINAL_QUALITY_REVIEW','financialUnchanged':adapter.financial_fingerprint()==binding['financialFingerprint']})
        success=True
    finally:
        finalize_execution(browser,child,server,Path(config['run']),mode,log,success,primary_present=sys.exception() is not None)


if __name__=='__main__':
    try:execute()
    except Exception as error:
        code=str(error)
        if not code.startswith('V2_') or not code.replace('_','').isalnum():code='V2_PAID_EXECUTION_FAILED'
        private_write(RUN/'inner-failure.json',{'code':code,'errorType':type(error).__name__})
        print(code,file=sys.stderr);raise SystemExit(1)
