package tw.york.gatekeeper

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 寫死的規則：AI 改不了這些。 */
object Rules {
    const val MAX_MINUTES = 30
    const val MAX_UNLOCKS_PER_DAY = 5
    const val COOLDOWN_MINUTES = 15
    const val MAX_ROUNDS = 6

    // 運動獎勵：邊運動邊聽小說
    const val EXERCISE_MINUTES = 30
    const val MAX_EXERCISE_GRANTS_PER_DAY = 4   // 最多連任到 2 小時

    // 搭返校專車：聽小說 1 小時，一天一次
    const val COMMUTE_MINUTES = 60
}

object Defaults {
    // ---- 許可制：說得出正當理由＋預估時間，就開指定的那一個 ----
    val PERMIT_APPS = setOf(
        "com.google.android.youtube",
        "com.zhiliaoapp.musically",      // TikTok
        "com.ss.android.ugc.trill",      // TikTok（亞洲）
        "com.ss.android.ugc.aweme",      // 抖音
        "com.instagram.android",
        "com.facebook.katana",
        "tv.danmaku.bili",               // Bilibili
        "com.bilibili.app.in"            // Bilibili 國際版
    )
    val PERMIT_SITES = setOf(
        "youtube.com", "youtu.be", "tiktok.com", "douyin.com",
        "instagram.com", "facebook.com", "bilibili.com"
    )

    // ---- 絕對禁止：小說、各種劇，怎麼說都不開 ----
    val BAN_APPS = setOf(
        "com.dragon.read",               // 番茄小說
        "com.qidian.qdreader",           // 起點讀書
        "com.newleaf.app.android.victor", // ReelShort
        "com.storymatrix.drama",         // DramaBox
        "com.phoenix.read"               // 紅果短劇
    )
    val BAN_SITES = setOf("reelshort.com", "dramabox")
    val BAN_KEYWORDS = setOf(
        "短劇", "短剧", "ai劇", "ai剧", "追劇", "追剧", "微短劇", "微短剧",
        "小說", "小说", "網文", "网文", "drama", "novel"
    )
}

