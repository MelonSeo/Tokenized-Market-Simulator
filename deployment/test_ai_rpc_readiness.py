import unittest
from unittest.mock import patch,MagicMock
from ai_rpc_readiness import await_rpc,probe


class ReadinessTest(unittest.TestCase):
    def test_bounded_read_only_wait_records_failure_then_success(self):
        ticks=[0.0];calls=[]
        def send(port,timeout):
            calls.append((port,timeout));ticks[0]+=.1
            return {'ok':len(calls)==2,'stage':'CONNECT' if len(calls)==1 else 'BODY','code':'CONNECTION_REFUSED' if len(calls)==1 else 'CHAIN_ID_OK'}
        result=await_rpc(35000,1,send=send,clock=lambda:ticks[0],sleep=lambda n:ticks.__setitem__(0,ticks[0]+n))
        self.assertTrue(result['ready']);self.assertEqual(len(result['attempts']),2);self.assertTrue(all(t<=1 for _,t in calls))
    def test_deadline_exhaustion_and_invalid_scope(self):
        ticks=[0.0]
        def send(port,timeout):ticks[0]+=timeout;return {'ok':False,'stage':'CONNECT','code':'TIMEOUT'}
        result=await_rpc(35000,1,send=send,clock=lambda:ticks[0],sleep=lambda _:None)
        self.assertFalse(result['ready']);self.assertEqual(len(result['attempts']),1)
        with self.assertRaises(ValueError):await_rpc(8545)
    def test_exact_chain_validation_and_connection_classification(self):
        connection=MagicMock()
        def frame(body):return f'HTTP/1.1 200 OK\r\nContent-Length: {len(body)}\r\n\r\n'.encode()+body
        with patch('ai_rpc_readiness.socket.create_connection',return_value=connection):
            connection.recv.return_value=frame(b'{"id":1,"result":"0x7a69"}')
            self.assertTrue(probe(35000,1)['ok']);self.assertIn(b'eth_chainId',connection.sendall.call_args.args[0]);connection.close.assert_called()
            connection.recv.return_value=frame(b'{"id":1,"result":"0x1"}');self.assertEqual(probe(35000,1)['code'],'WRONG_CHAIN_RESPONSE')
            connection.recv.return_value=frame(b'[]');self.assertEqual(probe(35000,1)['code'],'INVALID_RESPONSE')
        with patch('ai_rpc_readiness.socket.create_connection',side_effect=ConnectionRefusedError()):self.assertEqual(probe(35000,1)['code'],'CONNECTION_REFUSED')
    def test_late_success_is_not_ready(self):
        ticks=[0.0]
        def send(*_):ticks[0]=3;return {'ok':True,'stage':'BODY','code':'CHAIN_ID_OK'}
        result=await_rpc(35000,1,send=send,clock=lambda:ticks[0],sleep=lambda _:None)
        self.assertFalse(result['ready']);self.assertEqual(result['attempts'][0]['code'],'DEADLINE_EXCEEDED')
    def test_slow_body_and_header_cannot_reset_deadline(self):
        for header_only in (False,True):
            ticks=[0.0];connection=MagicMock();calls=[]
            def receive(_):
                ticks[0]+=.4;calls.append(True)
                return b'H' if header_only else b'HTTP/1.1 200 OK\r\nContent-Length: 30\r\n\r\n{' if len(calls)==1 else b' '
            connection.recv.side_effect=receive
            with patch('ai_rpc_readiness.socket.create_connection',return_value=connection):
                result=probe(35000,1,clock=lambda:ticks[0]);self.assertEqual(result['code'],'TIMEOUT');self.assertEqual(len(calls),3);connection.close.assert_called()

if __name__=='__main__':unittest.main()
