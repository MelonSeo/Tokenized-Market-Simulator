"""Owned, unpaid HTTP/pgvector readiness test. Never reads .env or evaluation ledgers."""
import argparse
import ctypes
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import time

REPO = Path(__file__).resolve().parent.parent
PLAN = REPO/'deployment/ai-readiness-plan.json'


def plan_hash():
    return hashlib.sha256(json.dumps(json.loads(PLAN.read_text()),sort_keys=True,separators=(',',':')).encode()).hexdigest()


def resources():
    if os.name == 'nt':
        class Memory(ctypes.Structure):
            _fields_=[('length',ctypes.c_ulong),('load',ctypes.c_ulong)]+[(n,ctypes.c_ulonglong) for n in ('totalPhys','availPhys','totalPage','availPage','totalVirtual','availVirtual','extended')]
        m=Memory();m.length=ctypes.sizeof(m)
        if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m)):raise RuntimeError('MEMORY_API_FAILED')
        available=m.availPhys
    else:
        entries=dict(line.split(':',1) for line in Path('/proc/meminfo').read_text().splitlines())
        available=int(entries['MemAvailable'].split()[0])*1024
    return resource_result(available,shutil.disk_usage(REPO).free)


def resource_result(available,free):
    result={'availableRamMiB':round(available/1024**2,2),'freeDiskGiB':round(free/1024**3,2)}
    p=json.loads(PLAN.read_text())
    result['ready']=available>=p['minimumRamMiB']*1024**2 and free>=p['minimumDiskGiB']*1024**3
    return result


def clean_env():
    keep={'PATH','SYSTEMROOT','WINDIR','COMSPEC','USERPROFILE','HOME','APPDATA','LOCALAPPDATA','TEMP','TMP','JAVA_HOME','GRADLE_USER_HOME','DOCKER_CONFIG','DOCKER_CONTEXT','NUMBER_OF_PROCESSORS','PROCESSOR_ARCHITECTURE','LANG'}
    result={k:v for k,v in os.environ.items() if k.upper() in keep}
    result.update({'AI_ENABLED':'false','AI_AUTO_DIAGNOSIS_ENABLED':'false','BLOCKCHAIN_ENABLED':'false',
                   'JAVA_TOOL_OPTIONS':'-Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false'})
    return result


def command(args,timeout=30):
    return subprocess.run(args,cwd=REPO,env=clean_env(),capture_output=True,timeout=timeout,check=True).stdout.decode('utf-8',errors='replace').strip()


def inspect_owned(name,run_id):
    obj=json.loads(command(['docker','inspect',name]))[0]
    if obj['Config']['Labels'].get('tms.ai-readiness.run')!=run_id or obj['Name']!='/'+name or obj['Config']['Image']!=json.loads(PLAN.read_text())['image']:
        raise RuntimeError('RESOURCE_OWNERSHIP_MISMATCH')
    return obj


def validate_metrics(value):
    keys={'schema','baseCommit','planSha256','mode','environment','load','cache','faults','security','resources','invariants','verdict'}
    if not isinstance(value,dict) or set(value)!=keys:raise ValueError('PUBLIC_SCHEMA_INVALID')
    denied={'password','token','accessToken','refreshToken','privateKey','apiKey','secret','credentials','question','answer','body','toolEvidence','knowledgeSources','jdbcUrl'}
    def visit(v):
        if isinstance(v,dict):
            for k,w in v.items():
                if k in denied or not re.fullmatch(r'[A-Za-z0-9_]{1,80}',k):raise ValueError('PUBLIC_FIELD_INVALID')
                visit(w)
        elif isinstance(v,list):
            for w in v:visit(w)
        elif isinstance(v,str):
            if not re.fullmatch(r'[A-Za-z0-9_.:-]{1,120}',v) or re.search(r'(?i)sk-|bearer|eyJ|jdbc:|[a-z]:|Users',v):raise ValueError('PUBLIC_STRING_INVALID')
        elif isinstance(v,float):
            if not math.isfinite(v):raise ValueError('PUBLIC_NUMBER_INVALID')
        elif v is not None and not isinstance(v,(bool,int)):raise ValueError('PUBLIC_VALUE_INVALID')
    visit(value)
    if value['baseCommit']!=json.loads(PLAN.read_text())['baseCommit'] or value['planSha256']!=plan_hash():raise ValueError('PUBLIC_IDENTITY_INVALID')
    return value


