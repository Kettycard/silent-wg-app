package xyz.anekol.silentwg

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.util.concurrent.Executors

/**
 * SilentWG 控制台壳：
 * - WebView 直接加载 KernelSU 模块的 webroot/index.html（WG 控制台零重复开发，模块升级即同步）
 * - 注入 ksu JS bridge：网页里的 ksu.exec(cmd, cb) 走 App 的 su 同步执行（三态兼容的同步签名）
 * - 顶部原生开关：LSPosed 全局 DNS 接管（setprop persist.silentwg.dns）
 */
class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var web: WebView
    private lateinit var dnsBox: CheckBox
    private lateinit var tip: TextView

    private val fallbackHtml: String
        get() = assets.open("console_fallback.html").bufferedReader().use { it.readText() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
        }

        dnsBox = CheckBox(this).apply {
            text = "LSPosed 全局 DNS 接管（→ 192.168.6.115 fakeip 链）"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(32, 24, 32, 0)
            setOnCheckedChangeListener { _, checked -> io.execute { applyDns(checked) } }
        }
        tip = TextView(this).apply {
            setTextColor(Color.parseColor("#8e8e93"))
            textSize = 12f
            setPadding(32, 0, 32, 16)
            gravity = Gravity.CENTER_VERTICAL
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(SuBridge(), "ksu")
            webViewClient = WebViewClient()
        }

        root.addView(dnsBox)
        root.addView(tip)
        root.addView(ScrollView(this).apply { addView(web) })
        setContentView(root)

        loadConsole()
        io.execute {
            val on = sh("getprop persist.silentwg.dns")?.trim().isNullOrEmpty().not()
            runOnUiThread {
                dnsBox.setOnCheckedChangeListener(null)
                dnsBox.isChecked = on
                dnsBox.setOnCheckedChangeListener { _, c -> io.execute { applyDns(c) } }
                tip.text = if (on) "接管中。切换一次飞行模式使 DNS 生效。"
                else "关闭状态：系统 DNS 按原设置。"
            }
        }
    }

    /** 加载模块的 webroot 控制台；模块不存在时用内置兜底页 */
    private fun loadConsole() {
        io.execute {
            val html = sh("cat /data/adb/modules/silent-wg/webroot/index.html")
                ?: runCatching { fallbackHtml }.getOrNull()
            runOnUiThread {
                web.loadDataWithBaseURL("https://local.silentwg/", html ?: "<h3 style='color:#fff'>控制台缺失</h3>",
                    "text/html", "utf-8", null)
            }
        }
    }

    private fun applyDns(enable: Boolean) {
        val r = if (enable) {
            sh("setprop persist.silentwg.dns 192.168.6.115 && " +
               "settings put global private_dns_mode opportunistic && " +
               "settings put global private_dns_specifier '' && echo OK")
        } else {
            sh("setprop persist.silentwg.dns '' && echo OK")
        }
        val ok = r?.contains("OK") == true
        runOnUiThread {
            tip.text = if (!ok) "写入失败（需要 root 授权）"
            else if (enable) "已接管。开关一次飞行模式让 DNS 立即生效。"
            else "已关闭接管。开关飞行模式恢复原 DNS。"
        }
    }

    /** JS bridge：兼容网页 ksu.exec 的三种签名（1参同步 / 2参回调 / 3参带options） */
    inner class SuBridge {
        @JavascriptInterface
        fun exec(cmd: String): String = sh(cmd) ?: ""

        @JavascriptInterface
        fun exec(cmd: String, cb: String): String {
            val out = sh(cmd) ?: ""
            runJsCallback(cb, out)
            return out
        }

        @JavascriptInterface
        fun exec(cmd: String, options: String, cb: String): String {
            val out = sh(cmd) ?: ""
            runJsCallback(cb, out)
            return out
        }

        private fun runJsCallback(cb: String, out: String) {
            val js = "$cb(0, ${org.json.JSONObject.quote(out)}, \"\")"
            web.post { web.evaluateJavascript(js, null) }
        }
    }

    private fun sh(cmd: String): String? = try {
        val p = ProcessBuilder("su", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        if (p.exitValue() == 0 || out.isNotEmpty()) out else null
    } catch (t: Throwable) {
        null
    }
}
