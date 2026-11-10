"""Recover the SAME owned clone only before any WEB submission; no ledger recopy/reset."""
import json,re,secrets,shutil,subprocess,time
from pathlib import Path
import ai_pilot_chrome_resume as r
from ai_pilot_unpaid_live import ROOT,cmd,env,save,restrict_owned_folder,stop_owned_container
from ai_pilot_linux import LABEL,PG,LOCK
from ai_pilot_linux_browser_probe import profile
from ai_receipt_clone import cleanup_resources,fingerprint
from product_read_resources import Observer
from product_read_tools import find_jdk

def stop_output_anvil(name,owner):
 """Let only this owned output writer finish; CLI budget covers its shutdown grace."""
 try:
  item=json.loads(cmd(['docker','inspect',name]))[0]
  if item['Name']!='/'+name or item['Config']['Labels'].get(LABEL)!=owner or item['Config']['Image']!=LOCK['images']['anvil']:return 'UNCONFIRMED'
  if item['State']['Running']:cmd(['docker','stop','--time','120',name],135)
  return 'STOPPED' if not json.loads(cmd(['docker','inspect',name]))[0]['State']['Running'] else 'UNCONFIRMED'
 except Exception:return 'UNCONFIRMED'

def retry(owner):
 if not re.fullmatch(r'pilot-linux-[a-f0-9]{16}',owner):raise ValueError('CHROME_RETRY_OWNER')
 base=ROOT/'deployment/runtime'/owner
 if base.is_symlink() or getattr(base.lstat(),'st_file_attributes',0)&0x400:raise ValueError('CHROME_RETRY_LINK')
 meta=json.loads((base/'owned.json').read_text())
 if meta.get('scope')!='CHROME_SIX_RESUME_ONLY' or meta.get('run')!=owner or meta.get('paidCalls')!=0 or meta.get('apiKeyReads')!=0 or not re.fullmatch(r'pilot-linux-[a-f0-9]{16}',meta.get('source','')):raise ValueError('CHROME_RETRY_UNPAID')
 claim=json.loads((ROOT/'deployment/runtime'/('chrome-resume-'+meta['source']+'.claim')).read_text())
 if claim.get('owner')!=owner or claim.get('source')!=meta['source'] or claim.get('unpaidOnly') is not True:raise ValueError('CHROME_RETRY_CLAIM')
 r.validate_web_ledger(base) # Requires WEB0/UNKNOWN0, original allocation and prior phases complete.
 for role,image in [('pg',PG),('anvil',LOCK['images']['anvil'])]:
  item=json.loads(cmd(['docker','inspect',owner+'-'+role]))[0]
  if item['Name']!='/'+owner+'-'+role or item['Config']['Labels'].get(LABEL)!=owner or item['Config']['Image']!=image or item['State']['Running']:raise ValueError('CHROME_RETRY_STOPPED_OWNER')
 net=json.loads(cmd(['docker','network','inspect',meta['names']['network']]))[0]
 if not net['Internal'] or net['Labels'].get(LABEL)!=owner or meta['names']['network']!=owner+'-network':raise ValueError('CHROME_RETRY_NETWORK')
 attempt=base/('attempt-'+secrets.token_hex(8));attempt.mkdir();restrict_owned_folder(attempt)
 observer=Observer(attempt/'resources.jsonl');observer.sample('RETRY_ADMISSION',True)
 mutex=base/'active-attempt.claim'
 with mutex.open('x',encoding='utf8') as f:json.dump({'owner':owner,'attempt':attempt.name},f)
 created=[];passed=False;app=owner+'-app-'+attempt.name[8:];anvil=owner+'-anvil-'+attempt.name[8:];state_volume=owner+'-state-'+attempt.name[8:];image=meta['image'];source=ROOT/'deployment/runtime'/meta['source']
 watched=['chain/state.json','session/session.json','session/pilot-v2.sqlite','final-fingerprint.json'];original={n:r.digest(source/n) for n in watched}
 try:
  save(attempt/'owned.json',{'run':owner,'attempt':attempt.name,'scope':'SAME_CLONE_PRE_WEB_RETRY','app':app,'sharedLedger':str(base/'session/pilot-v2.sqlite'),'paidCalls':0,'apiKeyReads':0})
  for name in ['v2.private.json','browser.private.json','source-files.json','source-final-fingerprint.json']:shutil.copyfile(base/name,attempt/name)
  props=dict(line.split('=',1) for line in (base/'trade.properties').read_text().splitlines() if '=' in line)
  props['app.jwt.secret']=secrets.token_urlsafe(48) # Fresh trial signer; original protected config is untouched.
  (attempt/'trade.properties').write_text('\n'.join(k+'='+v for k,v in props.items())+'\n')
  driver=attempt/'driver';driver.mkdir();classes=driver/'classes';classes.mkdir()
  files={'PilotChromeResume.java':ROOT/'deployment/PilotChromeResume.java','ai_pilot_chrome_entry.py':ROOT/'deployment/ai_pilot_chrome_entry.py',**{n:ROOT/'tools/websocket-test-client/scripts'/n for n in ['ai-linux-pilot-browser.mjs','pilot-dom-readiness.mjs','ai-linux-browser-proof.mjs','ai-linux-browser-diagnose.mjs','linux-sandbox-evidence.mjs','cdp-boundary.mjs']},**{n:base/'source/tools/websocket-test-client/scripts'/n for n in ['browser-executable.mjs','ai-pilot-v2-flow.mjs','native-sandbox-evidence.mjs']}}
  for name,p in files.items():shutil.copyfile(p,driver/name)
  cp=';'.join(str(base/'source/backend'/p) for p in ['build/classes/java/main','build/classes/java/test','build/resources/main','build/resources/test','build/pilot-v2-test/jars/*'])
  p=subprocess.run([str(find_jdk()['javac']),'-J-Xmx128m','-parameters','--release','21','-cp',cp,'-d',str(classes),str(driver/'PilotChromeResume.java')],env=env(),capture_output=True,timeout=60);(attempt/'compile.private.log').write_bytes(p.stdout+p.stderr)
  if p.returncode:raise ValueError('CHROME_RETRY_COMPILE')
  save(attempt/'driver-files.json',{p.relative_to(driver).as_posix():r.digest(p) for p in driver.rglob('*') if p.is_file()})
  # Never reload a potentially interrupted dump. Original checkpoint is RO; each
  # zero-WEB attempt writes its new headers to a fresh Linux volume, not Windows.
  created.append((owner+'-pg',PG));cmd(['docker','start',owner+'-pg'])
  cmd(['docker','volume','create','--label',LABEL+'='+owner,state_volume])
  if json.loads(cmd(['docker','volume','inspect',state_volume]))[0]['Labels'].get(LABEL)!=owner:raise ValueError('CHROME_STATE_VOLUME_OWNER')
  created.append((anvil,LOCK['images']['anvil']))
  cmd(['docker','create','--pull=never','--name',anvil,'--label',LABEL+'='+owner,'--network',meta['names']['network'],'--network-alias','anvil','--memory=384m','--entrypoint','anvil','--mount','type=bind,source='+str((source/'chain').resolve())+',target=/baseline,readonly','--mount','type=volume,source='+state_volume+',target=/output',LOCK['images']['anvil'],'--host','0.0.0.0','--accounts','0','--chain-id','31337','--block-time','1','--load-state','/baseline/state.json','--dump-state','/output/state.json','--preserve-historical-states','--quiet'])
  save(attempt/'chain-storage.json',{'baselineReadOnly':True,'baselineSha256':original['chain/state.json'],'outputVolume':state_volume,'damagedCloneFilePreserved':True})
  cmd(['docker','start',anvil])
  from ai_pilot_state_permission import prepare
  prepare(anvil)
  db='exchange_ai_pilot_trade_'+meta['source'][12:];end=time.monotonic()+60
  while True:
   try:before=fingerprint(owner+'-pg',db);cmd(['docker','exec',anvil,'cast','chain-id','--rpc-url','http://127.0.0.1:8545']);break
   except RuntimeError:
    if time.monotonic()>end:raise
    time.sleep(.5)
  cp='/driver/classes:'+':'.join('/work/backend/'+p for p in ['build/classes/java/main','build/classes/java/test','build/resources/main','build/resources/test','build/pilot-v2-test/jars/*','build/product-read-ci/helpers'])
  save(attempt/'java-command.json',['java','-Xmx384m','-XX:MaxMetaspaceSize=256m','-Djdk.httpclient.disableRetryConnect=true','-Djdk.httpclient.enableAllMethodRetry=false','-Djdk.httpclient.redirects.retrylimit=1','--add-opens=java.net.http/jdk.internal.net.http=ALL-UNNAMED','-cp',cp,'com.pricetrack.exchange.futures.chain.PilotChromeResume'])
  def mount(p,t,ro=False):return 'type=bind,source='+str(p.resolve())+',target='+t+(',readonly' if ro else '')
  created.append((app,image));cmd(['docker','create','--pull=never','--name',app,'--label',LABEL+'='+owner,'--network',meta['names']['network'],'--memory=2560m','--shm-size=256m','--security-opt','seccomp='+str(profile()),'--entrypoint','python','--mount',mount(attempt,'/run/pilot'),'--mount',mount(base/'session','/run/pilot/session'),'--mount',mount(driver,'/driver',True),'-e','AI_PILOT_BROWSER_RESUME=true','-e','AI_PILOT_V2_LINUX_INTERNAL=true','-e','AI_PILOT_V2_HTTP_TESTS=true','-e','AI_PILOT_V2_FOLDER=/run/pilot','-e','AI_PILOT_V2_APP=/run/pilot/trade.properties','-e','AI_PILOT_V2_CONFIG=/run/pilot/v2.private.json','-e','AI_EVAL_TEST_PYTHON=/usr/local/bin/python',image,'-B','-S','/driver/ai_pilot_chrome_entry.py'])
  cmd(['docker','cp',str((base/'source').resolve())+'/.',app+':/work'],120)
  config=json.loads(cmd(['docker','inspect',app]))[0]
  if config['Config']['User']!='1000:1000' or config['HostConfig']['Privileged'] or config['HostConfig'].get('CapAdd') or config['HostConfig']['PortBindings']:raise ValueError('CHROME_RETRY_ISOLATION')
  observer.sample('RETRY_BEFORE_APP',True);cmd(['docker','start',app]);end=time.monotonic()+150
  while not (attempt/'browser-ready.json').exists():
   if not json.loads(cmd(['docker','inspect',app]))[0]['State']['Running'] or time.monotonic()>end:raise ValueError('CHROME_RETRY_APP')
   time.sleep(.5)
  observer.sample('RETRY_BEFORE_BROWSER',True);save(attempt/'browser-admission.json',{'allowed':True})
  if cmd(['docker','wait',app],650)!='0':raise ValueError('CHROME_RETRY_SIX_FAILED')
  result=json.loads((attempt/'chrome-only-result.json').read_text());after=fingerprint(owner+'-pg',db)
  if result.get('browserRequests')!=6 or result.get('sandboxVerified') is not True or before!=after:raise ValueError('CHROME_RETRY_EVIDENCE')
  save(attempt/'clone-financial.json',{'before':before,'after':after,'unchanged':True});passed=True
 finally:
  try:
   logs=subprocess.run(['docker','logs',app],env=env(),capture_output=True,timeout=10);(attempt/'app.private.log').write_bytes(logs.stdout+logs.stderr)
  except Exception:pass
  anvil_state=stop_output_anvil(anvil,owner) if any(n==anvil for n,_ in created) else 'NOT_CREATED'
  save(attempt/'anvil-stop.json',{'state':anvil_state})
  # An uncertain dump may still be completing. Never override its grace with a second short stop.
  cleanup=cleanup_resources(attempt,[(n,i) for n,i in created if n!=anvil],owner,anvil);cleanup[anvil]=anvil_state
  observer.sample('STOPPED');unchanged=all(r.digest(source/n)==h for n,h in original.items())
  clean=all(v in {'STOPPED','NOT_CREATED'} for v in cleanup.values())
  save(attempt/'result.json',{'status':'PASS_UNPAID_CHROME_SIX_NOT_MODEL_QUALITY' if passed and clean and unchanged else 'PARTIAL','cleanup':cleanup,'originalFilesUnchanged':unchanged,'ledgerRecopied':False,'paidCalls':0,'apiKeyReads':0})
  if clean:mutex.rename(attempt/'attempt-claim.closed.json')
  print(json.dumps({'run':owner,'attempt':attempt.name,'passed':passed,'paidCalls':0,'apiKeyReads':0}))

if __name__=='__main__':
 import argparse
 p=argparse.ArgumentParser();p.add_argument('owner');retry(p.parse_args().owner)
