package app.wayline.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

class WaylineVpnService : VpnService() {
    @Volatile private var engine: LibboxBridge? = null
    @Volatile private var startedAt = 0L
    @Volatile private var currentMode = MODE_TUN
    @Volatile private var expectedStop = false
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Wayline-Libbox").apply { isDaemon = true }
    }
    private val stopWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Wayline-VPN-Stop").apply { isDaemon = true }
    }

    private fun sanitizeConfig(raw: String): String {
        return try {
            val root = org.json.JSONObject(raw)
            val inbounds = root.optJSONArray("inbounds")
            if (inbounds != null) {
                for (i in 0 until inbounds.length()) {
                    val inbound = inbounds.optJSONObject(i) ?: continue
                    if (inbound.optString("type") == "tun") {
                        val v4 = inbound.optString("inet4_address").takeIf { it.isNotBlank() }
                        val v6 = inbound.optString("inet6_address").takeIf { it.isNotBlank() }
                        inbound.remove("inet4_address")
                        inbound.remove("inet6_address")
                        inbound.remove("inet4_route_address")
                        inbound.remove("inet6_route_address")
                        inbound.remove("inet4_route_exclude_address")
                        inbound.remove("inet6_route_exclude_address")
                        inbound.remove("sniff")
                        inbound.remove("sniff_override_destination")
                        inbound.remove("domain_strategy")
                        inbound.remove("endpoint_independent_nat")

                        if (!inbound.has("interface_name")) inbound.put("interface_name", "tun0")
                        if (!inbound.has("address")) {
                            val addrs = org.json.JSONArray()
                            if (v4 != null) addrs.put(v4) else addrs.put("172.19.0.1/30")
                            if (v6 != null) addrs.put(v6) else addrs.put("fdfe:dcba:9876::1/126")
                            inbound.put("address", addrs)
                        }
                        inbound.put("auto_route", true)
                        inbound.put("strict_route", true)
                        inbound.put("stack", "system")
                    }
                }
            }
            var route = root.optJSONObject("route")
            if (route == null) {
                route = org.json.JSONObject()
                root.put("route", route)
            }
            route.put("auto_detect_interface", true)
            var rules = route.optJSONArray("rules")
            if (rules == null) {
                rules = org.json.JSONArray()
                route.put("rules", rules)
            }
            var hasSniff = false
            for (i in 0 until rules.length()) {
                val r = rules.optJSONObject(i)
                if (r?.optString("action") == "sniff") {
                    hasSniff = true
                    if (i != 0) {
                        rules.remove(i)
                        val newRules = org.json.JSONArray()
                        newRules.put(r)
                        for (j in 0 until rules.length()) newRules.put(rules.get(j))
                        route.put("rules", newRules)
                    }
                    break
                }
            }
            if (!hasSniff) {
                val newRules = org.json.JSONArray()
                newRules.put(org.json.JSONObject().put("action", "sniff"))
                for (j in 0 until rules.length()) newRules.put(rules.get(j))
                route.put("rules", newRules)
            }
            root.toString()
        } catch (_: Throwable) {
            raw
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                expectedStop = true
                saveStatus(running = false, connecting = false, error = null, mode = currentMode)
                stopWorker.execute { stopVpn() }
            }
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
                        activeBridge.start(sanitizeConfig(config))
                        if (expectedStop) throw CancellationException("Отключение запрошено во время запуска")
                        val storedError = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ERROR, null)
                        if (engine !== activeBridge || !storedError.isNullOrBlank()) {
                            throw IllegalStateException(storedError ?: "sing-box остановился во время запуска")
                        }
                        startedAt = System.currentTimeMillis()
                        saveStatus(running = true, connecting = false, error = null, mode = mode)
                        updateNotification(if (mode == MODE_TUN) "VPN подключён" else "Локальный прокси запущен")
                    } catch (error: Throwable) {
                        val cancelled = expectedStop || error is CancellationException
                        expectedStop = true
                        runCatching { bridge?.stop() }
                        if (engine === bridge) engine = null
                        startedAt = 0L
                        val previousError = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ERROR, null)
                        saveStatus(
                            running = false,
                            connecting = false,
                            error = if (cancelled) null else previousError ?: root(error).message ?: root(error).javaClass.simpleName,
                            mode = mode
                        )
                        if (!cancelled) android.util.Log.e("WaylineVpnService", "Не удалось запустить sing-box", error)
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
        expectedStop = true
        saveStatus(running = false, connecting = false, error = null, mode = currentMode)
        stopWorker.execute { stopVpn() }
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
        worker.shutdownNow()
        stopWorker.shutdownNow()
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
                    .putInt(KEY_PID, currentPid)
                    .apply()
            }
        }
        private const val CHANNEL_ID = "wayline_vpn_status"
        private const val NOTIFICATION_ID = 4101
    }
}
