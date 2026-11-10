import hashlib,io,json,tempfile,time,unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import Mock,patch
import ai_pilot_native_bridge as b
class NativeBridgeTests(unittest.TestCase):
 def test_new_frames_are_exact_and_never_overwrite_existing(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'frame-0.json';v={'op':'before','body':{'question':'동결 질문','target':{'quoteId':'trial'}}}
   b.write_new(p,v);self.assertEqual(b.read_frame(p),v)
   with self.assertRaises(ValueError):b.write_new(p,{'changed':True})
   self.assertEqual(b.read_frame(p),v)
 def test_stop_file_prevents_waiting_for_another_frame(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'frame-0.json';(Path(d)/'host-failed.json').write_text('{}')
   with self.assertRaisesRegex(RuntimeError,'STOPPED'):b.await_file(p,time.monotonic()+10)
 def test_deadline_is_finite(self):
  with tempfile.TemporaryDirectory() as d:
   with self.assertRaisesRegex(RuntimeError,'STOPPED'):b.await_file(Path(d)/'missing',time.monotonic()-1)
 def test_child_cannot_be_used_as_general_local_shell_or_directory(self):
  with self.assertRaisesRegex(ValueError,'CHILD_SCOPE'):b.child(Path('not-the-owned-linux-mount'))
 def test_relay_has_fixed_upstream_and_no_public_listener_or_response_mock(self):
  source=Path(b.__file__).read_text()
  self.assertIn("socket.create_connection(('127.0.0.1',8080)",source);self.assertIn("Server(('127.0.0.1',0)",source)
  self.assertNotIn('0.0.0.0',source);self.assertNotIn('HTTP/1.1 200',source);self.assertIn("container=item['Id']",source)
 def test_cleanup_failure_still_attempts_other_owned_handles(self):
  with tempfile.TemporaryDirectory() as d:
   host=b.NativeHost(Path(d),'app','image','owner',None);host.server=Mock();host.server.shutdown.side_effect=OSError('fixture');connection=Mock();host.connections.add(connection)
   with self.assertRaisesRegex(RuntimeError,'CLEANUP_UNCONFIRMED'):host.__exit__(None,None,None)
   host.server.server_close.assert_called_once();connection.shutdown.assert_called_once();self.assertFalse(json.loads((Path(d)/'native-cleanup.json').read_text())['ownedHandlesConfirmed'])
 def test_cleanup_success_is_recorded_without_other_process_actions(self):
  with tempfile.TemporaryDirectory() as d:
   host=b.NativeHost(Path(d),'app','image','owner',None);host.__exit__(None,None,None)
   self.assertTrue(json.loads((Path(d)/'native-cleanup.json').read_text())['ownedHandlesConfirmed'])
 def test_child_waits_for_host_completion_after_last_ack(self):
  with tempfile.TemporaryDirectory() as d:
   base=Path(d);(base/'v2.private.json').write_text('{"mode":"UNPAID_CONTROLLED_ONLY"}')
   class Folder:
    def resolve(self):return Path('/run/pilot')
    def __truediv__(self,name):return base/name
   frames=[{'op':'before' if i%2==0 else 'after','slot':i//2} for i in range(12)]+[{'complete':True}]
   with patch.object(b.sys,'platform','linux'),patch.dict(b.os.environ,{'AI_PILOT_V2_LINUX_INTERNAL':'true'}),patch.object(b,'await_file',side_effect=frames) as wait,patch.object(b.sys,'stdin',Mock(buffer=io.BytesIO(b'{"ok":true}\n'*12))),redirect_stdout(io.StringIO()):b.child(Folder())
   self.assertEqual(wait.call_count,13);self.assertEqual(wait.call_args.args[0].name,'host-complete.json')
 def test_supervisor_launches_only_manifest_verified_staged_script(self):
  with tempfile.TemporaryDirectory() as d:
   base=Path(d);p=base/'source/deployment/ai_pilot_native_bridge.py';p.parent.mkdir(parents=True);p.write_bytes(b'public-script')
   (base/'source-files.json').write_text(json.dumps({'deployment/ai_pilot_native_bridge.py':hashlib.sha256(p.read_bytes()).hexdigest()}))
   process=Mock();process.poll.return_value=0
   with patch.object(b.subprocess,'Popen',return_value=process) as spawn:
    with b.NativeProcessHost(base,'app','image','owner'):pass
    self.assertEqual(spawn.call_args.args[0][3],str(p));self.assertEqual(spawn.call_args.kwargs['cwd'],base/'source')
 def test_staged_drift_never_starts_native_host(self):
  with tempfile.TemporaryDirectory() as d,patch.object(b.subprocess,'Popen') as spawn:
   base=Path(d);p=base/'source/script';p.parent.mkdir();p.write_bytes(b'changed');(base/'source-files.json').write_text('{"script":"'+('0'*64)+'"}')
   with self.assertRaisesRegex(ValueError,'SOURCE_DRIFT'):b.NativeProcessHost(base,'app','image','owner').__enter__()
   spawn.assert_not_called()
if __name__=='__main__':unittest.main()
