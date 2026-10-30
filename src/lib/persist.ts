import type { Appearance } from './types';
const KEY='wayline.appearance.v1';
export const defaultAppearance:Appearance={theme:'Ocean',mode:'dark',style:'Material 3 Classic',accent:'#78c9ee',density:'comfortable',fontScale:100,overlay:72,blur:16,background:'waves',backgroundFit:'cover'};
export function loadAppearance():Appearance { try { const raw=localStorage.getItem(KEY); return raw?{...defaultAppearance,...JSON.parse(raw)}:defaultAppearance; } catch { return defaultAppearance; } }
export function saveAppearance(value:Appearance):boolean { try { localStorage.setItem(KEY,JSON.stringify(value)); return true; } catch { return false; } }
