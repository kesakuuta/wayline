package app.wayline.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

class WaylineVpnService : VpnService() {
    @Volatile private var engine: LibboxBridge? = null
    @Volatile private var startedAt = 0L
    @Volatile private var currentMode = MODE_TUN
    @Volatile private var expectedStop = false
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Wayline-Libbox").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> worker.execute { stopVpn() }
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_TUN
                currentMode = mode
                expectedStop = false
                startForeground(NOTIFICATION_ID, buildNotification("Запуск sing-box…"))
                saveStatus(running = false, connecting = true, error = null, mode = mode)
                worker.execute {
                    var bridge: LibboxBridge? = null
                    try {
                        if (config.isNullOrBlank()) throw IllegalArgumentException("Не передан sing-box config")
                        if (mode == MODE_TUN && VpnService.prepare(this) != null) {
                            throw IllegalStateException("Android не выдал разрешение VPN")
                        }
                        val activeBridge = LibboxBridge(this)
                        bridge = activeBridge
                        engine = activeBridge
                        activeBridge.start(config)
                        val storedError = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ERROR, null)
                        if (engine !== activeBridge || !storedError.isNullOrBlank()) {
                            throw IllegalStateException(storedError ?: "sing-box остановился во время запуска")
                        }
                        startedAt = System.currentTimeMillis()
                        saveStatus(running = true, connecting = false, error = null, mode = mode)
                        updateNotification(if (mode == MODE_TUN) "VPN подключён" else "Локальный прокси запущен")
                    } catch (error: Throwable) {
                        expectedStop = true
                        runCatching { bridge?.stop() }
                        if (engine === bridge) engine = null
                        startedAt = 0L
                        val previousError = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ERROR, null)
                        saveStatus(
                            running = false,
                            connecting = false,
                            error = previousError ?: root(error).message ?: root(error).javaClass.simpleName,
                            mode = mode
                        )
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun stopVpn() {
        expectedStop = true
        val active = engine
        engine = null
        runCatching { active?.stop() }
        startedAt = 0L
        saveStatus(running = false, connecting = false, error = null, mode = currentMode)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    internal fun onCoreStopped() {
        if (expectedStop) return
        val active = engine
        engine = null
        runCatching { active?.releaseAfterCoreStopped() }
        startedAt = 0L
        saveStatus(running = false, connecting = false, error = "sing-box остановлен", mode = currentMode)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    internal fun onCoreError(message: String) {
        if (expectedStop) return
        val active = engine
        engine = null
        runCatching { active?.releaseAfterCoreStopped() }
        startedAt = 0L
        saveStatus(running = false, connecting = false, error = message, mode = currentMode)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveStatus(running: Boolean, connecting: Boolean, error: String?, mode: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, running)
            .putBoolean(KEY_CONNECTING, connecting)
            .putString(KEY_ERROR, error)
            .putString(KEY_MODE, mode)
            .putInt(KEY_PID, Process.myPid())
            .putLong(KEY_STARTED, if (running) startedAt else 0L)
            .putLong(KEY_RX_START, if (running) TrafficStats.getUidRxBytes(Process.myUid()) else -1L)
            .putLong(KEY_TX_START, if (running) TrafficStats.getUidTxBytes(Process.myUid()) else -1L)
            .apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(status: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Wayline")
            .setContentText(status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Wayline", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onRevoke() {
        worker.execute { stopVpn() }
        super.onRevoke()
    }

    override fun onDestroy() {
        expectedStop = true
        val active = engine
        engine = null
        runCatching { active?.stop() }
        startedAt = 0L
        val lastError = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ERROR, null)
        saveStatus(running = false, connecting = false, error = lastError, mode = currentMode)
        worker.shutdown()
        super.onDestroy()
    }

    private fun root(error: Throwable): Throwable =
        (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error

    companion object {
        const val ACTION_START = "app.wayline.vpn.action.START"
        const val ACTION_STOP = "app.wayline.vpn.action.STOP"
        const val ACTION_STATUS = "app.wayline.vpn.action.STATUS"
        const val EXTRA_CONFIG = "config_json"
        const val EXTRA_MODE = "connection_mode"
        const val MODE_TUN = "tun"
        const val MODE_PROXY = "proxy"
        const val PREFS = "wayline_vpn"
        const val KEY_RUNNING = "running"
        const val KEY_CONNECTING = "connecting"
        const val KEY_ERROR = "error"
        const val KEY_STARTED = "started_at"
        const val KEY_RX_START = "rx_start"
        const val KEY_TX_START = "tx_start"
        const val KEY_MODE = "mode"
        const val KEY_PID = "process_id"

        fun recoverStaleState(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val active = prefs.getBoolean(KEY_RUNNING, false) || prefs.getBoolean(KEY_CONNECTING, false)
            val previousPid = prefs.getInt(KEY_PID, -1)
            val currentPid = Process.myPid()
            if (active && previousPid != currentPid) {
                prefs.edit()
                    .putBoolean(KEY_RUNNING, false)
                    .putBoolean(KEY_CONNECTING, false)
                    .putString(KEY_ERROR, "Предыдущее подключение было прервано. Подключитесь снова.")
                    .putLong(KEY_STARTED, 0L)
                    .putLong(KEY_RX_START, -1L)
                    .putLong(KEY_TX_START, -1L)
                    .putInt(KEY_PID, currentPid)
                    .apply()
            }
        }
        private const val CHANNEL_ID = "wayline_vpn_status"
        private const val NOTIFICATION_ID = 4101
    }
}
