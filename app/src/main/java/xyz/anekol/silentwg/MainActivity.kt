package xyz.anekol.silentwg

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
    private lateinit var editDnsServer: TextInputEditText
    private lateinit var btnSaveDns: MaterialButton

    private lateinit var switchAutoConnect: MaterialSwitch
    private lateinit var etSsidDown: TextInputEditText
    private lateinit var actvEpPrefer: AutoCompleteTextView
    private lateinit var etIncludeRoutes: TextInputEditText
    private lateinit var btnSaveRules: MaterialButton

    // 多节点管理
    private lateinit var btnImportConf: MaterialButton
    private lateinit var btnAddConfManual: MaterialButton
    private lateinit var layoutTunnelProfiles: LinearLayout
    private lateinit var etTunnelConf: TextInputEditText
    private lateinit var btnSaveTunnelConf: MaterialButton

    private lateinit var tvLogs: TextView

    private val wgctl = "/data/adb/modules/silent-wg/bin/wgctl"

    // 文件选择器：导入 .conf
    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { importConfFromUri(it) }
    }

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
        editDnsServer = findViewById(R.id.editDnsServer)
        btnSaveDns = findViewById(R.id.btnSaveDns)

        switchAutoConnect = findViewById(R.id.switchAutoConnect)
        etSsidDown = findViewById(R.id.etSsidDown)
        actvEpPrefer = findViewById(R.id.actvEpPrefer)
        etIncludeRoutes = findViewById(R.id.etIncludeRoutes)
        btnSaveRules = findViewById(R.id.btnSaveRules)

        btnImportConf = findViewById(R.id.btnImportConf)
        btnAddConfManual = findViewById(R.id.btnAddConfManual)
        layoutTunnelProfiles = findViewById(R.id.layoutTunnelProfiles)
        etTunnelConf = findViewById(R.id.etTunnelConf)
        btnSaveTunnelConf = findViewById(R.id.btnSaveTunnelConf)

        tvLogs = findViewById(R.id.tvLogs)

        val dotDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#F44336"))
        }
        viewStatusDot.background = dotDrawable

        val epAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, arrayOf("v4", "v6", "none"))
        actvEpPrefer.setAdapter(epAdapter)
    }

    private fun setupListeners() {
        swipeRefresh.setOnRefreshListener {
            loadAllData()
        }

        switchTunnel.setOnCheckedChangeListener { _, isChecked ->
            if (switchTunnel.isPressed) {
                executor.execute {
                    runRootCmd(if (isChecked) "$wgctl up" else "$wgctl down")
                    runOnUiThread { refreshStatus() }
                }
            }
        }

        btnReload.setOnClickListener {
            executor.execute {
                runRootCmd("$wgctl reload")
                runOnUiThread {
                    Toast.makeText(this, "已重载隧道配置", Toast.LENGTH_SHORT).show()
                    loadAllData()
                }
            }
        }

        btnDiag.setOnClickListener {
            executor.execute {
                val out = runRootCmd("$wgctl diag")
                runOnUiThread {
                    tvLogs.text = out.ifEmpty { "无诊断信息" }
                }
            }
        }

        switchDnsTakeover.setOnCheckedChangeListener { _, isChecked ->
            if (switchDnsTakeover.isPressed) {
                val targetDns = editDnsServer.text.toString().trim().ifEmpty { "192.168.6.115" }
                executor.execute {
                    if (isChecked) {
                        runRootCmd("setprop persist.silentwg.dns $targetDns")
                        runRootCmd("$wgctl reload")
                    } else {
                        runRootCmd("setprop persist.silentwg.dns \"\"")
                    }
                    runOnUiThread {
                        Toast.makeText(this, if (isChecked) "已开启 DNS 接管 ($targetDns)" else "已关闭 DNS 接管", Toast.LENGTH_SHORT).show()
                        refreshStatus()
                    }
                }
            }
        }

        btnSaveDns.setOnClickListener {
            val targetDns = editDnsServer.text.toString().trim()
            if (targetDns.isEmpty()) {
                Toast.makeText(this, "请输入有效的 DNS IP 地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            executor.execute {
                runRootCmd("setprop persist.silentwg.dns $targetDns")
                if (switchDnsTakeover.isChecked) {
                    runRootCmd("$wgctl reload")
                }
                runOnUiThread {
                    Toast.makeText(this, "DNS 已保存并生效: $targetDns", Toast.LENGTH_SHORT).show()
                    refreshStatus()
                }
            }
        }

        btnSaveRules.setOnClickListener {
            val autoConn = if (switchAutoConnect.isChecked) "1" else "0"
            val ssid = etSsidDown.text.toString().trim()
            val epPref = actvEpPrefer.text.toString().trim()
            val routes = etIncludeRoutes.text.toString().trim()

            executor.execute {
                runRootCmd("$wgctl set AUTO_CONNECT $autoConn")
                runRootCmd("$wgctl set SSID_DOWN \"$ssid\"")
                runRootCmd("$wgctl set ENDPOINT_PREFER \"$epPref\"")
                runRootCmd("$wgctl set INCLUDE_ROUTES \"$routes\"")
                runOnUiThread {
                    Toast.makeText(this, "运行规则已保存", Toast.LENGTH_SHORT).show()
                    loadAllData()
                }
            }
        }

        btnSaveTunnelConf.setOnClickListener {
            val conf = etTunnelConf.text.toString().trim()
            if (conf.isEmpty()) return@setOnClickListener
            val b64 = Base64.encodeToString(conf.toByteArray(), Base64.NO_WRAP)
            executor.execute {
                runRootCmd("$wgctl save-conf $b64")
                runRootCmd("$wgctl reload")
                runOnUiThread {
                    Toast.makeText(this, "当前节点配置已保存并重载", Toast.LENGTH_SHORT).show()
                    loadAllData()
                }
            }
        }

        // 导入 .conf 文件
        btnImportConf.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("*/*"))
        }

        // 手动新建/粘贴配置
        btnAddConfManual.setOnClickListener {
            showAddManualDialog()
        }
    }

    private fun showAddManualDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val nameInputLayout = TextInputLayout(this).apply {
            hint = "节点名称 (英文/数字/下划线, 如 home, vps)"
        }
        val etName = TextInputEditText(this).apply { maxLines = 1 }
        nameInputLayout.addView(etName)
        layout.addView(nameInputLayout)

        val confInputLayout = TextInputLayout(this).apply {
            hint = "WireGuard 配置内容 (粘贴 [Interface] [Peer])"
            val topMargin = (12 * resources.displayMetrics.density).toInt()
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = topMargin
            layoutParams = lp
        }
        val etConf = TextInputEditText(this).apply {
            minLines = 6
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        confInputLayout.addView(etConf)
        layout.addView(confInputLayout)

        MaterialAlertDialogBuilder(this)
            .setTitle("新建/导入 WireGuard 节点")
            .setView(layout)
            .setPositiveButton("保存并导入") { _, _ ->
                val name = etName.text.toString().trim().ifEmpty { "node_${System.currentTimeMillis() / 1000}" }
                val conf = etConf.text.toString().trim()
                if (conf.isNotEmpty()) {
                    importTunnel(name, conf)
                } else {
                    Toast.makeText(this, "配置内容不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importConfFromUri(uri: Uri) {
        executor.execute {
            try {
                var fileName = "imported_${System.currentTimeMillis() / 1000}"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
                val nodeName = fileName.replace(".conf", "").replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (text.isNotEmpty()) {
                    runOnUiThread {
                        importTunnel(nodeName, text)
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "文件内容为空", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun importTunnel(name: String, content: String) {
        val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
        executor.execute {
            val res = runRootCmd("$wgctl import $name $b64")
            runOnUiThread {
                Toast.makeText(this, "节点 [$name] 导入成功", Toast.LENGTH_SHORT).show()
                loadAllData()
            }
        }
    }

    private fun loadAllData() {
        swipeRefresh.isRefreshing = true
        executor.execute {
            try {
                refreshStatus()
                loadTunnelProfiles()
                loadRules()
                loadTunnelConf()
                loadLogs()
            } catch (e: Exception) {
                android.util.Log.e("SilentWG", "loadAllData error", e)
            } finally {
                runOnUiThread {
                    swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun refreshStatus() {
        val statusRaw = runRootCmd("$wgctl status")
        val dnsProp = runRootCmd("getprop persist.silentwg.dns").trim()
        val isDnsTakeover = dnsProp.isNotEmpty()

        var isUp = false
        var ep = "未连接"
        var handshake = "无"
        var rx = ""
        var tx = ""

        statusRaw.lines().forEach { line ->
            val l = line.trim()
            if (l.startsWith("state:")) isUp = (l.contains("up"))
            if (l.startsWith("endpoint:")) ep = l.substringAfter("endpoint:").trim()
            if (l.startsWith("latest handshake:")) handshake = l.substringAfter("latest handshake:").trim()
            if (l.startsWith("transfer:")) {
                val parts = l.substringAfter("transfer:").split("received,")
                if (parts.size >= 2) {
                    rx = parts[0].trim()
                    tx = parts[1].replace("sent", "").trim()
                }
            }
        }

        runOnUiThread {
            switchTunnel.isChecked = isUp
            switchTunnel.text = if (isUp) "隧道已建立" else "隧道已断开"

            val dot = viewStatusDot.background as? GradientDrawable
            if (isUp) {
                dot?.setColor(Color.parseColor("#4CAF50"))
                tvStatusTitle.text = "WireGuard 运行正常"
                tvStatusTitle.setTextColor(Color.parseColor("#4CAF50"))
                tvStatusDetail.text = "节点: $ep\n握手: $handshake" + if (rx.isNotEmpty()) "\n流量: ↓$rx  ↑$tx" else ""
            } else {
                dot?.setColor(Color.parseColor("#F44336"))
                tvStatusTitle.text = "WireGuard 已停止"
                tvStatusTitle.setTextColor(Color.parseColor("#F44336"))
                tvStatusDetail.text = "轻按右侧开关启动隧道"
            }

            switchDnsTakeover.isChecked = isDnsTakeover
            if (dnsProp.isNotEmpty()) {
                editDnsServer.setText(dnsProp)
            } else if (editDnsServer.text.isNullOrEmpty()) {
                editDnsServer.setText("192.168.6.115")
            }
        }
    }

    private fun loadTunnelProfiles() {
        val raw = runRootCmd("$wgctl tunnels")
        val lines = raw.lines().filter { it.contains("|") }
        runOnUiThread {
            renderTunnelProfiles(lines)
        }
    }

    private fun renderTunnelProfiles(lines: List<String>) {
        try {
            layoutTunnelProfiles.removeAllViews()

        if (lines.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "暂无多节点配置，可点击上方按钮导入或手动添加"
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(0, 16, 0, 16)
            }
            layoutTunnelProfiles.addView(emptyTv)
            return
        }

        val density = resources.displayMetrics.density

        lines.forEach { line ->
            val parts = line.split("|")
            if (parts.size >= 5) {
                val name = parts[0]
                val isCur = (parts[1] == "1")
                val isDef = (parts[2] == "1")
                val ep = parts[3]
                val addr = parts[4]

                val card = MaterialCardView(this).apply {
                    strokeWidth = (1 * density).toInt()
                    strokeColor = if (isCur) Color.parseColor("#4CAF50") else Color.parseColor("#40808080")
                    cardElevation = 0f
                    radius = 12 * density
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = (8 * density).toInt()
                    layoutParams = lp
                }

                val itemLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val p = (14 * density).toInt()
                    setPadding(p, p, p, p)
                }

                // 标题行：名称 + 状态芯片
                val headerLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val tvName = TextView(this).apply {
                    text = name
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                headerLayout.addView(tvName)

                if (isCur) {
                    val badgeActive = TextView(this).apply {
                        text = "● 激活中"
                        textSize = 11f
                        setTextColor(Color.parseColor("#2E7D32"))
                        setBackgroundColor(Color.parseColor("#E8F5E9"))
                        setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
                    }
                    headerLayout.addView(badgeActive)
                }

                if (isDef) {
                    val badgeDef = TextView(this).apply {
                        text = "★ 默认节点"
                        textSize = 11f
                        setTextColor(Color.parseColor("#1565C0"))
                        setBackgroundColor(Color.parseColor("#E3F2FD"))
                        val leftM = (6 * density).toInt()
                        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        lp.marginStart = leftM
                        layoutParams = lp
                        setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
                    }
                    headerLayout.addView(badgeDef)
                }

                itemLayout.addView(headerLayout)

                // 摘要行
                val tvSummary = TextView(this).apply {
                    text = "Endpoint: $ep\nAddress: $addr"
                    textSize = 12f
                    setTextColor(Color.GRAY)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = (6 * density).toInt()
                    layoutParams = lp
                }
                itemLayout.addView(tvSummary)

                // 操作按钮行
                val actionsLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = (10 * density).toInt()
                    layoutParams = lp
                }

                val btnSwitch = MaterialButton(this).apply {
                    text = if (isCur) "已激活" else "切换为此节点"
                    isEnabled = !isCur
                    textSize = 12f
                    setOnClickListener {
                        executor.execute {
                            runRootCmd("$wgctl switch $name")
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "已切换至节点 [$name]", Toast.LENGTH_SHORT).show()
                                loadAllData()
                            }
                        }
                    }
                }
                actionsLayout.addView(btnSwitch)

                val btnSetDef = MaterialButton(this).apply {
                    text = if (isDef) "默认" else "设为默认"
                    isEnabled = !isDef
                    textSize = 12f
                    setOnClickListener {
                        executor.execute {
                            runRootCmd("$wgctl set-default $name")
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "默认节点已设置为 [$name]", Toast.LENGTH_SHORT).show()
                                loadAllData()
                            }
                        }
                    }
                }
                actionsLayout.addView(btnSetDef)

                if (lines.size > 1 && !isCur) {
                    val btnDel = MaterialButton(this).apply {
                        text = "删除"
                        setTextColor(Color.parseColor("#E53935"))
                        textSize = 12f
                        setOnClickListener {
                            MaterialAlertDialogBuilder(this@MainActivity)
                                .setTitle("确认删除节点")
                                .setMessage("确定要删除节点配置 [$name] 吗？此操作无法撤销。")
                                .setPositiveButton("删除") { _, _ ->
                                    executor.execute {
                                        runRootCmd("$wgctl del-tunnel $name")
                                        runOnUiThread {
                                            Toast.makeText(this@MainActivity, "已删除 [$name]", Toast.LENGTH_SHORT).show()
                                            loadAllData()
                                        }
                                    }
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    }
                    actionsLayout.addView(btnDel)
                }

                itemLayout.addView(actionsLayout)
                card.addView(itemLayout)
                layoutTunnelProfiles.addView(card)
            }
        }
        } catch (e: Exception) {
            android.util.Log.e("SilentWG", "renderTunnelProfiles error", e)
        }
    }

    private fun loadRules() {
        val autoConn = runRootCmd("$wgctl get AUTO_CONNECT").trim()
        val ssid = runRootCmd("$wgctl get SSID_DOWN").trim()
        val epPref = runRootCmd("$wgctl get ENDPOINT_PREFER").trim()
        val routes = runRootCmd("$wgctl get INCLUDE_ROUTES").trim()

        runOnUiThread {
            switchAutoConnect.isChecked = (autoConn != "0")
            etSsidDown.setText(ssid)
            actvEpPrefer.setText(if (epPref.isEmpty()) "v4" else epPref, false)
            etIncludeRoutes.setText(routes)
        }
    }

    private fun loadTunnelConf() {
        val conf = runRootCmd("cat /data/adb/silent-wg/tunnel.conf").trim()
        runOnUiThread {
            etTunnelConf.setText(conf)
        }
    }

    private fun loadLogs() {
        val logs = runRootCmd("$wgctl log").trim()
        runOnUiThread {
            tvLogs.text = logs.ifEmpty { "暂无运行日志" }
        }
    }

    private fun runRootCmd(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            output.toString().trim()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}
