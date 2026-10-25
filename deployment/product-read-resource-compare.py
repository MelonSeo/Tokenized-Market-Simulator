"""Bounded diagnostic only. WMI subprocess is never used by live admission."""
import json,os,queue,subprocess,threading,time,uuid
from pathlib import Path
import product_read_resources as r

ROOT=Path(__file__).resolve().parents[1]
def main():
 out=ROOT/'deployment/runtime/product-read'/('resource-compare-'+uuid.uuid4().hex[:12]);out.mkdir()
 samples=[]
 command="$s=[Diagnostics.Stopwatch]::StartNew();$o=Get-CimInstance Win32_OperatingSystem;$t=[DateTime]::UtcNow.ToString('o');$p=Get-Process -Id $PID;[pscustomobject]@{at=$t;availableKiB=[uint64]$o.FreePhysicalMemory;elapsedMs=$s.Elapsed.TotalMilliseconds;workingSetBytes=$p.WorkingSet64;peakWorkingSetBytes=$p.PeakWorkingSet64}|ConvertTo-Json -Compress;[Console]::Out.Flush();[void][Console]::In.ReadLine()"
 env={k:v for k,v in os.environ.items() if k.upper() in {'PATH','PATHEXT','SYSTEMROOT','WINDIR','COMSPEC','TEMP','TMP','USERPROFILE','APPDATA','LOCALAPPDATA','PROGRAMFILES'}}
 for index in range(3):
  before=r.memory();start=time.perf_counter_ns()
  child=subprocess.Popen(['powershell','-NoProfile','-Command',command],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,env=env)
  lines=queue.Queue(1);reader=threading.Thread(target=lambda:lines.put(child.stdout.readline()),daemon=True);reader.start()
  try:
   wmi=json.loads(lines.get(timeout=12).decode('utf8'));during=r.memory()
   child.communicate(b'\n',timeout=3)
   if child.returncode!=0:raise r.ResourceError('PRODUCT_READ_WMI_COMPARE_FAILED')
  finally:
   if child.poll() is None:child.kill();child.wait(timeout=3)
   reader.join(1)
  elapsed=time.perf_counter_ns()-start;after=r.memory()
  comparison=r.compare(wmi['availableKiB'],during['availableBytes'],during['availableBytes'])
  samples.append({'index':index,'before':before,'wmi':wmi,'winApiWhileWmiProcessAlive':during,'after':after,'wmiProcessElapsedNs':elapsed,**comparison})
 result={'samples':samples,'status':'REVIEW_REQUIRED' if any(s['status']=='REVIEW_REQUIRED' or s['thresholdDecisionDiffers'] for s in samples) else 'COMPARISON_WITHIN_THRESHOLD',
  'note':'Non-simultaneous samples; WMI child itself consumes RAM. No threshold change.'}
 (out/'result.json').write_text(json.dumps(result,indent=2),encoding='utf8')
 print(json.dumps({'evidence':out.name,**result}))
if __name__=='__main__':main()
