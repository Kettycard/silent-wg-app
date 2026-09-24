package xyz.anekol.silentwg

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 全局 DNS 接管（等效官方 WireGuard app 的 VpnService.addDnsServer）。
 *
 * HyperOS/Android 16+：DnsManager 位于 tethering APEX（service-connectivity.jar），
 * 类名重定位为 android.net.connectivity.<原名>，且由 APEX 专用 ClassLoader 加载，
 * system_server 主 CL 直接 findClass 会 ClassNotFoundError。
 *
 * v1.5 策略：
 *  1) 主 CL 直接尝试（部分 ROM 并入主 CL）
 *  2) hook ClassLoader.loadClass，捕获任意 android.net.connectivity.* 类首次加载，
 *     从返回的 Class 反查定义 ClassLoader，在其上挂 DnsManager —— 自发现，免猜类名
 *  3) 属性轮询：persist.silentwg.dns 变化时用缓存的真实调用参数重发一次（免飞行模式）
 */
class Hook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "SilentWG"
        private const val PROP = "persist.silentwg.dns"
        private const val DM_RELOCATED = "android.net.connectivity.com.android.server.connectivity.DnsManager"
        private const val DM_ORIGINAL = "com.android.server.connectivity.DnsManager"
        private const val NMS = "com.android.server.net.NetworkManagementService"
        private const val CL_PREFIX = "android.net.connectivity."
    }

    @Volatile private var cachedThis: Any? = null
    @Volatile private var cachedArgs: Array<out Any?>? = null
    @Volatile private var dnsHookInstalled = false

    /** 核心 hook：改写 DNS 配置下发（新旧签名通吃） */
    private val rewriter = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val dns = readDns()
            if (dns.isEmpty()) return
            if (param.args.isEmpty()) return

            // 缓存真实调用（实例+参数副本）：开关切换时轮询线程主动重调
            try {
                cachedThis = param.thisObject
                cachedArgs = param.args.copyOf()
            } catch (t: Throwable) { /* 缓存失败不影响主改写 */ }

            val a1: Any? = param.args.getOrNull(1)
            if (a1 is Array<*>) {
                // 老签名: (netId, String[] servers, ...)
                val ns = a1 as? Array<String>
                if (ns != null && !ns.contains(dns)) {
                    param.args[1] = arrayOf(dns)
                    XposedBridge.log("$TAG: DNS takeover ${ns.joinToString()} -> $dns")
                }
                if (param.args.size >= 6) {
                    val tlsArg = param.args[5]
                    if (tlsArg != null && tlsArg is Array<*> && tlsArg.isNotEmpty()) {
                        param.args[5] = arrayOf<String>()
                        XposedBridge.log("$TAG: DoT disabled to prevent bypass")
                    }
                }
            } else if (a1 != null && a1.javaClass.name.endsWith("ResolverParamsParcel")) {
                // APEX 新签名: (netId, ResolverParamsParcel params) —— 字段反射改写
                try {
                    val ns = XposedHelpers.getObjectField(a1, "dnsServers") as? Array<String>
                    if (ns != null && !ns.contains(dns)) {
                        XposedHelpers.setObjectField(a1, "dnsServers", arrayOf(dns))
                        XposedBridge.log("$TAG: DNS takeover(parcel) ${ns.joinToString()} -> $dns")
                    }
                    val tls = XposedHelpers.getObjectField(a1, "tlsServers") as? Array<String>
                    if (tls != null && tls.isNotEmpty()) {
                        XposedHelpers.setObjectField(a1, "tlsServers", arrayOf<String>())
                        XposedBridge.log("$TAG: DoT disabled (parcel)")
                    }
                    val tlsName = XposedHelpers.getObjectField(a1, "tlsName")
                    if (tlsName is String && tlsName.isNotEmpty()) {
                        XposedHelpers.setObjectField(a1, "tlsName", "")
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: parcel rewrite failed: $t")
                }
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return   // 只作用于 system_server

        // 诊断：boot classpath 里的 connectivity 相关 jar
        try {
            val bcp = System.getProperty("java.boot.class.path")
                ?: System.getProperty("sun.boot.class.path") ?: ""
            val rel = bcp.split(":").filter { it.contains("connectivity") || it.contains("tethering") }
            XposedBridge.log("$TAG: boot-jars: ${rel.joinToString(", ").ifEmpty { "none" }}")
        } catch (t: Throwable) { }

        // 1) 主 CL 直接尝试
        tryInstall(lpparam.classLoader, "primary")

        // 2) 捕获 tethering APEX ClassLoader：任何 connectivity 前缀类加载即反查其 CL
        try {
            XposedHelpers.findAndHookMethod(
                ClassLoader::class.java, "loadClass",
                String::class.java, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (dnsHookInstalled) return
                        val name = param.args.getOrNull(0) as? String ?: return
                        val res = param.result ?: return
                        if (name.startsWith(CL_PREFIX)) {
                            val cl = res.javaClass.classLoader ?: return
                            tryInstall(cl, "captured:${name.substringAfterLast('.')}")
                        }
                    }
                }
            )
            XposedBridge.log("$TAG: loadClass trap armed")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: loadClass hook failed: $t")
        }

        // 2b) SystemServiceManager.startService：ConnectivityService 从这里起，
        //     返回的 service 实例的 ClassLoader 就是 tethering APEX 专用 CL
        try {
            val ssm = XposedHelpers.findClass("com.android.server.SystemServiceManager", lpparam.classLoader)
            val svcCapture = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (dnsHookInstalled) return
                    val r = param.result ?: return
                    val cl = r.javaClass.classLoader ?: return
                    tryInstall(cl, "ssm:${r.javaClass.simpleName}")
                }
            }
            for (m in listOf("startService", "startServiceFromSource")) {
                val n = XposedBridge.hookAllMethods(ssm, m, svcCapture)
                XposedBridge.log("$TAG: SSM.$m armed ($n overloads)")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: SSM hook failed: ${t.javaClass.simpleName}")
        }

        // 3) NMS 兜底（framework 主 CL；方法名为空集是常态，命中算意外之喜）
        try {
            val nms = XposedHelpers.findClass(NMS, lpparam.classLoader)
            for (m in listOf("setDnsConfigurationForNetwork", "setDnsServersForNetwork", "setDnsServers")) {
                val n = XposedBridge.hookAllMethods(nms, m, rewriter)
                if (n.isNotEmpty()) XposedBridge.log("$TAG: hooked NMS.$m (${n.size})")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: NMS unavailable -> ${t.javaClass.simpleName}")
        }

        startPropPoller()
    }

    /** 在给定 ClassLoader 上挂 DnsManager（重定位名与原始名都试） */
    private fun tryInstall(cl: ClassLoader, via: String) {
        if (dnsHookInstalled) return
        for (name in listOf(DM_RELOCATED, DM_ORIGINAL)) {
            try {
                val clz = XposedHelpers.findClass(name, cl)
                val a = XposedBridge.hookAllMethods(clz, "setDnsConfigurationForNetwork", rewriter)
                val b = XposedBridge.hookAllMethods(clz, "setDnsServers", rewriter)
                XposedBridge.log("$TAG: DnsManager@[$via] $name hooked (setDnsConfig=${a.size}, setDnsServers=${b.size})")
                if (a.isNotEmpty() || b.isNotEmpty()) {
                    dnsHookInstalled = true
                    return
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: DnsManager@[$via] not loadable: ${t.javaClass.simpleName}")
            }
        }
    }

    /** 属性变化 → 用缓存的真实调用参数主动重调（走进 rewriter 自动改写），2s 内生效 */
    private fun startPropPoller() {
        Thread {
            var last = readDns()
            while (true) {
                try {
                    Thread.sleep(2000)
                    val cur = readDns()
                    if (cur != last) {
                        last = cur
                        val ths = cachedThis
                        val args = cachedArgs
                        if (ths != null && args != null) {
                            val m = ths.javaClass.declaredMethods.firstOrNull {
                                it.name == "setDnsConfigurationForNetwork" && it.parameterTypes.size == args.size
                            }
                            if (m != null) {
                                m.isAccessible = true
                                m.invoke(ths, *args)
                                XposedBridge.log("$TAG: prop changed -> re-issued setDnsConfigurationForNetwork (dns=$cur)")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: poller: $t")
                }
            }
        }.apply { isDaemon = true; name = "SilentWG-prop-poller" }.start()
    }

    private fun readDns(): String {
        return try {
            val cl = XposedHelpers.findClass("android.os.SystemProperties", null)
            XposedHelpers.callStaticMethod(cl, "get", PROP, "") as? String ?: ""
        } catch (t: Throwable) {
            ""
        }
    }
}
