import json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import ai_pilot_broker_audit as a
class AuditSourceTests(unittest.TestCase):
 def test_bad_name_never_reads_docker(self):
  with patch.object(a,'cmd') as command:
   with self.assertRaises(ValueError):a.validate_source('../existing')
   command.assert_not_called()
 def setup_source(self,root):
  name='pilot-linux-'+'a'*16;b=root/'deployment/runtime'/name;(b/'source').mkdir(parents=True);(b/'session').mkdir()
  values={'owned.json':{'run':name,'scope':'C_LATENCY','paidCalls':0,'apiKeyReads':0,'image':'app-image'},'result.json':{'status':'PARTIAL'},'v2.private.json':{'mode':'UNPAID_CONTROLLED_ONLY'},'session/session.json':{'mode':'UNPAID_CONTROLLED_ONLY'},'session/pilot-v2.sqlite':{}}
  for p,v in values.items():(b/p).write_text(json.dumps(v))
  return name,b
 def test_success_or_key_access_source_is_rejected_before_docker(self):
  with tempfile.TemporaryDirectory() as d,patch.object(a,'ROOT',Path(d)),patch.object(a,'cmd') as command:
   name,b=self.setup_source(Path(d));(b/'result.json').write_text('{"status":"PASS"}')
   with self.assertRaisesRegex(ValueError,'FAILURE_REQUIRED'):a.validate_source(name)
   (b/'result.json').write_text('{"status":"PARTIAL"}');m=json.loads((b/'owned.json').read_text());m['apiKeyReads']=1;(b/'owned.json').write_text(json.dumps(m))
   with self.assertRaisesRegex(ValueError,'FAILURE_REQUIRED'):a.validate_source(name)
   command.assert_not_called()
 def test_paid_mode_is_rejected_before_docker(self):
  with tempfile.TemporaryDirectory() as d,patch.object(a,'ROOT',Path(d)),patch.object(a,'cmd') as command:
   name,b=self.setup_source(Path(d));(b/'session/session.json').write_text('{"mode":"PAID_EXPLICIT_V2"}')
   with self.assertRaisesRegex(ValueError,'UNPAID_REQUIRED'):a.validate_source(name)
   command.assert_not_called()
 def test_running_source_is_rejected(self):
  with tempfile.TemporaryDirectory() as d,patch.object(a,'ROOT',Path(d)),patch.object(a,'cmd',return_value=json.dumps([{'State':{'Running':True},'Config':{'Image':'app-image','Labels':{}}}])):
   name,b=self.setup_source(Path(d))
   with self.assertRaisesRegex(ValueError,'STOPPED_OWNED'):a.validate_source(name)
if __name__=='__main__':unittest.main()
