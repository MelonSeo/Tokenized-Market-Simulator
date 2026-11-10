"""Concrete frozen Spring/PG/DOM adapter. No provider/key/approval creation.

Java control uses an owned private directory, never a new public HTTP endpoint.
Actual response evidence and semantic review remain separate from transport PASS.
"""
import hashlib
import json
import os
import selectors
import subprocess
import time
import uuid
from pathlib import Path
from ai_pilot_preflight import ROOT,canonical,need
from ai_pilot_channel import handle
from ai_pilot_http_adapter import PilotAuthenticatedHttp
BOUNDS=json.loads((ROOT/'deployment/ai-pilot-driver-bounds.json').read_text())


def private_write(path,value):
    path=Path(path);need(not path.exists() and not path.is_symlink(),'V2_PRIVATE_EVIDENCE_EXISTS')
    raw=canonical(value);need(len(raw)<=2_100_000,'V2_PRIVATE_FRAME_LIMIT')
    temporary=path.with_name('.'+path.name+'.'+uuid.uuid4().hex+'.partial')
    fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'wb') as out:out.write(raw);out.flush();os.fsync(out.fileno())
    need(not path.exists(),'V2_PRIVATE_EVIDENCE_EXISTS');temporary.rename(path)


class ServerControl:
    def __init__(self,folder):self.folder=Path(folder)/'control'
    def __call__(self,op,**fields):
        need(op in {'fingerprint','budget','pace','verify','shutdown'},'V2_SERVER_CONTROL_OPERATION')
        token=uuid.uuid4().hex;request=self.folder/('request-'+token+'.json');reply=self.folder/('reply-'+token+'.json')
        need(self.folder.is_dir() and not self.folder.is_symlink(),'V2_SERVER_CONTROL_FOLDER')
        private_write(request,{'op':op,**fields});end=time.monotonic()+(160 if op=='pace' else 20)
        while not reply.exists():
            need(time.monotonic()<end,'V2_SERVER_CONTROL_TIMEOUT_NO_REPLAY');time.sleep(.04)
        need(not reply.is_symlink() and reply.stat().st_size<=2_100_000,'V2_PRIVATE_FRAME_LIMIT')
        value=json.loads(reply.read_text());need(value.get('ok') is True,'V2_SERVER_CONTROL_REJECTED')
        return value['value']


class FrameReader:
    def __init__(self,stream):
        self.fd=stream.fileno();os.set_blocking(self.fd,False);self.pending=bytearray()
        self.selector=selectors.DefaultSelector();self.selector.register(self.fd,selectors.EVENT_READ)
    def read(self,timeout):
        end=time.monotonic()+timeout
        while b'\n' not in self.pending:
            left=end-time.monotonic();need(left>0,'V2_BROWSER_PROTOCOL_DEADLINE')
            need(bool(self.selector.select(left)),'V2_BROWSER_PROTOCOL_DEADLINE')
            part=os.read(self.fd,65536);need(bool(part),'V2_BROWSER_PROTOCOL_EOF')
            self.pending.extend(part);need(len(self.pending)<=2_800_000,'V2_BROWSER_PROTOCOL_LIMIT')
        line,_,self.pending=self.pending.partition(b'\n')
        return json.loads(line)
    def close(self):self.selector.close()


class BrowserAdapter:
    def __init__(self,folder,configuration,*,host_admission):
        self.folder=Path(folder);self.configuration=configuration;self.host_admission=host_admission
        self.child=None;self.reader=None;self.awaiting_ack=False;self.submitted=0;self.log=None
    def ack(self,ok=True):
        self.child.stdin.write(canonical({'ok':ok})+b'\n');self.child.stdin.flush()
    def start(self):
        from product_read_linux_support import memory
        sample=memory()
        for name,file in (('cgroupMaxBytes','memory.max'),('cgroupCurrentBytes','memory.current')):
            raw=Path('/sys/fs/cgroup',file).read_text().strip();sample[name]=int(raw) if raw.isdecimal() else raw
        private_write(self.folder/'browser-linux-resources.json',sample)
        need(sample['availableBytes']>=1920*1024**2,'V2_BROWSER_LINUX_RAM_LOW');self.host_admission()
        private_write(self.folder/'browser.private.json',self.configuration)
        self.log=(self.folder/'browser.private.log').open('xb')
        self.child=subprocess.Popen(['node','tools/websocket-test-client/scripts/ai-linux-pilot-browser.mjs',str(self.folder/'browser.private.json'),str(self.folder/'browser')],cwd=ROOT,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.log)
        self.reader=FrameReader(self.child.stdout)
        need(self.reader.read(BOUNDS['browserFrameStartMs']/1000).get('op')=='ready','V2_BROWSER_READY_REQUIRED')
        profiles=list((self.folder/'browser').glob('profile-*'));need(len(profiles)==1,'V2_BROWSER_PROFILE_SCOPE')
        port=(profiles[0]/'DevToolsActivePort').read_text().splitlines()[0];need(port.isdecimal(),'V2_BROWSER_DEBUG_PORT')
        with (self.folder/'sandbox-proof.private.log').open('xb') as out:
            r=subprocess.run(['node','tools/websocket-test-client/scripts/ai-linux-browser-proof.mjs',port,'/run/pilot/browser/linux-sandbox.private.json'],cwd=ROOT,stdout=out,stderr=subprocess.STDOUT,timeout=30)
        need(r.returncode==0,'V2_BROWSER_SANDBOX_UNVERIFIED');self.ack()
    def web_submit(self,actor,body):
        if self.child is None:self.start()
        if self.awaiting_ack:self.ack();self.awaiting_ack=False
        before=self.reader.read(BOUNDS['browserFrameBeforeMs']/1000)
        need(before.get('op')=='before' and before.get('actor')==actor and before.get('body')==body,'V2_BROWSER_REQUEST_CHANGED')
        self.ack();after=self.reader.read(BOUNDS['browserFrameAfterMs']/1000)
        need(after.get('op')=='after' and after.get('slot')==before.get('slot'),'V2_BROWSER_RESPONSE_CHANGED')
        self.awaiting_ack=True;self.submitted+=1
        return after['reply']['http'],after['reply']['body']
    def review_complete(self,accepted):
        need(self.awaiting_ack,'V2_BROWSER_REVIEW_SEQUENCE')
        self.ack(accepted);self.awaiting_ack=False
    def close(self,success=False):
        if self.child is None:return
        try:
            if self.awaiting_ack:self.ack(success);self.awaiting_ack=False
            try:code=self.child.wait(timeout=BOUNDS['browserSuccessExitMs']/1000 if success else 15)
            except subprocess.TimeoutExpired:
                self.child.terminate()
                try:self.child.wait(timeout=5)
                except subprocess.TimeoutExpired:self.child.kill();self.child.wait(timeout=5)
                code=-1
            if success:
                need(code==0,'V2_BROWSER_CLEAN_EXIT_REQUIRED')
                proof=json.loads((self.folder/'browser/six-dom-submissions.json').read_text())
                need(proof['requests']==self.submitted and proof.get('humanSatisfaction') is False,'V2_BROWSER_COMPLETION_EVIDENCE')
        finally:
            if self.reader:self.reader.close()
            self.child.stdin.close();self.child.stdout.close();self.log.close()


