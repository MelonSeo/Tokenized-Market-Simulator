"""UNPAID native-browser bridge. Real TCP bytes only, never injected HTTP replies.
Linux keeps DB/RPC and v2 admission. Windows owns a loopback-only relay and a new
Chrome profile. Private before/after frames are forwarded to the existing Java host.
"""
import argparse,hashlib,json,os,re,select,socket,socketserver,subprocess,sys,threading,time
from pathlib import Path

def write_new(path,value):
    data=json.dumps(value,ensure_ascii=True).encode();temporary=path.with_suffix('.tmp')
    with temporary.open('xb') as f:f.write(data)
    if path.exists():raise ValueError('NATIVE_FRAME_ALREADY_EXISTS')
    temporary.rename(path)
def read_frame(path):
    if path.is_symlink() or path.stat().st_size>2_800_000:raise ValueError('NATIVE_FRAME_SIZE')
    return json.loads(path.read_text(encoding='utf8'))
def await_file(path,end,stop=lambda:False):
    while not path.exists():
        if stop() or time.monotonic()>end or (path.parent/'host-failed.json').exists():raise RuntimeError('NATIVE_BRIDGE_STOPPED')
        time.sleep(.05)
    return read_frame(path)
def child(folder):
    if sys.platform!='linux' or folder.resolve()!=Path('/run/pilot') or os.environ.get('AI_PILOT_V2_LINUX_INTERNAL')!='true':raise ValueError('NATIVE_CHILD_SCOPE')
    if json.loads((folder/'v2.private.json').read_text())['mode']!='UNPAID_CONTROLLED_ONLY':raise ValueError('NATIVE_UNPAID_REQUIRED')
    wire=folder/'browser-wire';wire.mkdir();write_new(wire/'ready.json',{'ready':True});end=time.monotonic()+720
    for i in range(13):
        frame=await_file(wire/f'frame-{i}.json',end)
        if frame.get('op') not in {'before','after','stop'}:raise ValueError('NATIVE_FRAME_OPERATION')
        print(json.dumps(frame),flush=True);raw=sys.stdin.buffer.readline(4097)
        if not raw or len(raw)>4096:raise ValueError('NATIVE_JAVA_ACK')
        answer=json.loads(raw);write_new(wire/f'ack-{i}.json',answer)
        if answer.get('ok') is not True:raise ValueError('NATIVE_JAVA_REJECTED')
        if frame['op']=='stop':return
        if i==11:
            if await_file(wire/'host-complete.json',end).get('complete') is not True:raise ValueError('NATIVE_HOST_NOT_COMPLETE')
            return
    raise ValueError('NATIVE_FRAME_LIMIT')
def tcp():
    if sys.platform!='linux' or os.environ.get('AI_PILOT_V2_LINUX_INTERNAL')!='true':raise ValueError('NATIVE_TCP_SCOPE')
    with socket.create_connection(('127.0.0.1',8080),timeout=3) as peer:
        end=time.monotonic()+150;inputs=[sys.stdin.buffer,peer]
        while inputs and time.monotonic()<end:
            ready,_,_=select.select(inputs,[],[],min(1,end-time.monotonic()))
            for source in ready:
                data=os.read(source.fileno(),65536)
                if not data:
                    inputs.remove(source)
                    if source is sys.stdin.buffer:peer.shutdown(socket.SHUT_WR)
                    else:return
                elif source is peer:sys.stdout.buffer.write(data);sys.stdout.buffer.flush()
                else:peer.sendall(data)

