package app.wayline.vpn

import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Runtime adapter for the gomobile API shipped by singbox-android/libbox.
 * The package and lifecycle follow the public Android integration API:
 * io.nekohasekai.libbox.Libbox.setup(SetupOptions), CommandServer(handler, platform),
 * and CommandServer.startOrReloadService(config, OverrideOptions).
 */
internal class LibboxBridge(private val service: WaylineVpnService) {
    private val boxPackage = "io.nekohasekai.libbox."
    private var commandServer: Any? = null
    private var tunFd: Int? = null
    private var defaultInterfaceCallback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start(configJson: String) {
        require(configJson.isNotBlank()) { "sing-box config is empty" }
        stop()

        val loader = service.classLoader ?: javaClass.classLoader!!
        val setupOptionsType = load("SetupOptions", loader)
        val setupOptions = setupOptionsType.getDeclaredConstructor().newInstance()
        setProperty(setupOptions, "basePath", service.filesDir.absolutePath)
        setProperty(setupOptions, "workingPath", (service.getExternalFilesDir(null) ?: service.filesDir).absolutePath)
        setProperty(setupOptions, "tempPath", service.cacheDir.absolutePath)
        setProperty(setupOptions, "logMaxLines", 3000)

        val libbox = load("Libbox", loader)
        val setup = libbox.methods.firstOrNull {
            it.name.equals("setup", true) && it.parameterTypes.size == 1 &&
                it.parameterTypes[0].isAssignableFrom(setupOptionsType)
        } ?: throw IllegalStateException("В libbox 1.14.0 не найден Libbox.setup(SetupOptions)")
        invoke(setup, null, setupOptions)

        val platformType = load("PlatformInterface", loader)
        val handlerType = load("CommandServerHandler", loader)
        val platform = Proxy.newProxyInstance(loader, arrayOf(platformType)) { proxy, method, args ->
            when (method.name.lowercase()) {
                "tostring" -> "WaylinePlatformInterface"
                "hashcode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "useplatformautodetectinterfacecontrol" -> true
                "autodetectinterfacecontrol" -> {
                    val fd = (args?.getOrNull(0) as? Number)?.toInt()
                        ?: throw IllegalStateException("Libbox не передал TUN socket fd")
                    if (!service.protect(fd)) throw IllegalStateException("Не удалось исключить сокет sing-box из VPN")
                    null
                }
                "opentun" -> openTun(args?.getOrNull(0) ?: error("Libbox не передал TunOptions"))
                "useprocfs", "undernetworkextension", "includeallnetworks" -> false
                "getinterfaces" -> networkInterfaces(loader)
                "startdefaultinterfacemonitor" -> {
                    args?.getOrNull(0)?.let { notifyDefaultInterface(it) }
                    null
                }
                "closedefaultinterfacemonitor", "cleardnscache", "registermyinterface",
                "startneighbormonitor", "closeneighbormonitor" -> null
                "findconnectionowner", "localdnstransport", "readwifistate" -> null
                "systemcertificates" -> iteratorProxy(loader, "StringIterator", emptyList())
                "sendnotification" -> null
                else -> defaultValue(method.returnType)
            }
        }

        val handler = Proxy.newProxyInstance(loader, arrayOf(handlerType)) { proxy, method, args ->
            when (method.name.lowercase()) {
                "tostring" -> "WaylineCommandServerHandler"
                "hashcode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "getsystemproxystatus" -> {
                    val statusType = load("SystemProxyStatus", loader)
                    statusType.getDeclaredConstructor().newInstance().also {
                        setProperty(it, "available", false)
                        setProperty(it, "enabled", false)
                    }
                }
                "servicestop" -> { service.onCoreStopped(); null }
                "servicereload", "setsystemproxyenabled", "triggernativecrash",
                "writedebugmessage", "connectsshagent", "writemessage", "seterror" -> null
                else -> defaultValue(method.returnType)
            }
        }