class Store(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("gatekeeper", Context.MODE_PRIVATE)

    var apiKey: String
        get() = p.getString("apiKey", "") ?: ""
        set(v) { p.edit().putString("apiKey", v.trim()).apply() }

    var model: String
        get() = p.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) { p.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply() }

    // ---- 名單：只能加，不能刪 ----
    fun permitApps() = getSet("permitApps", Defaults.PERMIT_APPS)
    fun permitSites() = getSet("permitSites", Defaults.PERMIT_SITES)
    fun banApps() = getSet("banApps", Defaults.BAN_APPS)
    fun banSites() = getSet("banSites", Defaults.BAN_SITES)
    fun banKeywords() = getSet("banKeywords", Defaults.BAN_KEYWORDS)

    fun addPermitApp(v: String) = addTo("permitApps", Defaults.PERMIT_APPS, v)
    fun addPermitSite(v: String) = addTo("permitSites", Defaults.PERMIT_SITES, v)
    fun addBanApp(v: String) = addTo("banApps", Defaults.BAN_APPS, v)
    fun addBanSite(v: String) = addTo("banSites", Defaults.BAN_SITES, v)
    fun addBanKeyword(v: String) = addTo("banKeywords", Defaults.BAN_KEYWORDS, v)

    private fun getSet(key: String, def: Set<String>): Set<String> =
        HashSet(p.getStringSet(key, def) ?: def)

    private fun addTo(key: String, def: Set<String>, v: String): Boolean {
        val s = v.trim().lowercase()
        if (s.isEmpty()) return false
        val set = HashSet(getSet(key, def))
        val added = set.add(s)
        p.edit().putStringSet(key, set).apply()
        return added
    }

    /** 網址或畫面文字有沒有碰到絕對禁止；有就回傳命中的字。 */
    fun matchBan(text: String): String? {
        val t = text.lowercase()
        banSites().firstOrNull { t.contains(it) }?.let { return it }
        banKeywords().firstOrNull { t.contains(it) }?.let { return it }
        return null
    }

    /** 網址屬於哪個許可制網站；不屬於回傳 null。 */
    fun matchPermitSite(url: String): String? {
        val u = url.lowercase()
        return permitSites().firstOrNull { u.contains(it) }
    }

    // ---- 放行（針對單一對象）與冷卻 ----
    fun allowUntil(key: String): Long = p.getLong("allow_$key", 0L)
    fun isAllowed(key: String) = System.currentTimeMillis() < allowUntil(key)
    fun revoke(key: String) { p.edit().remove("allow_$key").apply() }

    /** 目前有放行中的對象（給設定畫面顯示）。 */
    fun activeAllowances(): Map<String, Long> {
        val now = System.currentTimeMillis()
        return p.all.filterKeys { it.startsWith("allow_") }
            .mapNotNull { (k, v) -> (v as? Long)?.takeIf { it > now }?.let { k.removePrefix("allow_") to it } }
            .toMap()
    }

    val cooldownUntil: Long get() = p.getLong("cooldownUntil", 0L)
    fun inCooldown() = System.currentTimeMillis() < cooldownUntil

    fun unlocksToday(): Int =
        if (p.getString("day", "") == today()) p.getInt("unlocks", 0) else 0

    /** 程式最後把關：超過次數就不放行。 */
    fun grant(key: String, minutes: Int): Boolean {
        if (unlocksToday() >= Rules.MAX_UNLOCKS_PER_DAY) return false
        val m = minutes.coerceIn(1, Rules.MAX_MINUTES)
        p.edit()
            .putString("day", today())
            .putInt("unlocks", unlocksToday() + 1)
            .putLong("allow_$key", System.currentTimeMillis() + m * 60_000L)
            .apply()
        return true
    }

    fun deny() {
        p.edit().putLong("cooldownUntil", System.currentTimeMillis() + Rules.COOLDOWN_MINUTES * 60_000L).apply()
    }

    // ---- 運動獎勵 ----
    val exUntil: Long get() = p.getLong("exUntil", 0L)
    val exStepsStart: Long get() = p.getLong("exStepsStart", -1L)
    /** 任何「聽小說」獎勵（運動／專車）的結束時間，時間到要暫停播放。 */
    val rewardUntil: Long get() = p.getLong("rewardUntil", 0L)
    val rewardPaused: Boolean get() = p.getBoolean("rewardPaused", true)

    fun exerciseGrantsToday(): Int =
        if (p.getString("exDay", "") == today()) p.getInt("exCount", 0) else 0

    /** 上一次運動放行是不是剛結束（30 分鐘內）＝這次算「連任」。 */
    fun isRenewal(): Boolean {
        val now = System.currentTimeMillis()
        return exUntil in (now - 30 * 60_000L)..now
    }

    fun grantExercise(key: String, steps: Long): Boolean {
        if (exerciseGrantsToday() >= Rules.MAX_EXERCISE_GRANTS_PER_DAY) return false
        val until = System.currentTimeMillis() + Rules.EXERCISE_MINUTES * 60_000L
        p.edit()
            .putString("exDay", today())
            .putInt("exCount", exerciseGrantsToday() + 1)
            .putLong("allow_$key", until)
            .putLong("exUntil", until)
            .putLong("exStepsStart", steps)
            .putLong("rewardUntil", until)
            .putBoolean("rewardPaused", false)
            .apply()
        return true
    }

    fun commuteUsedToday(): Boolean = p.getString("commuteDay", "") == today()

    fun grantCommute(key: String): Boolean {
        if (commuteUsedToday()) return false
        val until = System.currentTimeMillis() + Rules.COMMUTE_MINUTES * 60_000L
        p.edit()
            .putString("commuteDay", today())
            .putLong("allow_$key", until)
            .putLong("rewardUntil", until)
            .putBoolean("rewardPaused", false)
            .apply()
        return true
    }

    fun markRewardPaused() { p.edit().putBoolean("rewardPaused", true).apply() }

    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"

        fun minutesLeft(until: Long): Long {
            val ms = until - System.currentTimeMillis()
            return if (ms <= 0) 0 else (ms + 59_999) / 60_000
        }
    }
}
