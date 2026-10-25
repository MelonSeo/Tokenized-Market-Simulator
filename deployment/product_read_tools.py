"""JDK discovery only; never log installation paths or silently fall back from JAVA_HOME."""
import os,re,shutil,subprocess
from pathlib import Path

class ToolError(RuntimeError):pass

def find_jdk(env=None,platform=None,which=None,invoke=None,is_file=None):
 env=os.environ if env is None else env;platform=os.name if platform is None else platform
 which=which or (lambda name:shutil.which(name,path=env.get('PATH','')))
 is_file=is_file or (lambda path:path.is_file())
 extension='.exe' if platform=='nt' else ''
 if env.get('JAVA_HOME'):
  home=Path(env['JAVA_HOME']);java=home/'bin'/('java'+extension);javac=home/'bin'/('javac'+extension)
 else:
  a,b=which('java'+extension),which('javac'+extension)
  if not a or not b:raise ToolError('PRODUCT_READ_JDK_NOT_FOUND_SET_JAVA_HOME_OR_PATH')
  java,javac=Path(a),Path(b)
 if not is_file(java) or not is_file(javac):raise ToolError('PRODUCT_READ_JDK_EXECUTABLE_MISSING')
 java,javac=java.resolve(),javac.resolve()
 if java.parent!=javac.parent:raise ToolError('PRODUCT_READ_JDK_MIXED_INSTALLATIONS')
 if invoke is None:
  def invoke(path):
   clean={k:v for k,v in env.items() if k.upper() in {'PATH','SYSTEMROOT','WINDIR','TEMP','TMP','HOME','USERPROFILE'}}
   r=subprocess.run([str(path),'-version'],capture_output=True,text=True,timeout=5,env=clean)
   if r.returncode:raise ToolError('PRODUCT_READ_JDK_VERSION_PROBE_FAILED')
   return r.stdout+'\n'+r.stderr
 try:
  a=re.search(r'(?:openjdk|java) version\s+"?([0-9]+(?:\.[0-9]+){0,3})(?:["\s+]|$)',invoke(java))
  b=re.search(r'javac\s+([0-9]+(?:\.[0-9]+){0,3})(?:[\s+]|$)',invoke(javac))
 except Exception:raise ToolError('PRODUCT_READ_JDK_VERSION_PROBE_FAILED') from None
 if not a or not b or a.group(1)!=b.group(1) or a.group(1).split('.')[0]!='21':raise ToolError('PRODUCT_READ_JAVA_21_MATCHING_JDK_REQUIRED')
 return {'java':java,'javac':javac,'version':a.group(1)}
