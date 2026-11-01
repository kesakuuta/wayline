package app.wayline.vpn

import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.util.Log
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.system.OsConstants
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
    companion object {
        private val setupLock = Any()
        @Volatile private var setupComplete = false
    }

    private val boxPackage = "io.nekohasekai.libbox."
    private var commandServer: Any? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var defaultInterfaceCallback: ConnectivityManager.NetworkCallback? = null
    private var networkCallbackThread: HandlerThread? = null

    @Synchronized
    fun start(configJson: String) {
        require(configJson.isNotBlank()) { "sing-box config is empty" }
        stop()

        val loader = service.classLoader ?: javaClass.classLoader!!
        val libbox = load("Libbox", loader)
        synchronized(setupLock) {
            if (!setupComplete) {
                runCatching {
                    libbox.methods.firstOrNull {
                        it.name.equals("setLocale", true) && it.parameterTypes.contentEquals(arrayOf(String::class.java))
                    }?.invoke(null, java.util.Locale.getDefault().toLanguageTag())
                }
                val setupOptionsType = load("SetupOptions", loader)
                val setupOptions = setupOptionsType.getDeclaredConstructor().newInstance()
                setProperty(setupOptions, "basePath", service.filesDir.absolutePath)
                setProperty(setupOptions, "workingPath", (service.getExternalFilesDir(null) ?: service.filesDir).absolutePath)
                setProperty(setupOptions, "tempPath", service.cacheDir.absolutePath)
                setProperty(setupOptions, "logMaxLines", 3000)
                setProperty(setupOptions, "fixAndroidStack", true)
                val setup = libbox.methods.firstOrNull {
                    it.name.equals("setup", true) && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0].isAssignableFrom(setupOptionsType)
                } ?: throw IllegalStateException("В libbox не найден Libbox.setup(SetupOptions)")
                invoke(setup, null, setupOptions)
                setupComplete = true
            }
        }

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
                "opentun" -> { val fd = openTun(args?.getOrNull(0) ?: error("Libbox не передал TunOptions")); if (method.returnType == java.lang.Long.TYPE || method.returnType == java.lang.Long::class.java) fd.toLong() else fd }
                "useprocfs", "undernetworkextension", "includeallnetworks" -> false
                "getinterfaces" -> networkInterfaces(loader)
                "startdefaultinterfacemonitor" -> {
                    args?.getOrNull(0)?.let { startDefaultInterfaceMonitor(it) }
                    null
                }
                "closedefaultinterfacemonitor" -> {
                    closeDefaultInterfaceMonitor()
                    null
                }
                "cleardnscache", "registermyinterface", "startneighbormonitor", "closeneighbormonitor" -> null
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
                "seterror" -> {
                    val message = args?.firstOrNull()?.toString()?.takeIf { it.isNotBlank() } ?: "Неизвестная ошибка Libbox"
                    service.onCoreError(message)
                    null
                }
                "writedebugmessage", "writemessage" -> {
                    Log.i("WaylineLibbox", args?.firstOrNull()?.toString().orEmpty())
                    null
                }
                "servicereload", "setsystemproxyenabled", "triggernativecrash", "connectsshagent" -> null
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
        val builder = service.Builder().setSession("Wayline")
        val mtu = call(options, "getMtu") as? Number
        if (mtu != null && mtu.toInt() > 0) builder.setMtu(mtu.toInt())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val ipv4AddressCount = addAddressList(builder, options, "getInet4Address")
        val ipv6AddressCount = addAddressList(builder, options, "getInet6Address")
        if (ipv4AddressCount + ipv6AddressCount == 0) {
            throw IllegalStateException("sing-box не передал адреса для Android VPN-интерфейса")
        }

        val autoRoute = call(options, "getAutoRoute") as? Boolean ?: true
        if (autoRoute) {
            if (!dnsDisabled(options)) {
                consume(call(options, "getDNSServerAddress")) { address ->
                    builder.addDnsServer(address)
                }
            }
            val ipv4RouteCount: Int
            val ipv6RouteCount: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ipv4RouteCount = addRouteList(builder, options, "getInet4RouteAddress")
                ipv6RouteCount = addRouteList(builder, options, "getInet6RouteAddress")
                addExcludeRouteList(builder, options, "getInet4RouteExcludeAddress")
                addExcludeRouteList(builder, options, "getInet6RouteExcludeAddress")
            } else {
                ipv4RouteCount = addRouteList(builder, options, "getInet4RouteRange")
                ipv6RouteCount = addRouteList(builder, options, "getInet6RouteRange")
            }
            if (ipv4AddressCount > 0 && ipv4RouteCount == 0) builder.addRoute("0.0.0.0", 0)
            if (ipv6AddressCount > 0 && ipv6RouteCount == 0) builder.addRoute("::", 0)
            consume(call(options, "getIncludePackage")) { packageName ->
                builder.addAllowedApplication(packageName)
            }
            consume(call(options, "getExcludePackage")) { packageName ->
                builder.addDisallowedApplication(packageName)
            }
        }

        val descriptor = builder.establish()
            ?: throw IllegalStateException("Android не выдал VPN-интерфейс (разрешение отозвано?)")
        tunDescriptor = descriptor
        return descriptor.fd
    }

    private fun hasNext(iterator: Any?): Boolean {
        if (iterator == null) return false
        val method = iterator.javaClass.methods.firstOrNull { it.name.equals("hasNext", true) } ?: return false
        return runCatching { invoke(method, iterator) as? Boolean == true }.getOrDefault(false)
    }

    private fun dnsDisabled(options: Any): Boolean {
        val mode = call(options, "getDnsMode") ?: return false
        val value = (call(mode, "getValue") as? Number)?.toInt() ?: return false
        val disabled = runCatching { load("Libbox", service.classLoader ?: javaClass.classLoader!!).getField("DNSModeDisabled").getInt(null) }.getOrNull()
        return disabled != null && value == disabled
    }

    private fun addAddressList(builder: VpnService.Builder, options: Any, getter: String): Int {
        var added = 0
        consumeObjects(call(options, getter)) { prefix ->
            val address = call(prefix, "address") as? String ?: return@consumeObjects
            val length = (call(prefix, "prefix") as? Number)?.toInt() ?: return@consumeObjects
            builder.addAddress(address, length)
            added++
        }
        return added
    }

    private fun addRouteList(builder: VpnService.Builder, options: Any, getter: String): Int {
        var added = 0
        consumeObjects(call(options, getter)) { prefix ->
            val address = call(prefix, "address") as? String ?: return@consumeObjects
            val length = (call(prefix, "prefix") as? Number)?.toInt() ?: return@consumeObjects
            builder.addRoute(address, length)
            added++
        }
        return added
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
        val connectivity = service.getSystemService(ConnectivityManager::class.java)
            ?: return iteratorProxy(loader, "NetworkInterfaceIterator", emptyList())
        val values = mutableListOf<Any>()

        // Mirror the official sing-box Android platform adapter: resolve each Android
        // Network separately. Do not reuse one Wi-Fi/cellular type for every interface.
        for (network in connectivity.allNetworks) {
            runCatching {
                val link = connectivity.getLinkProperties(network) ?: return@runCatching
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@runCatching
                val name = link.interfaceName ?: return@runCatching
                val networkInterface = NetworkInterface.getByName(name) ?: return@runCatching
                val item = itemType.getDeclaredConstructor().newInstance()
                setProperty(item, "name", name)
                setProperty(item, "index", networkInterface.index)
                setProperty(item, "mtu", networkInterface.mtu)
                val flags = (if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                } else 0) or
                    (if (networkInterface.isLoopback) OsConstants.IFF_LOOPBACK else 0) or
                    (if (networkInterface.isPointToPoint) OsConstants.IFF_POINTOPOINT else 0) or
                    (if (networkInterface.supportsMulticast()) OsConstants.IFF_MULTICAST else 0)
                setProperty(item, "flags", flags)
                val interfaceType = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> libboxConstant(load("Libbox", loader), "InterfaceTypeWIFI")
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> libboxConstant(load("Libbox", loader), "InterfaceTypeCellular")
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> libboxConstant(load("Libbox", loader), "InterfaceTypeEthernet")
                    else -> libboxConstant(load("Libbox", loader), "InterfaceTypeOther")
                }
                setProperty(item, "type", interfaceType)
                setProperty(item, "metered", !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                setProperty(item, "addresses", iteratorProxy(loader, "StringIterator",
                    networkInterface.interfaceAddresses.mapNotNull { it.address?.hostAddress?.let { ip -> "$ip/${it.networkPrefixLength}" } }))
                setProperty(item, "dnsServer", iteratorProxy(loader, "StringIterator",
                    link.dnsServers.mapNotNull { it.hostAddress }))
                setProperty(item, "gateway", iteratorProxy(loader, "StringIterator",
                    link.routes.filter { it.destination.prefixLength == 0 }
                        .mapNotNull { it.gateway?.takeUnless(InetAddress::isAnyLocalAddress)?.hostAddress }))
                setProperty(item, "dnsSearchDomain", iteratorProxy(loader, "StringIterator",
                    link.domains?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()))
                values.add(item)
            }.onFailure {
                Log.w("WaylineLibbox", "Не удалось описать сетевой интерфейс", it)
            }
        }
        return iteratorProxy(loader, "NetworkInterfaceIterator", values)
    }

    private fun startDefaultInterfaceMonitor(listener: Any) {
        closeDefaultInterfaceMonitor()
        val connectivity = service.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        val thread = HandlerThread("Wayline-NetworkMonitor").apply { start() }
        val handler = Handler(thread.looper)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isVpnNetwork(connectivity, network)) return
                notifyDefaultInterface(listener, network)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    notifyDefaultInterface(listener, network)
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                if (!isVpnNetwork(connectivity, network)) {
                    notifyDefaultInterface(listener, network)
                }
            }

            override fun onLost(network: Network) {
                notifyDefaultInterface(listener, findUnderlyingNetwork(connectivity))
            }
        }
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    // requestNetwork selects the non-VPN network. registerDefaultNetworkCallback
                    // can return the VPN itself on Android 9+ and create a routing loop.
                    connectivity.requestNetwork(request, callback, handler)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    connectivity.registerDefaultNetworkCallback(callback, handler)
                }
                else -> connectivity.registerDefaultNetworkCallback(callback)
            }
            defaultInterfaceCallback = callback
            networkCallbackThread = thread
            notifyDefaultInterface(listener, findUnderlyingNetwork(connectivity))
        } catch (error: Throwable) {
            thread.quitSafely()
            Log.e("WaylineLibbox", "Не удалось отслеживать физическую сеть", error)
            notifyDefaultInterface(listener, findUnderlyingNetwork(connectivity))
        }
    }

    private fun closeDefaultInterfaceMonitor() {
        val connectivity = service.getSystemService(ConnectivityManager::class.java)
        val callback = defaultInterfaceCallback
        if (connectivity != null && callback != null) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
        defaultInterfaceCallback = null
        networkCallbackThread?.quitSafely()
        networkCallbackThread = null
    }

    private fun isVpnNetwork(connectivity: ConnectivityManager, network: Network): Boolean =
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private fun findUnderlyingNetwork(connectivity: ConnectivityManager): Network? {
        val networks = connectivity.allNetworks
        return networks.firstOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun notifyDefaultInterface(listener: Any, network: Network?) {
        val connectivity = service.getSystemService(ConnectivityManager::class.java) ?: return
        val physicalNetwork = network?.takeUnless { isVpnNetwork(connectivity, it) }
            ?: findUnderlyingNetwork(connectivity)
        val link = physicalNetwork?.let { connectivity.getLinkProperties(it) }
        val name = link?.interfaceName.orEmpty()
        val index = runCatching { NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        runCatching {
            listener.javaClass.methods.firstOrNull {
                it.name.equals("updateDefaultInterface", true) && it.parameterTypes.size == 4
            }?.invoke(listener, name, index, false, false)
        }.onFailure { Log.w("WaylineLibbox", "Не удалось обновить default interface", it) }
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
        val property = name.removePrefix("get").replaceFirstChar { it.lowercase() }
        val getter = "get" + property.replaceFirstChar { it.uppercase() }
        val booleanGetter = "is" + property.replaceFirstChar { it.uppercase() }
        val method = target.javaClass.methods.firstOrNull {
            it.parameterTypes.isEmpty() &&
                (it.name.equals(name, true) || it.name.equals(getter, true) || it.name.equals(booleanGetter, true))
        } ?: return null
        return invoke(method, target)
    }

    private fun setProperty(target: Any, property: String, value: Any) {
        val setterName = "set" + property.replaceFirstChar { it.uppercase() }
        val setter = target.javaClass.methods.firstOrNull { it.name.equals(setterName, true) && it.parameterTypes.size == 1 } ?: return
        invoke(setter, target, value)
    }

    private fun libboxConstant(libbox: Class<*>, name: String): Int =
        runCatching { libbox.getField(name).getInt(null) }.getOrDefault(0)

    private fun load(name: String, loader: ClassLoader): Class<*> = Class.forName(boxPackage + name, true, loader)

    private fun invoke(method: java.lang.reflect.Method, target: Any?, vararg args: Any?): Any? =
        try { method.invoke(target, *args) } catch (error: Throwable) { throw root(error) }

    private fun invoke(constructor: java.lang.reflect.Constructor<*>, vararg args: Any?): Any? =
        try { constructor.newInstance(*args) } catch (error: Throwable) { throw root(error) }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Float.TYPE -> 0.0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    @Synchronized
    internal fun releaseAfterCoreStopped() {
        val server = commandServer
        commandServer = null
        if (server != null) {
            runCatching {
                server.javaClass.methods.firstOrNull {
                    it.name.equals("closeService", true) && it.parameterTypes.isEmpty()
                }?.invoke(server)
            }
            runCatching {
                server.javaClass.methods.firstOrNull {
                    it.name.equals("close", true) && it.parameterTypes.isEmpty()
                }?.invoke(server)
            }
        }
        closeDefaultInterfaceMonitor()
        closeTun()
    }

    @Synchronized
    fun stop() {
        commandServer?.let { server ->
            runCatching { server.javaClass.methods.first { it.name.equals("closeService", true) && it.parameterTypes.isEmpty() }.invoke(server) }
            runCatching { server.javaClass.methods.first { it.name.equals("close", true) && it.parameterTypes.isEmpty() }.invoke(server) }
        }
        commandServer = null
        closeDefaultInterfaceMonitor()
        closeTun()
    }

    private fun closeTun() {
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
    }

    private fun root(error: Throwable): Throwable =
        (error as? InvocationTargetException)?.targetException ?: error
}
