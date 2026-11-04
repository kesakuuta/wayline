import assert from 'node:assert/strict';
import { test } from 'node:test';
import { importConfigs, parseShareLink } from '../src/lib/links';
import { extractCountryCode } from '../src/components/CountryFlag';
import { decodeSubscriptionTitle } from '../src/lib/encoding';

test('decodeSubscriptionTitle handles base64, URL, MIME and plain names', () => {
  assert.equal(decodeSubscriptionTitle('base64:UmlvdCBWUE4gKNCe0YHQvdC+0LLQvdC+0Lkp'), 'Riot VPN (Основной)');
  assert.equal(decodeSubscriptionTitle('%D0%A5%D0%B8%D1%82%D0%BE%D1%80%D0%B8'), 'Хитори');
  assert.equal(decodeSubscriptionTitle('=?UTF-8?B?UmlvdCBWUE4=?='), 'Riot VPN');
  assert.equal(decodeSubscriptionTitle("UTF-8''%D0%9C%D0%BE%D0%B9%20VPN"), 'Мой VPN');
  assert.equal(decodeSubscriptionTitle('Обычный профиль'), 'Обычный профиль');
});

test('VLESS REALITY with flow and SNI', () => {
  const s = parseShareLink('vless://12345678-1234-1234-1234-123456789abc@edge.example.com:443?security=reality&pbk=abc&sid=12ab&sni=www.example.com&flow=xtls-rprx-vision#EU');
  assert.equal(s.outbound.type, 'vless');
  assert.equal((s.outbound.tls as any).reality.public_key, 'abc');
  assert.equal(s.name, 'EU');
});

test('VLESS with xhttp transport', () => {
  const s = parseShareLink('vless://12345678-1234-1234-1234-123456789abc@edge.example.com:443?type=xhttp&path=/xhttp-path&host=custom.host&security=tls#NodeX');
  assert.equal(s.outbound.type, 'vless');
  assert.equal((s.outbound.transport as any).type, 'xhttp');
  assert.equal((s.outbound.transport as any).path, '/xhttp-path');
  assert.equal((s.outbound.transport as any).host, 'custom.host');
  assert.equal(s.name, 'NodeX');
});

test('Hysteria2 password, port hopping and obfs', () => {
  const s = parseShareLink('hy2://secret@vpn.example.net:443,10000-20000?sni=cdn.example.net&obfs=salamander&obfs-password=obf#Node');
  assert.equal(s.outbound.type, 'hysteria2');
  assert.equal(s.outbound.server_port, 443);
  assert.deepEqual(s.outbound.obfs, { type: 'salamander', password: 'obf' });
});

test('TUIC protocol link import', () => {
  const s = parseShareLink('tuic://12345678-1234-1234-1234-123456789abc:my-pass@vpn.example.net:8443?sni=tuic.example.net&congestion_control=bbr#TuicNode');
  assert.equal(s.outbound.type, 'tuic');
  assert.equal(s.outbound.server_port, 8443);
  assert.equal(s.outbound.uuid, '12345678-1234-1234-1234-123456789abc');
  assert.equal(s.outbound.password, 'my-pass');
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

test('Country flags are inferred from the server alias', () => {
  const de = parseShareLink('vless://12345678-1234-1234-1234-123456789abc@edge.example.com:443#Germany%20Frankfurt');
  const fi = parseShareLink('vless://12345678-1234-1234-1234-123456789abc@edge.example.com:443#Helsinki%20FI');
  assert.equal(de.flag, '🇩🇪');
  assert.equal(fi.flag, '🇫🇮');
});

test('extractCountryCode handles emojis, codes, and server names', () => {
  assert.equal(extractCountryCode('🇷🇺'), 'RU');
  assert.equal(extractCountryCode('EE'), 'EE');
  assert.equal(extractCountryCode('RU Россия #2'), 'RU');
  assert.equal(extractCountryCode('EE Эстония #1 GRPC'), 'EE');
  assert.equal(extractCountryCode('FR Франция #2'), 'FR');
  assert.equal(extractCountryCode('LV Латвия #1'), 'LV');
  assert.equal(extractCountryCode('Helsinki FI'), 'FI');
});
