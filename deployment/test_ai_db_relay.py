import copy
import contextlib
import io
import json
import socket
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch, MagicMock

import ai_db_relay as r
import ai_db_fixture as f
import ai_live_run_prepare as live


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.token='a'*32; self.rid='ai_db_probe_'+self.token
        self.meta={'runId':self.rid,'dbName':'exchange_ai_eval_'+self.token,'dbUser':'exchange_ai_eval_'+self.token,
                   'dbPort':59971,'dbImage':'sha256:'+'b'*64}
        self.container={'Id':'c'*64,'Config':{'Labels':{'ai-evaluation.run':self.rid}},'State':{'Running':True},
                        'Image':self.meta['dbImage'],'HostConfig':{'NetworkMode':'none','PortBindings':{},'Privileged':False,'CapAdd':None},
                        'Mounts':[{'Type':'volume','Name':'own','Destination':'/var/lib/postgresql/data'}]}
        self.volume={'Labels':{'ai-evaluation.run':self.rid}}

    def check(self):
        with patch.object(r,'docker_json',side_effect=[[self.container],[self.volume]]):
            return r.verify_target(self.meta)

    def test_owned_none_network_only(self):
        self.assertEqual(self.check(),'c'*64)

    def test_other_owner_denied_before_exec(self):
        self.container['Config']['Labels']['ai-evaluation.run']='another-project'
        with self.assertRaises(ValueError): self.check()

    def test_egress_network_or_published_port_denied(self):
        for field,value in [('NetworkMode','bridge'),('PortBindings',{'5432/tcp':[{}]}),('Privileged',True),('CapAdd',['NET_ADMIN'])]:
            with self.subTest(field=field):
                c=copy.deepcopy(self.container);self.container['HostConfig'][field]=value
                with self.assertRaises(ValueError):self.check()
                self.container=c

    def test_bindmount_and_volume_owner_denied(self):
        self.container['Mounts'][0]['Type']='bind'
        with self.assertRaises(ValueError):self.check()
        self.container['Mounts'][0]['Type']='volume';self.volume['Labels']['ai-evaluation.run']='foreign'
        with self.assertRaises(ValueError):self.check()

    def test_invalid_identity_port_and_db_denied(self):
        for field,value in [('runId','foreign'),('dbPort',5433),('dbPort',True),('dbName','exchange_ai')]:
            with self.subTest(field=field):
                original=dict(self.meta);self.meta[field]=value
                with self.assertRaises(ValueError):self.check()
                self.meta=original

    def test_no_shell_and_only_fixed_database_destination(self):
        self.assertIn("PeerAddr=>'127.0.0.1',PeerPort=>5432",r.PERL)
        self.assertNotIn('system(',r.PERL)
        with self.assertRaises(ValueError):r.Relay(self.meta,0)
        with self.assertRaises(ValueError):r.Relay(self.meta,max_connections=1000)

    def test_connection_alarm_matches_configured_relay_budget(self):
        self.assertNotIn('alarm 600',r.PERL)
        self.assertIn('alarm $limit',r.PERL)
        self.assertIn('$limit<=14400',r.PERL)
        self.assertEqual(r.Relay(self.meta,seconds=3600).seconds,3600)

    def test_loopback_bind_and_automatic_deadline(self):
        listener=MagicMock();listener.accept.side_effect=socket.timeout()
        with patch.object(r,'verify_target',return_value='c'*64),patch.object(r.subprocess,'run'),patch.object(r.socket,'socket',return_value=listener):
            relay=r.Relay(self.meta,seconds=1).start();relay.wait()
            listener.bind.assert_called_once_with(('127.0.0.1',59971))
            listener.setsockopt.assert_not_called()
            self.assertTrue(relay.done.is_set());listener.close.assert_called()

    def test_connection_limit_stops_before_subprocess(self):
        listener=MagicMock();client=MagicMock();listener.accept.return_value=(client,('127.0.0.1',1))
        with patch.object(r,'verify_target',return_value='c'*64),patch.object(r.subprocess,'run'),patch.object(r.socket,'socket',return_value=listener),patch.object(r.Relay,'bridge') as bridge:
            relay=r.Relay(self.meta,seconds=2,max_connections=1).start();relay.wait()
            self.assertEqual(bridge.call_count,1);self.assertTrue(relay.done.is_set())

    def test_probe_preparation_has_no_approval_key_or_ledger(self):
        with tempfile.TemporaryDirectory() as folder:
            m=f.prepare(folder,59971,'sha256:'+'b'*64)
            self.assertEqual(m['mode'],'offline-database-probe')
            self.assertEqual(set(p.name for p in Path(folder).iterdir()),{'fixture.json','db.env','compose.json'})
            self.assertIn('POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256',(Path(folder)/'db.env').read_text())
            config=f.compose(m);service=config['services']['ai-postgres']
            self.assertEqual(service['network_mode'],'none');self.assertNotIn('ports',service);self.assertNotIn('networks',config)
            with self.assertRaises(ValueError):f.prepare(folder,59971,'sha256:'+'b'*64)

    def test_live_preparation_is_none_scram_and_explicit_key_only_no_sender(self):
        with tempfile.TemporaryDirectory() as folder:
            meta={**self.meta,'mode':'live','sourceHash':'c'*64,'corpusHash':'d'*64}
            with patch.object(live,'load_run',return_value=(Path(folder),meta)),patch.object(live,'read_key',return_value='offline-placeholder') as key,\
                 patch('sys.argv',['prepare','--run-dir',folder,'--credentials-file','explicit-only','--image',self.meta['dbImage']]),contextlib.redirect_stdout(io.StringIO()) as out:
                live.main()
            key.assert_called_once_with('explicit-only')
            self.assertNotIn('offline-placeholder',out.getvalue())
            config=json.loads((Path(folder)/'compose.json').read_text());service=config['services']['ai-postgres']
            self.assertEqual(service['network_mode'],'none');self.assertNotIn('ports',service)
            self.assertIn('POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256',(Path(folder)/'db.env').read_text())
            self.assertEqual(json.loads((Path(folder)/'relay.json').read_text())['dbImage'],self.meta['dbImage'])

    def test_live_preparation_refuses_existing_material_before_reading_key(self):
        with tempfile.TemporaryDirectory() as folder:
            (Path(folder)/'approval.json').touch()
            with patch.object(live,'load_run',return_value=(Path(folder),{**self.meta,'mode':'live'})),patch.object(live,'read_key') as key,\
                 patch('sys.argv',['prepare','--run-dir',folder,'--credentials-file','explicit-only','--image',self.meta['dbImage']]):
                with self.assertRaises(SystemExit):live.main()
                key.assert_not_called()


if __name__=='__main__':unittest.main()
