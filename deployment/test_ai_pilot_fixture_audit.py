import json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import ai_pilot_fixture_audit as a
class FixtureAuditTests(unittest.TestCase):
 def test_foreign_or_traversal_name_never_reaches_docker(self):
  with patch.object(a,'cmd') as command:
   for name in ['existing-postgres','../pilot-linux-'+'a'*16]:
    with self.assertRaises(ValueError):a.validate_source(name)
   command.assert_not_called()
 def setup_source(self,root,status='PARTIAL',mode='UNPAID_CONTROLLED_ONLY'):
  name='pilot-linux-'+'a'*16;base=root/'deployment/runtime'/name;base.mkdir(parents=True)
  for path,v in [('owned.json',{'run':name,'scope':'FULL','paidCalls':0,'apiKeyReads':0,'image':'app','names':{'pgdata':name+'-pgdata'}}),('result.json',{'status':status}),('v2.private.json',{'mode':mode})]:(base/path).write_text(json.dumps(v))
  return name,base
 def test_success_or_paid_source_is_rejected_without_start(self):
  with tempfile.TemporaryDirectory() as d,patch.object(a,'ROOT',Path(d)),patch.object(a,'cmd') as command:
   name,b=self.setup_source(Path(d),status='PASS')
   with self.assertRaisesRegex(ValueError,'FAILED_UNPAID'):a.validate_source(name)
   (b/'result.json').write_text('{"status":"PARTIAL"}');(b/'v2.private.json').write_text('{"mode":"PAID_EXPLICIT_V2"}')
   with self.assertRaisesRegex(ValueError,'AUDIT_UNPAID'):a.validate_source(name)
   command.assert_not_called()
 def test_running_source_cannot_be_audited(self):
  with tempfile.TemporaryDirectory() as d,patch.object(a,'ROOT',Path(d)),patch.object(a,'cmd',return_value=json.dumps([{'Name':'/any','State':{'Running':True},'Config':{'Image':'app','Labels':{}}}])) as command:
   name,b=self.setup_source(Path(d))
   with self.assertRaisesRegex(ValueError,'STOPPED_OWNER'):a.validate_source(name)
   self.assertFalse(any('start' in c.args[0] for c in command.call_args_list))
if __name__=='__main__':unittest.main()
