package xyz.anekol.silentwg

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.net.InetAddress

/**
 * 全局 DNS 接管（等效官方 WireGuard app 的 VpnService.addDnsServer）。
 *
 * 经真实 DEX 逆向证实：
 * HyperOS 4 / Android 17 的核心网络下发全部汇聚于：
 *  1) android.net.connectivity.android.net.IDnsResolver$Stub$Proxy.setResolverConfiguration(ResolverParamsParcel parcel)
 *     -> 无论哪个网络(WiFi/蜂窝/VPN)，下发到底层 netd resolver 的终极管道！
 *     -> parcel.servers 存放 DNS 列表
 *     -> parcel.tlsServers / tlsName 存放 DoT
 *  2) android.net.connectivity.com.android.server.ConnectivityService.updateDnses(LinkProperties newLp, LinkProperties oldLp, int netId)
 *     -> 上层判定 DNS 变更并分发给 DnsManager 的源头！
 */
class Hook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "SilentWG"
        private const val PROP = "persist.silentwg.dns"

        private const val TARGET_RESOLVER_PROXY = "android.net.connectivity.android.net.IDnsResolver\$Stub\$Proxy"
        private const val TARGET_RESOLVER_INTERFACE = "android.net.connectivity.android.net.IDnsResolver"
        private const val TARGET_CS = "android.net.connectivity.com.android.server.ConnectivityService"
    }

    @Volatile private var resolverHooked = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return   // 只作用于 system_server

        // 尝试在主 ClassLoader 预挂
        tryHookConnectivity(lpparam.classLoader, "primary")

        // 通过 SystemServiceManager 捕获 ConnectivityServiceInitializer 专属 ClassLoader
        try {
            val ssm = XposedHelpers.findClass("com.android.server.SystemServiceManager", lpparam.classLoader)
            val svcCapture = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val r = param.result ?: return
                    val cl = r.javaClass.classLoader ?: return
                    val simpleName = r.javaClass.simpleName
                    if (simpleName.contains("Connectivity", ignoreCase = true) ||
                        simpleName.contains("Tethering", ignoreCase = true) ||
                        simpleName.contains("Network", ignoreCase = true)) {
                        tryHookConnectivity(cl, "ssm:$simpleName")
                    }
                }
            }
            for (m in listOf("startService", "startServiceFromSource")) {
                XposedBridge.hookAllMethods(ssm, m, svcCapture)
            }
            XposedBridge.log("$TAG: SSM hooks armed")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: SSM hook failed: ${t.javaClass.simpleName}")
        }
    }

    private fun tryHookConnectivity(cl: ClassLoader, via: String) {
        if (resolverHooked) return

        var hookedCount = 0

        // 1. 终极管道：IDnsResolver$Stub$Proxy.setResolverConfiguration
        for (targetName in listOf(TARGET_RESOLVER_PROXY, TARGET_RESOLVER_INTERFACE)) {
            try {
                val clz = XposedHelpers.findClass(targetName, cl)
                val hooks = XposedBridge.hookAllMethods(clz, "setResolverConfiguration", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val dns = readDns()
                        if (dns.isEmpty()) return
                        val parcel = param.args.getOrNull(0) ?: return

                        try {
                            val servers = XposedHelpers.getObjectField(parcel, "servers") as? Array<String>
                            if (servers != null && !servers.contains(dns)) {
                                XposedHelpers.setObjectField(parcel, "servers", arrayOf(dns))
                                XposedBridge.log("$TAG: [ResolverProxy] DNS takeover ${servers.joinToString()} -> $dns")
                            }

                            // 禁用 DoT 阻断绕过
                            val tlsServers = XposedHelpers.getObjectField(parcel, "tlsServers") as? Array<String>
                            if (tlsServers != null && tlsServers.isNotEmpty()) {
                                XposedHelpers.setObjectField(parcel, "tlsServers", arrayOf<String>())
                            }
                            val tlsName = XposedHelpers.getObjectField(parcel, "tlsName") as? String
                            if (!tlsName.isNullOrEmpty()) {
                                XposedHelpers.setObjectField(parcel, "tlsName", "")
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG: parcel rewrite error: $t")
                        }
                    }
                })
                if (hooks.isNotEmpty()) {
                    XposedBridge.log("$TAG: [$via] Hooked $targetName.setResolverConfiguration (${hooks.size} overloads)")
                    hookedCount++
                }
            } catch (t: Throwable) {
                // Ignore class not found on non-matching CL
            }
        }

        // 2. 上层源头：ConnectivityService.updateDnses(LinkProperties newLp, LinkProperties oldLp, int netId)
        try {
            val csClz = XposedHelpers.findClass(TARGET_CS, cl)
            val csHooks = XposedBridge.hookAllMethods(csClz, "updateDnses", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val dns = readDns()
                    if (dns.isEmpty()) return
                    val newLp = param.args.getOrNull(0) ?: return

                    try {
                        val currentServers = XposedHelpers.callMethod(newLp, "getDnsServers") as? Collection<*>
                        val currentStr = currentServers?.joinToString { it.toString() } ?: ""
                        if (!currentStr.contains(dns)) {
                            val targetAddr = InetAddress.getByName(dns)
                            XposedHelpers.callMethod(newLp, "setDnsServers", listOf(targetAddr))
                            XposedBridge.log("$TAG: [CS.updateDnses] Overwrote newLp DNS servers [$currentStr] -> [$dns]")
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: CS.updateDnses rewrite error: $t")
                    }
                }
            })
            if (csHooks.isNotEmpty()) {
                XposedBridge.log("$TAG: [$via] Hooked ConnectivityService.updateDnses (${csHooks.size} overloads)")
                hookedCount++
            }
        } catch (t: Throwable) {
            // Ignore
        }

        if (hookedCount > 0) {
            resolverHooked = true
            XposedBridge.log("$TAG: [$via] Successfully armed all DNS resolvers!")
        }
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