def safe_junit_failures(path):
    """Names/types/own source frames only, never assertion values or provider/DB bodies."""
    import xml.etree.ElementTree as ET
    out=[]
    for case in ET.parse(path).getroot().iter('testcase'):
        for failure in list(case.findall('failure'))+list(case.findall('error')):
            name=case.attrib.get('classname','')+'.'+case.attrib.get('name','')
            kind=failure.attrib.get('type','')
            frames=re.findall(r'com\.pricetrack\.exchange\.[A-Za-z0-9_.$]+\([A-Za-z0-9_]+\.java:[0-9]+\)',failure.text or '')[:3]
            if re.fullmatch(r'[A-Za-z0-9_.$() -]{1,250}',name) and re.fullmatch(r'[A-Za-z0-9_.$]{1,150}',kind):out.append({'test':name,'type':kind,'frames':frames})
    return out


def verify_web(folder):
    expected={'01-ai-agent.png','02-ai-abnormal.png','manifest.json'}
    if folder.is_symlink() or {p.name for p in folder.iterdir()}!=expected:raise ValueError('WEB_ARTIFACT_ALLOWLIST')
    m=json.loads((folder/'manifest.json').read_text())
    if set(m)!={'mode','sha256'} or m['mode']!='OWNED_FIXTURE_CONTROLLED_PROVIDER_NOT_REAL_MODEL':raise ValueError('WEB_ARTIFACT_MODE')
    if set(m['sha256'])!=expected-{'manifest.json'}:raise ValueError('WEB_ARTIFACT_MANIFEST')
    for name,sha in m['sha256'].items():
        f=folder/name
        if f.is_symlink() or f.stat().st_size>5_000_000 or not f.read_bytes().startswith(b'\x89PNG\r\n\x1a\n') or hashlib.sha256(f.read_bytes()).hexdigest()!=sha:raise ValueError('WEB_ARTIFACT_INVALID')


