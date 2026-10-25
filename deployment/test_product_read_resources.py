import ctypes,json,tempfile,unittest
from pathlib import Path
from unittest.mock import Mock
import product_read_resources as r

class Resources(unittest.TestCase):
 def fake(self,available):
  def invoke(s):s.total_phys=16*1024**3;s.avail_phys=available;return 1
  return invoke
 def test_layout(self):self.assertEqual(ctypes.sizeof(r.MemoryStatus),64)
 def test_memory_units_and_timestamps(self):
  x=r.memory(self.fake(r.MIN_RAM));self.assertEqual(x['availableBytes'],1920*1024**2)
  self.assertLessEqual(x['memoryStartedAt'],x['memoryCompletedAt']);self.assertGreater(x['memoryElapsedNs'],0)
 def test_api_false(self):
  with self.assertRaises(r.ResourceError):r.memory(lambda s:0)
 def test_api_exception(self):
  with self.assertRaisesRegex(r.ResourceError,'API_FAILED'):r.memory(Mock(side_effect=OSError('SECRET')))
 def test_invalid_api(self):
  with self.assertRaisesRegex(r.ResourceError,'API_INVALID'):r.memory(lambda s:1)
 def test_available_larger_than_total(self):
  def invalid(s):s.total_phys=1;s.avail_phys=2;return 1
  with self.assertRaises(r.ResourceError):r.memory(invalid)
 def test_exact_thresholds(self):self.assertEqual(r.decision(r.MIN_RAM,r.MIN_DISK)['decision'],'SUFFICIENT')
 def test_ram_one_byte_low(self):self.assertEqual(r.decision(r.MIN_RAM-1,r.MIN_DISK),{'decision':'RAM_LOW','ramShortfallBytes':1,'diskShortfallBytes':0})
 def test_disk_one_byte_low(self):self.assertEqual(r.decision(r.MIN_RAM,r.MIN_DISK-1)['decision'],'DISK_LOW')
 def test_both_low(self):self.assertEqual(r.decision(0,0)['decision'],'RAM_AND_DISK_LOW')
 def test_wmi_conversion(self):self.assertEqual(r.compare(1920*1024,r.MIN_RAM,r.MIN_RAM)['wmiAvailableBytes'],r.MIN_RAM)
 def test_significant_difference(self):self.assertEqual(r.compare(1,r.MIN_RAM,r.MIN_RAM)['status'],'REVIEW_REQUIRED')
 def test_bracket_boundary(self):self.assertEqual(r.compare(1920*1024,r.MIN_RAM,r.MIN_RAM+1)['distanceOutsideBracketBytes'],0)
 def test_threshold_disagreement_is_visible(self):self.assertTrue(r.compare(1920*1024-1,r.MIN_RAM,r.MIN_RAM)['thresholdDecisionDiffers'])
 def test_record_before_rejection(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'sample.jsonl';o=r.Observer(p,lambda:r.memory(self.fake(r.MIN_RAM-1)),lambda:r.MIN_DISK)
   with self.assertRaisesRegex(r.ResourceError,'RAM_LOW'):o.sample('ADMISSION',True)
   x=json.loads(p.read_text());self.assertEqual(x['ramShortfallBytes'],1);self.assertTrue(x['enforced'])
 def test_api_failure_records_and_blocks(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'sample.jsonl';o=r.Observer(p,Mock(side_effect=OSError('SECRET')),lambda:r.MIN_DISK)
   with self.assertRaises(r.ResourceError):o.sample('ADMISSION',True)
   text=p.read_text();self.assertNotIn('SECRET',text);self.assertEqual(json.loads(text)['decision'],'MEASUREMENT_FAILED')
 def test_disk_failure_blocks(self):
  with tempfile.TemporaryDirectory() as d:
   o=r.Observer(Path(d)/'s',lambda:r.memory(self.fake(r.MIN_RAM)),Mock(side_effect=OSError()))
   with self.assertRaises(r.ResourceError):o.sample('ADMISSION',True)
 def test_record_limit(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'s';o=r.Observer(p,lambda:r.memory(self.fake(r.MIN_RAM)),lambda:r.MIN_DISK)
   for i in range(32):o.sample('STAGE')
   with self.assertRaises(r.ResourceError):o.sample('STAGE')
   self.assertEqual(len(p.read_text().splitlines()),32)
 def test_live_guard_has_no_powershell(self):
  text=Path(__file__).with_name('product-read-integration.py').read_text(encoding='utf8')
  self.assertNotIn('Get-CimInstance',text);self.assertIn("observer.sample('ADMISSION',enforce=True)",text)
  self.assertIn("observer.sample('BEFORE_CLAIM',enforce=True)",text)
 def test_observe_is_not_new_midrun_guard(self):
  with tempfile.TemporaryDirectory() as d:
   o=r.Observer(Path(d)/'s',lambda:r.memory(self.fake(1)),lambda:r.MIN_DISK)
   self.assertEqual(o.sample('APP_READY')['decision'],'RAM_LOW');self.assertEqual(o.minimum,1)
 def test_exclusive_record_file(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'s';p.touch()
   with self.assertRaises(FileExistsError):r.Observer(p)
 def test_write_failure_blocks(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'s';o=r.Observer(p,lambda:r.memory(self.fake(r.MIN_RAM)),lambda:r.MIN_DISK);p.unlink();p.mkdir()
   with self.assertRaises(OSError):o.sample('ADMISSION',True)

if __name__=='__main__':unittest.main()
