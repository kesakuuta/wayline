import React from 'react';

/**
 * Extracts a 2-letter uppercase ISO country code from:
 * 1. An existing unicode flag emoji (e.g. 🇷🇺 -> 'RU')
 * 2. A 2-letter code string (e.g. 'RU', 'EE', 'fr')
 * 3. Server name text containing country keywords or city names
 */
export function extractCountryCode(input?: string): string {
  if (!input) return '';
  const str = input.trim();

  // 1. Unicode Regional Indicator Symbols (0x1F1E6 - 0x1F1FF)
  const flagRegex = /([\u{1F1E6}-\u{1F1FF}])([\u{1F1E6}-\u{1F1FF}])/u;
  const match = str.match(flagRegex);
  if (match) {
    const c1 = match[1].codePointAt(0)! - 0x1F1E6 + 65;
    const c2 = match[2].codePointAt(0)! - 0x1F1E6 + 65;
    return (String.fromCharCode(c1) + String.fromCharCode(c2)).toUpperCase();
  }

  // 2. Direct 2-letter ISO code
  if (/^[A-Za-z]{2}$/.test(str)) {
    return str.toUpperCase();
  }

  const lower = str.toLowerCase();

  // 3. Common country / city names
  if (lower.includes('росси') || lower.includes('russia') || lower.includes('москв') || lower.includes('петербург') || lower.includes('spb') || lower.includes('moscow')) return 'RU';
  if (lower.includes('эстони') || lower.includes('estonia') || lower.includes('таллин') || lower.includes('tallinn')) return 'EE';
  if (lower.includes('франци') || lower.includes('france') || lower.includes('париж') || lower.includes('paris')) return 'FR';
  if (lower.includes('латви') || lower.includes('latvia') || lower.includes('рига') || lower.includes('riga')) return 'LV';
  if (lower.includes('финлянд') || lower.includes('finland') || lower.includes('хельсинки') || lower.includes('helsinki')) return 'FI';
  if (lower.includes('герман') || lower.includes('germany') || lower.includes('франкфурт') || lower.includes('frankfurt') || lower.includes('берлин') || lower.includes('berlin')) return 'DE';
  if (lower.includes('нидерланд') || lower.includes('netherlands') || lower.includes('амстердам') || lower.includes('amsterdam') || lower.includes('holland')) return 'NL';
  if (lower.includes('сша') || lower.includes('usa') || lower.includes('united states') || lower.includes('new york') || lower.includes('йорк') || lower.includes('los angeles')) return 'US';
  if (lower.includes('великобритан') || lower.includes('london') || lower.includes('лондон') || lower.includes('uk') || lower.includes('britain')) return 'GB';
  if (lower.includes('швеци') || lower.includes('sweden') || lower.includes('стокгольм') || lower.includes('stockholm')) return 'SE';
  if (lower.includes('польш') || lower.includes('poland') || lower.includes('варшав') || lower.includes('warsaw')) return 'PL';
  if (lower.includes('украин') || lower.includes('ukraine') || lower.includes('киев') || lower.includes('kyiv')) return 'UA';
  if (lower.includes('казахстан') || lower.includes('kazakhstan') || lower.includes('алматы') || lower.includes('астана')) return 'KZ';
  if (lower.includes('турци') || lower.includes('turkey') || lower.includes('стамбул') || lower.includes('istanbul')) return 'TR';
  if (lower.includes('литв') || lower.includes('lithuania') || lower.includes('вильнюс') || lower.includes('vilnius')) return 'LT';
  if (lower.includes('швейцари') || lower.includes('switzerland') || lower.includes('цюрих') || lower.includes('zurich')) return 'CH';
  if (lower.includes('австри') || lower.includes('austria') || lower.includes('вена') || lower.includes('vienna')) return 'AT';
  if (lower.includes('италия') || lower.includes('italy') || lower.includes('рим') || lower.includes('rome')) return 'IT';
  if (lower.includes('испани') || lower.includes('spain') || lower.includes('мадрид') || lower.includes('madrid')) return 'ES';
  if (lower.includes('чехия') || lower.includes('czech') || lower.includes('прага') || lower.includes('prague')) return 'CZ';
  if (lower.includes('япони') || lower.includes('japan') || lower.includes('токио') || lower.includes('tokyo')) return 'JP';
  if (lower.includes('сингапур') || lower.includes('singapore')) return 'SG';
  if (lower.includes('канада') || lower.includes('canada') || lower.includes('торонто') || lower.includes('toronto')) return 'CA';
  if (lower.includes('норвеги') || lower.includes('norway') || lower.includes('осло') || lower.includes('oslo')) return 'NO';
  if (lower.includes('дания') || lower.includes('denmark') || lower.includes('копенгаген') || lower.includes('copenhagen')) return 'DK';
  if (lower.includes('болгари') || lower.includes('bulgaria') || lower.includes('софия') || lower.includes('sofia')) return 'BG';
  if (lower.includes('румыни') || lower.includes('romania') || lower.includes('бухарест') || lower.includes('bucharest')) return 'RO';
  if (lower.includes('молдов') || lower.includes('moldova') || lower.includes('кишинев') || lower.includes('chisinau')) return 'MD';
  if (lower.includes('грузи') || lower.includes('georgia') || lower.includes('тбилиси') || lower.includes('tbilisi')) return 'GE';
  if (lower.includes('армени') || lower.includes('armenia') || lower.includes('ереван') || lower.includes('yerevan')) return 'AM';
  if (lower.includes('беларус') || lower.includes('belarus') || lower.includes('минск') || lower.includes('minsk')) return 'BY';
  if (lower.includes('серби') || lower.includes('serbia') || lower.includes('белград') || lower.includes('belgrade')) return 'RS';
  if (lower.includes('индия') || lower.includes('india')) return 'IN';
  if (lower.includes('эмират') || lower.includes('дубай') || lower.includes('dubai') || lower.includes('uae')) return 'AE';
  if (lower.includes('корея') || lower.includes('korea') || lower.includes('сеул') || lower.includes('seoul')) return 'KR';
  if (lower.includes('австрали') || lower.includes('australia') || lower.includes('сидней') || lower.includes('sydney')) return 'AU';
  if (lower.includes('бразили') || lower.includes('brazil')) return 'BR';
  if (lower.includes('гонконг') || lower.includes('hong kong') || lower.includes('hongkong')) return 'HK';

  // 4. Token boundary match (e.g. "RU #2", "[EE]", "FR -")
  const tokenMatches = str.match(/\b([A-Za-z]{2})\b/g);
  if (tokenMatches) {
    const known = new Set([
      'RU','EE','FR','LV','FI','DE','NL','US','GB','UK','SE','PL','UA','KZ','TR',
      'LT','CH','AT','IT','ES','CZ','JP','SG','CA','NO','DK','BG','RO','MD','GE',
      'AM','BY','RS','IN','AE','KR','AU','BR','HK','EU'
    ]);
    for (const t of tokenMatches) {
      const up = t.toUpperCase();
      if (known.has(up)) return up === 'UK' ? 'GB' : up;
    }
  }

  return '';
}

