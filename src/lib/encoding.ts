export function decodeSubscriptionTitle(raw: string | null | undefined): string {
  if (!raw || typeof raw !== 'string') return '';
  let text = raw.trim();
  if (!text) return '';

  // Remove surrounding quotes if present
  if ((text.startsWith('"') && text.endsWith('"')) || (text.startsWith("'") && text.endsWith("'"))) {
    text = text.slice(1, -1).trim();
  }

  // 1. Handle base64: prefix or b64: prefix (e.g. "base64:UmlvdCBWUE4gKNCe0YHQvdC+0LLQvdC+0Lkp")
  const b64Prefix = text.match(/^(?:base64|b64)[:=]\s*(.+)$/i);
  if (b64Prefix) {
    const candidate = tryDecodeBase64(b64Prefix[1].trim());
    if (candidate) return candidate;
  }

  // 2. Handle RFC 2047 MIME encoded-words (e.g. =?UTF-8?B?...?= or =?windows-1251?B?...?=)
  if (text.includes('=?') && text.includes('?=')) {
    const decodedMime = text.replace(/=\?([^?]+)\?([BQbq])\?([^?]+)\?=/g, (_, charset, encoding, data) => {
      const enc = encoding.toUpperCase();
      if (enc === 'B') {
        const decoded = tryDecodeBase64(data, charset);
        return decoded || data;
      } else if (enc === 'Q') {
        try {
          const rawBytes = data.replace(/_/g, ' ').replace(/=([0-9A-Fa-f]{2})/g, (_m: string, hex: string) => String.fromCharCode(parseInt(hex, 16)));
          const bytes = Uint8Array.from(rawBytes, (c: string) => c.charCodeAt(0));
          return new TextDecoder(charset).decode(bytes);
        } catch {
          return data;
        }
      }
      return data;
    });
    if (decodedMime && decodedMime !== text) return decodedMime.trim();
  }

  // 3. Handle RFC 5987 / RFC 6266 (e.g. UTF-8''%D0%A0%D0%B8%D0%BE%D1%82 or filename*=UTF-8''...)
  if (text.includes("''")) {
    const parts = text.split("''");
    if (parts.length >= 2) {
      try {
        const decoded = decodeURIComponent(parts[1]);
        if (decoded) return decoded.trim();
      } catch {}
    }
  }

  // 4. Handle URL percent-encoding (e.g. %D0%A0%D0%B8%D0%BE%D1%82)
  if (/%[0-9A-Fa-f]{2}/.test(text)) {
    try {
      const urlDecoded = decodeURIComponent(text);
      if (urlDecoded && urlDecoded !== text) {
        text = urlDecoded.trim();
      }
    } catch {}
  }

  // 5. If string looks like standalone base64 without prefix (e.g. "UmlvdCBWUE4gKNCe0YHQvdC+0LLQvdC+0Lkp")
  if (text.length >= 8 && text.length % 4 === 0 && /^[A-Za-z0-9+/=_-]+$/.test(text) && !text.includes(' ')) {
    const candidate = tryDecodeBase64(text);
    if (candidate && /[\p{L}\p{N}]/u.test(candidate) && !/[\x00-\x08\x0E-\x1F]/.test(candidate)) {
      return candidate;
    }
  }

  // 6. Fix potential Latin-1 / UTF-8 mojibake (e.g. "Р РёРѕС‚")
  if (/[\u00C0-\u00FF]/.test(text)) {
    try {
      const bytes = Uint8Array.from(text, c => c.charCodeAt(0) & 0xff);
      const utf8 = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
      if (utf8 && /[\u0400-\u04FF]/.test(utf8)) {
        return utf8.trim();
      }
    } catch {}
  }

  return text.trim();
}

function tryDecodeBase64(input: string, charset = 'utf-8'): string | null {
  try {
    const clean = input.replace(/-/g, '+').replace(/_/g, '/').replace(/\s/g, '');
    const padded = clean.padEnd(Math.ceil(clean.length / 4) * 4, '=');
    const binStr = atob(padded);
    const bytes = Uint8Array.from(binStr, c => c.charCodeAt(0));
    try {
      return new TextDecoder(charset).decode(bytes).trim();
    } catch {
      try {
        return new TextDecoder('windows-1251').decode(bytes).trim();
      } catch {
        return new TextDecoder('utf-8').decode(bytes).trim();
      }
    }
  } catch {
    return null;
  }
}
