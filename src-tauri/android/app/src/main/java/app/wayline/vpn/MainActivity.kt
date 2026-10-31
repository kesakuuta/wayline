package app.wayline.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject

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
                .put("port", JSONObject.NULL)
                .put("uptimeSeconds", if (running && started > 0) ((System.currentTimeMillis() - started) / 1000) else 0)
                .put("verification", if (running) "unverified" else JSONObject.NULL)
                .put("engine", if (running || connecting) "sing-box" else JSONObject.NULL)
                .put("error", prefs.getString(WaylineVpnService.KEY_ERROR, null) ?: JSONObject.NULL)
                .toString()
        }

        @JavascriptInterface
        fun readClipboard(): String {
            val clipboard = getSystemService(ClipboardManager::class.java) ?: return ""
            val clip: ClipData = clipboard.primaryClip ?: return ""
            if (clip.itemCount == 0) return ""
            return clip.getItemAt(0).coerceToText(this@MainActivity)?.toString().orEmpty()
        }
    }
}
