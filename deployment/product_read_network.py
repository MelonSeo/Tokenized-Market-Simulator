"""Read-only route inventory + one explicit subnet. Never prunes, retries or restarts."""
import ipaddress,json,re,subprocess,time
from c_gate_network import select_subnet

def decode(raw):
    if isinstance(raw,str):return raw
    return raw.decode('utf-16-le' if b'\0' in raw else 'utf8',errors='replace').lstrip('\ufeff')

def proc_routes(text):
    lines=text.splitlines()
    if not lines or 'Destination' not in lines[0] or 'Mask' not in lines[0]:raise ValueError('NETWORK_PROC_HEADER')
    routes=[]
    for line in lines[1:]:
        if not line.strip():continue
        cols=line.split()
        if len(cols)<8 or not all(re.fullmatch('[0-9A-Fa-f]{8}',cols[n]) for n in (1,7)):raise ValueError('NETWORK_PROC_FORMAT')
        dst=str(ipaddress.IPv4Address(int(cols[1],16).to_bytes(4,'little')))
        mask=str(ipaddress.IPv4Address(int(cols[7],16).to_bytes(4,'little')))
        routes.append(str(ipaddress.ip_network(dst+'/'+mask,strict=False)))
    if not routes:raise ValueError('NETWORK_PROC_EMPTY')
    return sorted(set(routes))

def safe_stream(raw,stdout=False):
    """Do not persist arbitrary daemon/OS text: it may contain credentials or private paths."""
    text=decode(raw).strip()
    if not text:return ''
    if stdout and re.fullmatch('[0-9a-f]{64}',text):return text
    known=[('all predefined address pools have been fully subnetted','ADDRESS_POOL_EXHAUSTED'),
      ('could not find an available, non-overlapping IPv4 address pool','ADDRESS_POOL_UNAVAILABLE'),
      ('Pool overlaps with other one on this address space','SUBNET_OVERLAP'),
      ('permission denied','PERMISSION_DENIED'),('Access is denied','ACCESS_DENIED'),
      ('Cannot connect to the Docker daemon','DAEMON_UNAVAILABLE'),('already exists','NAME_EXISTS')]
    for phrase,code in known:
        if phrase.lower() in text.lower():return code+': '+phrase
    return 'REDACTED_NONEMPTY_STDOUT' if stdout else 'REDACTED_UNRECOGNIZED_STDERR'

def create_args(run,subnet):
    if not re.fullmatch('ccpg-[0-9a-f]{16}',run):raise ValueError('NETWORK_RUN')
    net=ipaddress.ip_network(subnet,strict=True)
    if net.version!=4 or net.prefixlen!=28 or not net.subnet_of(ipaddress.ip_network('10.254.0.0/16')):raise ValueError('NETWORK_SUBNET')
    return ['docker','network','create','--internal','--subnet',str(net),'--label','tms.product.read.owner='+run,run+'-network']

def command(execute,stage,args,timeout=15):
    """Return safe output metadata even on CLI timeout; never retries the request."""
    if stage not in {'NETWORK_CREATE','NETWORK_VERIFY'}:raise ValueError('NETWORK_STAGE')
    began=time.monotonic()
    try:
        r=execute(args,timeout=timeout)
        record={'stage':stage,'exitCode':r.returncode,'elapsedMs':int((time.monotonic()-began)*1000),
          'stdout':safe_stream(r.stdout,True),'stderr':safe_stream(r.stderr)}
        return r,record
    except subprocess.TimeoutExpired:
        return None,{'stage':stage,'exitCode':None,'elapsedMs':int((time.monotonic()-began)*1000),'stdout':'','stderr':'CLI_TIMEOUT_TRANSMISSION_UNCERTAIN'}

def inventory(execute):
    end=time.monotonic()+45
    def read(args):
        remaining=end-time.monotonic()
        if remaining<=0:raise ValueError('NETWORK_INVENTORY_DEADLINE')
        r=execute(args,timeout=min(10,remaining))
        if r.returncode!=0:raise ValueError('NETWORK_INVENTORY_'+('DOCKER' if args[0]=='docker' else 'WSL' if args[0]=='wsl.exe' else 'WINDOWS')+'_FAILED')
        if len(r.stdout)>4*1024*1024:raise ValueError('NETWORK_INVENTORY_SIZE')
        return decode(r.stdout)
    ids=read(['docker','network','ls','-q']).split()
    if not ids or len(ids)>4096 or not all(re.fullmatch('[0-9a-f]{12,64}',n) for n in ids):raise ValueError('NETWORK_INVENTORY_IDS')
    docker=[]
    for start in range(0,len(ids),64):
        rows=json.loads(read(['docker','network','inspect',*ids[start:start+64]]))
        if not isinstance(rows,list) or len(rows)!=len(ids[start:start+64]):raise ValueError('NETWORK_INSPECT_SHAPE')
        for row in rows:
            for config in (row.get('IPAM',{}).get('Config') or []):
                if config.get('Subnet'):
                    n=ipaddress.ip_network(config['Subnet'],strict=False)
                    if n.version==4:docker.append(str(n))
    windows=json.loads(read(['powershell','-NoProfile','-Command',"[Console]::WriteLine((ConvertTo-Json -Compress -InputObject @(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty DestinationPrefix)))"]))
    if not isinstance(windows,list) or not windows:raise ValueError('NETWORK_WINDOWS_ROUTES')
    windows=sorted(set(str(ipaddress.IPv4Network(n,strict=False)) for n in windows))
    running=read(['wsl.exe','--list','--running','--quiet']).splitlines()
    names=[n.strip() for n in running if n.strip()]
    if not names or len(names)>32 or any(len(n)>128 or any(ord(c)<32 for c in n) for n in names):raise ValueError('NETWORK_WSL_RUNNING')
    wsl=[]
    for name in names:
        # Do not issue an exec against an already stopped distribution.
        current=read(['wsl.exe','--list','--running','--quiet']).splitlines()
        if name not in [n.strip() for n in current]:raise ValueError('NETWORK_WSL_STOPPED')
        wsl+=proc_routes(read(['wsl.exe','-d',name,'--exec','cat','/proc/net/route']))
    docker=sorted(set(docker));wsl=sorted(set(wsl))
    selected=select_subnet(docker,windows+wsl)
    return {'subnet':selected,'networkCount':len(ids),'dockerSubnets':docker,'windowsRoutes':windows,'wslRoutes':wsl,'runningWslCount':len(names),'collisionFree':True}
