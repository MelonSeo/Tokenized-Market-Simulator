import unittest
from pathlib import Path
import ai_pilot_linux_browser_probe as probe

class ProbeTests(unittest.TestCase):
 def test_exact_upstream_profile_and_default_deny(self):
  self.assertEqual(probe.profile(),probe.PROFILE)
  self.assertIn('deployment/pilot-linux-image/chromium-seccomp.json text eol=lf',(probe.ROOT/'.gitattributes').read_text())
 def test_small_nonroot_network_none_probe_never_uses_security_bypass(self):
  source=Path(probe.__file__).read_text(encoding='utf8')
  for forbidden in ['--no-sandbox','--privileged','--cap-add','seccomp=unconfined','--ipc=host']:
   self.assertNotIn(forbidden,source)
  self.assertIn("'--network','none'",source);self.assertIn("'1000:1000'",source)
  self.assertIn("observer.sample('LINUX_SANDBOX_ADMISSION',True)",source)
  self.assertIn("proof.get('verified') is True",source)
 def test_real_sandbox_ui_is_polled_not_inferred_from_exit_zero(self):
  source=(probe.ROOT/'tools/websocket-test-client/scripts/ai-linux-sandbox-probe.mjs').read_text(encoding='utf8')
  for key in ['layersVerified(text)','/proc/${pid}/status','p.noNewPrivs===1','p.seccomp===2']:
   self.assertIn(key,source)
  self.assertNotIn('--no-sandbox',source)
 def test_profile_failure_is_closed(self):
  from unittest.mock import patch
  with patch.object(probe.PROFILE.__class__,'read_bytes',return_value=b'changed'):
   with self.assertRaisesRegex(ValueError,'CHROME_SECCOMP_IDENTITY'):probe.profile()

if __name__=='__main__':unittest.main()
