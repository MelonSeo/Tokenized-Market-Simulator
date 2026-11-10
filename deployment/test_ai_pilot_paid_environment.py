"""No key files, provider sockets or paid approval files. In-memory fake permits only."""
import hashlib
import http.client
import json
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import ai_pilot_egress as relay
import ai_pilot_paid_environment as environment
import ai_pilot_paid_transport as transport
from ai_evaluation_broker import _https_sender


class EnvironmentTests(unittest.TestCase):
    def test_child_environment_does_not_read_excluded_key_values(self):
        import ai_pilot_unpaid_live as live
        class Env:
            def __iter__(self):return iter(['PATH','OPENAI_API_KEY','OTHER_SECRET'])
            def __getitem__(self,key):
                self.assert_only_path(key)
                return 'CONTROLLED_PATH'
            @staticmethod
            def assert_only_path(key):
                if key!='PATH':raise AssertionError('EXCLUDED_ENV_VALUE_READ')
        with patch.object(live.os,'environ',Env()):
            value=live.env()
        self.assertEqual(value['PATH'],'CONTROLLED_PATH')
        self.assertNotIn('OPENAI_API_KEY',value)

    def plan(self):
        return environment.topology('pilot-paid-'+'a'*16, 'sha256:'+'b'*64, 'sha256:'+'c'*64,
                                    internal_subnet='10.254.250.0/28',outbound_subnet='10.254.250.16/28')

    def test_topology_keeps_pg_localhost_and_app_internal_without_public_ports(self):
        p = self.plan(); args = p['commands'][-1]
        self.assertEqual(args[args.index('--network')+1], 'container:'+p['names']['pg'])
        self.assertTrue(all(u.startswith('jdbc:postgresql://127.0.0.1:5432/') for u in p['jdbc'].values()))
        flat = [x for c in p['commands'] for x in c]
        for denied in ('--publish', '-p', '--privileged', '--cap-add', '--no-sandbox', '--network=host'):
            self.assertNotIn(denied, flat)
        self.assertNotIn('docker', flat[flat.index('connect')+1:flat.index('connect')+3])
        self.assertEqual(p['budgetAuthority'], hashlib.sha256((p['jdbc']['budget']+'\npilot').encode()).hexdigest())

    def test_foreign_owner_and_unpinned_images_reject(self):
        for owner, tools, pg in [('existing-user', 'sha256:'+'b'*64, 'sha256:'+'c'*64),
                                 ('pilot-paid-'+'a'*16, 'latest', 'sha256:'+'c'*64)]:
            with self.assertRaises(ValueError):
                environment.topology(owner, tools, pg,internal_subnet='10.254.250.0/28',outbound_subnet='10.254.250.16/28')

    def test_unverified_or_overlapping_subnet_shapes_reject(self):
        for a,b in [('10.254.0.0/28','10.254.0.0/28'),('192.168.1.0/28','10.254.0.0/28'),('10.254.0.0/24','10.254.1.0/28')]:
            with self.assertRaises(ValueError):
                environment.topology('pilot-paid-'+'a'*16,'sha256:'+'b'*64,'sha256:'+'c'*64,internal_subnet=a,outbound_subnet=b)

    def test_blueprint_and_preview_never_assert_live_execution_ready(self):
        p = self.plan()
        self.assertFalse(p['approvalIssued']); self.assertFalse(p['evaluationDriverReady'])
        self.assertEqual(len(p['unresolved']), 3)
        self.assertFalse(environment.preview()['activationAllowed'])

    def test_unapproved_boundary_never_invokes_launcher(self):
        launch = Mock()
        with patch('ai_pilot_paid_sender.sys.platform', 'linux'):
            with self.assertRaisesRegex(ValueError, 'V2_PAID_APPROVAL_REQUIRED'):
                environment.guarded_launch({'mode': 'UNPAID_CONTROLLED_ONLY'}, {}, 'a'*64, launch)
        launch.assert_not_called()


