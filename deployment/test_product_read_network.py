import json,subprocess,unittest
from types import SimpleNamespace
from product_read_network import command,create_args,decode,inventory,proc_routes,safe_stream
from c_gate_network import select_subnet

class NetworkTests(unittest.TestCase):
    def test_proc_little_endian_and_default(self):
        out=proc_routes('Iface Destination Gateway Flags RefCnt Use Metric Mask\neth0 00000000 017012AC 0003 0 0 0 00000000\neth0 007012AC 00000000 0001 0 0 0 00F0FFFF\n')
        self.assertEqual(out,['0.0.0.0/0','172.18.112.0/20'])
    def test_malformed_proc_fails_closed(self):
        for text in ['', 'no header', 'Iface Destination Mask\neth0 secret 0\n']:
            with self.assertRaises(ValueError):proc_routes(text)
    def test_wsl_utf16(self):self.assertEqual(decode('Ubuntu\r\n'.encode('utf-16-le')),'Ubuntu\r\n')
    def test_selector_excludes_docker_windows_and_wsl(self):
        out=select_subnet(['10.254.0.0/28'],['0.0.0.0/0','10.254.0.16/28','10.254.0.32/28'])
        self.assertEqual(out,'10.254.0.48/28')
    def test_selector_exhaustion(self):
        with self.assertRaises(ValueError):select_subnet([],['10.0.0.0/8'])
    def test_explicit_internal_single_create(self):
        args=create_args('ccpg-'+'1'*16,'10.254.0.48/28');self.assertIn('--internal',args);self.assertEqual(args.count('create'),1);self.assertIn('--subnet',args)
        for subnet in ['0.0.0.0/0','10.254.0.1/28','192.168.0.0/28','10.254.0.0/24']:
            with self.assertRaises(ValueError):create_args('ccpg-'+'1'*16,subnet)
    def test_diagnostics_redact_arbitrary_secrets(self):
        for text in ['JWT=fixture-not-real','password=fixture-only','private-key=fixture-only']:
            self.assertNotIn(text,safe_stream(text.encode()));self.assertNotIn(text,safe_stream(text.encode(),True))
    def test_known_daemon_error_is_retained_without_suffix(self):
        out=safe_stream(b'Error response from daemon: all predefined address pools have been fully subnetted\npassword=fixture-only')
        self.assertEqual(out,'ADDRESS_POOL_EXHAUSTED: all predefined address pools have been fully subnetted')
    def test_no_retry_and_failure_exit_code(self):
        calls=[]
        def fake(args,timeout):calls.append(args);return SimpleNamespace(returncode=1,stdout=b'',stderr=b'Pool overlaps with other one on this address space')
        result,record=command(fake,'NETWORK_CREATE',['docker','network','create']);self.assertEqual(len(calls),1);self.assertEqual(record['exitCode'],1);self.assertIn('SUBNET_OVERLAP',record['stderr'])
    def test_timeout_remains_uncertain_without_output(self):
        def fake(args,timeout):raise subprocess.TimeoutExpired(args,timeout,output=b'JWT=fixture-only')
        result,record=command(fake,'NETWORK_CREATE',['docker']);self.assertIsNone(result);self.assertIsNone(record['exitCode']);self.assertNotIn('JWT',str(record))
    def test_inventory_reads_routes_without_network_create(self):
        calls=[]
        def fake(args,timeout):
            calls.append(args)
            if args[:3]==['docker','network','ls']:out=b'a'*12
            elif args[:3]==['docker','network','inspect']:out=json.dumps([{'IPAM':{'Config':[{'Subnet':'10.254.0.0/28'}]}}]).encode()
            elif args[0]=='powershell':out=b'["0.0.0.0/0","10.254.0.16/28"]'
            elif '--list' in args:out='Ubuntu\r\n'.encode('utf-16-le')
            else:out=b'Iface Destination Gateway Flags RefCnt Use Metric Mask\neth0 2000FE0A 0 1 0 0 0 F0FFFFFF\n'
            return SimpleNamespace(returncode=0,stdout=out,stderr=b'')
        plan=inventory(fake);self.assertEqual(plan['subnet'],'10.254.0.48/28');self.assertTrue(plan['collisionFree'])
        self.assertFalse(any('create' in a or 'shutdown' in a or 'terminate' in a for a in calls))
    def test_inventory_failure_blocks_selection(self):
        def fake(args,timeout):return SimpleNamespace(returncode=1,stdout=b'',stderr=b'password=fixture-only')
        with self.assertRaisesRegex(ValueError,'NETWORK_INVENTORY_DOCKER_FAILED'):inventory(fake)
if __name__=='__main__':unittest.main()
