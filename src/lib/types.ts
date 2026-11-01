export type ThemeName = 'Ocean'|'Graphite'|'AMOLED'|'Lavender'|'Forest'|'Sunset'|'Sand'|'Nord';
export type AppearanceStyle = 'Material 3 Classic'|'Expressive'|'Minimal'|'Soft Glass'|'Liquid Glass';
export type Mode = 'dark'|'light'|'system';
export type Server = { id:string; name:string; region:string; flag:string; protocol:string; transport:string; favorite?:boolean; outbound?:Record<string,unknown> };
export type Subscription = { id:string; name:string; servers:Server[]; updated?:string; sourceUrl?:string };
export type Appearance = { theme:ThemeName; mode:Mode; style:AppearanceStyle; accent:string; density:'comfortable'|'compact'; fontScale:number; overlay:number; blur:number; listOpacity:number; background:'waves'|'gradient'|'solid'|'custom'; customImage?:string; backgroundFit:'cover'|'contain'|'stretch'|'tile' };