class TunnelTests(unittest.TestCase):
    def test_paid_access_checks_both_models_before_first_post_and_only_once(self):
        events=[]
        def connect(timeout):
            c=Mock();c.sock=None
            def request(method,path,**kw):events.append((method,path))
            c.request.side_effect=request
            model=('gpt-5.6-terra','text-embedding-3-small')[len(events)]
            r=Mock(status=200);r.read1.side_effect=[json.dumps({'id':model}).encode(),b'']
            c.getresponse.return_value=r
            return c
        with tempfile.TemporaryDirectory() as tmp,patch.object(transport,'connection',side_effect=connect),patch('ai_evaluation_broker._https_sender') as factory:
            factory.return_value.side_effect=lambda *a:events.append(('POST',a[0])) or {'ok':True}
            send=transport.sender(tmp,{'expiresAt':9999999999},'FAKE_ONLY')
            send('embeddings',b'{}',5);send('responses',b'{}',5)
            self.assertEqual(events,[('GET','/v1/models/gpt-5.6-terra'),('GET','/v1/models/text-embedding-3-small'),('POST','embeddings'),('POST','responses')])
            proof=json.loads((Path(tmp)/'provider-access.private.json').read_text())
            self.assertEqual(proof['models'],['gpt-5.6-terra','text-embedding-3-small'])
            self.assertNotIn('FAKE_ONLY',json.dumps(proof))

    def test_access_denial_or_wrong_identity_never_posts_or_retries(self):
        for status,identity in [(403,'gpt-5.6-terra'),(200,'different-model')]:
            with self.subTest(status=status),tempfile.TemporaryDirectory() as tmp,patch.object(transport,'connection') as connect,patch('ai_evaluation_broker._https_sender') as factory:
                c=connect.return_value;c.sock=None;r=c.getresponse.return_value;r.status=status
                r.read1.side_effect=[json.dumps({'id':identity}).encode(),b'']
                send=transport.sender(tmp,{'expiresAt':9999999999},'FAKE_ONLY')
                with self.assertRaises(ValueError):send('embeddings',b'{}',5)
                with self.assertRaisesRegex(ValueError,'V2_PROVIDER_ACCESS_PRECHECK_FAILED'):send('embeddings',b'{}',5)
                factory.return_value.assert_not_called();self.assertEqual(connect.call_count,1)
                c.close.assert_called_once()
                proof=json.loads((Path(tmp)/'provider-access.private.json').read_text())
                self.assertEqual(proof['httpStatus'],status)
                self.assertEqual(proof['failureCode'],'V2_PROVIDER_MODEL_ACCESS_DENIED' if status==403 else 'V2_PROVIDER_MODEL_IDENTITY')

    def test_access_consumes_existing_deadline_not_a_new_post_budget(self):
        with tempfile.TemporaryDirectory() as tmp,patch.object(transport,'connection') as connect,patch('ai_evaluation_broker._https_sender') as factory,patch.object(transport.time,'monotonic',side_effect=[10.,10.,10.,12.,12.]):
            c=connect.return_value;c.sock=None
            send=transport.sender(tmp,{'expiresAt':9999999999},'FAKE_ONLY')
            with self.assertRaisesRegex(ValueError,'V2_CALL_DEADLINE'):send('embeddings',b'{}',1)
            c.close.assert_called_once();factory.return_value.assert_not_called()

    def test_metadata_connection_errors_preserve_safe_failure_category(self):
        for error,code in [(ssl.SSLError('SENSITIVE_TEST_DETAIL'),'TLS_VERIFICATION_FAILED'),
                           (TimeoutError('SENSITIVE_TEST_DETAIL'),'METADATA_TIMEOUT'),
                           (OSError('SENSITIVE_TEST_DETAIL'),'METADATA_CONNECTION_FAILED')]:
            with self.subTest(code=code),tempfile.TemporaryDirectory() as tmp,patch.object(transport,'connection',side_effect=error),patch('ai_evaluation_broker._https_sender') as factory:
                send=transport.sender(tmp,{'expiresAt':9999999999},'FAKE_ONLY')
                with self.assertRaises(type(error)):send('embeddings',b'{}',1)
                proof=json.loads((Path(tmp)/'provider-access.private.json').read_text())
                self.assertEqual(proof['failureCode'],code)
                self.assertEqual(proof['activeModel'],'gpt-5.6-terra')
                self.assertNotIn('SENSITIVE_TEST_DETAIL',json.dumps(proof))
                factory.return_value.assert_not_called()

    def roundtrip(self, request, approved=False, responder=None):
        a, b = socket.socketpair(); dial = Mock()
        peers = []
        if responder:
            c, d = socket.socketpair(); peers = [c, d]; dial.return_value = c
            worker = threading.Thread(target=responder, args=(d,), daemon=True); worker.start()
        def work():
            with b:
                relay.handle(b, approved=approved, dial=dial, limit=.5)
        t = threading.Thread(target=work); t.start(); a.settimeout(2)
        try:
            a.sendall(request); response = a.recv(8192)
            if b'200 Connection' in response:
                a.sendall(b'opaque-test'); response += a.recv(8192)
        finally:
            a.close(); t.join(2)
            for s in peers: s.close()
        self.assertFalse(t.is_alive())
        return response, dial

    def test_default_denies_exact_provider_before_dns_or_dial(self):
        r, d = self.roundtrip(b'CONNECT api.openai.com:443 HTTP/1.1\r\nHost: api.openai.com:443\r\n\r\n')
        self.assertIn(b'403', r); d.assert_not_called()

    def test_non_provider_method_headers_ports_and_targets_deny_even_when_enabled(self):
        for request in [b'GET /v1/models HTTP/1.1\r\n\r\n', b'CONNECT localhost:443 HTTP/1.1\r\n\r\n',
                        b'CONNECT api.openai.com:80 HTTP/1.1\r\n\r\n', b'CONNECT api.openai.com:443@evil HTTP/1.1\r\n\r\n',
                        b'CONNECT api.openai.com:443 HTTP/1.1\r\nAuthorization: PRIVATE_TEST_VALUE\r\n\r\n',
                        b'CONNECT api.openai.com:443 HTTP/1.1\r\nHost: evil\r\n\r\n']:
            with self.subTest(request=request[:25]):
                r, d = self.roundtrip(request, approved=True)
                self.assertIn(b'403', r); d.assert_not_called()

    def test_tunnel_transfers_opaque_bytes_with_one_fake_connection(self):
        def echo(s):
            s.settimeout(1); s.sendall(s.recv(1024).upper())
        r, d = self.roundtrip(b'CONNECT api.openai.com:443 HTTP/1.0\r\n\r\n', approved=True, responder=echo)
        self.assertIn(b'OPAQUE-TEST', r); d.assert_called_once()

    def test_oversize_headers_deny_without_dial(self):
        r, d = self.roundtrip(b'X'*4097)
        self.assertIn(b'403', r); d.assert_not_called()

    def test_dns_private_addresses_block_before_connect(self):
        with patch.object(relay, 'resolve_provider', return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('127.0.0.1', 443))]), patch.object(relay.socket, 'socket') as s:
            with self.assertRaises(OSError): relay.dial_provider(1)
            s.assert_not_called()

    def test_dns_child_deadline_kills_and_reaps_only_dummy_process(self):
        children=[]
        def spawn(args,**options):
            self.assertIn('-I',args);self.assertIn('-S',args)
            self.assertNotIn('OPENAI_API_KEY',options['env'])
            # Replace DNS with an entirely local sleeping child, never contact a provider.
            c=subprocess.Popen([sys.executable,'-I','-B','-S','-c','import time;time.sleep(20)'],**options)
            children.append(c);return c
        with self.assertRaisesRegex(TimeoutError,'EGRESS_DNS_DEADLINE'):
            relay.resolve_provider(.03,spawn=spawn)
        self.assertEqual(len(children),1);self.assertIsNotNone(children[0].poll())

    def test_connect_uses_remaining_budget_after_dns_not_original_timeout(self):
        infos=[(socket.AF_INET,socket.SOCK_STREAM,6,'',('8.8.8.8',443))]
        with patch.object(relay,'resolve_provider',return_value=infos),patch.object(relay.time,'monotonic',side_effect=[10.,10.75]),patch.object(relay.socket,'socket') as s:
            relay.dial_provider(1.)
            s.return_value.settimeout.assert_called_once_with(.25)
            s.return_value.connect.assert_called_once_with(('8.8.8.8',443))

    def test_expired_dns_budget_closes_socket_without_connect_or_retry(self):
        infos=[(socket.AF_INET,socket.SOCK_STREAM,6,'',('8.8.8.8',443))]
        with patch.object(relay,'resolve_provider',return_value=infos),patch.object(relay.time,'monotonic',side_effect=[10.,12.]),patch.object(relay.socket,'socket') as s:
            with self.assertRaises(TimeoutError):relay.dial_provider(1.)
            s.return_value.connect.assert_not_called();s.return_value.close.assert_called_once()

    def test_client_verifies_provider_tls_hostname_through_fixed_proxy(self):
        with patch.object(transport.http.client, 'HTTPSConnection') as factory:
            transport.connection(1.25)
            args, kwargs = factory.call_args
            self.assertEqual(args, ('pilot-egress', 8443)); self.assertEqual(kwargs['timeout'], 1.25)
            self.assertTrue(kwargs['context'].check_hostname)
            self.assertEqual(kwargs['context'].verify_mode, ssl.CERT_REQUIRED)
            factory.return_value.set_tunnel.assert_called_once_with('api.openai.com', 443)

    def test_transport_rejects_metadata_and_invalid_deadlines_before_factory(self):
        factory = Mock()
        with tempfile.TemporaryDirectory() as tmp:
            send = _https_sender(Path(tmp), {'expiresAt': 9999999999}, 'FAKE_ONLY', connection_factory=factory)
            for path, limit in [('models', 1), ('responses', 0), ('responses', 61)]:
                with self.assertRaises(Exception): send(path, b'{}', limit)
        factory.assert_not_called()

    def test_proxy_worker_admission_has_no_unbounded_thread_queue(self):
        with relay.Relay(('127.0.0.1', 0)) as s:
            self.assertFalse(s.approved)
            self.assertTrue(s.slots.acquire(False)); self.assertTrue(s.slots.acquire(False))
            self.assertFalse(s.slots.acquire(False)); s.slots.release(); s.slots.release()


if __name__ == '__main__': unittest.main()
