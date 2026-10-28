import importlib.util,json,tempfile,unittest,subprocess,os,shutil
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('manual',ROOT/'deployment/product-manual.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class Manual(unittest.TestCase):
 def test_address_only(self):
  self.assertEqual(m.owner('0x'+'A'*40),'0x'+'a'*40)
  for x in ['0x'+'0'*40,'0x'+'1'*64,'1'*64,'seed words',None]:
   with self.assertRaises(ValueError):m.owner(x)
 def test_manual_is_stop_only_without_changing_ci_or_report_ttl(self):
  s=(ROOT/'deployment/product-manual.py').read_text(encoding='utf8');j=(ROOT/'deployment/ProductManualActor.java').read_text(encoding='utf8')
  self.assertNotIn('runtime_deadline',s);self.assertNotIn("add_argument('--seconds'",s)
  self.assertNotIn('Duration.ofSeconds',j);self.assertIn('while(!Files.exists(Path.of("/gate-control/STOP")))',j)
  self.assertIn("'--manual-stop-only','ready.json'",s)
  oracle=(ROOT/'deployment/CDirectOracleServer.java').read_text(encoding='utf8')
  self.assertIn('stopOnly||System.nanoTime()<until',oracle)
  self.assertIn('seconds>=30&&seconds<=3600',oracle)
  self.assertIn('r.validUntil().equals(r.observedAt().add(BigInteger.valueOf(30)))',oracle)
  self.assertIn("'360'",(ROOT/'deployment/product-direct-linux.py').read_text())
 def test_resource_fail_closed_before_docker_or_claim(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'ram.jsonl'
   with patch.object(m,'memory',return_value={'availableBytes':1919*1024**2}),patch.object(m.shutil,'disk_usage',return_value=type('Disk',(),{'free':3*1024**3})()):
    with self.assertRaises(Exception):m.admission(p)
   r=json.loads(p.read_text());self.assertEqual(r['decision'],'RAM_LOW');self.assertEqual(r['ramShortfallBytes'],1024**2)
 def test_scope_and_fresh_manifest(self):
  with self.assertRaises(ValueError):m.scoped(ROOT)
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'file';m.save(p,{'x':1})
   with self.assertRaises(FileExistsError):m.save(p,{'x':2})
   self.assertEqual(json.loads(p.read_text()),{'x':1})
 def record(self):
  run='ccpg-'+'1'*16
  return {'run':run,'containers':{'app':{'name':run+'-app','image':'sha256:abc','id':'owned-id'}}}
 def info(self):
  r=self.record();run=r['run']
  return {'Id':'owned-id','Name':'/'+run+'-app','Image':'sha256:abc','Config':{'Labels':{m.LABEL:run}},'HostConfig':{'NetworkMode':run+'-network','PortBindings':{'8080/tcp':[{'HostIp':'127.0.0.1','HostPort':'32175'}]}},'NetworkSettings':{'Networks':{run+'-network':{}}},'State':{'Running':True}}
 def test_ownership_and_exact_loopback_binding(self):
  with patch.object(m,'inspect',return_value=self.info()):m.owned(self.record(),'app')
  for field in ['id','image','owner','port','privileged','network']:
   info=self.info()
   if field=='id':info['Id']='other'
   if field=='image':info['Image']='other'
   if field=='owner':info['Config']['Labels'][m.LABEL]='other'
   if field=='port':info['HostConfig']['PortBindings']['8080/tcp'][0]['HostIp']='0.0.0.0'
   if field=='privileged':info['HostConfig']['Privileged']=True
   if field=='network':info['NetworkSettings']['Networks']['other']={}
   with self.subTest(field=field),patch.object(m,'inspect',return_value=info),self.assertRaises(ValueError):m.owned(self.record(),'app')
 def test_import_and_commands_do_not_implicitly_execute(self):
  with patch.object(m,'docker',side_effect=AssertionError('Docker forbidden')),patch.object(m,'execute',side_effect=AssertionError('process forbidden')):
   m.owner('0x'+'1'*40);m.source_map()
 def test_keyless_separation_and_no_trial_browser(self):
  s=(ROOT/'deployment/product-manual.py').read_text(encoding='utf8');j=(ROOT/'deployment/ProductManualActor.java').read_text(encoding='utf8')
  self.assertNotIn("launch('wallet'",s);self.assertNotIn('product-direct-browser.mjs',s);self.assertNotIn('READ_TEST_KEY_A=',s)
  self.assertNotIn('wallet-keys.private.json',j);self.assertIn('seed(String external)',j)
  self.assertIn('ProductDirectGate.Boundary.class',j);self.assertIn('ProductDirectDriver.bootstrap()',j)
  self.assertIn('/gate-control/STOP',j);self.assertIn('normal-stop.json',j)
  self.assertNotIn('Sign.sign',j);self.assertNotIn('personal_sign',j)
 def test_shutdown_preserves_and_does_not_delete(self):
  s=(ROOT/'deployment/product-manual.py').read_text(encoding='utf8')
  for command in ["docker('rm'","docker('volume','rm'","docker('network','rm'",'prune','anvil_loadState']:
   self.assertNotIn(command,s)
  for required in ['postgres.private.dump','anvil-state.private.json','STOP_CLAIM','STOPPED_PRESERVED_NOT_RESTART_VERIFIED']:self.assertIn(required,s)
 def test_build_stamp_required_and_source_covered(self):
  sources=m.source_map()
  for n in ['ProductManualActor.java','product-manual.py','product-manual.gradle','test_product_manual.py']:self.assertIn('deployment/'+n,sources)
  s=(ROOT/'deployment/product-manual.py').read_text(encoding='utf8');self.assertIn("stamp['source']==build_inputs() and stamp['outputs']==build_outputs()",s)
 def test_compilation_options(self):
  s=(ROOT/'deployment/product-manual.gradle').read_text(encoding='utf8')
  self.assertIn("'-parameters'",s);self.assertIn("'-proc:none'",s);self.assertIn('productManualBuildStamp',s)
  self.assertIn("tasks.named('productDirectCiClasses')",s)
 def test_real_actor_arguments_keep_transport_policy(self):
  args=m.actor_argv(['seed','0x'+'1'*40])
  for flag in m.direct_module().FLAGS:self.assertIn(flag,args)
  self.assertEqual(args[-3:],['ProductManualActor','seed','0x'+'1'*40])
 def session(self,root):
  p=root/('session-'+'1'*16);p.mkdir();(p/'control').mkdir()
  record={'run':'ccpg-'+'1'*16,'containers':{},'volumes':{},'network':None,'status':'READY','web':'loopback','rpc':'loopback','externalOwner':'0x'+'1'*40}
  m.save(p/'owned.json',record);return p,record
 def test_external_stop_is_request_only_and_repeatable(self):
  with tempfile.TemporaryDirectory() as d,patch.object(m,'docker',side_effect=AssertionError('no Docker on stop request')):
   root=Path(d);p,_=self.session(root)
   with patch.object(m,'RUNTIME',root),patch.object(m,'ROOT',root):
    for _ in range(2):self.assertEqual(m.request_stop(p)['status'],'STOP_REQUESTED_SUPERVISOR_MUST_CONFIRM')
    self.assertFalse((p/'STOP_CLAIM').exists());self.assertTrue((p/'control/STOP').exists())
    with self.assertRaisesRegex(ValueError,'STOP_REQUESTED'):m.check_stop(p)
 def test_stopped_status_does_not_report_old_ready(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);p,_=self.session(root);m.save(p/'stopped.json',{'status':'STOPPED_PRESERVATION_PARTIAL'})
   with patch.object(m,'RUNTIME',root),patch.object(m,'owned',side_effect=AssertionError('no container check after stopped')):
    self.assertEqual(m.status(p)['status'],'STOPPED_PRESERVATION_PARTIAL')
 def test_packaged_copy_and_build_output_drift_are_rejected(self):
  outputs={'backend/build/classes/java/main/A.class':'class-hash','backend/build/resources/main/schema.sql':'sql-hash','tools/websocket-test-client/dist/index.html':'web-hash'}
  stamp={'source':{'source':'hash'},'outputs':outputs};packaged=m.packaged_outputs(outputs)
  with patch.object(m,'build_inputs',return_value=stamp['source']),patch.object(m,'build_outputs',return_value=outputs):
   m.verify_packaged(stamp,packaged)
   with self.assertRaisesRegex(ValueError,'PACKAGED_OUTPUT_MISMATCH'):m.verify_packaged(stamp,dict(packaged,**{'classes/A.class':'changed'}))
  with patch.object(m,'build_inputs',return_value=stamp['source']),patch.object(m,'build_outputs',return_value=dict(outputs,extra='changed')):
   with self.assertRaisesRegex(ValueError,'BUILD_CHANGED_DURING_COPY'):m.verify_packaged(stamp,packaged)
  with patch.object(m,'build_inputs',return_value={'source':'changed'}),patch.object(m,'build_outputs',return_value=outputs):
   with self.assertRaisesRegex(ValueError,'BUILD_CHANGED_DURING_COPY'):m.verify_packaged(stamp,packaged)
 def test_build_source_bracket_precedes_compilation(self):
  s=(ROOT/'deployment/product-manual.gradle').read_text(encoding='utf8')
  self.assertIn("['compileJava','processResources','productReadCiClasses','productDirectCiClasses']",s)
  self.assertIn("dependsOn tasks.named('productManualBegin')",s)
  self.assertIn("if (before != src) throw new GradleException('MANUAL_SOURCE_CHANGED_DURING_BUILD')",s)
 def test_stop_during_network_start_prevents_later_resources(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);prep=root/('prepare-'+'2'*16);prep.mkdir();(prep/'fixture').mkdir()
   m.save(prep/'prepared.json',{'status':'PREPARED_NOT_RUNNING','source':{},'files':{}})
   calls=[];shutdown_records=[]
   def fake_docker(*args,**kwargs):calls.append(args);return 'sha256:locked'
   def stop_during_create(*args,**kwargs):
    session=next(root.glob('session-*'));m.request_stop(session)
    return subprocess.CompletedProcess([],0,b'',b''),{'stage':'NETWORK_CREATE'}
   def fake_inspect(kind,name):
    session=next(root.glob('session-*'));r=json.loads((session/'owned.json').read_text())
    return {'Id':'network-id','Internal':False,'Driver':'bridge','Labels':{m.LABEL:r['run']}}
   def fake_shutdown(path):shutdown_records.append(json.loads((path/'owned.json').read_text()))
   with patch.object(m,'RUNTIME',root),patch.object(m,'ROOT',root),patch.object(m,'source_map',return_value={}),patch.object(m,'tree',return_value={}),patch.object(m,'admission',return_value=type('O',(),{'sample':lambda *a:None})()),patch.object(m,'port_check'),patch.object(m,'docker',side_effect=fake_docker),patch.object(m.network,'inventory',return_value={'subnet':'10.254.1.144/28'}),patch.object(m.linux,'inventory',return_value={'subnet':'10.254.1.144/28'}),patch.object(m.network,'command',side_effect=stop_during_create),patch.object(m,'inspect',side_effect=fake_inspect),patch.object(m,'shutdown',side_effect=fake_shutdown):
    with self.assertRaisesRegex(ValueError,'STOP_REQUESTED'):m.start(prep,'0x'+'1'*40)
   self.assertEqual(len(shutdown_records),1);self.assertEqual(shutdown_records[0]['containers'],{})
   self.assertFalse(any(c[0] in ['run','volume'] for c in calls))
 def test_pg_snapshot_failure_still_stops_only_owned_chain_and_pg(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);p,r=self.session(root);r['containers']={x:{'name':r['run']+'-'+x,'image':'sha256:locked','id':x+'-id'} for x in ['pg','anvil']};(p/'owned.json').write_text(json.dumps(r))
   calls=[]
   def fake_docker(*args,**kwargs):calls.append(args);return 'owned-id' if args[:2]==('container','ls') else ''
   def fake_owned(record,role):return {'Id':role+'-id','State':{'Running':True,'ExitCode':0}}
   def fake_cp(args,timeout):Path(args[-1]).write_text('{}');return subprocess.CompletedProcess(args,0,b'',b'')
   with patch.object(m,'RUNTIME',root),patch.object(m,'docker',side_effect=fake_docker),patch.object(m,'owned',side_effect=fake_owned),patch.object(m.subprocess,'run',side_effect=subprocess.TimeoutExpired('pg_dump',45)),patch.object(m,'execute',side_effect=fake_cp):
    result=m.shutdown(p)
   self.assertEqual(result['status'],'STOPPED_PRESERVATION_PARTIAL');self.assertIn('PG_DUMP_UNCONFIRMED',result['errors'])
   self.assertEqual([c[-1] for c in calls if c[0]=='stop'],['anvil-id','pg-id'])
 def test_anvil_readiness_is_read_only_loopback_with_same_rpc_limit(self):
  class Reply:
   def __enter__(self):return self
   def __exit__(self,*args):pass
   def read(self,n):return b'{"jsonrpc":"2.0","id":1,"result":"0x7a69"}'
  calls=[]
  class Opener:
   def open(self,request,timeout):calls.append((request,timeout));return Reply()
  m.anvil_probe(opener=Opener())
  self.assertEqual(len(calls),1);request,timeout=calls[0]
  self.assertEqual(request.full_url,'http://127.0.0.1:32176');self.assertLessEqual(timeout,3)
  self.assertEqual(json.loads(request.data),{'jsonrpc':'2.0','id':1,'method':'eth_chainId','params':[]})
 def test_anvil_readiness_timeout_is_bounded_and_not_a_transaction_retry(self):
  calls=[];now=[0.0]
  def invoke(timeout):calls.append(timeout);now[0]+=timeout;raise subprocess.TimeoutExpired('own-read-only-child',timeout)
  with tempfile.TemporaryDirectory() as d,self.assertRaisesRegex(ValueError,'ANVIL_READY_TIMEOUT'):
   m.anvil_ready(Path(d),invoke=invoke,clock=lambda:now[0],sleep=lambda n:now.__setitem__(0,now[0]+n))
  self.assertLessEqual(len(calls),5);self.assertLessEqual(now[0],15);self.assertTrue(all(n<=3 for n in calls))
 def test_anvil_readiness_rejects_wrong_chain_without_retry_and_honors_stop(self):
  class Reply:
   def __enter__(self):return self
   def __exit__(self,*args):pass
   def read(self,n):return b'{"jsonrpc":"2.0","id":1,"result":"0x1"}'
  class Opener:
   calls=0
   def open(self,*args,**kw):self.calls+=1;return Reply()
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);op=Opener()
   with self.assertRaisesRegex(ValueError,'ANVIL_READY_IDENTITY'):m.anvil_probe(opener=op)
   self.assertEqual(op.calls,1);(p/'control').mkdir();(p/'control/STOP').touch()
   with self.assertRaisesRegex(ValueError,'STOP_REQUESTED'):m.anvil_ready(p,invoke=lambda t:op.open())
   self.assertEqual(op.calls,1)
 def test_late_body_success_and_stop_during_body_never_accept(self):
  good=json.dumps({'jsonrpc':'2.0','id':1,'result':'0x7a69'}).encode()
  for seconds,code in [(4,'RESPONSE_DEADLINE'),(16,'TOTAL_DEADLINE')]:
   with tempfile.TemporaryDirectory() as d:
    now=[0.0]
    def late(timeout):now[0]=seconds;return subprocess.CompletedProcess([],0,good,b'')
    with self.assertRaisesRegex(ValueError,code):m.anvil_ready(Path(d),invoke=late,clock=lambda:now[0],sleep=lambda _:None)
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'control').mkdir()
   def stopped(timeout):(p/'control/STOP').touch();return subprocess.CompletedProcess([],0,good,b'')
   with self.assertRaisesRegex(ValueError,'STOP_REQUESTED'):m.anvil_ready(p,invoke=stopped)
 def test_child_wall_timeout_and_private_keys_not_forwarded(self):
  with patch.object(m,'execute',return_value='done') as execute:
   self.assertEqual(m.invoke_anvil_probe(2.25),'done')
   args,timeout=execute.call_args.args
   self.assertEqual(timeout,2.25);self.assertEqual(args[0],m.sys.executable);self.assertEqual(args[-1],'--anvil-readiness-probe')
   self.assertNotIn('docker',args);self.assertNotIn('--owner',args)
 def test_actual_own_child_is_terminated_at_wall_timeout_without_socket(self):
  with self.assertRaises(subprocess.TimeoutExpired):m.execute([m.sys.executable,'-B','-c','import time; time.sleep(5)'],timeout=.2)
 def test_permanent_child_rejection_is_not_retried(self):
  calls=[]
  def rejected(timeout):calls.append(timeout);return subprocess.CompletedProcess([],2,b'',b'')
  with tempfile.TemporaryDirectory() as d,self.assertRaisesRegex(ValueError,'ANVIL_READY_PROBE_REJECTED'):m.anvil_ready(Path(d),invoke=rejected)
  self.assertEqual(len(calls),1)

 def test_fresh_state_rejects_old_foreign_nonlocal_or_configured_volumes(self):
  run='ccpg-'+'2'*16
  record={'run':run,'containers':{},'volumes':{'anvil':run+'-anvil-data'}}
  volume={'Name':run+'-anvil-data','Driver':'local','Options':None,'Labels':{m.LABEL:run}}
  with patch.object(m,'inspect',return_value=volume):m.fresh_state_volume(record)
  for role in ['state-init','anvil','seed']:
   with patch.object(m,'inspect',side_effect=AssertionError('must reject before inspect')),self.assertRaisesRegex(ValueError,'FRESH_STATE_ONLY'):
    m.fresh_state_volume(dict(record,containers={role:{}}))
  for field,value in [('Name','old-volume'),('Driver','nfs'),('Options',{'device':'/host'}),('Labels',{m.LABEL:'other'})]:
   with patch.object(m,'inspect',return_value=dict(volume,**{field:value})),self.assertRaisesRegex(ValueError,'STATE_VOLUME_OWNER'):m.fresh_state_volume(record)

 def init_info(self):
  info=self.info();run=self.record()['run'];info['Name']='/'+run+'-state-init'
  info['Config'].update(User='0:0',Entrypoint=['/bin/sh'],Cmd=['-ec',m.STATE_INIT])
  info['HostConfig'].update(CapAdd=['CHOWN'],CapDrop=['ALL'],ReadonlyRootfs=True,SecurityOpt=['no-new-privileges'],PortBindings={})
  info['Mounts']=[{'Type':'volume','Name':run+'-anvil-data','Destination':'/state','RW':True}]
  return info

 def test_chown_exception_is_exact_helper_only_not_anvil_or_app(self):
  r=self.record();r['containers']['state-init']=dict(r['containers']['app'],name=r['run']+'-state-init')
  with patch.object(m,'inspect',return_value=self.init_info()):m.owned(r,'state-init')
  docker_normalized=self.init_info();docker_normalized['HostConfig']['CapAdd']=['CAP_CHOWN']
  with patch.object(m,'inspect',return_value=docker_normalized):m.owned(r,'state-init')
  mutations=[('Config','User','1000'),('Config','Cmd',['-ec','chown -R 1000 /']),('HostConfig','CapAdd',['CHOWN','SYS_ADMIN']),('HostConfig','CapDrop',[]),('HostConfig','ReadonlyRootfs',False),('HostConfig','SecurityOpt',[])]
  for group,key,value in mutations:
   info=self.init_info();info[group][key]=value
   with patch.object(m,'inspect',return_value=info),self.assertRaises(ValueError):m.owned(r,'state-init')
  for field,value in [('Name','preserved-volume'),('Destination','/'),('RW',False),('Type','bind')]:
   info=self.init_info();info['Mounts'][0][field]=value
   with patch.object(m,'inspect',return_value=info),self.assertRaisesRegex(ValueError,'STATE_INIT_MOUNT'):m.owned(r,'state-init')
  info=self.info();info['HostConfig']['CapAdd']=['CHOWN']
  with patch.object(m,'inspect',return_value=info),self.assertRaisesRegex(ValueError,'PRIVILEGES'):m.owned(self.record(),'app')

 def test_permission_fix_is_nonrecursive_and_precedes_actual_anvil(self):
  self.assertNotIn('chmod',m.STATE_INIT);self.assertNotIn('chown -R',m.STATE_INIT)
  self.assertIn('test -z "$(ls -A /state)"',m.STATE_INIT)
  self.assertIn(m.STATE_RESUME_GUARD,m.STATE_START)
  self.assertIn('test -s "$STATE_DIR/anvil.json"',m.STATE_RESUME_GUARD)
  self.assertIn('1000:1000',m.STATE_START);self.assertIn('> /state/.manual-write-probe',m.STATE_START)
  self.assertIn('exec /usr/local/bin/anvil "$@"',m.STATE_START)
  s=(ROOT/'deployment/product-manual.py').read_text(encoding='utf8')
  start=s[s.index('def start('):s.index('def shutdown(')]
  self.assertLess(start.index('fresh_state_volume(record)'),start.index("launch('state-init'"))
  self.assertLess(start.index("wait_exit('state-init',10)"),start.index("launch('anvil'"))
  self.assertLess(start.index('internal_anvil_ready(session,record)'),start.index("launch('rpc-peer'"))
  self.assertLess(start.index('anvil_ready(session)'),start.index("actor('seed'"))
  self.assertIn("'--user','1000:1000','--cap-drop=ALL'",start)

 def test_tcp_headers_body_trace_closes_on_success_and_failure(self):
  events=[];calls=[]
  class Response:
   status=200
   def read(self,n):calls.append(('read',n));return b'{"jsonrpc":"2.0","id":1,"result":"0x7a69"}'
  class Connection:
   def __init__(self,*args,**kw):calls.append((args,kw))
   def connect(self):calls.append('connect')
   def request(self,*args,**kw):calls.append((args,kw))
   def getresponse(self):return Response()
   def close(self):calls.append('closed')
  m.anvil_probe(trace=events.append,connection_factory=Connection)
  self.assertEqual(events,['TCP_CONNECT','TCP_CONNECTED','HTTP_REQUEST','HTTP_HEADERS','BODY_READ','IDENTITY_OK'])
  self.assertEqual(calls[0],(('127.0.0.1',32176),{'timeout':3}));self.assertEqual(calls[-1],'closed')
  body=json.loads(calls[2][1]['body']);self.assertEqual(body['method'],'eth_chainId');self.assertEqual(body['params'],[])
  Response.status=503;events.clear()
  with self.assertRaisesRegex(ValueError,'ANVIL_READY_HTTP'):m.anvil_probe(trace=events.append,connection_factory=Connection)
  self.assertEqual(calls[-1],'closed');self.assertNotIn('BODY_READ',events)

 def test_timeout_trace_is_sanitized_and_retained_before_failure(self):
  raw=b'{"stage":"TCP_CONNECT","elapsedMs":1}\n{"stage":"BODY_READ","elapsedMs":2,"secret":"do-not-store"}\nprivate-key-like-text\n'
  self.assertEqual(m.probe_events(raw),[{'stage':'TCP_CONNECT','elapsedMs':1}])
  self.assertEqual(m.probe_events(b'{"stage":"IDENTITY_OK","elapsedMs":true}'),[])
  self.assertEqual(m.probe_events(b'null\n[]\n[{"x":1}]\n{"stage":[],"elapsedMs":1}\n'),[])
  with tempfile.TemporaryDirectory() as d:
   now=[0.0]
   def timeout(t):now[0]+=t;raise subprocess.TimeoutExpired('read-child',t,stderr=raw)
   with self.assertRaisesRegex(ValueError,'ANVIL_READY_TIMEOUT'):m.anvil_ready(Path(d),invoke=timeout,clock=lambda:now[0],sleep=lambda n:now.__setitem__(0,now[0]+n))
   saved=(Path(d)/'anvil-readiness.jsonl').read_text();self.assertNotIn('do-not-store',saved);self.assertNotIn('private-key-like-text',saved)
   rows=[json.loads(line) for line in saved.splitlines()];self.assertTrue(any(r['stage']=='HOST_CHILD_TIMEOUT' and r['events'] for r in rows))
   self.assertTrue(all('atUtc' in r for r in rows))

 def test_internal_rpc_is_single_fixed_read_and_no_raw_error_logging(self):
  info={'Id':'exact-anvil-id','State':{'Running':True,'ExitCode':0,'OOMKilled':False,'Error':''}}
  with tempfile.TemporaryDirectory() as d,patch.object(m,'owned',return_value=info),patch.object(m,'execute',return_value=subprocess.CompletedProcess([],0,b'31337\n',b'secret')) as exe:
   m.internal_anvil_ready(Path(d),{})
   args,timeout=exe.call_args.args;self.assertEqual(timeout,5);self.assertEqual(args[:3],['docker','exec','exact-anvil-id'])
   self.assertIn('chain-id',args);self.assertEqual(args[-2:],['--rpc-timeout','2'])
   self.assertNotIn('secret',(Path(d)/'anvil-readiness.jsonl').read_text())
  with tempfile.TemporaryDirectory() as d,patch.object(m,'owned',return_value=info),patch.object(m,'execute',return_value=subprocess.CompletedProcess([],0,b'1',b'')),self.assertRaisesRegex(ValueError,'INTERNAL_RPC'):
   m.internal_anvil_ready(Path(d),{})

 def test_failure_summary_does_not_persist_raw_logs_or_engine_errors(self):
  info={'Id':'exact-id','State':{'Running':False,'ExitCode':1,'OOMKilled':False,'Error':'secret-engine-data'}}
  with tempfile.TemporaryDirectory() as d,patch.object(m,'owned',return_value=info),patch.object(m,'execute',return_value=subprocess.CompletedProcess([],0,b'MANUAL_STATE_WRITABLE\nsecret',b'private')):
   m.failure_observation(Path(d),{'containers':{'anvil':{}}})
   saved=(Path(d)/'anvil-readiness.jsonl').read_text();self.assertNotIn('secret',saved);self.assertNotIn('private',saved)
   self.assertIn('"stateWritableMarker":true',saved)

 def test_internal_timeout_and_stop_block_without_any_other_process(self):
  info={'Id':'exact-id','State':{'Running':True,'ExitCode':0}}
  with tempfile.TemporaryDirectory() as d,patch.object(m,'owned',return_value=info),patch.object(m,'execute',side_effect=subprocess.TimeoutExpired('internal-read',5)) as exe:
   with self.assertRaisesRegex(ValueError,'INTERNAL_TIMEOUT'):m.internal_anvil_ready(Path(d),{})
   self.assertEqual(exe.call_count,1)
   (Path(d)/'control').mkdir();(Path(d)/'control/STOP').touch()
   with self.assertRaisesRegex(ValueError,'STOP_REQUESTED'):m.internal_anvil_ready(Path(d),{})
   self.assertEqual(exe.call_count,1)

 def test_anvil_cannot_run_as_root_or_with_different_entrypoint(self):
  r=self.record();r['containers']['anvil']=dict(r['containers']['app'],name=r['run']+'-anvil')
  def info():
   i=self.info();i['Name']='/'+r['run']+'-anvil'
   i['Config'].update(User='1000:1000',Entrypoint=['/bin/sh'],Cmd=['-ec',m.STATE_START,'manual-anvil',*m.ANVIL_ARGS])
   i['HostConfig'].update(CapDrop=['ALL'],PortBindings={'8545/tcp':[{'HostIp':'127.0.0.1','HostPort':'32176'}]});return i
  with patch.object(m,'inspect',return_value=info()):m.owned(r,'anvil')
  for key,value in [('User','0:0'),('Entrypoint',['anvil']),('Cmd',['--accounts','10'])]:
   i=info();i['Config'][key]=value
   with patch.object(m,'inspect',return_value=i),self.assertRaisesRegex(ValueError,'ANVIL_NONROOT_COMMAND'):m.owned(r,'anvil')

 def mocked_start(self,init_exit):
  # Execute real lifecycle code; every external process/Docker/socket boundary is replaced.
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);prep=root/('prepare-'+'2'*16);prep.mkdir();(prep/'fixture').mkdir()
   m.save(prep/'prepared.json',{'status':'PREPARED_NOT_RUNNING','source':{},'files':{}})
   events=[];shutdown_records=[]
   def record():return json.loads((next(root.glob('session-*'))/'owned.json').read_text())
   def fake_docker(*args,**kw):
    if args[:2]==('image','inspect'):return 'sha256:locked'
    if args[:2]==('volume','ls'):return ''
    if args[0]=='run':
     role=args[args.index('--name')+1][len(record()['run'])+1:];events.append(role)
     if role=='seed':raise ValueError('MANUAL_TEST_STOP_BEFORE_SEED')
    return ''
   def fake_inspect(kind,name):
    r=record();run=r['run'];net=run+'-network'
    if kind=='network':return {'Id':'network-id','Internal':False,'Driver':'bridge','Labels':{m.LABEL:run}}
    if kind=='volume':return {'Name':name,'Driver':'local','Options':None,'Labels':{m.LABEL:run}}
    role=name[len(run)+1:];i={'Id':role+'-id','Name':'/'+name,'Image':'sha256:locked','Config':{'Labels':{m.LABEL:run}},'HostConfig':{'NetworkMode':net,'PortBindings':{}},'NetworkSettings':{'Networks':{net:{}}},'State':{'Running':role in ['pg','anvil'],'ExitCode':init_exit if role=='state-init' else 0}}
    if role=='state-init':
     i['Config'].update(User='0:0',Entrypoint=['/bin/sh'],Cmd=['-ec',m.STATE_INIT]);i['HostConfig'].update(CapAdd=['CHOWN'],CapDrop=['ALL'],ReadonlyRootfs=True,SecurityOpt=['no-new-privileges'])
     i['Mounts']=[{'Type':'volume','Name':run+'-anvil-data','Destination':'/state','RW':True}]
    if role=='anvil':
     i['Config'].update(User='1000:1000',Entrypoint=['/bin/sh'],Cmd=['-ec',m.STATE_START,'manual-anvil',*m.ANVIL_ARGS]);i['HostConfig'].update(CapDrop=['ALL'],PortBindings={'8545/tcp':[{'HostIp':'127.0.0.1','HostPort':'32176'}]})
    return i
   def fake_execute(args,timeout):
    if 'chain-id' in args:events.append('internal-rpc');return subprocess.CompletedProcess(args,0,b'31337\n',b'')
    return subprocess.CompletedProcess(args,0,b'',b'')
   def host_ready(session):events.append('host-rpc')
   def created(*args,**kw):return subprocess.CompletedProcess([],0,b'',b''),{'stage':'NETWORK_CREATE'}
   with patch.object(m,'RUNTIME',root),patch.object(m,'ROOT',root),patch.object(m,'source_map',return_value={}),patch.object(m,'tree',return_value={}),patch.object(m,'admission',return_value=type('Observer',(),{'sample':lambda *a:None})()),patch.object(m,'port_check'),patch.object(m.network,'inventory',return_value={'subnet':'10.254.1.144/28'}),patch.object(m.linux,'inventory',return_value={'subnet':'10.254.1.144/28'}),patch.object(m.network,'command',side_effect=created),patch.object(m,'docker',side_effect=fake_docker),patch.object(m,'execute',side_effect=fake_execute),patch.object(m,'inspect',side_effect=fake_inspect),patch.object(m,'anvil_ready',side_effect=host_ready),patch.object(m,'actor_argv',side_effect=lambda args:['ProductManualActor',*args]),patch.object(m,'shutdown',side_effect=lambda p:shutdown_records.append(record())):
    expected='STATE-INIT_FAILED' if init_exit else 'TEST_STOP_BEFORE_SEED'
    with self.assertRaisesRegex(ValueError,expected):m.start(prep,'0x'+'1'*40)
   self.assertEqual(len(shutdown_records),1)
   self.assertEqual(shutdown_records[0]['status'],'BLOCKED_NO_RETRY')
   session=next(root.glob('session-*'));rows=[json.loads(line) for line in (session/'anvil-readiness.jsonl').read_text().splitlines()] if (session/'anvil-readiness.jsonl').exists() else []
   return events,rows

 def test_real_mocked_start_reaches_seed_boundary_after_state_and_all_rpc_checks(self):
  events,rows=self.mocked_start(0)
  self.assertEqual(events[:7],['pg','state-init','anvil','internal-rpc','rpc-peer','host-rpc','seed'])
  self.assertTrue(any(r['stage']=='STATE_INIT_EXIT' for r in rows))
  self.assertTrue(any(r['stage']=='DOCKER_NETWORK_RPC_OK' for r in rows))

 def test_real_mocked_start_stops_at_init_failure_before_anvil_or_rpc(self):
  events,rows=self.mocked_start(1)
  self.assertEqual(events,['pg','state-init']);self.assertEqual(rows,[])

 def test_actual_shell_restore_guard_rejects_missing_or_empty_snapshot(self):
  # Git's bundled Bash, never Windows system32/bash.exe (which would launch WSL).
  if os.name=='nt':
   git=shutil.which('git');self.assertIsNotNone(git,'Git required for offline shell guard test')
   shell=Path(git).resolve().parent.parent/'usr/bin/bash.exe';self.assertTrue(shell.is_file(),'Git Bash required; no WSL fallback')
   flags=['--noprofile','--norc','-ec']
  else:
   shell=shutil.which('sh');self.assertIsNotNone(shell,'POSIX shell required');flags=['-ec']
  cases=[(None,None,True),('writable','snapshot',True),('writable',None,False),('writable','',False),(None,'snapshot',False),('other','snapshot',False),('',None,False)]
  for marker,state,ok in cases:
   with self.subTest(marker=marker,state=state),tempfile.TemporaryDirectory() as d:
    p=Path(d)
    if marker is not None:(p/'.manual-write-probe').write_text(marker)
    if state is not None:(p/'anvil.json').write_text(state)
    before={f.name:f.read_bytes() for f in p.iterdir()}
    env=m.environment();env['STATE_DIR']=p.as_posix()
    r=subprocess.run([str(shell),*flags,'PATH=/usr/bin:/bin; '+m.STATE_RESUME_GUARD+'; printf GUARD_OK'],env=env,capture_output=True,timeout=3)
    self.assertEqual(r.returncode==0,ok);self.assertEqual(r.stdout==b'GUARD_OK',ok)
    self.assertEqual(before,{f.name:f.read_bytes() for f in p.iterdir()})

 def test_manual_bridge_is_scoped_and_read_ci_stays_internal(self):
  run='ccpg-'+'4'*16;subnet='10.254.1.144/28'
  args=m.manual_network_args(run,subnet)
  self.assertNotIn('--internal',args);self.assertEqual(args[3:5],['--driver','bridge'])
  self.assertEqual(args[-1],run+'-network');self.assertIn(m.LABEL+'='+run,args)
  self.assertIn('--internal',m.network.create_args(run,subnet))
  for bad in ['other-existing-network','ccpg-invalid']:
   with self.assertRaises(ValueError):m.manual_network_args(bad,subnet)

 def test_log_timeout_cannot_skip_independent_internal_rpc_diagnostic(self):
  info={'Id':'owned-id','State':{'Running':True,'ExitCode':0}}
  with tempfile.TemporaryDirectory() as d,patch.object(m,'owned',return_value=info),patch.object(m,'internal_anvil_ready') as internal,patch.object(m,'execute',side_effect=subprocess.TimeoutExpired('logs',3)):
   m.failure_observation(Path(d),{'containers':{'anvil':{}}})
   internal.assert_called_once();self.assertIn('FAILURE_LOG_UNAVAILABLE',(Path(d)/'anvil-readiness.jsonl').read_text())

 def test_active_lifecycle_preserves_original_stopped_and_uses_new_stop(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);p,r=self.session(root);m.save(p/'stopped.json',{'status':'OLD_STOPPED'})
   epoch=p/('lifecycle-'+'a'*16);epoch.mkdir();(epoch/'control').mkdir();r['lifecycle']=epoch.name;r['stopPolicy']='EXPLICIT_STOP_OR_FAILURE';m.persist(p,r)
   original=(p/'stopped.json').read_bytes()
   with patch.object(m,'RUNTIME',root),patch.object(m,'ROOT',root):
    self.assertEqual(m.status(p)['status'],'READY');m.check_stop(p);m.request_stop(p)
    self.assertTrue((epoch/'control/STOP').exists());self.assertFalse((p/'control/STOP').exists())
    with self.assertRaisesRegex(ValueError,'STOP_REQUESTED'):m.check_stop(p)
   self.assertEqual((p/'stopped.json').read_bytes(),original)
   for name in ['../other','lifecycle-invalid','/tmp']:
    with self.assertRaisesRegex(ValueError,'LIFECYCLE_NAME'):m.active_path(p,{'lifecycle':name})

 def test_shutdown_writes_new_lifecycle_without_overwriting_old_snapshots(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);p,r=self.session(root);r['lifecycle']='lifecycle-'+'b'*16;epoch=p/r['lifecycle'];epoch.mkdir();(epoch/'control').mkdir();m.persist(p,r)
   m.save(p/'stopped.json',{'status':'OLD'});(p/'postgres.private.dump').write_bytes(b'old-pg');(p/'anvil-state.private.json').write_bytes(b'old-chain')
   with patch.object(m,'RUNTIME',root),patch.object(m,'docker',side_effect=AssertionError('no external resource expected')):
    out=m.shutdown(p)
   self.assertEqual(out['status'],'STOPPED_PRESERVED_NOT_RESTART_VERIFIED');self.assertTrue((epoch/'stopped.json').exists())
   self.assertEqual((p/'postgres.private.dump').read_bytes(),b'old-pg');self.assertEqual((p/'anvil-state.private.json').read_bytes(),b'old-chain');self.assertEqual(json.loads((p/'stopped.json').read_text())['status'],'OLD')

 def test_checkpoint_resume_compatibility_allows_lifecycle_only(self):
  old={'source':{'backend/src/main/Auth.java':'auth','deployment/ProductManualActor.java':'old'},'files':{'classes/Auth.class':'auth-class','artifacts/token.json':'token','jars/core.jar':'jar','helpers/Manual.class':'old'}}
  new={'source':dict(old['source'],**{'deployment/ProductManualActor.java':'new'}),'files':dict(old['files'],**{'helpers/Manual.class':'new'})}
  m.resume_compatible(old,new)
  windows_old={'source':dict(old['source'],**{'deployment\\CDirectOracleServer.java':'old-oracle'}),'files':old['files']}
  windows_new={'source':dict(new['source'],**{'deployment\\CDirectOracleServer.java':'new-oracle'}),'files':new['files']}
  m.resume_compatible(windows_old,windows_new)
  duplicate={'source':dict(windows_new['source'],**{'deployment/CDirectOracleServer.java':'masked-oracle'}),'files':new['files']}
  with self.assertRaisesRegex(ValueError,'PATH_ALIAS'):m.resume_compatible(windows_old,duplicate)
  changed={'source':dict(new['source'],**{'backend/src/main/Auth.java':'changed'}),'files':new['files']}
  with self.assertRaisesRegex(ValueError,'PRODUCT_SOURCE_CHANGED'):m.resume_compatible(old,changed)
  for field in ['classes/Auth.class','artifacts/token.json','jars/core.jar']:
   changed={'source':new['source'],'files':dict(new['files'],**{field:'changed'})}
   with self.assertRaisesRegex(ValueError,'PRODUCT_BUILD_CHANGED'):m.resume_compatible(old,changed)

if __name__=='__main__':unittest.main()
