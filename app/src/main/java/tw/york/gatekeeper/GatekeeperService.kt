package tw.york.gatekeeper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** 偵測前景 App 與瀏覽器網址，碰到黑名單就攔下來、叫出守門員。 */
class GatekeeperService : AccessibilityService() {

    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())
    private var lastContentCheck = 0L
    private var lastGateLaunch = 0L

    // 每 20 秒檢查一次：放行時間到了，就算你還停在影片裡也會被趕出來
    private val tick = object : Runnable {
        override fun run() {
            checkCurrentWindow()
            handler.postDelayed(this, 20_000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = Store(this)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 20_000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::store.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = System.currentTimeMillis()
            if (now - lastContentCheck < 800) return
            lastContentCheck = now
        }
        evaluate(pkg)
    }

    private fun checkCurrentWindow() {
        if (!::store.isInitialized) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        evaluate(pkg)
    }

    private fun evaluate(pkg: String) {
        if (pkg == packageName) return
        if (store.isAllowedNow()) return

        val target: String? = when {
            pkg in store.apps() -> appLabel(pkg)
            BROWSERS.containsKey(pkg) -> readUrl(pkg)?.let { store.matchUrl(it) }
            else -> null
        }
        if (target != null) block(target)
    }

    private fun readUrl(pkg: String): String? {
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return null
        for (id in BROWSERS[pkg].orEmpty()) {
            for (node in root.findAccessibilityNodeInfosByViewId(id)) {
                val t = node.text?.toString()
                if (!t.isNullOrBlank()) return t
            }
        }
        return null
    }

    private fun appLabel(pkg: String): String = try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun block(target: String) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = System.currentTimeMillis()
        if (now - lastGateLaunch < 1500) return
        lastGateLaunch = now
        val i = Intent(this, GateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(GateActivity.EXTRA_TARGET, target)
        startActivity(i)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    companion object {
        /** 各瀏覽器網址列的元件 ID。不在這裡的瀏覽器，交給 AppBlock 的「封鎖不支援的瀏覽器」。 */
        val BROWSERS: Map<String, List<String>> = mapOf(
            "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
            "org.mozilla.firefox" to listOf(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/url_bar_title"
            ),
            "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
            "com.sec.android.app.sbrowser" to listOf("com.sec.android.app.sbrowser:id/location_bar_edit_text"),
            "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
            "com.opera.browser" to listOf("com.opera.browser:id/url_field")
        )
    }
}
