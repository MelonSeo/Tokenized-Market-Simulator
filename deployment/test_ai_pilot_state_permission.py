import unittest
from pathlib import Path
import ai_pilot_state_permission as p
class StatePermissionTests(unittest.TestCase):
 def test_only_fresh_owned_output_suffix_is_accepted(self):
  for name in ['user-anvil','pilot-linux-'+'a'*16+'-anvil','../pilot-linux-'+'a'*16+'-anvil-'+'b'*16]:
   with self.assertRaisesRegex(ValueError,'STATE_INIT_SCOPE'):p.prepare(name)
 def test_baseline_readonly_empty_output_and_exact_uid_mode_are_required(self):
  source=Path(p.__file__).read_text(encoding='utf8')
  for required in ["source[0]['RW']",'find /output -mindepth 1 -print -quit','chown 1000:1000 /output','chmod 700 /output',"proof!='1000:1000:700'","observer.sample('NEW_STATE_OUTPUT_INIT',True)"]:
   self.assertIn(required,source)
  for forbidden in ['chmod 777','chown -R','--privileged','--cap-add','/var/lib/postgresql']:
   self.assertNotIn(forbidden,source)
if __name__=='__main__':unittest.main()