        val commandType = load("CommandServer", loader)
        val constructor = commandType.constructors.firstOrNull {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0].isAssignableFrom(handlerType) &&
                it.parameterTypes[1].isAssignableFrom(platformType)
        } ?: commandType.constructors.firstOrNull { it.parameterTypes.size == 2 }
            ?: throw IllegalStateException("В libbox не найден CommandServer(handler, platform)")
        val server = invoke(constructor, handler, platform)
            ?: throw IllegalStateException("Не удалось создать CommandServer")
        invoke(server.javaClass.methods.first { it.name.equals("start", true) && it.parameterTypes.isEmpty() }, server)

        try {
            val overrideType = load("OverrideOptions", loader)
            val overrideOptions = overrideType.getDeclaredConstructor().newInstance()
            val start = server.javaClass.methods.firstOrNull {
                it.name.equals("startOrReloadService", true) &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java
            } ?: throw IllegalStateException("В libbox не найден StartOrReloadService(config, OverrideOptions)")
            invoke(start, server, configJson, overrideOptions)
            commandServer = server
        } catch (error: Throwable) {
            runCatching { server.javaClass.methods.first { it.name.equals("closeService", true) }.invoke(server) }
            runCatching { server.javaClass.methods.first { it.name.equals("close", true) }.invoke(server) }
            closeTun()
            throw IllegalStateException("sing-box не запустился: ${root(error).message}", root(error))
        }
    }

    private fun openTun(options: Any): Int {
        if (VpnService.prepare(service) != null) {
            throw IllegalStateException("Android VPN permission has not been granted")
        }
        closeTun()
        val builder = service.Builder().setSession("Wayline VPN")
        val mtu = call(options, "getMtu") as? Number
        if (mtu != null && mtu.toInt() > 0) builder.setMtu(mtu.toInt())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        addAddressList(builder, options, "getInet4Address")
        addAddressList(builder, options, "getInet6Address")

        val autoRoute = call(options, "getAutoRoute") as? Boolean ?: true
        if (autoRoute) {
            val route4 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "getInet4RouteAddress" else "getInet4RouteRange"
            val route6 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "getInet6RouteAddress" else "getInet6RouteRange"
            addRouteList(builder, options, route4)
            addRouteList(builder, options, route6)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                addExcludeRouteList(builder, options, "getInet4RouteExcludeAddress")
                addExcludeRouteList(builder, options, "getInet6RouteExcludeAddress")
            }
            consume(call(options, "getDNSServerAddress")) { address ->
                runCatching { builder.addDnsServer(address) }
            }
        }

        consume(call(options, "getIncludePackage")) { packageName ->
            builder.addAllowedApplication(packageName)
        }
        consume(call(options, "getExcludePackage")) { packageName ->
            builder.addDisallowedApplication(packageName)
        }

        val descriptor = builder.establish()
            ?: throw IllegalStateException("Android не выдал VPN-интерфейс (разрешение отозвано?)")
        val fd = descriptor.detachFd()
        tunFd = fd
        return fd
    }

    private fun addAddressList(builder: VpnService.Builder, options: Any, getter: String) {
        consumeObjects(call(options, getter)) { prefix ->
            val address = call(prefix, "address") as? String ?: return@consumeObjects
            val length = (call(prefix, "prefix") as? Number)?.toInt() ?: return@consumeObjects
            builder.addAddress(address, length)
        }
    }

    private fun addRouteList(builder: VpnService.Builder, options: Any, getter: String) {
        consumeObjects(call(options, getter)) { prefix ->
            val address = call(prefix, "address") as? String ?: return@consumeObjects
            val length = (call(prefix, "prefix") as? Number)?.toInt() ?: return@consumeObjects
            builder.addRoute(address, length)
        }
    }

    private fun addExcludeRouteList(builder: VpnService.Builder, options: Any, getter: String) {
        consumeObjects(call(options, getter)) { prefix ->
            val address = call(prefix, "address") as? String ?: return@consumeObjects
            val length = (call(prefix, "prefix") as? Number)?.toInt() ?: return@consumeObjects
            runCatching {
                builder.excludeRoute(IpPrefix(InetAddress.getByName(address), length))
            }
        }
    }

    private fun networkInterfaces(loader: ClassLoader): Any {
        val itemType = load("NetworkInterface", loader)
        val values = mutableListOf<Any>()
        val all = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return iteratorProxy(loader, "NetworkInterfaceIterator", values)
        val connectivity = service.getSystemService(ConnectivityManager::class.java)
        while (all.hasMoreElements()) {
            val networkInterface = all.nextElement()
            runCatching {
                val item = itemType.getDeclaredConstructor().newInstance()
                setProperty(item, "name", networkInterface.name)
                setProperty(item, "index", networkInterface.index)
                setProperty(item, "mtu", networkInterface.mtu)
                setProperty(item, "flags", 0)
                setProperty(item, "type", 0)
                setProperty(item, "metered", false)
                setProperty(item, "addresses", iteratorProxy(loader, "StringIterator",
                    networkInterface.interfaceAddresses.mapNotNull { it.address?.hostAddress?.let { ip -> "$ip/${it.networkPrefixLength}" } }))
                val link = connectivity?.getLinkProperties(connectivity.activeNetwork)
                if (link?.interfaceName == networkInterface.name) {
                    setProperty(item, "dnsServer", iteratorProxy(loader, "StringIterator", link.dnsServers.mapNotNull { it.hostAddress }))
                    setProperty(item, "gateway", iteratorProxy(loader, "StringIterator",
                        link.routes.filter { it.destination.prefixLength == 0 }.mapNotNull { it.gateway?.hostAddress }))
                } else {
                    setProperty(item, "dnsServer", iteratorProxy(loader, "StringIterator", emptyList()))
                    setProperty(item, "gateway", iteratorProxy(loader, "StringIterator", emptyList()))
                }
                setProperty(item, "dnsSearchDomain", iteratorProxy(loader, "StringIterator", emptyList()))
                values.add(item)
            }
        }
        return iteratorProxy(loader, "NetworkInterfaceIterator", values)
    }

    private fun notifyDefaultInterface(listener: Any) {
        val connectivity = service.getSystemService(ConnectivityManager::class.java) ?: return
        val network: Network? = connectivity.activeNetwork
        val link = network?.let { connectivity.getLinkProperties(it) }
        val name = link?.interfaceName.orEmpty()
        val index = runCatching { NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        runCatching {
            listener.javaClass.methods.firstOrNull {
                it.name.equals("updateDefaultInterface", true) && it.parameterTypes.size == 4
            }?.invoke(listener, name, index, false, false)
        }
    }

    private fun iteratorProxy(loader: ClassLoader, interfaceName: String, items: List<Any>): Any {
        val type = load(interfaceName, loader)
        val iterator = items.iterator()
        return Proxy.newProxyInstance(loader, arrayOf(type)) { proxy, method, args ->
            when (method.name.lowercase()) {
                "hasnext" -> iterator.hasNext()
                "next" -> iterator.next()
                "len" -> items.size
                "tostring" -> "$interfaceName(${items.size})"
                "hashcode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun consume(iterator: Any?, action: (String) -> Unit) {
        consumeObjects(iterator) { value -> if (value is String) action(value) }
    }

    private fun consumeObjects(iterator: Any?, action: (Any) -> Unit) {
        if (iterator == null) return
        val hasNext = iterator.javaClass.methods.firstOrNull { it.name.equals("hasNext", true) } ?: return
        val next = iterator.javaClass.methods.firstOrNull { it.name.equals("next", true) } ?: return
        var count = 0
        while ((invoke(hasNext, iterator) as? Boolean) == true && count++ < 4096) {
            val item = invoke(next, iterator) ?: continue
            action(item)
        }
    }

    private fun call(target: Any, name: String): Any? {
        val method = target.javaClass.methods.firstOrNull { it.name.equals(name, true) && it.parameterTypes.isEmpty() } ?: return null
        return invoke(method, target)
    }

    private fun setProperty(target: Any, property: String, value: Any) {
        val setterName = "set" + property.replaceFirstChar { it.uppercase() }
        val setter = target.javaClass.methods.firstOrNull { it.name.equals(setterName, true) && it.parameterTypes.size == 1 } ?: return
        invoke(setter, target, value)
    }

    private fun load(name: String, loader: ClassLoader): Class<*> = Class.forName(boxPackage + name, true, loader)

    private fun invoke(method: java.lang.reflect.Method, target: Any?, vararg args: Any?): Any? =
        try { method.invoke(target, *args) } catch (error: Throwable) { throw root(error) }

    private fun invoke(constructor: java.lang.reflect.Constructor<*>, vararg args: Any?): Any? =
        try { constructor.newInstance(*args) } catch (error: Throwable) { throw root(error) }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE, java.lang.Long.TYPE, java.lang.Short.TYPE, java.lang.Byte.TYPE -> 0
        java.lang.Float.TYPE, java.lang.Double.TYPE -> 0.0
        else -> null
    }

    @Synchronized
    fun stop() {
        commandServer?.let { server ->
            runCatching { server.javaClass.methods.first { it.name.equals("closeService", true) && it.parameterTypes.isEmpty() }.invoke(server) }
            runCatching { server.javaClass.methods.first { it.name.equals("close", true) && it.parameterTypes.isEmpty() }.invoke(server) }
        }
        commandServer = null
        defaultInterfaceCallback = null
        closeTun()
    }

    private fun closeTun() {
        tunFd?.let { fd -> runCatching { ParcelFileDescriptor.adoptFd(fd).close() } }
        tunFd = null
    }

    private fun root(error: Throwable): Throwable =
        (error as? InvocationTargetException)?.targetException ?: error
}
