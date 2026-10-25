"""Small Windows resource samples; no subprocess, polling thread or threshold relaxation."""
import ctypes
import datetime
import json
import os
import shutil
import time

MIB=1024**2
MIN_RAM=1920*MIB
MIN_DISK=2*1024**3

class ResourceError(RuntimeError):pass

class MemoryStatus(ctypes.Structure):
 _fields_=[('length',ctypes.c_uint32),('load',ctypes.c_uint32)]+[(name,ctypes.c_uint64) for name in
  ['total_phys','avail_phys','total_page','avail_page','total_virtual','avail_virtual','avail_extended']]

def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()

def memory(invoke=None):
 start=utc();clock=time.perf_counter_ns();state=MemoryStatus();state.length=ctypes.sizeof(state)
 if invoke is None:
  if os.name!='nt':raise ResourceError('PRODUCT_READ_RESOURCE_PLATFORM')
  api=ctypes.WinDLL('kernel32',use_last_error=True).GlobalMemoryStatusEx
  api.argtypes=[ctypes.POINTER(MemoryStatus)];api.restype=ctypes.c_int
  invoke=lambda s:api(ctypes.byref(s))
 try:ok=invoke(state)
 except Exception:raise ResourceError('PRODUCT_READ_MEMORY_API_FAILED') from None
 if not ok:raise ResourceError('PRODUCT_READ_MEMORY_API_FAILED')
 if state.length!=64 or state.total_phys==0 or state.avail_phys>state.total_phys:raise ResourceError('PRODUCT_READ_MEMORY_API_INVALID')
 return {'memoryStartedAt':start,'memoryCompletedAt':utc(),'memoryElapsedNs':time.perf_counter_ns()-clock,
  'availableBytes':state.avail_phys,'totalPhysicalBytes':state.total_phys}

def decision(available,disk):
 ram=max(0,MIN_RAM-available);space=max(0,MIN_DISK-disk)
 return {'decision':'RAM_AND_DISK_LOW' if ram and space else 'RAM_LOW' if ram else 'DISK_LOW' if space else 'SUFFICIENT',
  'ramShortfallBytes':ram,'diskShortfallBytes':space}

class Observer:
 """At most 32 small JSONL records. Write failure is fatal, never a reason to bypass admission."""
 def __init__(self,path,read_memory=memory,read_disk=None):
  self.path=path;self.read_memory=read_memory;self.read_disk=read_disk or (lambda:shutil.disk_usage('C:\\').free)
  self.count=0;self.minimum=None
  with path.open('x',encoding='utf8'):pass
 def sample(self,stage,enforce=False):
  if self.count>=32:raise ResourceError('PRODUCT_READ_RESOURCE_RECORD_LIMIT')
  event={'stage':stage,'at':utc(),'enforced':enforce,'requiredRAMBytes':MIN_RAM,'requiredDiskBytes':MIN_DISK,
   'availableBytes':None,'diskFreeBytes':None,'ramShortfallBytes':None,'diskShortfallBytes':None}
  failed=False
  try:
   event.update(self.read_memory())
   self.minimum=event['availableBytes'] if self.minimum is None else min(self.minimum,event['availableBytes'])
  except Exception:failed=True;event['memoryRead']='FAILED'
  try:
   space=self.read_disk()
   if not isinstance(space,int) or space<0:raise ResourceError('PRODUCT_READ_DISK_API_INVALID')
   event['diskFreeBytes']=space
  except Exception:failed=True;event['diskRead']='FAILED'
  if failed:event['decision']='MEASUREMENT_FAILED'
  else:event.update(decision(event['availableBytes'],event['diskFreeBytes']))
  # One bounded record; never include an exception message or environment data.
  with self.path.open('a',encoding='utf8') as stream:stream.write(json.dumps(event,separators=(',',':'))+'\n')
  self.count+=1
  if failed:raise ResourceError('PRODUCT_READ_RESOURCE_MEASUREMENT_FAILED')
  if enforce and event['decision']!='SUFFICIENT':raise ResourceError('PRODUCT_READ_RESOURCES_'+event['decision'])
  return event

def compare(wmi_kib,before,after,tolerance=128*MIB):
 """Non-simultaneous samples: compare WMI with its bracketing WinAPI range, in bytes."""
 wmi=wmi_kib*1024;low=min(before,after);high=max(before,after)
 distance=max(low-wmi,wmi-high,0)
 return {'wmiAvailableBytes':wmi,'winApiBeforeBytes':before,'winApiAfterBytes':after,
  'distanceOutsideBracketBytes':distance,'reviewThresholdBytes':tolerance,
  'thresholdDecisionDiffers':(wmi>=MIN_RAM)!=(before>=MIN_RAM) or (wmi>=MIN_RAM)!=(after>=MIN_RAM),
  'status':'REVIEW_REQUIRED' if distance>tolerance else 'WITHIN_REVIEW_THRESHOLD'}