def run():
    p=json.loads(PLAN.read_text());root=REPO/'deployment/runtime'
    folder=root/('ai-readiness-'+dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+secrets.token_hex(4))
    folder.mkdir(parents=True,exist_ok=False)
    if os.name=='nt':
        who=command(['whoami'])
        command(['icacls',str(folder),'/inheritance:r','/grant:r',who+':(OI)(CI)F','SYSTEM:(OI)(CI)F'])
    else:folder.chmod(0o700)
    resource=resources();(folder/'resources.json').write_text(json.dumps(resource))
    print('READINESS_RUN='+str(folder),flush=True);print(json.dumps(resource),flush=True)
    if not resource['ready']:raise RuntimeError('RESOURCE_GUARD_BLOCKED')
    command(['git','merge-base','--is-ancestor',p['baseCommit'],'HEAD'])
    command(['git','diff','--exit-code',p['baseCommit'],'--','backend/src/main','contracts',':(exclude)backend/src/main/java/com/pricetrack/exchange/ai/**'])
    command(['docker','image','inspect',p['image']])  # cached, never auto-pull or build
    run_id=secrets.token_hex(16);name='tms-ai-readiness-'+run_id
    db='exchange_ai_readiness_'+run_id;password=secrets.token_urlsafe(36)
    env_file=folder/'db.env';env_file.write_text('POSTGRES_DB='+db+'\nPOSTGRES_USER=readiness\nPOSTGRES_PASSWORD='+password+'\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\n')
    created=False;create_attempted=False;removed=False;process=None;started=time.monotonic();exit_code=1;stage='CREATE_DATABASE'
    try:
        create_attempted=True
        command(['docker','create','--name',name,'--label','tms.ai-readiness.run='+run_id,'--memory','320m','--cpus','1',
            '--read-only','--tmpfs','/var/lib/postgresql/data:rw,size=201326592','--tmpfs','/var/run/postgresql:rw,size=16777216',
            '--tmpfs','/tmp:rw,size=16777216','--env-file',str(env_file),'-p','127.0.0.1::5432',p['image'],
            'postgres','-c','shared_buffers=16MB','-c','max_connections=16'])
        created=True;stage='START_DATABASE';command(['docker','start',name])
        obj=inspect_owned(name,run_id)
        if any(m['Type']!='tmpfs' for m in obj['Mounts']):raise RuntimeError('UNEXPECTED_PERSISTENT_MOUNT')
        binding=obj['NetworkSettings']['Ports']['5432/tcp'][0]
        if binding['HostIp']!='127.0.0.1':raise RuntimeError('NON_LOOPBACK_DATABASE')
        stage='DATABASE_READY';end=time.monotonic()+45
        while True:
            ready=subprocess.run(['docker','exec',name,'pg_isready','-U','readiness','-d',db],env=clean_env(),capture_output=True,timeout=5)
            if ready.returncode==0:break
            if time.monotonic()>end:raise RuntimeError('OWN_DATABASE_NOT_READY')
            time.sleep(.5)
        with socket.create_connection(('127.0.0.1',int(binding['HostPort'])),timeout=5):pass
        props=folder/'run.properties'
        props.write_text('db.url=jdbc:postgresql://127.0.0.1:'+binding['HostPort']+'/'+db+'?connectTimeout=2&socketTimeout=5\ndb.user=readiness\ndb.password='+password+
            '\ncontainer='+name+'\nidentity='+run_id+'\nimage='+p['image']+'\noutput='+folder.as_posix()+'\n',encoding='utf-8')
        # Measured server startup happens only after all resource/ownership checks.
        env=clean_env();env.update({'AI_READINESS_TESTS':'true','AI_READINESS_RUN':str(props)})
        if os.environ.get('AI_PILOT_BROWSER')=='true':
            dist=REPO/'tools/websocket-test-client/dist'
            if not (dist/'index.html').is_file():raise RuntimeError('BUILD_WEB_FIRST')
            shutil.copytree(dist,REPO/'backend/build/resources/main/static',dirs_exist_ok=True)
            env['AI_PILOT_BROWSER']='true'
        gradle=[str(REPO/'backend/gradlew.bat')] if os.name=='nt' else ['bash',str(REPO/'backend/gradlew')]
        args=gradle+['-p',str(REPO/'backend'),'-I',str(REPO/'deployment/ai-readiness.gradle'),'test','--offline','--no-daemon','--max-workers=1',
            '-Dorg.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=192m','--tests','*AiReadinessHttpPerformanceTest','--tests','*AiProviderBudgetPostgresTest']
        stage='HTTP_LOAD_AND_FAULTS'
        with (folder/'java.private.log').open('wb') as log:
            process=subprocess.Popen(args,cwd=REPO,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=os.name!='nt')
            try:exit_code=process.wait(timeout=p['runTimeoutSeconds'])
            except subprocess.TimeoutExpired:
                stop_process(process);raise RuntimeError('OWN_TEST_PROCESS_TIMEOUT')
        for test in ('AiReadinessHttpPerformanceTest','AiProviderBudgetPostgresTest'):
            junit=REPO/('backend/build/test-results/test/TEST-com.pricetrack.exchange.ai.readiness.'+test+'.xml')
            if junit.is_file():
                shutil.copy2(junit,folder/(test+'.private.xml'))
                if exit_code:print(json.dumps({'safeJunitFailures':safe_junit_failures(junit)}),flush=True)
        if exit_code and (folder/'browser.private.log').is_file():
            for code in re.findall(r'AI_BROWSER_FAILED_[A-Z_]{1,80}',(folder/'browser.private.log').read_text(errors='replace')):print(code,flush=True)
        stage='VALIDATE_EVIDENCE';metrics=folder/'metrics.json'
        if metrics.exists():
            public=validate_metrics(json.loads(metrics.read_text(encoding='utf-8')))
            if exit_code and public['verdict']=='PASS':raise RuntimeError('FAILED_JUNIT_CANNOT_PUBLISH_PASS')
            dest=folder/'public';dest.mkdir(exist_ok=False)
            (dest/'metrics.json').write_text(json.dumps(public,indent=2),encoding='utf-8')
            (dest/'manifest.json').write_text(json.dumps({'sha256':hashlib.sha256((dest/'metrics.json').read_bytes()).hexdigest(),'file':'metrics.json','planSha256':plan_hash()}))
        else:raise RuntimeError('MEASUREMENTS_NOT_PRODUCED')
        if exit_code:raise RuntimeError('READINESS_ASSERTION_OR_EXECUTION_FAILED')
        if os.environ.get('AI_PILOT_BROWSER')=='true':
            observed=json.loads((folder/'browser/result.json').read_text())
            if not observed.get('passed') or not observed.get('controlledProvider') or observed.get('realModel') is not False or not observed.get('ownChromeExited'):raise RuntimeError('OWN_WEB_NOT_VERIFIED')
            web=folder/'public-web';web.mkdir()
            hashes={}
            for screenshot_name in ('01-ai-agent.png','02-ai-abnormal.png'):
                shutil.copy2(folder/'browser'/screenshot_name,web/screenshot_name);hashes[screenshot_name]=hashlib.sha256((web/screenshot_name).read_bytes()).hexdigest()
            (web/'manifest.json').write_text(json.dumps({'mode':'OWNED_FIXTURE_CONTROLLED_PROVIDER_NOT_REAL_MODEL','sha256':hashes}));verify_web(web)
    finally:
        try:
            if process is not None and process.poll() is None:stop_process(process)
            # A CLI timeout can happen after daemon-side create succeeded.
            exists=create_attempted and name in command(['docker','container','ls','-a','--filter','name=^/'+name+'$','--format','{{.Names}}']).splitlines()
            if exists:
                obj=inspect_owned(name,run_id)
                if obj['State']['Paused']:command(['docker','unpause',name])
                logs=subprocess.run(['docker','logs',name],capture_output=True,env=clean_env(),timeout=15)
                (folder/'postgres.private.log').write_bytes(logs.stdout+logs.stderr)
                command(['docker','stop','--time','10',name]);command(['docker','rm',name]);removed=True
        finally:
            for secret in (env_file,folder/'run.properties'):
                if secret.is_file():secret.unlink()  # only this run's temporary generated credentials
            termination={'exitCode':exit_code,'stage':stage,'elapsedSeconds':round(time.monotonic()-started,3),'ownContainerRemoved':removed}
            (folder/'termination.json').write_text(json.dumps(termination));print(json.dumps(termination),flush=True)
    if not removed:raise RuntimeError('OWN_CONTAINER_CLEANUP_UNCONFIRMED')
    print('READINESS_EXECUTION_PASS_NOT_REAL_PROVIDER_SLA',flush=True)


