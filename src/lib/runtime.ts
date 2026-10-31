import { invoke, isTauri } from '@tauri-apps/api/core';

export type CoreMode = 'tun' | 'proxy';
export type CoreEngine = 'xray' | 'sing-box';
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
  binary: string
): Promise<CoreStatus> {
  if (isAndroidNative()) {
    const selected = { ...outbound, tag: 'proxy' };
    const inbounds = mode === 'tun'
      ? [{
          type: 'tun',
          tag: 'tun-in',
          interface_name: 'wayline-tun',
          address: ['172.19.0.1/30', 'fdfe:dcba:9876::1/126'],
          mtu: 9000,
          auto_route: true,
          strict_route: false,
          stack: 'system',
          sniff: true
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
        servers: [{ tag: 'dns-remote', address: '1.1.1.1', detour: 'proxy' }],
        final: 'dns-remote',
        strategy: 'prefer_ipv4'
      },
      inbounds,
      outbounds: [selected, { type: 'direct', tag: 'direct' }],
      route: { auto_detect_interface: true, final: 'proxy' }
    };
    window.WaylineAndroid.connect(JSON.stringify(config), mode);
    return androidStatus();
  }
  requireDesktop();
  return invoke<CoreStatus>('core_connect', { outbound, mode, engine, binary });
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
