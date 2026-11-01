package app.wayline.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class MainActivity : TauriActivity() {
    private var pendingConfig: String? = null
    private var pendingMode: String = WaylineVpnService.MODE_TUN

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn(pendingConfig, pendingMode)
        } else {
            setError("Разрешение Android на VPN не предоставлено")
        }
        pendingConfig = null
    }

    override fun onWebViewCreate(webView: WebView) {
        super.onWebViewCreate(webView)
        webView.addJavascriptInterface(WaylineJsBridge(), "WaylineAndroid")
        // Disable browser-like long-press selection and copy menus in the Android shell.
        webView.setOnLongClickListener { true }
        webView.isHapticFeedbackEnabled = false
        webView.setOnCreateContextMenuListener { _, _, _ -> }
        webView.evaluateJavascript("(function(){document.addEventListener('contextmenu',function(e){e.preventDefault();},true);document.addEventListener('selectstart',function(e){if(!e.target.closest('input,textarea,select,[contenteditable=true]'))e.preventDefault();},true);})();", null)
    }

    private fun startVpn(config: String?, mode: String) {
        if (config.isNullOrBlank()) {
            setError("Пустая конфигурация sing-box")
            return
        }
        try {
            val intent = Intent(this, WaylineVpnService::class.java)
                .setAction(WaylineVpnService.ACTION_START)
                .putExtra(WaylineVpnService.EXTRA_CONFIG, config)
                .putExtra(WaylineVpnService.EXTRA_MODE, mode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (error: Throwable) {
            setError(error.message ?: "Не удалось запустить VPN-сервис")
        }
    }

    private fun setError(message: String) {
        getSharedPreferences(WaylineVpnService.PREFS, MODE_PRIVATE).edit()
            .putBoolean(WaylineVpnService.KEY_RUNNING, false)
            .putBoolean(WaylineVpnService.KEY_CONNECTING, false)
            .putString(WaylineVpnService.KEY_ERROR, message)
            .apply()
    }

    inner class WaylineJsBridge {
        @JavascriptInterface
        fun connect(configJson: String, mode: String): String {
            pendingConfig = configJson
            pendingMode = if (mode == WaylineVpnService.MODE_PROXY) WaylineVpnService.MODE_PROXY else WaylineVpnService.MODE_TUN
            getSharedPreferences(WaylineVpnService.PREFS, MODE_PRIVATE).edit()
                .putBoolean(WaylineVpnService.KEY_RUNNING, false)
                .putBoolean(WaylineVpnService.KEY_CONNECTING, true)
                .putString(WaylineVpnService.KEY_ERROR, null)
                .putString(WaylineVpnService.KEY_MODE, pendingMode)
                .apply()
            runOnUiThread {
                if (pendingMode == WaylineVpnService.MODE_PROXY) {
                    startVpn(pendingConfig, pendingMode)
                } else {
                    val permission = VpnService.prepare(this@MainActivity)
                    if (permission == null) startVpn(pendingConfig, pendingMode) else vpnPermission.launch(permission)
                }
            }
            return "connecting"
        }

        @JavascriptInterface
        fun disconnect(): String {
            // If the VPN permission dialog is still open, do not let its callback
            // resurrect a connection after the user has already pressed Disconnect.
            pendingConfig = null
            getSharedPreferences(WaylineVpnService.PREFS, MODE_PRIVATE).edit()
                .putBoolean(WaylineVpnService.KEY_CONNECTING, false)
                .apply()
            runOnUiThread {
                startService(Intent(this@MainActivity, WaylineVpnService::class.java).setAction(WaylineVpnService.ACTION_STOP))
            }
            return "stop_requested"
        }

        @JavascriptInterface
        fun status(): String {
            val prefs = getSharedPreferences(WaylineVpnService.PREFS, MODE_PRIVATE)
            val running = prefs.getBoolean(WaylineVpnService.KEY_RUNNING, false)
            val connecting = prefs.getBoolean(WaylineVpnService.KEY_CONNECTING, false)
            val started = prefs.getLong(WaylineVpnService.KEY_STARTED, 0L)
            val mode = prefs.getString(WaylineVpnService.KEY_MODE, WaylineVpnService.MODE_TUN)
            return JSONObject()
                .put("running", running)
                .put("connecting", connecting)
                .put("mode", mode ?: JSONObject.NULL)
                .put("port", if (running && mode == WaylineVpnService.MODE_PROXY) 2080 else JSONObject.NULL)
                .put("uptimeSeconds", if (running && started > 0) ((System.currentTimeMillis() - started) / 1000) else 0)
                .put("rxBytes", sessionTraffic(prefs.getLong(WaylineVpnService.KEY_RX_START, -1L), TrafficStats.getUidRxBytes(Process.myUid()), running))
                .put("txBytes", sessionTraffic(prefs.getLong(WaylineVpnService.KEY_TX_START, -1L), TrafficStats.getUidTxBytes(Process.myUid()), running))
                .put("verification", if (running) "unverified" else JSONObject.NULL)
                .put("engine", if (running || connecting) "sing-box" else JSONObject.NULL)
                .put("error", prefs.getString(WaylineVpnService.KEY_ERROR, null) ?: JSONObject.NULL)
                .toString()
        }

        @JavascriptInterface
        fun readClipboard(): String {
            return runCatching {
                val clipboard = getSystemService(ClipboardManager::class.java)
                val clip: ClipData? = clipboard?.primaryClip
                if (clip == null || clip.itemCount == 0) "" else {
                    clip.getItemAt(0).coerceToText(this@MainActivity)?.toString().orEmpty().take(2_000_000)
                }
            }.getOrDefault("")
        }

        @JavascriptInterface
        fun fetchSubscription(rawUrl: String): String {
            var connection: HttpsURLConnection? = null
            return try {
                val url = URL(rawUrl)
                val host = url.host.lowercase()
                require(url.protocol.equals("https", true)) { "Подписка должна использовать HTTPS" }
                require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) { "Локальные адреса подписок запрещены" }
                connection = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 12_000
                    instanceFollowRedirects = false
                    useCaches = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Wayline/0.2.0 Android")
                }
                val code = connection.responseCode
                require(code in 200..299) { "Сервер подписки ответил HTTP $code" }
                require(connection.contentLength <= 2_000_000) { "Подписка превышает 2 МБ" }
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= 2_000_000) { "Подписка превышает 2 МБ" }
                        output.write(buffer, 0, read)
                    }
                }
                val content = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
                val disposition = connection.getHeaderField("Content-Disposition").orEmpty()
                val filename = Regex("(?i)filename\\s*=\\s*\\\"?([^\\\";]+)").find(disposition)?.groupValues?.getOrNull(1)
                val title = connection.getHeaderField("profile-title") ?: connection.getHeaderField("x-profile-title") ?: filename
                JSONObject().put("content", content).put("title", title?.takeIf { it.isNotBlank() } ?: JSONObject.NULL).toString()
            } catch (error: Throwable) {
                val message = error.message?.takeIf { it.startsWith("Подписка") || it.startsWith("Сервер") || it.startsWith("Локальные") }
                JSONObject().put("error", message ?: "Не удалось загрузить подписку (${error.javaClass.simpleName})").toString()
            } finally {
                connection?.disconnect()
            }
        }

        private fun sessionTraffic(start: Long, current: Long, running: Boolean): Any {
            if (!running || start < 0 || current < 0) return JSONObject.NULL
            return (current - start).coerceAtLeast(0L)
        }
    }
}
