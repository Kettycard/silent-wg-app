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

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return   // 只作用于 system_server

        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.connectivity.DnsManager",
                lpparam.classLoader,
                "setDnsConfigurationForNetwork",
                Int::class.javaPrimitiveType,               // netId
                Array<String>::class.java,                  // assignedServers (目标)
                Array<String>::class.java,                  // domains
                Array<String>::class.java,                  // params
                String::class.java,                         // tlsHostname
                Array<String>::class.java,                  // tlsServers (DoT)
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val dns = readDns()
                        if (dns.isEmpty()) return           // 开关关闭时不做任何事

                        @Suppress("UNCHECKED_CAST")
                        val ns = param.args[1] as? Array<String>
                        if (ns != null && !ns.contains(dns)) {
                            param.args[1] = arrayOf(dns)
                            XposedBridge.log("$TAG: DNS takeover ${ns.joinToString()} -> $dns")
                        }
                        // 清空 DoT 服务器（严格/自动私有 DNS 无法绕过我们）
                        if (param.args[5] != null) {
                            @Suppress("UNCHECKED_CAST")
                            val tls = param.args[5] as Array<String>
                            if (tls.isNotEmpty()) {
                                param.args[5] = arrayOf<String>()
                                XposedBridge.log("$TAG: DoT disabled to prevent bypass")
                            }
                        }
                    }
                }
            )
            XposedBridge.log("$TAG: DnsManager hook installed")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: hook failed: $t")
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
