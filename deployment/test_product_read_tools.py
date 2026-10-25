import unittest
from pathlib import Path
from product_read_tools import ToolError,find_jdk

class Tools(unittest.TestCase):
 def version(self,path):return 'javac 21.0.10' if path.stem=='javac' else 'openjdk version "21.0.10"'
 def discover(self,env=None,platform='posix',which=None,invoke=None,is_file=None):
  return find_jdk(env or {},platform,which or (lambda n:'/jdk/bin/'+n),invoke or self.version,is_file or (lambda p:True))
 def test_java_home_linux(self):self.assertEqual(self.discover({'JAVA_HOME':'/jdk'})['java'].name,'java')
 def test_java_home_windows(self):self.assertEqual(self.discover({'JAVA_HOME':'/jdk'},'nt')['javac'].name,'javac.exe')
 def test_path_linux(self):self.assertEqual(self.discover()['version'],'21.0.10')
 def test_path_windows(self):self.assertEqual(self.discover(platform='nt')['java'].name,'java.exe')
 def test_missing_path(self):
  with self.assertRaisesRegex(ToolError,'NOT_FOUND'):self.discover(which=lambda n:None)
 def test_bad_explicit_home_does_not_fall_back(self):
  with self.assertRaisesRegex(ToolError,'MISSING'):self.discover({'JAVA_HOME':'/missing'},is_file=lambda p:False)
 def test_wrong_major(self):
  with self.assertRaisesRegex(ToolError,'JAVA_21'):self.discover(invoke=lambda p:'javac 17.0.1' if p.stem=='javac' else 'java version "17.0.1"')
 def test_mismatched_versions(self):
  with self.assertRaisesRegex(ToolError,'MATCHING'):self.discover(invoke=lambda p:'javac 21.0.9' if p.stem=='javac' else 'java version "21.0.10"')
 def test_mixed_installations(self):
  with self.assertRaisesRegex(ToolError,'MIXED'):self.discover(which=lambda n:('/other/' if n=='javac' else '/jdk/')+n)
 def test_probe_failure_is_redacted(self):
  def fail(p):raise OSError('PRIVATE_PATH')
  with self.assertRaisesRegex(ToolError,'PROBE_FAILED') as c:self.discover(invoke=fail)
  self.assertNotIn('PRIVATE_PATH',str(c.exception))

if __name__=='__main__':unittest.main()