def stop_process(process):
    if process.poll() is not None:return
    if os.name=='nt':
        subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'],capture_output=True,timeout=20,check=True)
    else:
        import signal
        # This group was created by our Popen(start_new_session=True), never the user's shell.
        os.killpg(process.pid,signal.SIGTERM)
    process.wait(timeout=20)


def verify_public():
    files=list((REPO/'deployment/runtime').glob('ai-readiness-*/public/metrics.json'))
    if not files:raise RuntimeError('NO_PUBLIC_METRICS')
    for metrics in files:
        if metrics.is_symlink() or metrics.parent.is_symlink() or (metrics.parent/'manifest.json').is_symlink():raise RuntimeError('UNSAFE_PUBLIC_PATH')
        if {p.name for p in metrics.parent.iterdir()}!={'metrics.json','manifest.json'}:raise RuntimeError('UNEXPECTED_PUBLIC_FILE')
        validate_metrics(json.loads(metrics.read_text()))
        manifest=json.loads((metrics.parent/'manifest.json').read_text())
        if manifest!={'sha256':hashlib.sha256(metrics.read_bytes()).hexdigest(),'file':'metrics.json','planSha256':plan_hash()}:raise RuntimeError('PUBLIC_MANIFEST_INVALID')
        web=metrics.parent.parent/'public-web'
        if web.exists():verify_web(web)
    print('PUBLIC_NUMERIC_METRICS_VERIFIED')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('action',choices=['run','guard','verify-public']);a=parser.parse_args()
    if a.action=='guard':
        value=resources();print(json.dumps(value))
        if not value['ready']:raise SystemExit(2)
    elif a.action=='verify-public':verify_public()
    else:run()
