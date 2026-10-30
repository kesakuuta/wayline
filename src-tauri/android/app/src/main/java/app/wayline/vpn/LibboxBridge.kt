package app.wayline.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.lang.reflect.Proxy

/** Thin gomobile adapter. Reflection keeps this source tolerant of generated Java naming,
 * while the runtime still requires the pinned libbox 1.14.0 interfaces in the AAR. */
internal class LibboxBridge(private val service: VpnService) {
    private var commandServer: Any? = null
    private var tunFd: Int? = null

    @Synchronized fun start(configJson: String) {
        require(configJson.isNotBlank()) { "sing-box config is empty" }
        stop()
        val libbox = Class.forName("libbox.Libbox")
        val base = service.filesDir.absolutePath
        runCatching { libbox.getMethod("setup", String::class.java).invoke(null, base) }
            .getOrElse { throw IllegalStateException("Не удалось инициализировать libbox: ${root(it).message}", root(it)) }

        val loader = libbox.classLoader
        val platformType = Class.forName("libbox.PlatformInterface", true, loader)
        val handlerType = Class.forName("libbox.CommandServerHandler", true, loader)
        val platform = Proxy.newProxyInstance(loader, arrayOf(platformType)) { _, method, args ->
            when (method.name.lowercase()) {
                "useplatformautodetectinterfacecontrol" -> true
                "autodetectinterfacecontrol" -> { service.protect((args?.getOrNull(0) as Number).toInt()); null }
                "opentun" -> openTun(args!![0])
                "useprocfs", "undernetworkextension", "includeallnetworks" -> false
                "cleardnscache", "registermyinterface" -> null
                "findconnectionowner", "localdnstransport", "readwifistate" -> null
                "startdefaultinterfacemonitor", "closedefaultinterfacemonitor", "startneighbormonitor", "closeneighbormonitor" -> null
                "sendnotification" -> null
                "systemcertificates" -> emptyIterator(loader, "StringIterator")
                "getinterfaces" -> emptyIterator(loader, "NetworkInterfaceIterator")
                else -> defaultValue(method.returnType)
            }
        }
        val handler = Proxy.newProxyInstance(loader, arrayOf(handlerType)) { _, method, _ ->
            when (method.name.lowercase()) {
                "getsystemproxystatus" -> null
                "servicestop" -> { service.onCoreStopped(); null }
                "servicereload", "setsystemproxyenabled", "triggernativecrash", "writedebugmessage", "connectsshagent", "writemessage", "seterror" -> null
                else -> defaultValue(method.returnType)
            }
        }
        val factory = libbox.methods.firstOrNull { it.name.equals("newCommandServer", true) && it.parameterTypes.size == 2 }
            ?: throw IllegalStateException("В libbox 1.14.0 не найден NewCommandServer")
        val server = factory.invoke(null, handler, platform)
        server.javaClass.getMethod("start").invoke(server)
        try {
            val start = server.javaClass.methods.firstOrNull { it.name.equals("startOrReloadService", true) && it.parameterTypes.size == 2 }
                ?: throw IllegalStateException("В libbox не найден StartOrReloadService")
            start.invoke(server, configJson, null)
            commandServer = server
        } catch (e: Throwable) {
            runCatching { server.javaClass.getMethod("close").invoke(server) }
            closeTun()
            throw IllegalStateException("sing-box не запустился: ${root(e).message}", root(e))
        }
    }

    private fun openTun(options: Any): Int {
        closeTun()
        val builder = service.Builder().setSession("Wayline VPN")
        val mtu = call(options, "getMTU") as? Number
        if (mtu != null && mtu.toInt() > 0) builder.setMtu(mtu.toInt())
        addPrefixes(builder, options, "getInet4Address", true, false)
        addPrefixes(builder, options, "getInet6Address", true, false)
        val autoRoute = call(options, "getAutoRoute") as? Boolean ?: true
        if (autoRoute) {
            addPrefixes(builder, options, "getInet4RouteRange", false, true)
            addPrefixes(builder, options, "getInet6RouteRange", false, true)
        } else {
            addPrefixes(builder, options, "getInet4RouteAddress", false, true)
            addPrefixes(builder, options, "getInet6RouteAddress", false, true)
        }
        val dns = runCatching { call(options, "getDNSServerAddress") }.getOrNull()
        consume(dns) { address -> runCatching { builder.addDnsServer(address) } }
        consume(call(options, "getIncludePackage")) { name -> runCatching { builder.addAllowedApplication(name) } }
        consume(call(options, "getExcludePackage")) { name -> runCatching { builder.addDisallowedApplication(name) } }
        val established = builder.establish() ?: throw IllegalStateException("Android не выдал VPN-интерфейс")
        val fd = established.detachFd()
        tunFd = fd
        return fd
    }

    private fun addPrefixes(builder: VpnService.Builder, options: Any, getter: String, address: Boolean, route: Boolean) {
        val iterator = runCatching { call(options, getter) }.getOrNull() ?: return
        consume(iterator) { item ->
            val ip = call(item, "address") as? String ?: return@consume
            val prefix = (call(item, "prefix") as? Number)?.toInt() ?: return@consume
            runCatching { if (address) builder.addAddress(ip, prefix) else if (route) builder.addRoute(ip, prefix) }
        }
    }

    private fun call(target: Any, name: String): Any? {
        val method = target.javaClass.methods.firstOrNull { it.name.equals(name, true) && it.parameterTypes.isEmpty() }
            ?: return null
        return method.invoke(target)
    }

    private fun consume(iterator: Any?, action: (String) -> Unit) {
        if (iterator == null) return
        runCatching {
            val has = iterator.javaClass.methods.first { it.name.equals("hasNext", true) }
            val next = iterator.javaClass.methods.first { it.name.equals("next", true) }
            var count = 0
            while ((has.invoke(iterator) as? Boolean) == true && count++ < 4096) {
                val value = next.invoke(iterator)
                if (value is String) action(value)
            }
        }
    }

    private fun emptyIterator(loader: ClassLoader, interfaceName: String): Any? = runCatching {
        val type = Class.forName("libbox.$interfaceName", true, loader)
        Proxy.newProxyInstance(loader, arrayOf(type)) { _, method, _ ->
            when (method.name.lowercase()) { "hasnext" -> false; "next" -> null; else -> defaultValue(method.returnType) }
        }
    }.getOrNull()

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE, java.lang.Long.TYPE, java.lang.Short.TYPE, java.lang.Byte.TYPE -> 0
        java.lang.Float.TYPE, java.lang.Double.TYPE -> 0.0
        else -> null
    }

    @Synchronized fun stop() {
        commandServer?.let { server ->
            runCatching { server.javaClass.methods.first { it.name.equals("closeService", true) && it.parameterTypes.isEmpty() }.invoke(server) }
            runCatching { server.javaClass.methods.first { it.name.equals("close", true) && it.parameterTypes.isEmpty() }.invoke(server) }
        }
        commandServer = null
        closeTun()
    }

    private fun closeTun() { tunFd?.let { fd -> runCatching { ParcelFileDescriptor.adoptFd(fd).close() } }; tunFd = null }
    private fun root(error: Throwable): Throwable = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
}
