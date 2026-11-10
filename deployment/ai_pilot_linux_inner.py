"""Linux-only child orchestration. No keys/API/approval imports; model traffic stays controlled."""
import hashlib,json,os,subprocess,sys,time
from pathlib import Path
from product_read_linux_support import memory

RUN=Path('/run/pilot');ROOT=Path('/work')

def verify_source(root,manifest):
    if not isinstance(manifest,dict) or not manifest:raise RuntimeError('LINUX_SOURCE_MANIFEST')
    for name,expected in manifest.items():
        p=Path(name)
        if p.is_absolute() or '..' in p.parts or not isinstance(expected,str) or len(expected)!=64:raise RuntimeError('LINUX_SOURCE_MANIFEST')
        file=root/p
        if file.is_symlink() or not file.is_file():raise RuntimeError('LINUX_SOURCE_COPY_MISMATCH')
        with file.open('rb') as stream:
            if hashlib.file_digest(stream,'sha256').hexdigest()!=expected:raise RuntimeError('LINUX_SOURCE_COPY_MISMATCH')
    return len(manifest)

def stages(scope):
    full=('FULL_HTTP_CHROME','com.pricetrack.exchange.futures.chain.PilotV2LiveHttpTest',[],1500)
    if scope=='FULL':return [('C_SMOKE','PilotV2LinuxSmoke',['/run/pilot/smoke.properties'],160),full]
    if scope=='A2_PREFIX':return [('A2_PREFIX_HTTP',full[1],[],1200)]
    if scope=='NOVEL30':return [('NOVEL30_HTTP',full[1],[],1500)]
    if scope=='C_LATENCY':return [('C_LATENCY_HTTP',full[1],[],1500)]
    if scope=='RECEIPT_PROBE':return [('RECEIPT_PROBE','com.pricetrack.exchange.futures.chain.PilotReceiptProbeTest',[],480)]
    if scope=='RECEIPT_AGENT':return [('RECEIPT_AGENT_PREFIX_HTTP',full[1],[],1200)]
    raise RuntimeError('LINUX_HTTP_SCOPE_INVALID')

