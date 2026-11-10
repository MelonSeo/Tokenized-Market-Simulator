"""Read-only SQL audit of a stopped failed owned trial, no application/worker start."""
import hashlib,json,re,secrets,time
from ai_pilot_linux import ROOT,PG,LOCK,LABEL
from ai_pilot_unpaid_live import cmd,save,restrict_owned_folder,stop_owned_container
from ai_receipt_clone import fingerprint
from product_read_resources import Observer
def validate_source(source):
    if not re.fullmatch(r'pilot-linux-[a-f0-9]{16}',source):raise ValueError('FIXTURE_AUDIT_SCOPE')
    base=ROOT/'deployment/runtime'/source
    if base.is_symlink() or getattr(base.lstat(),'st_file_attributes',0)&0x400:raise ValueError('FIXTURE_AUDIT_LINK')
    meta=json.loads((base/'owned.json').read_text());result=json.loads((base/'result.json').read_text())
    if meta['run']!=source or meta['scope'] not in {'FULL','C_LATENCY'} or meta['paidCalls']!=0 or meta.get('apiKeyReads')!=0 or result['status']!='PARTIAL':raise ValueError('FIXTURE_AUDIT_FAILED_UNPAID')
    if json.loads((base/'v2.private.json').read_text())['mode']!='UNPAID_CONTROLLED_ONLY':raise ValueError('FIXTURE_AUDIT_UNPAID')
    if meta['names']['pgdata']!=source+'-pgdata':raise ValueError('FIXTURE_AUDIT_VOLUME_SCOPE')
    for role,image in [('app',meta['image']),('pg',PG),('anvil',LOCK['images']['anvil'])]:
        item=json.loads(cmd(['docker','inspect',source+'-'+role]))[0]
        if item['Name']!='/'+source+'-'+role or item['State']['Running'] or item['Config']['Image']!=image or item['Config']['Labels'].get(LABEL)!=source:raise ValueError('FIXTURE_AUDIT_STOPPED_OWNER')
        if role=='pg' and (item['Config']['Image']!=PG or not any(m.get('Name')==meta['names']['pgdata'] and m['Destination']=='/var/lib/postgresql/data' for m in item['Mounts'])):raise ValueError('FIXTURE_AUDIT_PG_VOLUME')
    volume=json.loads(cmd(['docker','volume','inspect',meta['names']['pgdata']]))[0]
    if volume['Labels'].get(LABEL)!=source:raise ValueError('FIXTURE_AUDIT_VOLUME_OWNER')
    return base,meta
def run(source):
    base,meta=validate_source(source)
    owner='pilot-linux-'+secrets.token_hex(8);folder=ROOT/'deployment/runtime'/owner;folder.mkdir();restrict_owned_folder(folder)
    observer=Observer(folder/'resources.jsonl');observer.sample('READONLY_PG_AUDIT',True)
    save(folder/'owned.json',{'run':owner,'scope':'FIXTURE_SQL_READONLY','source':source,'paidCalls':0,'apiKeyReads':0})
    pg=source+'-pg';db='exchange_ai_pilot_trade_'+source[12:]
    def sql(query):return cmd(['docker','exec',pg,'psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','pilot','-d',db,'-c','BEGIN READ ONLY; SET LOCAL statement_timeout=5000; '+query+'; COMMIT;'])
    state=base/'chain/state.json'
    with state.open('rb') as f:state_hash=hashlib.file_digest(f,'sha256').hexdigest()
    with state.open('rb') as f:prefix=f.read(65536).decode('utf8')
    match=re.search(r'"timestamp"\s*:\s*("(?:0x[0-9a-fA-F]+|[0-9]+)"|[0-9]+)',prefix)
    stamp=json.loads(match.group(1)) if match else None;stamp=int(stamp,0) if isinstance(stamp,str) else stamp
    try:
        cmd(['docker','start',pg]);end=time.monotonic()+15
        while True:
            try:sql('SELECT 1');break
            except RuntimeError:
                if time.monotonic()>end:raise
                time.sleep(.5)
        before=fingerprint(pg,db);rows={}
        allowed={'status','action','user_id','version','call_epoch','deadline','last_observed_at','pending_operation','block_reason','observed_at','created_at','updated_at','error_code','reason','price','collateral_before','collateral_after','verification','quantity','entry_price','collateral'}
        for table in ['futures_positions','futures_operations','futures_transactions']:
            names=json.loads(sql("SELECT coalesce(json_agg(column_name),'[]') FROM information_schema.columns WHERE table_schema='public' AND table_name='"+table+"'"));fields=sorted(allowed.intersection(names))
            rows[table]=json.loads(sql('SELECT coalesce(json_agg(t),\'[]\') FROM (SELECT '+','.join(fields)+' FROM '+table+' LIMIT 100) t'))
        after=fingerprint(pg,db)
        save(folder/'fixture-audit.private.json',{'source':source,'rows':rows,'financialUnchanged':before==after,'stateTimestamp':stamp,'stateFileModifiedAt':state.stat().st_mtime,'paidCalls':0,'apiKeyReads':0})
        print(json.dumps({'run':owner,'financialUnchanged':before==after,'stateTimestamp':stamp,'rows':rows}))
    finally:
        status=stop_owned_container(pg,source,PG,20)
        with state.open('rb') as f:unchanged=hashlib.file_digest(f,'sha256').hexdigest()==state_hash
        save(folder/'cleanup.json',{'sourcePg':status,'sourceAnvilStateUnchanged':unchanged});observer.sample('STOPPED')
if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('source');run(p.parse_args().source)
