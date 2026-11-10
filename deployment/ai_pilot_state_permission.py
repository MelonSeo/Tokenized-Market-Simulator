"""Minimum ownership init of a new, empty, exact-owned Anvil output volume only."""
import json,re
from ai_pilot_linux import ROOT,LABEL,LOCK
from ai_pilot_unpaid_live import cmd,save
from product_read_resources import Observer

def prepare(container):
 m=re.fullmatch(r'(pilot-linux-[a-f0-9]{16})-anvil-([a-f0-9]{16})',container)
 if not m:raise ValueError('STATE_INIT_SCOPE')
 owner,suffix=m.groups();volume=owner+'-state-'+suffix;folder=ROOT/'deployment/runtime'/owner/('attempt-'+suffix)
 if folder.is_symlink() or not folder.is_dir():raise ValueError('STATE_INIT_FOLDER')
 observer=Observer(folder/'state-permission-resources.jsonl');observer.sample('NEW_STATE_OUTPUT_INIT',True)
 item=json.loads(cmd(['docker','inspect',container]))[0]
 if item['Name']!='/'+container or item['Config']['Labels'].get(LABEL)!=owner or item['Config']['Image']!=LOCK['images']['anvil'] or not item['State']['Running']:raise ValueError('STATE_INIT_OWNER')
 mounts=item['Mounts'];dest=[p for p in mounts if p['Destination']=='/output'];source=[p for p in mounts if p['Destination']=='/baseline']
 if len(dest)!=1 or dest[0].get('Name')!=volume or len(source)!=1 or source[0]['RW']:raise ValueError('STATE_INIT_MOUNTS')
 if json.loads(cmd(['docker','volume','inspect',volume]))[0]['Labels'].get(LABEL)!=owner:raise ValueError('STATE_INIT_VOLUME')
 before=cmd(['docker','exec',container,'stat','-c','%u:%g:%a','/output'])
 if before=='0:0:755':
  cmd(['docker','exec','--user','0:0',container,'sh','-c','test -z "$(find /output -mindepth 1 -print -quit)" && chown 1000:1000 /output && chmod 700 /output'])
 elif before!='1000:1000:700':raise ValueError('STATE_INIT_NOT_NEW')
 proof=cmd(['docker','exec',container,'sh','-c','test "$(id -u):$(id -g)" = 1000:1000 && touch /output/.write-proof && stat -c %u:%g:%a /output'])
 if proof!='1000:1000:700':raise ValueError('STATE_INIT_WRITE_PROOF')
 save(folder/'state-permission.json',{'container':container,'volume':volume,'before':before,'after':proof,'executingUid':1000,'baselineReadOnly':True,'noOtherVolumeTouched':True})
 print(json.dumps({'outputWritable':True,'uid':1000,'mode':'700'}))
if __name__=='__main__':
 import argparse
 p=argparse.ArgumentParser();p.add_argument('container');prepare(p.parse_args().container)
