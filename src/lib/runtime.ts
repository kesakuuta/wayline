import { invoke, isTauri } from '@tauri-apps/api/core';

export type CoreMode = 'tun' | 'proxy';
export type CoreEngine = 'xray' | 'sing-box';
export type RouteSettings = { policy:'rules'|'direct'; rulesEnabled:boolean; domainRulesEnabled:boolean; geoSiteRulesEnabled:boolean; geoIpRulesEnabled:boolean; dohEnabled:boolean; bypassLan:boolean; blockAds:boolean; geoCountry:string; directDomains:string; proxyDomains:string; blockDomains:string; directGeoSiteTags:string; proxyGeoSiteTags:string; blockGeoSiteTags:string; dohUrl:string; dnsRoute:'proxy'|'direct'; dnsStrategy:'prefer_ipv4'|'prefer_ipv6'|'ipv4_and_ipv6' };
export const defaultRouteSettings:RouteSettings = { policy:'rules', rulesEnabled:true, domainRulesEnabled:true, geoSiteRulesEnabled:true, geoIpRulesEnabled:true, dohEnabled:true, bypassLan:true, blockAds:false, geoCountry:'', directDomains:'', proxyDomains:'', blockDomains:'', directGeoSiteTags:'', proxyGeoSiteTags:'', blockGeoSiteTags:'', dohUrl:'https://cloudflare-dns.com/dns-query', dnsRoute:'proxy', dnsStrategy:'prefer_ipv4' };
export function loadRouteSettings():RouteSettings { try { const saved=JSON.parse(localStorage.getItem('wayline.routing.v1')||'{}'); const result={...defaultRouteSettings,...saved}; if(result.policy!=='direct') result.policy='rules'; return result as RouteSettings; } catch { return defaultRouteSettings; } }
const domainList=(value:string)=>value.split(/[\n,;]+/).map(x=>x.trim().toLowerCase()).filter(x=>x && /^[a-z0-9*._-]+$/.test(x)).slice(0,256);
export type CoreStatus = {
  running: boolean;
  connecting?: boolean;
  mode: CoreMode | null;
  port: number | null;
  uptimeSeconds: number | null;
  verification: 'checking' | 'verified' | 'unverified' | null;
  engine: CoreEngine | null;
  error: string | null;
};
declare global {
  interface Window {
    WaylineAndroid?: {
      connect(config: string, mode: string): string;
      disconnect(): string;
      status(): string;
      readClipboard(): string;
      fetchSubscription(url: string): string;
      ping(host: string, port: number): number;
      coreLog(): string;
    };
  }
}
const isAndroidNative = () => typeof window !== 'undefined' && !!window.WaylineAndroid;
const requireDesktop = () => {
  if (!isTauri()) throw new Error('Для подключения запустите нативное приложение. Веб-превью не может создать TUN.');
};
function androidStatus(): CoreStatus {
  if (!window.WaylineAndroid) throw new Error('Нативный Android-модуль Wayline не найден. Пересоберите приложение.');
  return JSON.parse(window.WaylineAndroid.status()) as CoreStatus;
}
export async function status(): Promise<CoreStatus> {
  if (isAndroidNative()) return androidStatus();
  requireDesktop();
  return invoke<CoreStatus>('core_status');
}
export async function connect(
  outbound: Record<string, unknown>,
  mode: CoreMode,
  engine: CoreEngine,
  routing: RouteSettings = loadRouteSettings()
): Promise<CoreStatus> {
  if (isAndroidNative()) {
    const android = window.WaylineAndroid;
    if (!android) throw new Error('Нативный Android-модуль Wayline не найден. Пересоберите приложение.');
    const selected = { ...outbound, tag: 'proxy' };
    const inbounds = mode === 'tun'
      ? [{
          type: 'tun',
          tag: 'tun-in',
          inet4_address: '172.19.0.1/30',
          inet6_address: 'fdfe:dcba:9876::1/126',
          address: ['172.19.0.1/30', 'fdfe:dcba:9876::1/126'],
          mtu: 1500,
          auto_route: true,
          endpoint_independent_nat: true,
          stack: 'mixed'
        }]
      : [{
          type: 'mixed',
          tag: 'local',
          listen: '127.0.0.1',
          listen_port: 2080
        }];
    const rules: Record<string, unknown>[] = [
      { action: 'sniff' },
      { protocol: 'dns', action: 'hijack-dns' }
    ];
    const ruleSets: Record<string, unknown>[] = [];
    if (routing.rulesEnabled) {
      if (routing.geoSiteRulesEnabled && routing.blockAds) {
        ruleSets.push({ type: 'remote', tag: 'geosite-category-ads-all', format: 'binary', url: 'https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs', http_client: { detour: 'direct' }, update_interval: '24h' });
        rules.push({ rule_set: ['geosite-category-ads-all'], action: 'reject' });
      }
      if (routing.domainRulesEnabled) {
        const blocked = domainList(routing.blockDomains);
        const direct = domainList(routing.directDomains);
        const proxied = domainList(routing.proxyDomains);
        if (blocked.length) rules.push({ domain_suffix: blocked, action: 'reject' });
        if (direct.length) rules.push({ domain_suffix: direct, action: 'route', outbound: 'direct' });
        if (proxied.length) rules.push({ domain_suffix: proxied, action: 'route', outbound: 'proxy' });
      }
      const addGeoSite = (tag: string) => {
        const normalized = tag.toLowerCase().replace(/^geosite:/, '').replace(/[^a-z0-9_-]/g, '');
        if (!normalized) return null;
        const setTag = 'geosite-' + normalized;
        if (!ruleSets.some(set => set.tag === setTag)) ruleSets.push({ type: 'remote', tag: setTag, format: 'binary', url: 'https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/' + setTag + '.srs', http_client: { detour: 'direct' }, update_interval: '24h' });
        return setTag;
      };
      if (routing.geoSiteRulesEnabled) {
        const blockedSites = domainList(routing.blockGeoSiteTags).map(addGeoSite).filter(Boolean) as string[];
        const directSites = domainList(routing.directGeoSiteTags).map(addGeoSite).filter(Boolean) as string[];
        const proxySites = domainList(routing.proxyGeoSiteTags).map(addGeoSite).filter(Boolean) as string[];
        if (blockedSites.length) rules.push({ rule_set: blockedSites, action: 'reject' });
        if (directSites.length) rules.push({ rule_set: directSites, action: 'route', outbound: 'direct' });
        if (proxySites.length) rules.push({ rule_set: proxySites, action: 'route', outbound: 'proxy' });
      }
      const country = routing.geoCountry.trim().toLowerCase();
      if (routing.geoIpRulesEnabled && /^[a-z]{2}$/.test(country)) {
        const ipTag = 'geoip-' + country;
        ruleSets.push({ type: 'remote', tag: ipTag, format: 'binary', url: 'https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/' + ipTag + '.srs', http_client: { detour: 'direct' }, update_interval: '24h' });
        rules.push({ rule_set: [ipTag], action: 'route', outbound: 'direct' });
      }
      if (routing.bypassLan) rules.push({ ip_is_private: true, action: 'route', outbound: 'direct' });
    }
    let doh: URL;
    try { doh = new URL(routing.dohUrl); if (doh.protocol !== 'https:') throw new Error('HTTPS required'); }
    catch { doh = new URL(defaultRouteSettings.dohUrl); }
    const dns = routing.dohEnabled
      ? {
          servers: [
            { type: 'local', tag: 'bootstrap' },
            { type: 'https', tag: 'dns-remote', server: doh.hostname, server_port: Number(doh.port) || 443, path: doh.pathname + doh.search, detour: routing.dnsRoute, tls: { enabled: true, server_name: doh.hostname } }
          ],
          final: 'dns-remote',
          strategy: routing.dnsStrategy
        }
      : {
          servers: [{ type: 'local', tag: 'system' }],
          final: 'system',
          strategy: routing.dnsStrategy
        };
    const config = {
      log: { level: 'info', timestamp: true },
      dns,
      inbounds,
      outbounds: [selected, { type: 'direct', tag: 'direct' }],
      route: {
        auto_detect_interface: true,
        default_domain_resolver: { server: routing.dohEnabled ? 'bootstrap' : 'system' },
        rule_set: ruleSets,
        rules,
        final: routing.policy === 'direct' ? 'direct' : 'proxy'
      },
      experimental: { cache_file: { enabled: true } }
    };
    android.connect(JSON.stringify(config), mode);
    // Return immediately so the UI can poll status and let the user cancel startup.
    return androidStatus();
  }
  requireDesktop();
  return invoke<CoreStatus>('core_connect', { outbound, mode, engine, routing });
}
export async function disconnect(): Promise<CoreStatus> {
  if (isAndroidNative()) {
    window.WaylineAndroid?.disconnect();
    const deadline = Date.now() + 8_000;
    let current = androidStatus();
    while ((current.running || current.connecting) && Date.now() < deadline) {
      await new Promise<void>(resolve => window.setTimeout(resolve, 150));
      current = androidStatus();
    }
    if (current.running || current.connecting) throw new Error('Android VPN-служба ещё завершает остановку. Повторите отключение.');
    return current;
  }
  requireDesktop();
  return invoke<CoreStatus>('core_disconnect');
}
export async function fetchSubscription(url: string): Promise<SubscriptionFetch> {
  if (isAndroidNative()) {
    const raw = window.WaylineAndroid?.fetchSubscription(url);
    if (!raw) throw new Error('Android не вернул содержимое подписки.');
    const result = JSON.parse(raw) as { content?: string; title?: string | null; expireAt?: number | null; error?: string };
    if (result.error) throw new Error(result.error);
    if (typeof result.content !== 'string') throw new Error('Ответ подписки пуст или повреждён.');
    return { content: result.content, title: result.title ?? null, expireAt: result.expireAt ?? null };
  }
  requireDesktop();
  return invoke<SubscriptionFetch>('fetch_subscription', { url });
}
export type SubscriptionFetch = { content: string; title: string | null; expireAt?: number | null };
export async function readClipboard(): Promise<string> {
  if (isAndroidNative()) {
    return window.WaylineAndroid?.readClipboard?.() ?? '';
  }
  try {
    return await navigator.clipboard.readText();
  } catch {
    return '';
  }
}
export async function coreLog(): Promise<string> {
  if (isAndroidNative()) return window.WaylineAndroid?.coreLog?.() ?? 'Журнал Android недоступен.';
  requireDesktop();
  return invoke<string>('core_log');
}
export { isTauri, isAndroidNative };

