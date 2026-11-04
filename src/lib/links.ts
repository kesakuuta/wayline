import type { Server } from './types';
import { decodeSubscriptionTitle } from './encoding';

export type ImportedServer = Server & { outbound: Record<string, unknown> };
type AnyJson = Record<string, unknown>;

const isObject = (value: unknown): value is AnyJson => Boolean(value) && typeof value === 'object' && !Array.isArray(value);
const display = (host: string) => host.replace(/^\[|\]$/g, '');
const string = (v: unknown) => typeof v === 'string' ? v : '';
const positivePort = (port: unknown) => {
  const n = Number(port);
  if (!Number.isInteger(n) || n < 1 || n > 65535) throw new Error('Некорректный порт сервера');
  return n;
};
const decodeB64 = (input: string) => {
  const data = input.replace(/-/g, '+').replace(/_/g, '/').replace(/\s/g, '');
  const bytes = Uint8Array.from(atob(data.padEnd(Math.ceil(data.length / 4) * 4, '=')), c => c.charCodeAt(0));
  return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
};
const decodeName = (fragment: string) => {
  try {
    const raw = decodeURIComponent(fragment.replace(/^#/, ''));
    return decodeSubscriptionTitle(raw) || raw;
  } catch {
    const raw = fragment.replace(/^#/, '');
    return decodeSubscriptionTitle(raw) || raw;
  }
};
const truth = (v: string | null) => v === '1' || v === 'true';

function tlsParams(p: URLSearchParams, enable = false): AnyJson | undefined {
  const security = p.get('security');
  if (!enable && security !== 'tls' && security !== 'reality') return undefined;
  const tls: AnyJson = { enabled: true };
  const sni = p.get('sni') || p.get('peer') || p.get('serverName');
  if (sni) tls.server_name = sni;
  if (truth(p.get('insecure')) || truth(p.get('allowInsecure'))) tls.insecure = true;
  const alpn = p.get('alpn');
  if (alpn) tls.alpn = alpn.split(',').filter(Boolean);
  const fingerprint = p.get('fp') || p.get('fingerprint');
  if (fingerprint && fingerprint !== 'none') tls.utls = { enabled: true, fingerprint };
  if (security === 'reality') {
    const publicKey = p.get('pbk');
    if (!publicKey) throw new Error('REALITY: отсутствует public key (pbk)');
    tls.reality = { enabled: true, public_key: publicKey, short_id: p.get('sid') || '' };
  }
  return tls;
}

function transport(p: URLSearchParams): AnyJson | undefined {
  const type = (p.get('type') || p.get('net') || p.get('network') || 'tcp').toLowerCase();
  if (type === 'tcp' || type === 'none' || type === 'raw') return undefined;
  if (type === 'ws' || type === 'websocket') {
    const headers: AnyJson = {};
    if (p.get('host')) headers.Host = p.get('host');
    const ws: AnyJson = { type: 'ws', path: p.get('path') || '/' };
    if (Object.keys(headers).length) ws.headers = headers;
    return ws;
  }
  if (type === 'grpc') return { type: 'grpc', service_name: p.get('serviceName') || p.get('service_name') || '' };
  if (type === 'httpupgrade') return { type: 'httpupgrade', path: p.get('path') || '/', host: p.get('host') || '' };
  if (type === 'http' || type === 'h2') return { type: 'http', path: p.get('path') || '/', host: p.get('host') ? [p.get('host')] : [] };
  if (type === 'xhttp' || type === 'splithttp') {
    return { type: 'xhttp', path: p.get('path') || '/', host: p.get('host') || '', mode: p.get('mode') || 'auto' };
  }
  return { type, path: p.get('path') || '/', host: p.get('host') || '' };
}

const countryNames: Record<string, string[]> = {
  RU: ['russia', 'россия', 'russian', 'moscow', 'москва'],
  US: ['united states', 'usa', 'america', 'сша', 'new york', 'los angeles'],
  GB: ['united kingdom', 'great britain', 'britain', 'england', 'london', 'uk'],
  DE: ['germany', 'deutschland', 'германия', 'frankfurt', 'berlin'],
  NL: ['netherlands', 'holland', 'нидерланды', 'amsterdam'],
  FI: ['finland', 'suomi', 'финляндия', 'helsinki'],
  FR: ['france', 'франция', 'paris'],
  SE: ['sweden', 'sverige', 'швеция', 'stockholm'],
  NO: ['norway', 'norge', 'норвегия', 'oslo'],
  PL: ['poland', 'polska', 'польша', 'warsaw'],
  CZ: ['czech', 'czechia', 'чехия', 'prague'],
  CH: ['switzerland', 'schweiz', 'швейцария', 'zurich'],
  AT: ['austria', 'österreich', 'австрия', 'vienna'],
  IT: ['italy', 'италия', 'rome', 'milan'],
  ES: ['spain', 'españa', 'испания', 'madrid'],
  PT: ['portugal', 'португалия', 'lisbon'],
  TR: ['turkey', 'türkiye', 'турция', 'istanbul'],
  EE: ['estonia', 'eesti', 'эстония', 'tallinn'],
  LV: ['latvia', 'латвия', 'riga'],
  LT: ['lithuania', 'литва', 'vilnius'],
  UA: ['ukraine', 'украина', 'kyiv'],
  KZ: ['kazakhstan', 'казахстан', 'almaty'],
  SG: ['singapore', 'сингапур'],
  JP: ['japan', 'япония', 'tokyo'],
  KR: ['south korea', 'korea', 'корея', 'seoul'],
  HK: ['hong kong', 'гонконг'],
  TW: ['taiwan', 'тайвань', 'taipei'],
  CA: ['canada', 'канада', 'toronto'],
  AU: ['australia', 'австралия', 'sydney'],
  BR: ['brazil', 'brasil', 'бразилия'],
  AE: ['uae', 'united arab emirates', 'эмираты', 'dubai'],
  IN: ['india', 'индия', 'mumbai'],
  ID: ['indonesia', 'индонезия', 'jakarta'],
  TH: ['thailand', 'таиланд', 'bangkok'],
  VN: ['vietnam', 'вьетнам', 'hanoi']
};
const countryFlag = (code: string) => {
  const normalized = code.toUpperCase();
  if (normalized === 'EU') return '🇪🇺';
  if (normalized.length !== 2) return '🌐';
  return String.fromCodePoint(...[...normalized].map(char => 0x1f1e6 + char.charCodeAt(0) - 65));
};
function inferCountryFlag(alias: string, host: string): string {
  const source = alias.trim();
  const emoji = source.match(/[\u{1F1E6}-\u{1F1FF}]{2}/u);
  if (emoji) return emoji[0];
  const normalized = source.toLowerCase();
  for (const [code, names] of Object.entries(countryNames)) {
    if (names.some(name => normalized.includes(name))) return countryFlag(code);
    const token = new RegExp('(?:^|[^a-z])' + code + '(?:$|[^a-z])', 'i');
    if (token.test(source)) return countryFlag(code);
  }
  if (/\bEU\b/i.test(source)) return countryFlag('EU');
  const tld = host.toLowerCase().match(/\.([a-z]{2})$/)?.[1]?.toUpperCase();
  if (tld && countryNames[tld]) return countryFlag(tld);
  return '🌐';
}

function asServer(outbound: AnyJson, name: string): ImportedServer {
  const host = string(outbound.server);
  if (!host || /[\s\x00-\x1f]/.test(host)) throw new Error('Некорректный адрес сервера');
  const port = positivePort(outbound.server_port);
  const type = string(outbound.type);
  if (!['vless', 'vmess', 'trojan', 'shadowsocks', 'hysteria2', 'tuic', 'anytls', 'socks', 'http'].includes(type)) throw new Error(`Протокол ${type} пока не поддерживается`);
  const secret = type === 'vless' || type === 'vmess' ? string(outbound.uuid) : type === 'shadowsocks' || type === 'trojan' || type === 'hysteria2' || type === 'tuic' ? string(outbound.password) : 'provided';
  if (!secret) throw new Error(`Отсутствуют учётные данные: ${type}`);
  const label = decodeSubscriptionTitle(name) || `${type.toUpperCase()} · ${display(host)}`;
  const outboundCopy = { ...outbound };
  delete outboundCopy.tag;
  return {
    id: crypto.randomUUID(), name: label, region: host, flag: inferCountryFlag(label, host),
    protocol: type === 'hysteria2' ? 'Hysteria2' : type === 'tuic' ? 'TUIC' : type.toUpperCase(),
    transport: `${isObject(outbound.tls) && outbound.tls.reality ? 'REALITY' : outbound.tls ? 'TLS' : 'TCP'} · ${port}`,
    outbound: outboundCopy,
  };
}

export function parseShareLink(raw: string): ImportedServer {
  const uri = raw.trim();
  if (uri.startsWith('vmess://')) {
    const encoded = uri.slice(8).split('#', 1)[0];
    const data = JSON.parse(decodeB64(encoded)) as AnyJson;
    const host = string(data.add) || string(data.host);
    const port = positivePort(data.port);
    const out: AnyJson = { type: 'vmess', server: host, server_port: port, uuid: string(data.id), alter_id: Number(data.aid ?? 0), security: string(data.scy) || 'auto' };
    const params = new URLSearchParams();
    params.set('type', string(data.net) || 'tcp');
    if (data.path) params.set('path', string(data.path));
    if (data.host) params.set('host', string(data.host));
    if (data.sni) params.set('sni', string(data.sni));
    if (string(data.tls) === 'tls') params.set('security', 'tls');
    const t = transport(params); if (t) out.transport = t;
    const tls = tlsParams(params); if (tls) out.tls = tls;
    return asServer(out, string(data.ps));
  }
  // Legacy SIP002 links can encode the entire method:password@host:port section.
  if (uri.startsWith('ss://')) {
    const core = uri.slice(5).split(/[?#]/)[0];
    if (!core.includes('@')) {
      const decoded = decodeB64(core);
      const match = /^([^:]+):(.+)@(.+):(\d+)$/.exec(decoded);
      if (!match) throw new Error('Shadowsocks: неверный формат ссылки');
      return asServer({ type: 'shadowsocks', server: match[3], server_port: positivePort(match[4]), method: match[1], password: match[2] }, decodeName(uri.includes('#') ? uri.slice(uri.indexOf('#')) : ''));
    }
  }

  // Normalize potential multi-port / port hopping (e.g. host:443,10000-20000) for URL parser
  const normalizedUri = uri.replace(/(:\d+)(?:,\d+)*(?:-\d+)*/, '$1');
  const u = new URL(normalizedUri);
  const kind = u.protocol.slice(0, -1).toLowerCase();
  const name = decodeName(u.hash);
  const p = u.searchParams;
  const host = u.hostname;
  const port = positivePort(u.port || (kind === 'hysteria2' || kind === 'hy2' || kind === 'tuic' ? 443 : 0));
  let out: AnyJson;
  if (kind === 'vless') {
    out = { type: 'vless', server: host, server_port: port, uuid: decodeURIComponent(u.username) };
    if (p.get('flow')) out.flow = p.get('flow');
    const tls = tlsParams(p); if (tls) out.tls = tls;
    const t = transport(p); if (t) out.transport = t;
  } else if (kind === 'trojan') {
    out = { type: 'trojan', server: host, server_port: port, password: decodeURIComponent(u.username), tls: tlsParams(p, true) };
    const t = transport(p); if (t) out.transport = t;
  } else if (kind === 'hysteria2' || kind === 'hy2') {
    const password = decodeURIComponent(u.password || u.username || p.get('auth') || '');
    const sni = p.get('sni') || p.get('peer') || host;
    const tls: AnyJson = { enabled: true, server_name: sni };
    if (truth(p.get('insecure')) || truth(p.get('allowInsecure'))) tls.insecure = true;
    const alpn = p.get('alpn');
    tls.alpn = alpn ? alpn.split(',').filter(Boolean) : ['h3'];
    const fingerprint = p.get('fp') || p.get('fingerprint');
    if (fingerprint && fingerprint !== 'none') tls.utls = { enabled: true, fingerprint };
    out = { type: 'hysteria2', server: host, server_port: port, password, tls };
    const obfs = p.get('obfs');
    if (obfs) {
      out.obfs = { type: obfs, password: p.get('obfs-password') || p.get('obfs_password') || '' };
    }
    if (p.get('upmbps') || p.get('up_mbps')) out.up_mbps = Number(p.get('upmbps') || p.get('up_mbps'));
    if (p.get('downmbps') || p.get('down_mbps')) out.down_mbps = Number(p.get('downmbps') || p.get('down_mbps'));
  } else if (kind === 'tuic' || kind === 'tuic5') {
    const uuid = decodeURIComponent(u.username || '');
    const password = decodeURIComponent(u.password || '');
    const sni = p.get('sni') || host;
    const tls: AnyJson = { enabled: true, server_name: sni, alpn: p.get('alpn')?.split(',') || ['h3'] };
    out = { type: 'tuic', server: host, server_port: port, uuid, password, tls, congestion_control: p.get('congestion_control') || 'bbr' };
  } else if (kind === 'ss') {
    if (u.username) {
      const creds = decodeURIComponent(u.username).includes(':') ? decodeURIComponent(u.username) : decodeB64(u.username);
      const pivot = creds.indexOf(':');
      if (pivot < 1) throw new Error('Shadowsocks: отсутствуют метод или пароль');
      out = { type: 'shadowsocks', server: host, server_port: port, method: creds.slice(0, pivot), password: creds.slice(pivot + 1) };
    } else throw new Error('Shadowsocks: отсутствуют данные авторизации');
  } else throw new Error(`Ссылка ${kind}:// пока не поддерживается`);
  return asServer(out, name);
}

export function importConfigs(raw: string): { servers: ImportedServer[]; failed: string[] } {
  const input = raw.trim();
  if (!input) throw new Error('Вставьте ссылку, JSON или содержимое подписки');
  if (input.length > 2_000_000) throw new Error('Подписка больше 2 МБ');
  if (input.startsWith('{') || input.startsWith('[')) {
    const json = JSON.parse(input) as unknown;
    const sources = Array.isArray(json) ? json : isObject(json) && Array.isArray(json.outbounds) ? json.outbounds : [json];
    const servers = sources.filter(isObject).filter(o => o.server && o.server_port).map(o => asServer(o, string(o.tag)));
    if (!servers.length) throw new Error('В JSON нет поддерживаемых серверных outbounds');
    return { servers, failed: [] };
  }
  let source = input;
  if (!input.includes('://') && !input.includes('\n')) {
    try { const decoded = decodeB64(input); if (decoded.includes('://')) source = decoded; } catch { /* invalid base64 is a normal invalid import */ }
  }
  const urls = source.split(/\r?\n/).map(s => s.trim()).filter(s => s && !s.startsWith('#'));
  const servers: ImportedServer[] = [];
  const failed: string[] = [];
  for (const line of urls) {
    try { servers.push(parseShareLink(line)); }
    catch (e) { failed.push(`${line.slice(0, 18).split('://')[0] || 'строка'}: ${e instanceof Error ? e.message : String(e)}`); }
  }
  if (!servers.length) throw new Error(`Нет импортированных узлов. ${failed[0] || 'Проверьте формат'}`);
  return { servers, failed };
}
