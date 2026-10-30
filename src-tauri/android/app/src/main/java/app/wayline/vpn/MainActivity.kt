package app.wayline.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import app.tauri.TauriActivity
import org.json.JSONObject

class MainActivity : TauriActivity() {
    private var pendingConfig: String? = null
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpn(pendingConfig)
        else setError("Разрешение Android на VPN не предоставлено")
        pendingConfig = null
    }

    override fun onWebViewCreate(webView: WebView) {
        super.onWebViewCreate(webView)
        webView.addJavascriptInterface(WaylineJsBridge(), "WaylineAndroid")
    }

    private fun startVpn(config: String?) {
        if (config.isNullOrBlank()) { setError("Пустая конфигурация sing-box"); return }
        val intent = Intent(this, WaylineVpnService::class.java).setAction(WaylineVpnService.ACTION_START)
            .putExtra(WaylineVpnService.EXTRA_CONFIG, config)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun setError(message: String) {
        getSharedPreferences(WaylineVpnService.PREFS, MODE_PRIVATE).edit()
            .putBoolean(WaylineVpnService.KEY_RUNNING, false).putString(WaylineVpnService.KEY_ERROR, message).apply()
    }

    inner class WaylineJsBridge {
        @JavascriptInterface fun connect(configJson: String): String {
            runOnUiThread {
                pendingConfig = configJson
                val permission = VpnService.prepare(this@MainActivity)
                if (permission == null) startVpn(pendingConfig) else vpnPermission.launch(permission)
            }
            return "permission_flow_started"
        }

        @JavascriptInterface fun disconnect(): String {
            runOnUiThread { startService(Intent(this@MainActivity, WaylineVpnService::class.java).setAction(WaylineVpnService.ACTION_STOP)) }
            return "stop_requested"
        }

        @JavascriptInterface fun status(): String {
            val prefs = getSharedPreferences(WaylineVpnService.PREFS, MODE_PRIVATE)
            val running = prefs.getBoolean(WaylineVpnService.KEY_RUNNING, false)
            val started = prefs.getLong(WaylineVpnService.KEY_STARTED, 0L)
            return JSONObject().put("running", running).put("mode", if (running) "tun" else JSONObject.NULL)
                .put("port", JSONObject.NULL).put("uptimeSeconds", if (running && started > 0) ((System.currentTimeMillis()-started)/1000) else 0)
                .put("verification", if (running) "unverified" else JSONObject.NULL).put("engine", if (running) "sing-box" else JSONObject.NULL)
                .put("error", prefs.getString(WaylineVpnService.KEY_ERROR, null) ?: JSONObject.NULL).toString()
        }
    }
}
