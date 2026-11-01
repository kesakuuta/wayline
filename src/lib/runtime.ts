import { invoke, isTauri } from '@tauri-apps/api/core';
import type { RoutingSettings } from './types';

export type CoreMode = 'tun' | 'proxy';
export type CoreEngine = 'xray' | 'sing-box';
export type CoreStatus = {
  running: boolean;
  connecting?: boolean;
  mode: CoreMode | null;
  port: number | null;
  uptimeSeconds: number | null;
  rxBytes?: number | null;
  txBytes?: number | null;
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
export async function pingServer(host: string, port: number): Promise<number> { if (isAndroidNative()) throw new Error('TCP ping is not available in the Android bridge yet.'); requireDesktop(); return invoke<number>('core_ping', { host, port }); }
export async function status(): Promise<CoreStatus> {
  if (isAndroidNative()) return androidStatus();
  requireDesktop();
  return invoke<CoreStatus>('core_status');
}
export async function connect(
  outbound: Record<string, unknown>,
  mode: CoreMode,
  engine: CoreEngine,
  routing?: RoutingSettings
): Promise<CoreStatus> {
  if (isAndroidNative()) {
    const android = window.WaylineAndroid;
    if (!android) throw new Error('Нативный Android-модуль Wayline не найден. Пересоберите приложение.');
    const selected = { ...outbound, tag: 'proxy' };
    // Android's system proxy is published through VpnService.Builder.setHttpProxy,
    // so both UI modes need a VPN interface. Proxy mode additionally configures
    // Android's per-VPN HTTP proxy for apps that honour system proxy settings.
    const inbounds = [{ type: 'tun', tag: 'tun-in', address: ['172.19.0.1/30', 'fdfe:dcba:9876::1/126'], mtu: 1500, auto_route: true, stack: 'system' }, ...(mode === 'proxy' ? [{ type: 'mixed', tag: 'local', listen: '127.0.0.1', listen_port: 2080 }] : [])];
    const domainList = (value?: string) => (value ?? '').split(/[\n,;]+/).map(v => v.trim().replace(/^\./, '')).filter(v => v && !/[\s\x00-\x1f]/.test(v));
    const dnsAddress = routing?.dnsServer === 'google' ? '8.8.8.8' : routing?.dnsServer === 'quad9' ? '9.9.9.9' : '1.1.1.1';
    const dnsServers = routing?.dnsServer === 'system' ? [{ type: 'local', tag: 'dns-remote' }] : [{ type: 'https', tag: 'dns-remote', server: dnsAddress, detour: 'proxy' }];
    const routeRules: Record<string, unknown>[] = [{ action: 'sniff' }, { protocol: 'dns', action: 'hijack-dns' }];
    const ruleSets: Record<string, unknown>[] = [];
    const addRouteList = (value: string | undefined, action: 'direct'|'proxy'|'reject') => {
      const suffixes: string[] = []; const tags: string[] = [];
      for (const item of domainList(value)) {
        const geo = item.match(/^(geosite|geoip):([a-z0-9_.-]+)$/i);
        if (!geo) { suffixes.push(item); continue; }
        const kind = geo[1].toLowerCase(); const tag = kind + '-' + geo[2].toLowerCase(); tags.push(tag);
        const source = kind === 'geosite' ? 'sing-geosite' : 'sing-geoip';
        const url = 'https://raw.githubusercontent.com/SagerNet/' + source + '/rule-set/' + tag + '.srs';
        if (!ruleSets.some(rule => rule.tag === tag)) ruleSets.push({ type: 'remote', tag, format: 'binary', url, download_detour: 'proxy' });
      }
      if (suffixes.length) routeRules.push(action === 'reject' ? { domain_suffix: suffixes, action: 'reject' } : { domain_suffix: suffixes, action: 'route', outbound: action });
      if (tags.length) routeRules.push(action === 'reject' ? { rule_set: tags, action: 'reject' } : { rule_set: tags, action: 'route', outbound: action });
    };
    if (routing?.enabled !== false && routing?.mode === 'rule' && routing?.bypassLan !== false) routeRules.push({ ip_is_private: true, action: 'route', outbound: 'direct' });
    if (routing?.enabled !== false && routing?.mode === 'rule') {
      addRouteList(routing?.directDomains, 'direct'); addRouteList(routing?.proxyDomains, 'proxy'); addRouteList(routing?.blockDomains, 'reject');
      if (routing?.blockAds) { ruleSets.push({ type: 'remote', tag: 'geosite-category-ads-all', format: 'binary', url: 'https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs', download_detour: 'proxy' }); routeRules.push({ rule_set: ['geosite-category-ads-all'], action: 'reject' }); }
    }
    const routeFinal = routing?.mode === 'direct' ? 'direct' : 'proxy';
    const config = {
      log: { level: 'info', timestamp: true },
      dns: {
        servers: [{ type: 'local', tag: 'bootstrap' }, ...dnsServers],
        final: 'dns-remote',
        strategy: routing?.dnsStrategy ?? 'prefer_ipv4'
      },
      inbounds,
      outbounds: [selected, { type: 'direct', tag: 'direct' }],
      route: {
        auto_detect_interface: true,
        default_domain_resolver: { server: 'bootstrap' },
        rule_set: ruleSets,
        rules: routeRules,
        final: routeFinal
      }
    };
    android.connect(JSON.stringify(config), mode);
    // Do not report "connected" merely because the Android service request was queued.
    // Wait for VpnService + libbox to either reach running state or return a real error.
    const deadline = Date.now() + 30_000;
    let current = androidStatus();
    while (current.connecting && Date.now() < deadline) {
      await new Promise<void>(resolve => window.setTimeout(resolve, 350));
      current = androidStatus();
    }
    if (current.connecting) {
      throw new Error('Android VPN всё ещё запускается. Проверьте уведомление и журнал Android.');
    }
    if (!current.running) {
      throw new Error(current.error || 'sing-box не смог запустить VPN-службу.');
    }
    return current;
  }
  requireDesktop();
  return invoke<CoreStatus>('core_connect', { outbound, mode, engine, routing });
}
export async function disconnect(): Promise<CoreStatus> {
  if (isAndroidNative()) {
    window.WaylineAndroid?.disconnect();
    return androidStatus();
  }
  requireDesktop();
  return invoke<CoreStatus>('core_disconnect');
}
export async function fetchSubscription(url: string): Promise<SubscriptionFetch> {
  if (isAndroidNative()) {
    const raw = window.WaylineAndroid?.fetchSubscription(url);
    if (!raw) throw new Error('Android не вернул содержимое подписки.');
    const result = JSON.parse(raw) as { content?: string; title?: string | null; expiresAt?: number | null; error?: string };
    if (result.error) throw new Error(result.error);
    if (typeof result.content !== 'string') throw new Error('Ответ подписки пуст или повреждён.');
    return { content: result.content, title: result.title ?? null, expiresAt: result.expiresAt ?? null };
  }
  requireDesktop();
  return invoke<SubscriptionFetch>('fetch_subscription', { url });
}
export type SubscriptionFetch = { content: string; title: string | null; expiresAt?: number | null };
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
  if (isAndroidNative()) return 'Журнал Android sing-box пока доступен только через системный logcat.';
  requireDesktop();
  return invoke<string>('core_log');
}
export { isTauri, isAndroidNative };

export type BackupInfo = { name: string; path: string; createdAt: number; size: number };
export async function listBackups(): Promise<BackupInfo[]> { requireDesktop(); return invoke<BackupInfo[]>('backup_list'); }
export async function createBackup(data: unknown): Promise<BackupInfo> { requireDesktop(); return invoke<BackupInfo>('backup_create', { data }); }
export async function restoreBackup(name: string): Promise<{version?:number;subscriptions?:unknown;activeSub?:string}> { requireDesktop(); return invoke('backup_restore', { name }); }
