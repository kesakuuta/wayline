package app.wayline.vpn

import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import app.wayline.vpn.ui.WaylineApp
import app.wayline.vpn.ui.theme.WaylineTheme

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var pendingConfig: String? = null
    private var pendingMode: String = WaylineVpnService.MODE_TUN

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val config = pendingConfig
        val mode = pendingMode
        pendingConfig = null
        if (config.isNullOrBlank()) return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            startVpnService(config, mode)
        } else {
            setErrorState("Разрешение Android на VPN не предоставлено")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(WaylineVpnService.PREFS, MODE_PRIVATE)
        WaylineVpnService.recoverStaleState(this)

        setContent {
            WaylineTheme {
                var running by remember { mutableStateOf(prefs.getBoolean(WaylineVpnService.KEY_RUNNING, false)) }
                var connecting by remember { mutableStateOf(prefs.getBoolean(WaylineVpnService.KEY_CONNECTING, false)) }
                var error by remember { mutableStateOf(prefs.getString(WaylineVpnService.KEY_ERROR, null)) }

                val listener = remember {
                    SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
                        when (key) {
                            WaylineVpnService.KEY_RUNNING -> running = sp.getBoolean(key, false)
                            WaylineVpnService.KEY_CONNECTING -> connecting = sp.getBoolean(key, false)
                            WaylineVpnService.KEY_ERROR -> error = sp.getString(key, null)
                        }
                    }
                }

                DisposableEffect(Unit) {
                    prefs.registerOnSharedPreferenceChangeListener(listener)
                    onDispose {
                        prefs.unregisterOnSharedPreferenceChangeListener(listener)
                    }
                }

                WaylineApp(
                    connected = running,
                    connecting = connecting,
                    error = error,
                    onConnect = { config, mode ->
                        connectVpn(config, mode)
                    },
                    onDisconnect = {
                        disconnectVpn()
                    }
                )
            }
        }
    }

    private fun connectVpn(configJson: String, mode: String) {
        pendingConfig = configJson
        pendingMode = if (mode == WaylineVpnService.MODE_PROXY) WaylineVpnService.MODE_PROXY else WaylineVpnService.MODE_TUN

        prefs.edit()
            .putBoolean(WaylineVpnService.KEY_RUNNING, false)
            .putBoolean(WaylineVpnService.KEY_CONNECTING, true)
            .putString(WaylineVpnService.KEY_ERROR, null)
            .putString(WaylineVpnService.KEY_MODE, pendingMode)
            .apply()

        if (pendingMode == WaylineVpnService.MODE_PROXY) {
            startVpnService(pendingConfig, pendingMode)
        } else {
            val permissionIntent = VpnService.prepare(this)
            if (permissionIntent == null) {
                startVpnService(pendingConfig, pendingMode)
            } else {
                vpnPermission.launch(permissionIntent)
            }
        }
    }

    private fun disconnectVpn() {
        pendingConfig = null
        try {
            val intent = Intent(this, WaylineVpnService::class.java).setAction(WaylineVpnService.ACTION_STOP)
            startService(intent)
        } catch (_: Throwable) {
        }
        prefs.edit()
            .putBoolean(WaylineVpnService.KEY_RUNNING, false)
            .putBoolean(WaylineVpnService.KEY_CONNECTING, false)
            .apply()
    }

    private fun startVpnService(config: String?, mode: String) {
        if (config.isNullOrBlank()) {
            setErrorState("Пустая конфигурация sing-box")
            return
        }
        try {
            val intent = Intent(this, WaylineVpnService::class.java)
                .setAction(WaylineVpnService.ACTION_START)
                .putExtra(WaylineVpnService.EXTRA_CONFIG, config)
                .putExtra(WaylineVpnService.EXTRA_MODE, mode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Throwable) {
            setErrorState(e.message ?: "Не удалось запустить VPN-сервис")
        }
    }

    private fun setErrorState(message: String) {
        prefs.edit()
            .putBoolean(WaylineVpnService.KEY_RUNNING, false)
            .putBoolean(WaylineVpnService.KEY_CONNECTING, false)
            .putString(WaylineVpnService.KEY_ERROR, message)
            .apply()
    }
}
