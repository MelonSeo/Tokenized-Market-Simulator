"""Offline wiring regression; complementary to actual HTTP/browser acceptance, never a live PASS."""
import importlib.util
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('product_read_launcher',ROOT/'deployment/product-read-integration.py')
launcher=importlib.util.module_from_spec(spec);spec.loader.exec_module(launcher)

class Connections(unittest.TestCase):
 def read(self,name):return (ROOT/name).read_text(encoding='utf8')
 def test_owned_aliases(self):
  for role in ['app','pg','anvil']:self.assertEqual(launcher.aliases(role),['--network-alias',role])
  for role in ['bootstrap','verify']:self.assertEqual(launcher.aliases(role),[])
 def test_launch_and_inspect_use_aliases(self):
  s=self.read('deployment/product-read-integration.py')
  self.assertIn('*aliases(role),*extra,image',s)
  self.assertIn("require(role in (info['NetworkSettings']['Networks'][run+'-network'].get('Aliases') or [])",s)
 def test_actual_bind_before_ready(self):
  s=self.read('deployment/ProductReadGate.java')
  self.assertIn('getWebServer().getPort()!=8080',s)
  self.assertLess(s.index('socket.connect('),s.index('"ready.json"'))
  self.assertIn('p.setProperty("server.address","0.0.0.0")',self.read('deployment/ProductReadDriver.java'))
 def test_driver_fixed_target_and_bounded(self):
  s=self.read('deployment/ProductReadConnection.java')
  self.assertIn('BASE="http://app:8080"',s)
  self.assertIn('Duration.ofSeconds(10)',s)
  self.assertIn('readNBytes(65537)',s)
  self.assertIn('ProductReadConnection.http(',self.read('deployment/ProductReadDriver.java'))
 def test_browser_same_origin_and_deadlines(self):
  s=self.read('tools/websocket-test-client/scripts/product-read-browser.mjs')
  self.assertIn("assert.equal(c.origin,'http://127.0.0.1:32171')",s)
  self.assertIn('Date.now()+85000',s)
  self.assertIn('DevToolsActivePort',s)
  self.assertIn('about:blank',s)
  self.assertIn('financialRequests:financial?1:0',s)
 def test_pipe_and_product_relative_urls(self):
  self.assertIn('new InetSocketAddress("127.0.0.1",8080)',self.read('deployment/CDirectBrowserPipe.java'))
  self.assertIn('await fetch(path,',self.read('tools/websocket-test-client/src/api.js'))
  self.assertIn("new WebSocket(webSocketUrl('/ws'))",self.read('tools/websocket-test-client/src/websocket.js'))
 def test_source_manifest_covers_connections(self):
  sources={name.replace('\\','/') for name in launcher.source_map()}
  for name in ['ProductReadConnection.java','ProductReadConnectionTest.java','test_product_read_connections.py']:
   self.assertIn('deployment/'+name,sources)

if __name__=='__main__':unittest.main()
