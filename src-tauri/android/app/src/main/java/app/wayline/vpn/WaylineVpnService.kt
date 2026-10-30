package app.wayline.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class WaylineVpnService : VpnService() {
    private var engine: LibboxBridge? = null
    private var startedAt = 0L

    override fun onCreate() { super.onCreate(); createNotificationChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Запуск sing-box…"))
                val config = intent.getStringExtra(EXTRA_CONFIG)
                try {
                    if (config.isNullOrBlank()) throw IllegalArgumentException("Не передан sing-box config")
                    val bridge = LibboxBridge(this)
                    bridge.start(config)
                    engine = bridge
                    startedAt = System.currentTimeMillis()
                    saveStatus(true, null)
                    updateNotification("VPN подключён")
                } catch (e: Throwable) {
                    engine?.stop(); engine = null
                    saveStatus(false, e.message ?: "Ошибка запуска sing-box")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun stopVpn() {
        runCatching { engine?.stop() }
        engine = null
        startedAt = 0L
        saveStatus(false, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    internal fun onCoreStopped() {
        engine = null
        startedAt = 0L
        saveStatus(false, "sing-box остановлен")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveStatus(running: Boolean, error: String?) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, running).putString(KEY_ERROR, error)
            .putLong(KEY_STARTED, if (running) startedAt else 0L).apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(status: String): Notification {
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("Wayline VPN")
            .setContentText(status).setContentIntent(openApp).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Wayline VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }
    override fun onDestroy() { runCatching { engine?.stop() }; engine = null; saveStatus(false, null); super.onDestroy() }

    companion object {
        const val ACTION_START = "app.wayline.vpn.action.START"
        const val ACTION_STOP = "app.wayline.vpn.action.STOP"
        const val ACTION_STATUS = "app.wayline.vpn.action.STATUS"
        const val EXTRA_CONFIG = "config_json"
        const val PREFS = "wayline_vpn"
        const val KEY_RUNNING = "running"
        const val KEY_ERROR = "error"
        const val KEY_STARTED = "started_at"
        private const val CHANNEL_ID = "wayline_vpn_status"
        private const val NOTIFICATION_ID = 4101
    }
}
