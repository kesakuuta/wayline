package app.wayline.vpn.data

import android.util.Base64
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

object ConfigParser {

    fun parseConfigurations(rawText: String): List<Server> {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Check if entire text is a base64 subscription
        val textToProcess = if (!trimmed.contains("\n") && !trimmed.contains("://") && isLikelyBase64(trimmed)) {
            tryDecodeBase64(trimmed) ?: trimmed
        } else {
            trimmed
        }

        val lines = textToProcess.split("\n", "\r").map { it.trim() }.filter { it.isNotEmpty() }
        val servers = mutableListOf<Server>()

        for (line in lines) {
            val server = parseSingleLink(line)
            if (server != null) {
                servers.add(server)
            }
        }
        return servers
    }

    private fun isLikelyBase64(s: String): Boolean {
        if (s.length % 4 != 0 && s.length % 4 != 2 && s.length % 4 != 3) return false
        return s.matches(Regex("^[A-Za-z0-9+/=_-]+$"))
    }

    private fun tryDecodeBase64(s: String): String? {
        return try {
            val normalized = s.replace("-", "+").replace("_", "/")
            val bytes = Base64.decode(normalized, Base64.DEFAULT)
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    fun parseSingleLink(rawLink: String): Server? {
        val link = rawLink.trim()
        return try {
            when {
                link.startsWith("vless://") -> parseVless(link)
                link.startsWith("vmess://") -> parseVmess(link)
                link.startsWith("trojan://") -> parseTrojan(link)
                link.startsWith("ss://") -> parseShadowsocks(link)
                link.startsWith("hysteria2://") || link.startsWith("hy2://") -> parseHysteria2(link)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseVless(link: String): Server {
        val uri = URI(link)
        val name = decodeComponent(uri.fragment ?: uri.host ?: "VLESS")
        val query = parseQuery(uri.rawQuery)
        val transport = query["type"] ?: "tcp"
        return Server(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = "vless",
            address = uri.host ?: "127.0.0.1",
            port = if (uri.port > 0) uri.port else 443,
            raw = link,
            transport = transport,
            flag = extractCountryCode(name)
        )
    }

    private fun parseTrojan(link: String): Server {
        val uri = URI(link)
        val name = decodeComponent(uri.fragment ?: uri.host ?: "Trojan")
        val query = parseQuery(uri.rawQuery)
        val transport = query["type"] ?: "tcp"
        return Server(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = "trojan",
            address = uri.host ?: "127.0.0.1",
            port = if (uri.port > 0) uri.port else 443,
            raw = link,
            transport = transport,
            flag = extractCountryCode(name)
        )
    }

    private fun parseHysteria2(link: String): Server {
        val uri = URI(link)
        val name = decodeComponent(uri.fragment ?: uri.host ?: "Hysteria 2")
        return Server(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = "hysteria2",
            address = uri.host ?: "127.0.0.1",
            port = if (uri.port > 0) uri.port else 443,
            raw = link,
            transport = "udp",
            flag = extractCountryCode(name)
        )
    }

    private fun parseShadowsocks(link: String): Server {
        val withoutScheme = link.removePrefix("ss://")
        val parts = withoutScheme.split("#", limit = 2)
        val mainPart = parts[0]
        val name = if (parts.size > 1) decodeComponent(parts[1]) else "Shadowsocks"

        var host = "127.0.0.1"
        var port = 8388

        if (mainPart.contains("@")) {
            val hostPort = mainPart.substringAfter("@")
            val hp = hostPort.split(":", limit = 2)
            host = hp[0]
            if (hp.size > 1) port = hp[1].toIntOrNull() ?: 8388
        } else {
            val decoded = tryDecodeBase64(mainPart) ?: ""
            if (decoded.contains("@")) {
                val hostPort = decoded.substringAfter("@")
                val hp = hostPort.split(":", limit = 2)
                host = hp[0]
                if (hp.size > 1) port = hp[1].toIntOrNull() ?: 8388
            }
        }

        return Server(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = "shadowsocks",
            address = host,
            port = port,
            raw = link,
            transport = "tcp",
            flag = extractCountryCode(name)
        )
    }

    private fun parseVmess(link: String): Server {
        val b64 = link.removePrefix("vmess://")
        val jsonStr = tryDecodeBase64(b64) ?: "{}"
        val json = org.json.JSONObject(jsonStr)
        val name = json.optString("ps", "VMess")
        val host = json.optString("add", "127.0.0.1")
        val port = json.optInt("port", 443)
        val net = json.optString("net", "tcp")
        return Server(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = "vmess",
            address = host,
            port = port,
            raw = link,
            transport = net,
            flag = extractCountryCode(name)
        )
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (param in rawQuery.split("&")) {
            val pair = param.split("=", limit = 2)
            val key = decodeComponent(pair[0])
            val value = if (pair.size > 1) decodeComponent(pair[1]) else ""
            result[key] = value
        }
        return result
    }

    private fun decodeComponent(s: String): String {
        return try {
            URLDecoder.decode(s, StandardCharsets.UTF_8.name())
        } catch (_: Throwable) {
            s
        }
    }

    fun extractCountryCode(name: String): String {
        val upper = name.uppercase()
        val countryMap = mapOf(
            "RU" to "RU", "RUSSIA" to "RU", "РОССИЯ" to "RU", "MOSCOW" to "RU",
            "US" to "US", "USA" to "US", "UNITED STATES" to "US", "AMERICA" to "US",
            "DE" to "DE", "GERMANY" to "DE", "ГЕРМАНИЯ" to "DE", "FRANKFURT" to "DE",
            "NL" to "NL", "NETHERLANDS" to "NL", "НИДЕРЛАНДЫ" to "NL", "AMSTERDAM" to "NL",
            "FI" to "FI", "FINLAND" to "FI", "ФИНЛЯНДИЯ" to "FI", "HELSINKI" to "FI",
            "GB" to "GB", "UK" to "GB", "LONDON" to "GB", "UNITED KINGDOM" to "GB",
            "FR" to "FR", "FRANCE" to "FR", "ФРАНЦИЯ" to "FR", "PARIS" to "FR",
            "TR" to "TR", "TURKEY" to "TR", "ТУРЦИЯ" to "TR", "ISTANBUL" to "TR",
            "KZ" to "KZ", "KAZAKHSTAN" to "KZ", "КАЗАХСТАН" to "KZ", "ALMATY" to "KZ",
            "JP" to "JP", "JAPAN" to "JP", "ЯПОНИЯ" to "JP", "TOKYO" to "JP",
            "SG" to "SG", "SINGAPORE" to "SG", "СИНГАПУР" to "SG",
            "SE" to "SE", "SWEDEN" to "SE", "ШВЕЦИЯ" to "SE", "STOCKHOLM" to "SE",
            "PL" to "PL", "POLAND" to "PL", "ПОЛЬША" to "PL", "WARSAW" to "PL",
            "UA" to "UA", "UKRAINE" to "UA", "УКРАИНА" to "UA", "KYIV" to "UA",
            "CH" to "CH", "SWITZERLAND" to "CH", "ШВЕЙЦАРИЯ" to "CH", "ZURICH" to "CH"
        )

        for ((key, code) in countryMap) {
            if (upper.contains(key)) return code
        }
        return "UN"
    }

    fun getCountryEmoji(code: String): String {
        if (code.length != 2 || code == "UN") return "🌐"
        val firstChar = Character.codePointAt(code, 0) - 0x41 + 0x1F1E6
        val secondChar = Character.codePointAt(code, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
    }
}
