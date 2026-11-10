import unittest
from pathlib import Path
import ai_pilot_chrome_retry as r
class RetryTests(unittest.TestCase):
 def test_output_stop_cli_budget_covers_dump_grace_and_validates_owner(self):
  import json
  from unittest.mock import patch
  def item(running):return json.dumps([{'Name':'/owned-anvil','Config':{'Labels':{r.LABEL:'owned'},'Image':r.LOCK['images']['anvil']},'State':{'Running':running}}])
  with patch.object(r,'cmd',side_effect=[item(True),'stopped',item(False)]) as command:
   self.assertEqual(r.stop_output_anvil('owned-anvil','owned'),'STOPPED')
   self.assertEqual(command.call_args_list[1].args,(['docker','stop','--time','120','owned-anvil'],135))
  with patch.object(r,'cmd',return_value=item(True)) as command:
   self.assertEqual(r.stop_output_anvil('owned-anvil','different'),'UNCONFIRMED');self.assertEqual(command.call_count,1)
 def test_uncertain_output_stop_is_not_retried_or_declared_success(self):
  import json
  from unittest.mock import patch
  item=json.dumps([{'Name':'/n','Config':{'Labels':{r.LABEL:'owner'},'Image':r.LOCK['images']['anvil']},'State':{'Running':True}}])
  with patch.object(r,'cmd',side_effect=[item,TimeoutError()]) as command:
   self.assertEqual(r.stop_output_anvil('n','owner'),'UNCONFIRMED');self.assertEqual(command.call_count,2)
 def test_invalid_owner_is_rejected_before_any_state_access(self):
  for owner in ['user-project','../pilot-linux-'+'a'*16,'pilot-linux-not-hex']:
   with self.assertRaisesRegex(ValueError,'CHROME_RETRY_OWNER'):r.retry(owner)
 def test_shared_ledger_is_never_recopied_reset_or_fast_forwarded(self):
  source=Path(r.__file__).read_text(encoding='utf8')
  self.assertIn('r.validate_web_ledger(base)',source)
  self.assertIn("mount(base/'session','/run/pilot/session')",source)
  self.assertIn("mutex.open('x'",source);self.assertIn("mutex.rename(attempt/'attempt-claim.closed.json')",source)
  for forbidden in ['copytree','UPDATE control','DELETE FROM','prepare_unpaid','--no-sandbox','--privileged','--cap-add']:
   self.assertNotIn(forbidden,source)
  # A read-only preservation hash list legitimately names the old SQLite file.
  # Inspect copy operations instead of rejecting every occurrence of that name.
  import ast
  for call in ast.walk(ast.parse(source)):
   if isinstance(call,ast.Call) and isinstance(call.func,ast.Attribute) and call.func.attr in {'copyfile','copy2','copytree','backup'}:
    self.assertNotIn('session',ast.unparse(call));self.assertNotIn('sqlite',ast.unparse(call))
 def test_all_admission_guards_and_new_attempt_evidence_are_preserved(self):
  source=Path(r.__file__).read_text(encoding='utf8')
  for stage in ['RETRY_ADMISSION','RETRY_BEFORE_APP','RETRY_BEFORE_BROWSER']:
   self.assertIn("observer.sample('"+stage+"',True)",source)
  self.assertIn("attempt=base/('attempt-'",source);self.assertIn("claim.get('owner')!=owner",source)
 def test_immutable_chain_input_is_separate_from_new_linux_dump_volume(self):
  source=Path(r.__file__).read_text(encoding='utf8')
  for required in ['target=/baseline,readonly','target=/output',"'--load-state','/baseline/state.json'","'--dump-state','/output/state.json'","'damagedCloneFilePreserved':True"]:
   self.assertIn(required,source)
  self.assertNotIn("'--state','/data/state.json'",source)
if __name__=='__main__':unittest.main()
