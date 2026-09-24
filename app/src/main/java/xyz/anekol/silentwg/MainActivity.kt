package xyz.anekol.silentwg

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var viewStatusDot: View
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var switchTunnel: MaterialSwitch
    private lateinit var btnReload: MaterialButton
    private lateinit var btnDiag: MaterialButton

    private lateinit var switchDnsTakeover: MaterialSwitch

    private lateinit var switchAutoConnect: MaterialSwitch
    private lateinit var etSsidDown: TextInputEditText
    private lateinit var actvEpPrefer: AutoCompleteTextView
    private lateinit var etIncludeRoutes: TextInputEditText
    private lateinit var btnSaveRules: MaterialButton

    private lateinit var etTunnelConf: TextInputEditText
    private lateinit var btnSaveTunnelConf: MaterialButton

    private lateinit var tvLogs: TextView

    private val wgctl = "/data/adb/modules/silent-wg/bin/wgctl"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        loadAllData()
    }

    private fun initViews() {
        swipeRefresh = findViewById(R.id.swipeRefresh)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        switchTunnel = findViewById(R.id.switchTunnel)
        btnReload = findViewById(R.id.btnReload)
        btnDiag = findViewById(R.id.btnDiag)

        switchDnsTakeover = findViewById(R.id.switchDnsTakeover)

        switchAutoConnect = findViewById(R.id.switchAutoConnect)
        etSsidDown = findViewById(R.id.etSsidDown)
        actvEpPrefer = findViewById(R.id.actvEpPrefer)
        etIncludeRoutes = findViewById(R.id.etIncludeRoutes)
        btnSaveRules = findViewById(R.id.btnSaveRules)

        etTunnelConf = findViewById(R.id.etTunnelConf)
        btnSaveTunnelConf = findViewById(R.id.btnSaveTunnelConf)

        tvLogs = findViewById(R.id.tvLogs)

        val dotDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.RED)
        }
        viewStatusDot.background = dotDrawable

        val items = listOf("v6auto", "v4")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items)
        actvEpPrefer.setAdapter(adapter)
        actvEpPrefer.setText("v6auto", false)
    }

    private fun setupListeners() {
        swipeRefresh.setOnRefreshListener {
            loadAllData()
        }

        switchTunnel.setOnClickListener {
            val wantUp = switchTunnel.isChecked
            runSu(if (wantUp) "$wgctl up" else "$wgctl down") { res ->
                runOnUiThread {
                    Toast.makeText(this, if (wantUp) "已连接" else "已断开", Toast.LENGTH_SHORT).show()
                    loadAllData()
                }
            }
        }

        btnReload.setOnClickListener {
            runSu("$wgctl reload") {
                runOnUiThread {
                    Toast.makeText(this, "已重载", Toast.LENGTH_SHORT).show()
                    loadAllData()
                }
            }
        }

        btnDiag.setOnClickListener {
            Toast.makeText(this, "正在诊断...", Toast.LENGTH_SHORT).show()
            runSu("$wgctl diag") { res ->
                runOnUiThread {
                    tvLogs.text = res
                }
            }
        }

        switchDnsTakeover.setOnCheckedChangeListener { _, isChecked ->
            val cmd = if (isChecked) {
                "setprop persist.silentwg.dns 192.168.6.115"
            } else {
                "setprop persist.silentwg.dns ''"
            }
            runSu(cmd) {
                runOnUiThread {
                    Toast.makeText(this, if (isChecked) "DNS 接管已开启 (192.168.6.115)" else "DNS 接管已关闭", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnSaveRules.setOnClickListener {
            val autoConn = if (switchAutoConnect.isChecked) "1" else "0"
            val ssidDown = etSsidDown.text.toString().trim()
            val epPrefer = actvEpPrefer.text.toString().trim()
            val incRoutes = etIncludeRoutes.text.toString().trim()

            val script = """
                $wgctl set AUTO_CONNECT '$autoConn'
                $wgctl set SSID_DOWN '$ssidDown'
                $wgctl set ENDPOINT_PREFER '$epPrefer'
                $wgctl set INCLUDE_ROUTES '$incRoutes'
            """.trimIndent()

            runSu(script) {
                runOnUiThread {
                    Toast.makeText(this, "规则已保存", Toast.LENGTH_SHORT).show()
                    loadAllData()
                }
            }
        }

        btnSaveTunnelConf.setOnClickListener {
            val conf = etTunnelConf.text.toString()
            if (conf.isBlank()) {
                Toast.makeText(this, "配置不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val b64 = Base64.encodeToString(conf.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val script = "$wgctl save-conf $b64 && $wgctl reload"
            runSu(script) { res ->
                runOnUiThread {
                    Toast.makeText(this, "配置已保存并重载", Toast.LENGTH_SHORT).show()
                    loadAllData()
                }
            }
        }
    }

    private fun loadAllData() {
        swipeRefresh.isRefreshing = true
        executor.execute {
            // 1. 检查状态
            val statusOut = execSuSync("$wgctl status")
            val isUp = statusOut.contains("state: up")
            val ssid = Regex("ssid:\\s*(.*)").find(statusOut)?.groupValues?.get(1) ?: "未知"
            val transfer = Regex("transfer:\\s*(.*)").find(statusOut)?.groupValues?.get(1) ?: "0 B"

            // 2. DNS 接管属性
            val dnsProp = execSuSync("getprop persist.silentwg.dns").trim()
            val isDnsTakeover = dnsProp.isNotEmpty()

            // 3. 读取配置规则
            val autoConn = execSuSync("$wgctl get AUTO_CONNECT").trim()
            val ssidDown = execSuSync("$wgctl get SSID_DOWN").trim()
            val epPrefer = execSuSync("$wgctl get ENDPOINT_PREFER").trim()
            val incRoutes = execSuSync("$wgctl get INCLUDE_ROUTES").trim()

            // 4. 读取 tunnel.conf
            val tunnelConf = execSuSync("cat /data/adb/silent-wg/tunnel.conf 2>/dev/null")

            // 5. 读取日志
            val logOut = execSuSync("$wgctl log")

            runOnUiThread {
                swipeRefresh.isRefreshing = false

                // 更新状态 Card
                val dot = viewStatusDot.background as? GradientDrawable
                if (isUp) {
                    dot?.setColor(Color.parseColor("#4CAF50"))
                    tvStatusTitle.text = "隧道已连接"
                    tvStatusTitle.setTextColor(Color.parseColor("#4CAF50"))
                } else {
                    dot?.setColor(Color.parseColor("#E53935"))
                    tvStatusTitle.text = "隧道已断开"
                    tvStatusTitle.setTextColor(Color.parseColor("#E53935"))
                }
                switchTunnel.isChecked = isUp
                tvStatusDetail.text = "WiFi SSID: $ssid\n流量: $transfer"

                // DNS 接管
                switchDnsTakeover.isChecked = isDnsTakeover

                // 规则
                switchAutoConnect.isChecked = (autoConn != "0")
                etSsidDown.setText(ssidDown)
                actvEpPrefer.setText(if (epPrefer.isNotEmpty()) epPrefer else "v6auto", false)
                etIncludeRoutes.setText(incRoutes)

                // 隧道配置
                if (etTunnelConf.text.isNullOrEmpty() || !etTunnelConf.hasFocus()) {
                    etTunnelConf.setText(tunnelConf)
                }

                // 日志
                tvLogs.text = if (logOut.isNotBlank()) logOut else "暂无日志"
            }
        }
    }

    private fun runSu(command: String, callback: (String) -> Unit) {
        executor.execute {
            val res = execSuSync(command)
            callback(res)
        }
    }

    private fun execSuSync(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = process.outputStream
            os.write((command + "\nexit\n").toByteArray(Charsets.UTF_8))
            os.flush()
            os.close()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            process.waitFor()
            sb.toString()
        } catch (t: Throwable) {
            "执行异常: ${t.message}"
        }
    }
}
