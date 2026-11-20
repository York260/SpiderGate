package tw.york.gatekeeper

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 設定畫面：權限、API 金鑰、黑名單（只能加不能刪）。 */
class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var lists: TextView
    private lateinit var keyEdit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        section(col, "狀態")
        status = TextView(this).apply { textSize = 15f }
        col.addView(status)

        section(col, "第一次設定")
        button(col, "1. 開啟無障礙服務") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button(col, "2. 啟用裝置管理員（防止被解除安裝）") { requestAdmin() }

        section(col, "Claude API 金鑰")
        keyEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "sk-ant-..."
        }
        col.addView(keyEdit)
        button(col, "儲存金鑰") {
            val k = keyEdit.text.toString().trim()
            if (k.isEmpty()) {
                toast("金鑰是空的")
            } else {
                store.apiKey = k
                keyEdit.setText("")
                toast("已儲存")
                refresh()
            }
        }
        val modelEdit = EditText(this).apply { setText(store.model) }
        col.addView(TextView(this).apply { text = "模型名稱（通常不用改）" })
        col.addView(modelEdit)
        button(col, "儲存模型") {
            store.model = modelEdit.text.toString()
            modelEdit.setText(store.model)
            toast("已儲存")
        }

        section(col, "黑名單（只能加，不能刪）")
        lists = TextView(this).apply { textSize = 14f }
        col.addView(lists)
        val addEdit = EditText(this).apply { hint = "App 套件名、網域或關鍵字" }
        col.addView(addEdit)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(label: String, action: (String) -> Boolean) {
            row.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    val v = addEdit.text.toString()
                    if (action(v)) {
                        addEdit.setText("")
                        toast("已加入")
                        refresh()
                    } else {
                        toast("空白或已經在清單裡")
                    }
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        add("加 App") { store.addApp(it) }
        add("加網站") { store.addSite(it) }
        add("加關鍵字") { store.addKeyword(it) }
        col.addView(row)

        section(col, "測試")
        button(col, "跟守門員談談") {
            startActivity(
                Intent(this, GateActivity::class.java)
                    .putExtra(GateActivity.EXTRA_TARGET, "測試")
            )
        }

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun refresh() {
        val state = when {
            store.isAllowedNow() -> "放行中，還剩 ${Store.minutesLeft(store.allowUntil)} 分鐘"
            store.inCooldown() -> "冷卻中，還要 ${Store.minutesLeft(store.cooldownUntil)} 分鐘"
            else -> "鎖定中"
        }
        status.text = buildString {
            appendLine("無障礙服務：${if (a11yOn()) "已開啟" else "未開啟（守門員沒在運作）"}")
            appendLine("裝置管理員：${if (adminOn()) "已啟用" else "未啟用"}")
            appendLine("API 金鑰：${if (store.apiKey.isNotBlank()) "已設定" else "未設定"}")
            appendLine("今天放行：${store.unlocksToday()} / ${Rules.MAX_UNLOCKS_PER_DAY} 次")
            append("目前：$state")
        }
        lists.text = buildString {
            appendLine("App：")
            store.apps().sorted().forEach { appendLine("・$it") }
            appendLine()
            appendLine("網站：")
            store.sites().sorted().forEach { appendLine("・$it") }
            appendLine()
            appendLine("關鍵字：")
            store.keywords().sorted().forEach { appendLine("・$it") }
        }
    }

    private fun a11yOn(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cn = ComponentName(this, GatekeeperService::class.java)
        val names = setOf(cn.flattenToString(), cn.flattenToShortString())
        return enabled.split(':').any { s -> names.any { it.equals(s, ignoreCase = true) } }
    }

    private fun adminComponent() = ComponentName(this, AdminReceiver::class.java)

    private fun adminOn(): Boolean =
        getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComponent())

    private fun requestAdmin() {
        if (adminOn()) {
            toast("已經啟用了")
            return
        }
        startActivity(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "啟用後，要先到設定取消裝置管理員，才能解除安裝守門員。"
                )
        )
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#2B2B2B"))
            setPadding(0, dp(20), 0, dp(6))
        })
    }

    private fun button(parent: LinearLayout, label: String, onClick: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        })
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