interface CountryFlagProps {
  code?: string;
  name?: string;
  className?: string;
  size?: 'sm' | 'md' | 'lg';
}

export const CountryFlag: React.FC<CountryFlagProps> = ({ code, name, className = '', size = 'md' }) => {
  const resolvedCode = (code && extractCountryCode(code)) || (name && extractCountryCode(name)) || '';

  const dimensions = {
    sm: { width: 18, height: 13, rx: 2.5 },
    md: { width: 24, height: 17, rx: 3 },
    lg: { width: 28, height: 20, rx: 3.5 },
  }[size];

  const { width, height, rx } = dimensions;

  const renderFlagSvg = (iso: string) => {
    switch (iso) {
      // 🇷🇺 Россия
      case 'RU':
        return (
          <>
            <rect width="24" height="6" fill="#FFFFFF" />
            <rect y="6" width="24" height="6" fill="#0039A6" />
            <rect y="12" width="24" height="6" fill="#D52B1E" />
          </>
        );

      // 🇪🇪 Эстония
      case 'EE':
        return (
          <>
            <rect width="24" height="6" fill="#0072CE" />
            <rect y="6" width="24" height="6" fill="#000000" />
            <rect y="12" width="24" height="6" fill="#FFFFFF" />
          </>
        );

      // 🇫🇷 Франция
      case 'FR':
        return (
          <>
            <rect width="8" height="18" fill="#002654" />
            <rect x="8" width="8" height="18" fill="#FFFFFF" />
            <rect x="16" width="8" height="18" fill="#ED2939" />
          </>
        );

      // 🇱🇻 Латвия
      case 'LV':
        return (
          <>
            <rect width="24" height="18" fill="#9E3039" />
            <rect y="7" width="24" height="4" fill="#FFFFFF" />
          </>
        );

      // 🇫🇮 Финляндия
      case 'FI':
        return (
          <>
            <rect width="24" height="18" fill="#FFFFFF" />
            <rect x="7" width="4" height="18" fill="#002F6C" />
            <rect y="7" width="24" height="4" fill="#002F6C" />
          </>
        );

      // 🇩🇪 Германия
      case 'DE':
        return (
          <>
            <rect width="24" height="6" fill="#000000" />
            <rect y="6" width="24" height="6" fill="#DD0000" />
            <rect y="12" width="24" height="6" fill="#FFCE00" />
          </>
        );

      // 🇳🇱 Нидерланды
      case 'NL':
        return (
          <>
            <rect width="24" height="6" fill="#AE1C28" />
            <rect y="6" width="24" height="6" fill="#FFFFFF" />
            <rect y="12" width="24" height="6" fill="#21468B" />
          </>
        );

      // 🇺🇸 США
      case 'US':
        return (
          <>
            <rect width="24" height="18" fill="#FFFFFF" />
            <rect width="24" height="2.6" fill="#B22234" />
            <rect y="5.2" width="24" height="2.6" fill="#B22234" />
            <rect y="10.4" width="24" height="2.6" fill="#B22234" />
            <rect y="15.4" width="24" height="2.6" fill="#B22234" />
            <rect width="11" height="10" fill="#3C3B6E" />
            <circle cx="5.5" cy="5" r="1.5" fill="#FFFFFF" />
          </>
        );

      // 🇬🇧 Великобритания
      case 'GB':
      case 'UK':
        return (
          <>
            <rect width="24" height="18" fill="#012169" />
            <path d="M0,0 L24,18 M24,0 L0,18" stroke="#FFFFFF" strokeWidth="3.2" />
            <path d="M0,0 L24,18 M24,0 L0,18" stroke="#C8102E" strokeWidth="1.6" />
            <rect x="9.5" width="5" height="18" fill="#FFFFFF" />
            <rect y="6.5" width="24" height="5" fill="#FFFFFF" />
            <rect x="10.5" width="3" height="18" fill="#C8102E" />
            <rect y="7.5" width="24" height="3" fill="#C8102E" />
          </>
        );

      // 🇸🇪 Швеция
      case 'SE':
        return (
          <>
            <rect width="24" height="18" fill="#005293" />
            <rect x="7" width="4" height="18" fill="#FECC00" />
            <rect y="7" width="24" height="4" fill="#FECC00" />
          </>
        );

      // 🇵🇱 Польша
      case 'PL':
        return (
          <>
            <rect width="24" height="9" fill="#FFFFFF" />
            <rect y="9" width="24" height="9" fill="#DC143C" />
          </>
        );

      // 🇺🇦 Украина
      case 'UA':
        return (
          <>
            <rect width="24" height="9" fill="#0057B7" />
            <rect y="9" width="24" height="9" fill="#FFDD00" />
          </>
        );

      // 🇱🇹 Литва
      case 'LT':
        return (
          <>
            <rect width="24" height="6" fill="#FDB913" />
            <rect y="6" width="24" height="6" fill="#006A44" />
            <rect y="12" width="24" height="6" fill="#C1272D" />
          </>
        );

      // 🇮🇹 Италия
      case 'IT':
        return (
          <>
            <rect width="8" height="18" fill="#009246" />
            <rect x="8" width="8" height="18" fill="#FFFFFF" />
            <rect x="16" width="8" height="18" fill="#CE2B37" />
          </>
        );

      // 🇦🇹 Австрия
      case 'AT':
        return (
          <>
            <rect width="24" height="6" fill="#ED2939" />
            <rect y="6" width="24" height="6" fill="#FFFFFF" />
            <rect y="12" width="24" height="6" fill="#ED2939" />
          </>
        );

      // 🇨🇭 Швейцария
      case 'CH':
        return (
          <>
            <rect width="24" height="18" fill="#D52B1E" />
            <rect x="10" y="4" width="4" height="10" fill="#FFFFFF" rx="0.5" />
            <rect x="7" y="7" width="10" height="4" fill="#FFFFFF" rx="0.5" />
          </>
        );

      // 🇯🇵 Япония
      case 'JP':
        return (
          <>
            <rect width="24" height="18" fill="#FFFFFF" />
            <circle cx="12" cy="9" r="4.5" fill="#BC002D" />
          </>
        );

      // 🇹🇷 Турция
      case 'TR':
        return (
          <>
            <rect width="24" height="18" fill="#E30A17" />
            <circle cx="10" cy="9" r="4.2" fill="#FFFFFF" />
            <circle cx="11.2" cy="9" r="3.4" fill="#E30A17" />
            <polygon points="14.5,7.5 15.3,8.8 16.7,8.8 15.6,9.6 16,11 14.8,10.2 13.6,11 14,9.6 13,8.8 14.3,8.8" fill="#FFFFFF" transform="scale(0.8) translate(3, 1)" />
          </>
        );

      // 🇰🇿 Казахстан
      case 'KZ':
        return (
          <>
            <rect width="24" height="18" fill="#00AFCA" />
            <circle cx="12" cy="9" r="3.5" fill="#FEC50C" />
          </>
        );

      // 🇨🇦 Канада
      case 'CA':
        return (
          <>
            <rect width="6" height="18" fill="#FF0000" />
            <rect x="6" width="12" height="18" fill="#FFFFFF" />
            <rect x="18" width="6" height="18" fill="#FF0000" />
            <path d="M12,4.5 L13,7.5 L14.5,7 L14,8.5 L15.5,9.5 L13.5,10.5 L13,13.5 L12,12 L11,13.5 L10.5,10.5 L8.5,9.5 L10,8.5 L9.5,7 L11,7.5 Z" fill="#FF0000" />
          </>
        );

      // 🇪🇸 Испания
      case 'ES':
        return (
          <>
            <rect width="24" height="4.5" fill="#AA151B" />
            <rect y="4.5" width="24" height="9" fill="#F1BF00" />
            <rect y="13.5" width="24" height="4.5" fill="#AA151B" />
          </>
        );

      // 🇨🇿 Чехия
      case 'CZ':
        return (
          <>
            <rect width="24" height="9" fill="#FFFFFF" />
            <rect y="9" width="24" height="9" fill="#D7141A" />
            <polygon points="0,0 12,9 0,18" fill="#11457E" />
          </>
        );

      // 🇸🇬 Сингапур
      case 'SG':
        return (
          <>
            <rect width="24" height="9" fill="#ED2939" />
            <rect y="9" width="24" height="9" fill="#FFFFFF" />
            <circle cx="5" cy="4.5" r="3" fill="#FFFFFF" />
            <circle cx="6" cy="4.5" r="2.6" fill="#ED2939" />
          </>
        );

      // 🇪🇺 Евросоюз
      case 'EU':
        return (
          <>
            <rect width="24" height="18" fill="#003399" />
            <circle cx="12" cy="9" r="5" stroke="#FFCC00" strokeWidth="1.2" strokeDasharray="1.2 1.4" fill="none" />
          </>
        );

      default:
        // Stylized globe badge or 2-letter fallback
        return (
          <>
            <rect width="24" height="18" fill="#24303F" />
            {iso.length >= 2 ? (
              <text x="12" y="12" fill="#8BA0B6" fontSize="7.5" fontWeight="bold" textAnchor="middle" fontFamily="sans-serif">
                {iso.slice(0, 2)}
              </text>
            ) : (
              <circle cx="12" cy="9" r="4.5" stroke="#6C8299" strokeWidth="1.3" fill="none" />
            )}
          </>
        );
    }
  };

  return (
    <span
      className={`country-flag-badge ${className}`}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: `${width}px`,
        height: `${height}px`,
        borderRadius: `${rx}px`,
        overflow: 'hidden',
        boxShadow: '0 1px 3px rgba(0,0,0,0.28)',
        border: '0.6px solid rgba(255,255,255,0.18)',
        flexShrink: 0,
        verticalAlign: 'middle',
      }}
      title={resolvedCode || 'Сервер'}
    >
      <svg
        viewBox="0 0 24 18"
        width={width}
        height={height}
        style={{ display: 'block' }}
      >
        {renderFlagSvg(resolvedCode)}
      </svg>
    </span>
  );
};
