package tw.york.gatekeeper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 兩層把關：
 * 1. 絕對禁止：小說、劇類 App／網站／關鍵字，直接踢出去，不談。
 * 2. 許可制：其他黑名單對象要先說服守門員；放行只開「那一個」，時間到自動鎖回。
 *    放行期間也會掃畫面，一出現禁止內容（例如在 YouTube 點開短劇）照樣踢出去。
 */
class GatekeeperService : AccessibilityService() {

    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())
    private var lastContentCheck = 0L
    private var lastGateLaunch = 0L

    private val tick = object : Runnable {
        override fun run() {
            stopAudioIfExerciseEnded()
            checkCurrentWindow()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = Store(this)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 15_000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::store.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = System.currentTimeMillis()
            if (now - lastContentCheck < 1000) return
            lastContentCheck = now
        }
        evaluate(pkg)
    }

    /** 聽小說獎勵（運動／專車）時間到：搶音訊焦點，讓小說 App 暫停播放。 */
    private fun stopAudioIfExerciseEnded() {
        if (!::store.isInitialized) return
        if (store.rewardPaused || System.currentTimeMillis() < store.rewardUntil) return
        val am = getSystemService(AudioManager::class.java) ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        am.requestAudioFocus(req)
        store.markRewardPaused()
    }

    private fun checkCurrentWindow() {
        if (!::store.isInitialized) return
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return
        evaluate(pkg)
    }

    private fun evaluate(pkg: String) {
        if (pkg == packageName) return

        // 1. 絕對禁止的 App
        if (pkg.lowercase() in store.banApps()) {
            if (store.isAllowed(pkg)) return   // 運動獎勵放行中
            kick(GateActivity.MODE_BAN, appLabel(pkg), pkg)
            return
        }

        // 2. 瀏覽器：看網址
        if (BROWSERS.containsKey(pkg)) {
            val url = readUrl(pkg) ?: return
            store.matchBan(url)?.let { hit ->
                if (store.isAllowed(hit)) return
                kick(GateActivity.MODE_BAN, "含有「$hit」的網頁", hit)
                return
            }
            val site = store.matchPermitSite(url) ?: return
            if (store.isAllowed(site)) {
                scanScreenForBan(site)
            } else {
                kick(GateActivity.MODE_PERMIT, site, site)
            }
            return
        }

        // 3. 許可制 App
        if (pkg.lowercase() in store.permitApps()) {
            if (store.isAllowed(pkg)) {
                scanScreenForBan(pkg)
            } else {
                kick(GateActivity.MODE_PERMIT, appLabel(pkg), pkg)
            }
        }
    }

    /** 放行中也要掃畫面：看到禁止內容就收回放行、踢出去。 */
    private fun scanScreenForBan(key: String) {
        val root = rootInActiveWindow ?: return
        val sb = StringBuilder()
        collectText(root, sb, intArrayOf(0))
        val hit = store.matchBan(sb.toString()) ?: return
        if (store.isAllowed(hit)) return
        store.revoke(key)
        kick(GateActivity.MODE_BAN, "畫面上的「$hit」內容", hit)
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder, count: IntArray) {
        if (count[0] > 500) return
        count[0]++
        node.text?.let { sb.append(it).append('\n') }
        node.contentDescription?.let { sb.append(it).append('\n') }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, sb, count)
        }
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

    private fun kick(mode: String, label: String, key: String) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = System.currentTimeMillis()
        if (now - lastGateLaunch < 1500) return
        lastGateLaunch = now
        val i = Intent(this, GateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(GateActivity.EXTRA_MODE, mode)
            .putExtra(GateActivity.EXTRA_TARGET, label)
            .putExtra(GateActivity.EXTRA_KEY, key)
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
