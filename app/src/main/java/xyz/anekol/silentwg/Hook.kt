package xyz.anekol.silentwg

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 全局 DNS 接管（等效官方 WireGuard app 的 VpnService.Builder.addDnsServer）：
 * hook system_server 的 DnsManager.setDnsConfigurationForNetwork，
 * 把下发给 netd 的 nameserver 数组强制替换为内网 AGH（fakeip 链路入口），
 * 并清空 DoT(tlsServers) 防止私有 DNS 绕过。
 *
 * DNS 地址来源：persist.silentwg.dns 系统属性（App/WebUI 通过 su setprop 写入），
 * system_server 读取 system property 无 SELinux 障碍。
 */
class Hook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "SilentWG"
        private const val PROP = "persist.silentwg.dns"
        private const val DEFAULT_DNS = "192.168.6.115"
    }

    @Volatile private var cachedThis: Any? = null
    @Volatile private var cachedArgs: Array<out Any?>? = null

    /** 属性变化 → 用缓存的真实调用参数主动重调（hook 内自动改写），2s 内生效，免飞行模式 */
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

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return   // 只作用于 system_server

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val dns = readDns()
                if (dns.isEmpty()) return
                if (param.args.isEmpty()) return

                // 缓存真实调用（实例+参数副本）：开关切换时轮询线程用它主动重调，免飞行模式
                try {
                    cachedThis = param.thisObject
                    cachedArgs = param.args.copyOf()
                } catch (t: Throwable) { /* 缓存失败不影响主改写 */ }

                @Suppress("UNCHECKED_CAST")
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
                        if (tlsName != null && (tlsName as String).isNotEmpty()) {
                            XposedHelpers.setObjectField(a1, "tlsName", "")
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: parcel rewrite failed: $t")
                    }
                }
            }
        }

        // Android 16+/HyperOS：DnsManager 随 Connectivity mainline 模块移出主 ClassLoader，
        // 改 hook frameworks/base 内确定存在的下游 NetworkManagementService；
        // hookAllMethods 免疫签名差异。两处同时命中时幂等（contains 判断）。
        // APEX tethering(mainline) 已把类重定位: android.net.connectivity.<原名>。
        // DnsManager 就住在 service-connectivity.jar（已从手机拉回逆向确认）。
        val targets = listOf(
            "android.net.connectivity.com.android.server.connectivity.DnsManager",
            "com.android.server.net.NetworkManagementService"
        )
        for (name in targets) {
            try {
                val clz = XposedHelpers.findClass(name, lpparam.classLoader)
                // hookAllMethods 免疫参数形态差异（String[]/ResolverParamsParcel 两种签名通吃）
                val hooked = XposedBridge.hookAllMethods(clz, "setDnsConfigurationForNetwork", hook)
                XposedBridge.log("$TAG: hooked $name (${hooked.size} overloads)")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: $name unavailable -> $t")
            }
        }
        // NMS 兜底：方法名可能是 setDnsServersForNetwork
        try {
            val nms = XposedHelpers.findClass("com.android.server.net.NetworkManagementService", lpparam.classLoader)
            val hooked2 = XposedBridge.hookAllMethods(nms, "setDnsServersForNetwork", hook)
            XposedBridge.log("$TAG: hooked NMS.setDnsServersForNetwork (${hooked2.size} overloads)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: NMS.setDnsServersForNetwork unavailable -> $t")
        }
        startPropPoller()
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
