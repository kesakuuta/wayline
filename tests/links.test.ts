import assert from 'node:assert/strict';
import { test } from 'node:test';
import { importConfigs, parseShareLink } from '../src/lib/links';

test('VLESS REALITY with flow and SNI', () => {
  const s = parseShareLink('vless://12345678-1234-1234-1234-123456789abc@edge.example.com:443?security=reality&pbk=abc&sid=12ab&sni=www.example.com&flow=xtls-rprx-vision#EU');
  assert.equal(s.outbound.type, 'vless');
  assert.equal((s.outbound.tls as any).reality.public_key, 'abc');
  assert.equal(s.name, 'EU');
});
test('Hysteria2 password and obfs', () => {
  const s = parseShareLink('hy2://secret@vpn.example.net:443?sni=cdn.example.net&obfs=salamander&obfs-password=obf#Node');
  assert.equal(s.outbound.type, 'hysteria2');
  assert.deepEqual(s.outbound.obfs, { type: 'salamander', password: 'obf' });
});
test('Shadowsocks SIP002 legacy base64', () => {
  const encoded = btoa('aes-256-gcm:secret@ss.example.com:8388');
  const s = parseShareLink(`ss://${encoded}#SS`);
  assert.equal(s.outbound.method, 'aes-256-gcm');
  assert.equal(s.outbound.server, 'ss.example.com');
});
test('Plain and base64 subscriptions, invalid lines isolated', () => {
  const link = 'trojan://pass@vpn.example.net:443#test';
  assert.equal(importConfigs(btoa(link)).servers.length, 1);
  const result = importConfigs(`${link}\nnot a link`);
  assert.equal(result.servers.length, 1);
  assert.equal(result.failed.length, 1);
});
test('sing-box JSON imports real outbounds only', () => {
  const raw = JSON.stringify({ outbounds: [{ type: 'direct', tag: 'direct' }, { type: 'vless', server: 'vpn.example.com', server_port: 443, uuid: 'aaaa', tag: 'my server' }] });
  const result = importConfigs(raw);
  assert.equal(result.servers.length, 1);
  assert.equal(result.servers[0].name, 'my server');
});
test('Unsupported protocol is not silently imported', () => {
  assert.throws(() => importConfigs('wireguard://xxx@example.com:1234'), /Нет импортированных узлов/);
});
