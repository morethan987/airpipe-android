package com.morethan.airpipe

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * 唯一可见 UI（桌面图标入口）：状态区、6 位码配对、手动 IP（高级）、
 * 已配对列表（指纹前 8 位 + 取消配对）、权限三步引导。全代码构建，无 XML。
 */
class PairActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var eventView: TextView
    private lateinit var codeInput: EditText
    private lateinit var ipInput: EditText
    private lateinit var pairButton: Button
    private lateinit var pairedBox: LinearLayout
    private lateinit var permStatus: TextView
    private lateinit var notifStatus: TextView
    private lateinit var batteryStatus: TextView
    @Volatile private var polling = false

    companion object {
        private const val REQ_NOTIF = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        runCatching { startForegroundService(android.content.Intent(this, SyncService::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        polling = false
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        thread(name = "pair-ui-poll") {
            while (polling && !isFinishing) {
                // 配对结果到达立即刷新
                val r = synchronized(PairCtl.lock) { PairCtl.result }
                runOnUiThread {
                    refresh()
                    r?.let {
                        Toast.makeText(this@PairActivity, it.second, Toast.LENGTH_LONG).show()
                        pairButton.isEnabled = true
                    }
                }
                if (r != null) synchronized(PairCtl.lock) {
                    PairCtl.result = null
                }
                Thread.sleep(1000)
            }
        }
    }

    private fun refresh() {
        statusView.text = "状态：${SyncService.status()}"
        eventView.text = "最近事件：${SyncService.event()}"
        permStatus.text = "存储权限：${if (Environment.isExternalStorageManager()) "✅ 已开启" else "❌ 未开启（接收文件落盘需要）"}"
        notifStatus.text = "通知权限：${
            if (getSystemService(NotificationManager::class.java).areNotificationsEnabled()) "✅ 已开启"
            else "❌ 未开启（常驻通知需要）"
        }"
        batteryStatus.text = "省电策略：${
            if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) "✅ 无限制"
            else "❌ 未设无限制（后台可能被冻结）"
        }"
        renderPaired()
    }

    /** 通知权限：先请求运行时弹框；已永久拒绝则跳系统通知设置页。 */
    private fun requestNotif() {
        if (getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            refresh(); return
        }
        runCatching {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }.onFailure {
            jumpNotifSettings()
        }
    }

    private fun jumpNotifSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    /** 省电无限制：系统授权弹框；部分 ROM 无该页则退到总列表。 */
    private fun requestBattery() {
        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            refresh(); return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIF) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "通知权限已开启", Toast.LENGTH_SHORT).show()
        } else {
            // 拒绝后可能不再弹框 → 直接跳系统通知设置页
            jumpNotifSettings()
        }
        refresh()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(root) }

        fun title(s: String) = TextView(this).apply {
            text = s; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
        }
        fun body(s: String) = TextView(this).apply { text = s; textSize = 13f }
        fun gap() = View(this).apply { minimumHeight = dp(12) }

        root.addView(TextView(this).apply {
            text = "Airpipe"; textSize = 24f; setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(body("局域网剪贴板 / 文件同步（PC ↔ 手机）"))

        // 状态区
        root.addView(gap()); root.addView(title("状态"))
        statusView = body("").also { root.addView(it) }
        eventView = body("").also { root.addView(it) }

        // 配对区
        root.addView(gap()); root.addView(title("配对"))
        root.addView(body("PC 侧先执行 ap pair（显示 6 位码），再在此输入："))
        codeInput = EditText(this).apply {
            hint = "6 位配对码"; inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        root.addView(codeInput)
        pairButton = Button(this).apply {
            text = "配对"
            setOnClickListener { doPair() }
        }
        root.addView(pairButton)
        ipInput = EditText(this).apply {
            hint = "高级：手动填 PC IP（广播不可达时用，如 192.168.0.23）"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        root.addView(ipInput)

        // 已配对列表
        root.addView(gap()); root.addView(title("已配对 PC"))
        pairedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pairedBox)

        // 权限引导
        root.addView(gap()); root.addView(title("装机一次性设置"))
        permStatus = body("").also { root.addView(it) }
        root.addView(Button(this).apply {
            text = "① 开启存储权限（所有文件访问）"
            setOnClickListener {
                val pkg = Uri.parse("package:$packageName")
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
                }.onFailure {
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }
            }
        })
        notifStatus = body("").also { root.addView(it) }
        root.addView(Button(this).apply {
            text = "② 开启通知权限"
            setOnClickListener { requestNotif() }
        })
        batteryStatus = body("").also { root.addView(it) }
        root.addView(Button(this).apply {
            text = "③ 省电策略设为「无限制」"
            setOnClickListener { requestBattery() }
        })
        root.addView(Button(this).apply {
            text = "④ 允许自启动（HyperOS）"
            setOnClickListener {
                runCatching {
                    startActivity(Intent("miui.intent.action.OP_AUTO_START")
                        .putExtra("extra_pkg", packageName))
                }.onFailure {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")))
                    }
                }
            }
        })

        return scroll
    }

    private fun renderPaired() {
        pairedBox.removeAllViews()
        val devices = Store.load(this)
        if (devices.isEmpty()) {
            pairedBox.addView(TextView(this).apply {
                text = "（未配对——PC 执行 ap pair 后输入 6 位码）"; textSize = 13f
            })
            return
        }
        devices.forEach { d ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = "${d.name}  ·  ${Store.short(d.fingerprint)}"
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "取消配对"
                setOnClickListener {
                    Store.remove(this@PairActivity, d.fingerprint)
                    SyncService.event("已取消配对：${d.name}")
                    refresh()
                }
            })
            pairedBox.addView(row)
        }
    }

    private fun doPair() {
        val code = codeInput.text.toString().trim()
        if (!Regex("^\\d{6}$").matches(code)) {
            Toast.makeText(this, "请输入 6 位数字配对码", Toast.LENGTH_SHORT).show()
            return
        }
        pairButton.isEnabled = false
        SyncService.event("配对中…（确保 PC 已执行 ap pair）")
        SyncService.startPairing(this, code, ipInput.text.toString().trim())
    }
}