class SpringAdapter(PilotAuthenticatedHttp):
    def __init__(self,c,binding,descriptor,folder,*,browser,mode):
        self.c,self.binding,self.folder,self.browser,self.mode=c,binding,Path(folder),browser,mode
        self.dom_pending=False
        self.review_desk=None
        if mode=='PAID_EXPLICIT_V2':
            from ai_pilot_review import ReviewDesk
            self.review_desk=ReviewDesk(self.folder/'reviews',c.rows)
        self.server=ServerControl(folder)
        self.baseline_c=self.server('fingerprint')['c']
        super().__init__(descriptor['origin'],descriptor['accounts'],private_control=self.channel,
                         fingerprint=self.fingerprint,review=self.review_reply,budget_snapshot=lambda:self.server('budget'),
                         web_submit=browser.web_submit)
    def channel(self,frame):
        need(frame['op'] in {'active','index-request','reconcile'},'V2_ADAPTER_CHANNEL_OPERATION')
        return handle(self.c,frame,lambda *args:(_ for _ in ()).throw(ValueError('V2_ADAPTER_NO_PROVIDER')))
    def prepare_review(self,row):
        if self.mode=='PAID_EXPLICIT_V2':self.review_desk.arm(row)
    def fingerprint(self):
        value=self.server('fingerprint');need(value['c']==self.baseline_c,'V2_C_FINANCIAL_FINGERPRINT_CHANGED')
        return value['db']
    def request(self,actor,method,path,body):
        if path in {'/api/ai/index','/api/ai/agent/answers'}:self.server('pace',actor=actor)
        return super().request(actor,method,path,body)
    def web_submit(self,actor,body):
        self.server('pace',actor=actor)
        result=self.browser.web_submit(actor,body);self.dom_pending=True;return result
    def review_reply(self,row,reply):
        accepted=False;self.reviewed_failure=False
        try:
            accepted=self._review_reply(row,reply)
            return accepted
        finally:
            if self.dom_pending:
                self.browser.review_complete(accepted or self.reviewed_failure);self.dom_pending=False
    def review_failure_can_continue(self,row,reply):return self.reviewed_failure
    def _review_reply(self,row,reply):
        verification=self.server('verify',row=row,reply=reply)
        if self.mode=='UNPAID_CONTROLLED_ONLY':return True # transport probe, never model-quality PASS
        need(self.mode=='PAID_EXPLICIT_V2','V2_PAID_APPROVAL_REQUIRED')
        sources=[json.loads((ROOT/'backend/src/test/resources/ai'/name).read_text(encoding='utf8')) for name in ('novel-30-v2.json','pilot-acceptance-v1.json')]
        expected=next(c for s in sources for c in s['cases'] if c['id']==row['case'])
        evidence={'row':row,'reply':reply,'frozenExpected':expected,'criteria':sources[1]['criteria'],
                  'kind':'SEMANTIC_REVIEW_REQUIRED_NOT_HTTP200_PASS'}
        failed=verification.get('transportVerified') is False
        if failed:
            need(verification=={'transportVerified':False,'quality':'FAIL','safeReviewedContinuation':True},'V2_UNSAFE_QUALITY_FAILURE')
            evidence['transportVerification']=verification
        from ai_pilot_review import ReviewGate
        gate=ReviewGate(self.folder/'reviews',evidence,prepared=self.review_desk.prepared(row));accepted=gate.wait()
        if failed:
            need(accepted is False,'V2_FAILED_QUALITY_CANNOT_BECOME_PASS');self.reviewed_failure=True
        elif accepted is False and gate.safe_quality_failure is True:
            self.reviewed_failure=True # Explicit semantic FAIL, never a security/financial exception.
        return accepted
