import { invoke, isTauri } from '@tauri-apps/api/core';

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
export async function status(): Promise<CoreStatus> {
  if (isAndroidNative()) return androidStatus();
  requireDesktop();
  return invoke<CoreStatus>('core_status');
}
export async function connect(
  outbound: Record<string, unknown>,
  mode: CoreMode,
  engine: CoreEngine
): Promise<CoreStatus> {
  if (isAndroidNative()) {
    const android = window.WaylineAndroid;
    if (!android) throw new Error('Нативный Android-модуль Wayline не найден. Пересоберите приложение.');
    const selected = { ...outbound, tag: 'proxy' };
    const inbounds = mode === 'tun'
      ? [{
          type: 'tun',
          tag: 'tun-in',
          address: ['172.19.0.1/30', 'fdfe:dcba:9876::1/126'],
          mtu: 1500,
          auto_route: true,
          stack: 'system'
        }]
      : [{
          type: 'mixed',
          tag: 'local',
          listen: '127.0.0.1',
          listen_port: 2080
        }];
    const config = {
      log: { level: 'info', timestamp: true },
      dns: {
        servers: [
          { type: 'local', tag: 'bootstrap' },
          { type: 'udp', tag: 'dns-remote', server: '1.1.1.1', detour: 'proxy' }
        ],
        final: 'dns-remote',
        strategy: 'prefer_ipv4'
      },
      inbounds,
      outbounds: [selected, { type: 'direct', tag: 'direct' }],
      route: {
        auto_detect_interface: true,
        default_domain_resolver: { server: 'bootstrap' },
        rules: [
          { action: 'sniff' },
          { protocol: 'dns', action: 'hijack-dns' },
          { ip_is_private: true, action: 'route', outbound: 'direct' }
        ],
        final: 'proxy'
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
  return invoke<CoreStatus>('core_connect', { outbound, mode, engine });
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
    const result = JSON.parse(raw) as { content?: string; title?: string | null; error?: string };
    if (result.error) throw new Error(result.error);
    if (typeof result.content !== 'string') throw new Error('Ответ подписки пуст или повреждён.');
    return { content: result.content, title: result.title ?? null };
  }
  requireDesktop();
  return invoke<SubscriptionFetch>('fetch_subscription', { url });
}
export type SubscriptionFetch = { content: string; title: string | null };
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
