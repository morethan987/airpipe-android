package com.morethan.airpipe

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
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
    @Volatile private var polling = false

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
        renderPaired()
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
        root.addView(body(
            "① adb shell appops set com.morethan.airpipe MANAGE_EXTERNAL_STORAGE allow\n" +
                "② 系统设置 → 通知 → Airpipe → 开启通知权限\n" +
                "③ 省电策略设为「无限制」+ 允许自启动（HyperOS 后台冻结会掐 socket）"))

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