class NativeHost:
    def __init__(self,folder,app,image,owner,observer):
        self.folder=folder;self.app=app;self.image=image;self.owner=owner;self.observer=observer
        self.stop=threading.Event();self.finished=threading.Event();self.thread=None;self.process=None;self.server=None;self.done=False;self.error=None
        self.connections=set();self.children=set();self.lock=threading.Lock();self.total=0;self.slots=threading.BoundedSemaphore(16)
    def __enter__(self):
        self.thread=threading.Thread(target=self.run,daemon=True);self.thread.start();return self
    def __exit__(self,*args):
        from ai_pilot_unpaid_live import save
        self.stop.set();complete=True
        if self.process and self.process.poll() is None:
            from ai_pilot_unpaid_live import stop_child
            complete=stop_child(self.process)=='STOPPED' # only the Popen-owned Node/Chrome tree
        if self.server:
            for action in [self.server.shutdown,self.server.server_close]:
                try:action()
                except Exception:complete=False
        with self.lock:
            connections=list(self.connections);children=list(self.children)
        for connection in connections:
            try:connection.shutdown(socket.SHUT_RDWR)
            except OSError:pass
        for p in children:
            try:
                if p.poll() is None:p.terminate()
                p.wait(timeout=3)
            except Exception:complete=False
        if self.thread:self.thread.join(5)
        if self.thread and self.thread.is_alive():complete=False
        save(self.folder/'native-cleanup.json',{'ownedHandlesConfirmed':complete})
        if not complete and (not args or args[0] is None):raise RuntimeError('NATIVE_CLEANUP_UNCONFIRMED')
    def check(self):
        if self.error or not self.done:raise RuntimeError('NATIVE_BROWSER_NOT_COMPLETE')
    def run(self):
        from ai_pilot_unpaid_live import ROOT,env,cmd,save,LABEL
        try:
            if ROOT.resolve()!=(self.folder/'source').resolve() or self.folder.name!=self.owner or self.folder.is_symlink() or getattr(self.folder.lstat(),'st_file_attributes',0)&0x400 or not re.fullmatch(r'pilot-linux-[a-f0-9]{16}',self.owner):raise ValueError('NATIVE_OWNER_SCOPE')
            meta=read_frame(self.folder/'owned.json')
            if meta.get('run')!=self.owner or meta.get('scope')!='FULL' or meta.get('nativeBrowser') is not True or json.loads((self.folder/'v2.private.json').read_text())['mode']!='UNPAID_CONTROLLED_ONLY':raise ValueError('NATIVE_UNPAID_OWNER_REQUIRED')
            wire=self.folder/'browser-wire';end=time.monotonic()+1750
            while not (wire/'ready.json').exists():
                if self.stop.wait(.1):return
                if time.monotonic()>end:raise RuntimeError('NATIVE_READY_TIMEOUT')
            cfg=read_frame(self.folder/'browser.private.json')
            if cfg.get('mode')!='UNPAID_CONTROLLED_ONLY' or cfg.get('origin')!='http://127.0.0.1:8080' or len(cfg.get('rows',[]))!=6:raise ValueError('NATIVE_CONFIG')
            item=json.loads(cmd(['docker','inspect',self.app]))[0]
            if item['Config']['Labels'].get(LABEL)!=self.owner or item['Config']['Image']!=self.image or not item['State']['Running']:raise ValueError('NATIVE_APP_OWNER')
            container=item['Id'];supervisor=self;self.observer.sample('BEFORE_NATIVE_BROWSER',True)
            class Handler(socketserver.BaseRequestHandler):
                def handle(self):
                    if not supervisor.slots.acquire(False):return
                    try:self.transfer()
                    finally:supervisor.slots.release()
                def transfer(self):
                    p=subprocess.Popen(['docker','exec','-i',container,'python','-B','-S','/work/deployment/ai_pilot_native_bridge.py','tcp'],env=env(),stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
                    with supervisor.lock:supervisor.connections.add(self.request);supervisor.children.add(p);supervisor.total+=1
                    def send():
                        try:
                            while data:=self.request.recv(65536):p.stdin.write(data);p.stdin.flush()
                        except (OSError,ValueError):pass
                        finally:
                            try:p.stdin.close()
                            except OSError:pass
                    threading.Thread(target=send,daemon=True).start()
                    try:
                        while data:=os.read(p.stdout.fileno(),65536):self.request.sendall(data)
                    except OSError:pass
                    finally:
                        try:self.request.shutdown(socket.SHUT_RDWR)
                        except OSError:pass
                        if p.poll() is None:p.terminate()
                        try:p.wait(timeout=3)
                        except subprocess.TimeoutExpired:p.kill();p.wait(timeout=3)
                        with supervisor.lock:supervisor.connections.discard(self.request);supervisor.children.discard(p)
            class Server(socketserver.ThreadingTCPServer):daemon_threads=True;allow_reuse_address=False
            self.server=Server(('127.0.0.1',0),Handler);threading.Thread(target=self.server.serve_forever,daemon=True).start()
            cfg={**cfg,'origin':'http://127.0.0.1:'+str(self.server.server_address[1])};config=self.folder/'native-browser.private.json';write_new(config,cfg)
            log=(self.folder/'native-browser.private.log').open('xb');e=env();e['TMS_PROBE_PYTHON']=sys.executable;e['TMS_NATIVE_SANDBOX_CHECK']='true'
            try:
                self.process=subprocess.Popen(['node',str(ROOT/'tools/websocket-test-client/scripts/ai-pilot-v2-browser.mjs'),str(config),str(self.folder/'native-browser')],cwd=ROOT,env=e,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=log)
                for i in range(13):
                    raw=self.process.stdout.readline(2_800_001)
                    if not raw:break
                    if len(raw)>2_800_000:raise ValueError('NATIVE_FRAME_SIZE')
                    frame=json.loads(raw);write_new(wire/f'frame-{i}.json',frame)
                    ack=await_file(wire/f'ack-{i}.json',time.monotonic()+75,self.stop.is_set)
                    self.process.stdin.write(json.dumps(ack).encode()+b'\n');self.process.stdin.flush()
                code=self.process.wait(timeout=10)
                if code or not (self.folder/'native-browser/six-dom-submissions.json').exists():raise RuntimeError('NATIVE_SIX_FAILED')
            finally:log.close()
            save(self.folder/'native-bridge-result.json',{'status':'PASS_SIX_REAL_DOM','tcpConnections':self.total,'loopbackOnly':True,'responseInjection':False,'paidCalls':0,'apiKeyReads':0})
            self.done=True;write_new(wire/'host-complete.json',{'complete':True});self.finished.set()
        except Exception as error:
            self.error=type(error).__name__
            if (self.folder/'browser-wire').is_dir():
                try:write_new(self.folder/'browser-wire/host-failed.json',{'failed':True})
                except OSError:pass
            save(self.folder/'native-bridge-result.json',{'status':'FAIL','errorType':self.error,'paidCalls':0,'apiKeyReads':0})
            self.finished.set()

class NativeProcessHost:
    """Root supervisor executes the frozen host/driver dependencies, not live worktree code."""
    def __init__(self,folder,app,image,owner,observer=None):self.folder=folder;self.app=app;self.image=image;self.owner=owner;self.process=None;self.log=None
    def __enter__(self):
        from ai_pilot_unpaid_live import env
        source=self.folder/'source';manifest=read_frame(self.folder/'source-files.json')
        # Complete snapshot validation, including native scripts and dependency files.
        for name,expected in manifest.items():
            path=source/name
            if Path(name).is_absolute() or '..' in Path(name).parts or path.is_symlink() or not path.is_file():raise ValueError('NATIVE_SOURCE_SCOPE')
            with path.open('rb') as f:
                if hashlib.file_digest(f,'sha256').hexdigest()!=expected:raise ValueError('NATIVE_SOURCE_DRIFT')
        self.log=(self.folder/'native-host.private.log').open('xb')
        try:self.process=subprocess.Popen([sys.executable,'-B','-S',str(source/'deployment/ai_pilot_native_bridge.py'),'host',str(self.folder),'--app',self.app,'--image',self.image,'--owner',self.owner],cwd=source,env=env(),stdout=self.log,stderr=self.log)
        except Exception:self.log.close();raise
        return self
    def check(self):
        if not self.process or self.process.wait(timeout=10)!=0:raise RuntimeError('NATIVE_HOST_NOT_COMPLETE')
        if read_frame(self.folder/'native-bridge-result.json').get('status')!='PASS_SIX_REAL_DOM' or read_frame(self.folder/'native-cleanup.json').get('ownedHandlesConfirmed') is not True:raise RuntimeError('NATIVE_HOST_NOT_COMPLETE')
    def __exit__(self,*args):
        from ai_pilot_unpaid_live import stop_child,save
        try:state=stop_child(self.process)
        finally:
            if self.log:self.log.close()
        save(self.folder/'native-supervisor-cleanup.json',{'state':state})
        if state!='STOPPED' and (not args or args[0] is None):raise RuntimeError('NATIVE_HOST_CLEANUP_UNCONFIRMED')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['child','tcp','host']);p.add_argument('folder',nargs='?');p.add_argument('--app');p.add_argument('--image');p.add_argument('--owner');a=p.parse_args()
    if a.mode=='tcp':tcp()
    elif a.mode=='child':child(Path(a.folder))
    else:
        from product_read_resources import Observer
        folder=Path(a.folder)
        with NativeHost(folder,a.app,a.image,a.owner,Observer(folder/'native-resources.jsonl')) as host:
            if not host.finished.wait(1750):raise RuntimeError('NATIVE_HOST_TIMEOUT')
            host.check()
