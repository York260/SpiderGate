package tw.york.gatekeeper

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 寫死的規則：AI 改不了這些。 */
object Rules {
    const val MAX_MINUTES = 10
    const val MAX_UNLOCKS_PER_DAY = 3
    const val COOLDOWN_MINUTES = 15
    const val MAX_ROUNDS = 6
}

object Defaults {
    val APPS = setOf(
        "com.google.android.youtube",
        "com.zhiliaoapp.musically",      // TikTok
        "com.ss.android.ugc.trill",      // TikTok（亞洲）
        "com.ss.android.ugc.aweme",      // 抖音
        "com.instagram.android",
        "com.facebook.katana",
        "tv.danmaku.bili",               // Bilibili
        "com.bilibili.app.in"            // Bilibili 國際版
    )
    val SITES = setOf(
        "youtube.com", "youtu.be", "tiktok.com", "douyin.com",
        "instagram.com", "facebook.com", "bilibili.com"
    )
    val KEYWORDS = setOf("短劇", "短剧", "drama", "ai劇", "ai剧")
}

class Store(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("gatekeeper", Context.MODE_PRIVATE)

    var apiKey: String
        get() = p.getString("apiKey", "") ?: ""
        set(v) { p.edit().putString("apiKey", v.trim()).apply() }

    var model: String
        get() = p.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) { p.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply() }

    // ---- 黑名單：只能加，不能刪 ----
    fun apps(): Set<String> = getSet("apps", Defaults.APPS)
    fun sites(): Set<String> = getSet("sites", Defaults.SITES)
    fun keywords(): Set<String> = getSet("keywords", Defaults.KEYWORDS)
    fun addApp(v: String) = addTo("apps", Defaults.APPS, v)
    fun addSite(v: String) = addTo("sites", Defaults.SITES, v)
    fun addKeyword(v: String) = addTo("keywords", Defaults.KEYWORDS, v)

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

    /** 回傳命中的網站或關鍵字；沒命中回傳 null。 */
    fun matchUrl(raw: String): String? {
        val u = raw.lowercase()
        sites().firstOrNull { u.contains(it) }?.let { return it }
        keywords().firstOrNull { u.contains(it) }?.let { return "關鍵字「$it」" }
        return null
    }

    // ---- 放行與冷卻 ----
    val allowUntil: Long get() = p.getLong("allowUntil", 0L)
    val cooldownUntil: Long get() = p.getLong("cooldownUntil", 0L)

    fun isAllowedNow() = System.currentTimeMillis() < allowUntil
    fun inCooldown() = System.currentTimeMillis() < cooldownUntil

    fun unlocksToday(): Int =
        if (p.getString("day", "") == today()) p.getInt("unlocks", 0) else 0

    /** 程式最後把關：超過次數就不放行。 */
    fun grant(minutes: Int): Boolean {
        if (unlocksToday() >= Rules.MAX_UNLOCKS_PER_DAY) return false
        val m = minutes.coerceIn(1, Rules.MAX_MINUTES)
        p.edit()
            .putString("day", today())
            .putInt("unlocks", unlocksToday() + 1)
            .putLong("allowUntil", System.currentTimeMillis() + m * 60_000L)
            .apply()
        return true
    }

    fun deny() {
        p.edit().putLong("cooldownUntil", System.currentTimeMillis() + Rules.COOLDOWN_MINUTES * 60_000L).apply()
    }

    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"

        fun minutesLeft(until: Long): Long {
            val ms = until - System.currentTimeMillis()
            return if (ms <= 0) 0 else (ms + 59_999) / 60_000
        }
    }
}
