"""Observe normal native Chrome sandbox only; no server/container/model/key access."""
import ctypes,json,os,secrets,subprocess,sys
def token_status(ids):
    if os.name!='nt' or not 1<=len(ids)<=16 or any(not str(x).isdigit() or not 0<int(x)<2**32 for x in ids):raise ValueError('NATIVE_TOKEN_PID_SCOPE')
    from ctypes import wintypes as w
    k=ctypes.WinDLL('kernel32',use_last_error=True);a=ctypes.WinDLL('advapi32',use_last_error=True)
    k.OpenProcess.argtypes=[w.DWORD,w.BOOL,w.DWORD];k.OpenProcess.restype=w.HANDLE;k.CloseHandle.argtypes=[w.HANDLE]
    a.OpenProcessToken.argtypes=[w.HANDLE,w.DWORD,ctypes.POINTER(w.HANDLE)];a.GetTokenInformation.argtypes=[w.HANDLE,ctypes.c_int,ctypes.c_void_p,w.DWORD,ctypes.POINTER(w.DWORD)]
    a.IsTokenRestricted.argtypes=[w.HANDLE];a.GetSidSubAuthorityCount.argtypes=[ctypes.c_void_p];a.GetSidSubAuthorityCount.restype=ctypes.POINTER(ctypes.c_ubyte)
    a.GetSidSubAuthority.argtypes=[ctypes.c_void_p,w.DWORD];a.GetSidSubAuthority.restype=ctypes.POINTER(w.DWORD)
    class Label(ctypes.Structure):_fields_=[('sid',ctypes.c_void_p),('attributes',w.DWORD)]
    rows=[]
    for pid in ids:
        process=k.OpenProcess(0x1000,False,int(pid));token=w.HANDLE()
        try:
            if not process or not a.OpenProcessToken(process,8,ctypes.byref(token)):rows.append({'pid':int(pid),'status':'UNAVAILABLE'});continue
            size=w.DWORD();a.GetTokenInformation(token,25,None,0,ctypes.byref(size))
            if not 0<size.value<=4096:raise RuntimeError('NATIVE_TOKEN_SIZE')
            data=ctypes.create_string_buffer(size.value)
            if not a.GetTokenInformation(token,25,data,size,ctypes.byref(size)):raise RuntimeError('NATIVE_TOKEN_QUERY')
            sid=ctypes.cast(data,ctypes.POINTER(Label)).contents.sid;count=a.GetSidSubAuthorityCount(sid)[0]
            if not count:raise RuntimeError('NATIVE_TOKEN_SID')
            rows.append({'pid':int(pid),'status':'OBSERVED','restricted':bool(a.IsTokenRestricted(token)),'integrityRid':a.GetSidSubAuthority(sid,count-1)[0]})
        finally:
            if token:k.CloseHandle(token)
            if process:k.CloseHandle(process)
    return rows
def run():
    from ai_pilot_unpaid_live import ROOT,env,restrict_owned_folder,save
    from product_read_resources import Observer
    name='pilot-linux-'+secrets.token_hex(8);folder=ROOT/'deployment/runtime'/name
    folder.mkdir();restrict_owned_folder(folder);observer=Observer(folder/'resources.jsonl');observer.sample('NATIVE_SANDBOX',True)
    save(folder/'owned.json',{'run':name,'scope':'NATIVE_SANDBOX','paidCalls':0,'apiKeyReads':0})
    try:
        e=env();e['TMS_PROBE_PYTHON']=sys.executable
        p=subprocess.run(['node',str(ROOT/'tools/websocket-test-client/scripts/ai-native-sandbox-probe.mjs'),str(folder)],cwd=ROOT,env=e,capture_output=True,timeout=60)
        (folder/'native-probe.private.log').write_bytes(p.stdout+p.stderr)
        if p.returncode:raise RuntimeError('NATIVE_SANDBOX_PROBE_FAILED')
        if json.loads((folder/'native-sandbox.private.json').read_text())['verified'] is not True:raise RuntimeError('NATIVE_SANDBOX_UNVERIFIED')
        print(json.dumps({'run':name,'observation':'PASS_RESTRICTED_RENDERER_NOT_FULL_AUDIT','paidCalls':0,'apiKeyReads':0}))
    finally:observer.sample('STOPPED')
if __name__=='__main__':
    if len(sys.argv)>1 and sys.argv[1]=='--tokens':print(json.dumps(token_status(sys.argv[2:])))
    else:run()
