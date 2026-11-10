"""Linux admission and immutable driver/source verification before the web-only JVM."""
import hashlib,json,os,sys
from pathlib import Path
if sys.platform!='linux' or os.environ.get('AI_PILOT_BROWSER_RESUME')!='true':raise RuntimeError('CHROME_ENTRY_SCOPE')
sys.path.insert(0,'/work/deployment')
from ai_pilot_linux_inner import verify_source
from product_read_linux_support import memory
run=Path('/run/pilot');driver=Path('/driver')
if json.loads((run/'v2.private.json').read_text()).get('mode')!='UNPAID_CONTROLLED_ONLY':raise RuntimeError('CHROME_UNPAID_ONLY')
m=memory()
if m['availableBytes']<1920*1024**2:raise RuntimeError('CHROME_LINUX_RAM_LOW')
verify_source(Path('/work'),json.loads((run/'source-files.json').read_text()))
verify_source(driver,json.loads((run/'driver-files.json').read_text()))
(run/'linux-admission.json').write_text(json.dumps({'memory':m,'sourceHashesMatch':True,'driverHashesMatch':True,'paidCalls':0,'apiKeyReads':0}))
command=json.loads((run/'java-command.json').read_text())
if command[0]!='java' or command[-1]!='com.pricetrack.exchange.futures.chain.PilotChromeResume':raise RuntimeError('CHROME_JVM_SCOPE')
os.execvp('java',command)
