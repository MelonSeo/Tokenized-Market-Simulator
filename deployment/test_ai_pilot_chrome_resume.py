import json,sqlite3,tempfile,unittest
from pathlib import Path
import ai_pilot_chrome_resume as r

class ResumeTests(unittest.TestCase):
 def test_local_jdbc_keeps_existing_two_and_five_second_timeouts(self):
  good='jdbc:postgresql://pg:5432/exchange_ai_pilot_trade_a?connectTimeout=2&socketTimeout=5'
  self.assertTrue(r.local_database(good))
  for bad in [good.replace('pg:5432','external:5432'),good.replace('socketTimeout=5','socketTimeout=0'),good+'&socketFactory=other',good.replace('pg:5432','u:p@pg:5432'),good+'&socketTimeout=5']:
   self.assertFalse(r.local_database(bad))
 def fixture(self,root):
  s=root/'session';s.mkdir();meta={'mode':'UNPAID_CONTROLLED_ONLY','allocation':'trial','source':'a'*64};(s/'session.json').write_text(json.dumps(meta));c=sqlite3.connect(s/'pilot-v2.sqlite')
  c.executescript('create table identity(value text);create table control(phase int,stopped int);create table submissions(phase text);create table reservations(outcome text);create table phases(name text,complete int);')
  c.execute('insert into identity values(?)',(json.dumps({'mode':'UNPAID','allocation':'trial','source':'a'*64}),));c.execute('insert into control values(6,0)')
  c.executemany('insert into phases values(?,1)',[(n,) for n in ['INDEX_LEGACY','SAFETY_LEGACY','NOVEL30','INDEX_PILOT','SAFETY_C','LATENCY']]);c.commit();return s,c
 def test_valid_web_resume_reads_ledger_without_reset_or_new_allocation(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);s,c=self.fixture(root);c.close();before=r.digest(s/'pilot-v2.sqlite');self.assertEqual(r.validate_web_ledger(root),{'source':'a'*64,'reservations':0});self.assertEqual(before,r.digest(s/'pilot-v2.sqlite'))
 def test_stop_and_paid_mode_cannot_be_upgraded(self):
  for mode in ['stop','paid']:
   with tempfile.TemporaryDirectory() as d:
    root=Path(d);s,c=self.fixture(root);c.close()
    if mode=='stop':(s/'STOP').touch()
    else:(s/'session.json').write_text(json.dumps({'mode':'PAID_EXPLICIT_V2'}))
    with self.assertRaisesRegex(ValueError,'UNPAID_UNSTOPPED'):r.validate_web_ledger(root)
 def test_incomplete_prior_phase_or_prior_web_attempt_is_never_fast_forwarded(self):
  for sql in ['update control set phase=5','update control set stopped=1',"insert into submissions values('WEB_PILOT')","insert into reservations values('UNKNOWN')","update phases set complete=0 where name='LATENCY'"]:
   with tempfile.TemporaryDirectory() as d:
    root=Path(d);s,c=self.fixture(root);c.execute(sql);c.commit();c.close();before=r.digest(s/'pilot-v2.sqlite')
    with self.assertRaises(ValueError):r.validate_web_ledger(root)
    self.assertEqual(before,r.digest(s/'pilot-v2.sqlite'))
 def test_driver_uses_existing_six_and_stops_writer_without_financial_commands(self):
  source=(r.ROOT/'deployment/PilotChromeResume.java').read_text(encoding='utf8')
  for forbidden in ['commands.consume','commands.preview','ledger.initialize','prepare-unpaid']:
   self.assertNotIn(forbidden,source)
  for required in ['setInitMethodName(null)','postProcessBeforeInitialization','worker.close()','executor.isShutdown()','WEB_PILOT','verifyReply(','budgetRows()','frozenDb','frozenC','isEqualTo(6)','ai-linux-browser-proof.mjs']:
   self.assertIn(required,source)
 def test_linux_admission_verifies_both_source_sets_and_keeps_memory_threshold(self):
  source=(r.ROOT/'deployment/ai_pilot_chrome_entry.py').read_text(encoding='utf8')
  self.assertIn("verify_source(Path('/work')",source);self.assertIn('verify_source(driver',source);self.assertIn('1920*1024**2',source);self.assertIn('UNPAID_CONTROLLED_ONLY',source)
 def test_original_volume_is_readonly_and_no_new_ledger_is_prepared(self):
  source=Path(r.__file__).read_text(encoding='utf8')
  for required in ['target=/from,readonly','CHROME_WEB_ALREADY_ATTEMPTED',"claim.open('x'","observer.sample('CHROME_RESUME_BEFORE_BROWSER',True)"]:
   self.assertIn(required,source)
  for forbidden in ['prepare_unpaid','UPDATE control','DELETE FROM','--no-sandbox','--privileged','--cap-add']:
   self.assertNotIn(forbidden,source)

if __name__=='__main__':unittest.main()