def run():
    if sys.platform!='linux' or os.environ.get('AI_PILOT_V2_LINUX_INTERNAL')!='true':raise RuntimeError('LINUX_EXPLICIT_OPT_IN')
    record={'platform':sys.platform,'measurement':memory(),'paidCalls':0,'apiKeyReads':0,'stage':'TOOLS'}
    (RUN/'linux-start.json').write_text(json.dumps(record))
    if record['measurement']['availableBytes']<1920*1024**2:raise RuntimeError('LINUX_RAM_LOW')
    if os.environ.get('AI_PILOT_LOCAL_SOURCE')=='true':
        count=verify_source(ROOT,json.loads((RUN/'source-files.json').read_text()))
        (RUN/'linux-source-copy.json').write_text(json.dumps({'filesVerified':count,'hashesMatch':True}))
    scope=os.environ.get('AI_PILOT_V2_HTTP_SCOPE','FULL');plan=stages(scope)
    for name,args in [('java',['java','-version']),('python',['python','--version']),('node',['node','--version']),('chromium',['chromium','--version'])]:
        p=subprocess.run(args,capture_output=True,timeout=10,check=True);record[name]=(p.stdout+p.stderr).decode().strip()
    if '21.0.10' not in record['java'] or not record['python'].startswith('Python 3.12.') or record['node']!='v22.22.0':raise RuntimeError('LINUX_TOOL_VERSION')
    (RUN/'linux-tools.json').write_text(json.dumps(record))
    cp=':'.join('/work/backend/'+p for p in ['build/classes/java/main','build/classes/java/test','build/resources/main','build/resources/test','build/pilot-v2-test/jars/*','build/product-read-ci/helpers'])
    for stage,main,args,limit in plan:
        (RUN/'linux-stage.json').write_text(json.dumps({'stage':stage,'at':time.time(),'paidCalls':0}))
        command=['java','-Xmx384m','-XX:MaxMetaspaceSize=256m','-Djdk.httpclient.disableRetryConnect=true','-Djdk.httpclient.enableAllMethodRetry=false','-Djdk.httpclient.redirects.retrylimit=1','--add-opens=java.net.http/jdk.internal.net.http=ALL-UNNAMED','-cp',cp,main,*args]
        with (RUN/(stage.lower()+'.private.log')).open('xb') as out:
            child=subprocess.Popen(command,cwd=ROOT/'backend',stdout=out,stderr=subprocess.STDOUT)
            try:code=child.wait(timeout=limit)
            finally:
                if child.poll() is None:child.terminate();child.wait(timeout=15)
        if code:raise RuntimeError('LINUX_'+stage+'_FAILED')
    if scope=='RECEIPT_AGENT':
        result=json.loads((RUN/'receipt-agent-result.json').read_text())
        if result!={'requests':6,'financialUnchanged':True,'cStateUnchanged':True,'budgetMatching':True,'status':'PASS_PREFIX_NOT_FULL_ACCEPTANCE'}:raise RuntimeError('RECEIPT_AGENT_EVIDENCE_REQUIRED')
        if not json.loads((RUN/'receipt-timing.private.json').read_text()):raise RuntimeError('RECEIPT_AGENT_TRACE_REQUIRED')
        (RUN/'linux-result.json').write_text(json.dumps({'status':'PASS_RECEIPT_AGENT_PREFIX_NOT_ACCEPTANCE','paidCalls':0,'apiKeyReads':0,'full30':'NOT_RUN','cLatency':'NOT_RUN','browser':'NOT_RUN'}));return
    if scope=='RECEIPT_PROBE':
        result=json.loads((RUN/'receipt-result.json').read_text());fingerprint=json.loads((RUN/'receipt-fingerprint.json').read_text())
        if result.get('calls')!=16 or result.get('status')!='MEASURED_NOT_INTEGRATION_ACCEPTANCE' or fingerprint.get('unchanged') is not True:raise RuntimeError('RECEIPT_PROBE_EVIDENCE_REQUIRED')
        (RUN/'linux-result.json').write_text(json.dumps({'status':'MEASURED_RECEIPT_NOT_ACCEPTANCE','paidCalls':0,'apiKeyReads':0,'full30':'NOT_RUN','cLatency':'NOT_RUN','browser':'NOT_RUN'}));return
    if scope=='C_LATENCY':
        result=json.loads((RUN/'http-result-c-latency.json').read_text())
        if result!={'scope':'C_LATENCY','httpRequests':51,'cRequests':3,'latencyRequests':12,'financialUnchanged':True,'cStateUnchanged':True,'budgetMatching':True,'browser':'NOT_RUN','realModelQuality':'NOT_RUN'}:raise RuntimeError('LINUX_C_LATENCY_EVIDENCE_REQUIRED')
        (RUN/'linux-result.json').write_text(json.dumps({'status':'PASS_C_LATENCY_TRANSPORT_NOT_QUALITY','paidCalls':0,'apiKeyReads':0,'windowsPortRelay':False,'browser':'NOT_RUN'}))
        return
    if scope=='NOVEL30':
        result=json.loads((RUN/'http-result-novel30.json').read_text())
        if result!={'scope':'NOVEL30','httpRequests':36,'novelQuestions':30,'controlledProvider':True,'realModel':False,'financialUnchanged':True,'budgetMatching':True,'quality':'NOT_RUN_REAL_MODEL'}:raise RuntimeError('LINUX_NOVEL30_EVIDENCE_REQUIRED')
        (RUN/'linux-result.json').write_text(json.dumps({'status':'PASS_NOVEL30_HTTP_TRANSPORT_NOT_QUALITY','paidCalls':0,'apiKeyReads':0,'windowsPortRelay':False,'novel30Http':30,'realModelQuality':'NOT_RUN'}))
        return
    if scope=='A2_PREFIX':
        result=json.loads((RUN/'http-result-a2.json').read_text())
        if result.get('scope')!='A2_PREFIX' or result.get('httpRequests')!=8 or result.get('a2TransportPass') is not True or result.get('financialUnchanged') is not True or result.get('budgetMatching') is not True:raise RuntimeError('LINUX_A2_EVIDENCE_REQUIRED')
        (RUN/'linux-result.json').write_text(json.dumps({'status':'PASS_A2_HTTP_TRANSPORT_NOT_QUALITY','paidCalls':0,'apiKeyReads':0,'windowsPortRelay':False,'full30':'NOT_RUN','realModelQuality':'NOT_RUN'}))
        return
    result=json.loads((RUN/'http-result.json').read_text())
    if result!={'controlledProvider':True,'realModel':False,'httpRequests':51,'browserRequests':6,'financialUnchanged':True,'paidCalls':0,'keyReads':0}:raise RuntimeError('LINUX_COMPLETE_EVIDENCE_REQUIRED')
    (RUN/'linux-result.json').write_text(json.dumps({'status':'PASS','paidCalls':0,'apiKeyReads':0,'windowsPortRelay':False}))

if __name__=='__main__':
    try:run()
    except Exception as e:
        code=str(e) if str(e).replace('_','').isalnum() else type(e).__name__
        (RUN/'linux-failure.json').write_text(json.dumps({'code':code,'paidCalls':0,'apiKeyReads':0}))
        print('LINUX_UNPAID_FAILED_'+code,file=sys.stderr);raise SystemExit(1)