export async function pingServer(host: string, port: number): Promise<number|null> {
  if (!host || !Number.isInteger(port) || port < 1 || port > 65535) return null;
  if (isAndroidNative()) {
    const value = window.WaylineAndroid?.ping(host, port);
    return typeof value === 'number' && value >= 0 ? value : null;
  }
  requireDesktop();
  try { return await invoke<number>('core_ping', { host, port }); }
  catch { return null; }
}

export type PendingConnection = { outbound: Record<string, unknown>; mode: CoreMode; engine: CoreEngine; routing?: RouteSettings };
export async function takePendingConnection(): Promise<PendingConnection|null> {
  if (!isTauri()) return null;
  try { return await invoke<PendingConnection|null>('core_take_pending_connect'); }
  catch { return null; }
}

export type BackupInfo = { id:string; created_at:number; size:number };
export async function backupDirectory():Promise<string> { return invoke<string>('core_backup_directory'); }
export async function listBackups():Promise<BackupInfo[]> { return invoke<BackupInfo[]>('core_backup_list'); }
export async function createBackup(data:Record<string,unknown>):Promise<BackupInfo> { return invoke<BackupInfo>('core_backup_create',{data}); }
export async function restoreBackup(id:string):Promise<Record<string,unknown>> { return invoke<Record<string,unknown>>('core_backup_restore',{id}); }
export async function deleteBackup(id:string):Promise<void> { return invoke<void>('core_backup_delete',{id}); }
