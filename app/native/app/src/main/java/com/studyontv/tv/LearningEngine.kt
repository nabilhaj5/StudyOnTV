package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Calendar
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min

/**
 * 网课时段 / 大人豁免的命中判断（Issue #112）。
 *
 * 与 [LearningEngine] 内部的 `dayIndex()` 不同：这里用**真实星期几**，不把法定节假日折算成周日，
 * 因为「周三 19:00 的网课」在节假日也仍然是周三；0 = 周一 … 6 = 周日。
 *
 * 时间精确到分钟，区间左闭右开（19:00–20:15 含 19:00 与 20:14，不含 20:15）。
 * end < start 表示跨午夜（23:30–00:30），午夜之后的部分算作**前一天**的安排。
 */
object TimePointsMath {
    private val pattern = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")

    /** "HH:mm" → 当天第几分钟；格式不对返回 null。 */
    fun minutes(value: String?): Int? {
        val text = value ?: return null
        if (!pattern.matches(text)) return null
        return text.substring(0, 2).toInt() * 60 + text.substring(3, 5).toInt()
    }

    fun label(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    /** [minute] 是否落在 [start, end) 内；start == end 视为空窗口。 */
    fun inRange(start: Int, end: Int, minute: Int): Boolean = when {
        start == end -> false
        start < end -> minute >= start && minute < end
        else -> minute >= start || minute < end
    }

    /** [days] 里的某一天在 [minute] 是否命中 [start, end)；跨午夜时延续到次日的部分属于前一天。 */
    fun hits(days: Set<Int>, start: Int, end: Int, day: Int, minute: Int): Boolean = when {
        start == end -> false
        start < end -> day in days && inRange(start, end, minute)
        else -> (day in days && minute >= start) || ((day + 6) % 7 in days && minute < end)
    }
}

class LearningEngine(
    private val store: LocalStore,
    private val usageDayStart: () -> Long = store::startOfToday
) {
    companion object {
        private val SECRET_QUESTION_FIELDS = listOf("k", "answers", "correctIndices", "tip", "modelAnswer", "rubric")

        /** 把题目正确答案格式化成闪卡背面可直接阅读的答案文本。 */
        internal fun formatAnswerDisplay(question: JSONObject): String = when (val type = question.optString("type", "single")) {
            "boolean" -> {
                val opts = question.optJSONArray("a")
                val k = question.optInt("k", 0)
                opts?.optString(k).orEmpty().ifEmpty { if (k == 0) "Correcto" else "Incorrecto" }
            }
            "multiple" -> {
                val opts = question.optJSONArray("a")
                val indices = question.optJSONArray("correctIndices") ?: JSONArray()
                (0 until indices.length()).map { i ->
                    val k = indices.optInt(i)
                    val label = ('A' + k).toString()
                    val text = opts?.optString(k).orEmpty().trim()
                    if (text.isEmpty()) label else "$label. $text"
                }.joinToString(" / ")
            }
            "text" -> {
                val answers = question.optJSONArray("answers") ?: JSONArray()
                (0 until answers.length()).map { answers.optString(it).trim() }.filter { it.isNotEmpty() }.joinToString(" / ")
            }
            "subjective" -> question.optString("modelAnswer").trim()
            else -> {
                val opts = question.optJSONArray("a")
                val k = question.optInt("k", 0)
                val label = ('A' + k).toString()
                val text = opts?.optString(k).orEmpty().trim()
                if (text.isEmpty()) label else "$label. $text"
            }
        }
    }

    @Volatile var exitSnapshot = LearningExitSnapshot(false, 0)
        private set
    private fun number(key: String) = store.get(key, "0").toLongOrNull() ?: 0L
    private fun put(key: String, value: Long) = store.set(key, value.toString())
    private val time = TimePolicy(store, wallTime = { store.clockReading().millis }) { bootCount() }
    private val videos = VideoLessons(store)
    private var lastLoggedRollbackDay: Long? = null

    /** Cache publication must not turn a committed operation into a reported failure. */
    private fun publishNavigation() {
        try {
            if (!store.writableDatabase.inTransaction()) status()
        } catch (e: Exception) {
            exitSnapshot = LearningExitSnapshot(false, android.os.SystemClock.elapsedRealtime())
            android.util.Log.w("StudyOnTV", "Navigation snapshot unavailable", e)
        }
    }

    @Synchronized
    fun switchChild(id: String) {
        flushUsage()
        store.switchChild(id)
        status()
    }

    @Synchronized
    fun deleteChild(id: String): Boolean {
        flushUsage()
        val deleted = store.deleteChild(id)
        status()
        return deleted
    }

    /**
     * 今天还能扣掉的额度：每日上限 - 已用，并且不能超过「距离必须休息」的额度。
     */
    private fun capacity(config: JSONObject): Long {
        if (time.left("feedback_deadline") > 0) return 0
        return minOf(
            (time.dailyLimit(limitConfig(config)) - number("used_ms")).coerceAtLeast(0),
            time.beforeRest(config)
        )
    }

    /** 测试可以固定时钟（例如验证跨午夜窗口）；生产环境统一由 DeviceClock 取时。 */
    internal var now: () -> Calendar = { DeviceClock.calendar(store) }

    /** 一个命中的网课时段或大人豁免窗口。 */
    private class Window(val name: String, val days: Set<Int>, val start: Int, val end: Int,
                         val mode: String, val dailyMinutes: Int, val until: String)

    private fun windows(config: JSONObject, key: String, defaultMode: String): List<Window> {
        val raw = config.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<Window>(raw.length())
        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: continue
            val start = TimePointsMath.minutes(item.optString("start")) ?: continue
            val end = TimePointsMath.minutes(item.optString("end")) ?: continue
            if (start == end) continue
            val days = LinkedHashSet<Int>()
            item.optJSONArray("days")?.let { arr ->
                for (d in 0 until arr.length()) arr.optInt(d, -1).takeIf { it in 0..6 }?.let { days.add(it) }
            }
            if (days.isEmpty()) continue
            val mode = if (defaultMode == "exempt") "exempt" else if (item.optString("mode") == "counted") "counted" else "free"
            val minutes = item.optInt("dailyMinutes", 0)
            if (mode == "counted" && minutes !in 1..480) continue
            out.add(Window(item.optString("name").take(20), days, start, end, mode, if (mode == "counted") minutes else 0, TimePointsMath.label(end)))
        }
        return out
    }

    private fun resolve(config: JSONObject, cal: Calendar): Window? {
        val day = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        for (w in windows(config, "exemptWindows", "exempt")) if (TimePointsMath.hits(w.days, w.start, w.end, day, minute)) return w
        for (w in windows(config, "timePoints", "free")) if (TimePointsMath.hits(w.days, w.start, w.end, day, minute)) return w
        return null
    }

    private fun activeWindow(config: JSONObject): Window? = resolve(config, now())

    private fun limitConfig(config: JSONObject): JSONObject {
        val point = activeWindow(config) ?: return config
        if (point.mode != "counted") return config
        return JSONObject(config.toString()).put("dailyMinutes", point.dailyMinutes)
    }

    private fun freeBalance(): Long = number("free_quota_ms") + number("boot_quota_ms")

    private fun usableFree(config: JSONObject): Long = min(freeBalance(), capacity(config))

    private fun usableCredit(config: JSONObject): Long =
        min(number("credit_ms"), (capacity(config) - usableFree(config)).coerceAtLeast(0))

    @Synchronized fun awake(awake: Boolean) { day(); time.awake(store.config(), awake); publishNavigation() }

    private fun day() {
        val db = store.writableDatabase
        // 结算在开启事务前确定用量日期，事务中的 status() 不能重新日切。
        if (db.inTransaction()) return
        val current = usageDayStart()
        val previousUsageDay = store.get("usage_day").toLongOrNull()
        // 部分设备冷启动会先使用出厂日期，校时后才恢复。回拨时保留原来的用量日期和余额。
        if (previousUsageDay != null && current < previousUsageDay && lastLoggedRollbackDay != previousUsageDay) {
            lastLoggedRollbackDay = previousUsageDay
            Diag.warn("Day", "检测到日期回拨，保留记账日和额度 previous=$previousUsageDay current=$current")
        }
        if (previousUsageDay == null || current > previousUsageDay) {
            db.beginTransaction()
            try {
                if (pendingUsageMs > 0) store.logEvent("usage", JSONObject().put("milliseconds", pendingUsageMs))
                put("usage_day", current)
                put("credit_ms", 0)
                put("used_ms", 0)
                put("cap_adjust_ms", 0)
                store.set("video_lesson", "")
                if (time.left("rest_deadline") == 0L) put("rest_watched_ms", 0)
                val freeMin = store.config().optLong("dailyFreeMinutes", 0L)
                put("free_quota_ms", freeMin * 60000L)
                put("free_used_ms", 0)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            pendingUsageMs = 0
        } else if (store.get("free_used_ms").isEmpty()) {
            // 旧版本升级：没有 free_used_ms，用当天已用时长估算已消耗的免费额度。
            // 免费额度总是先于奖励余额扣除，所以这个估算不会把奖励时间算成免费时间。
            val daily = store.config().optLong("dailyFreeMinutes", 0L) * 60000L
            put("free_used_ms", min(number("used_ms"), daily).coerceAtLeast(0))
        }
    }

    @Synchronized
    fun saveConfig(input: JSONObject): JSONObject {
        day()
        val previousDaily = store.config().optLong("dailyFreeMinutes") * 60000L
        val previousFree = number("free_quota_ms")
        val usedFree = number("free_used_ms")
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val config = store.saveConfig(input)
            val daily = config.optLong("dailyFreeMinutes") * 60000L
            val granted = (previousFree + (daily - previousDaily)).coerceAtLeast(0)
            val cap = (daily - usedFree).coerceAtLeast(0)
            put("free_quota_ms", min(granted, cap))
            db.setTransactionSuccessful()
            return config
        } finally { db.endTransaction(); publishNavigation() }
    }

    private fun weekSlots(config: JSONObject): List<String>? {
        val raw = config.optJSONArray("weekSlots") ?: return null
        if (raw.length() != 7) return null
        val list = (0 until 7).map { raw.optString(it) }
        return list.takeIf { days -> days.all { it.length == 48 && it.all { ch -> ch == '0' || ch == '1' } } }
    }

    /** 周一 = 0 … 周日 = 6；法定节假日可以按周日安排 */
    private fun dayIndex(config: JSONObject, cal: Calendar): Int {
        if (config.optString("holidayMode", "sunday") == "sunday") {
            val holiday = try { HolidayService.isHoliday(DeviceRuntime.appContext, cal) } catch (_: Throwable) { false }
            if (holiday) return 6
        }
        return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    }

    private fun allowedAt(config: JSONObject, cal: Calendar): Boolean {
        if (config.optBoolean("allDay")) return true
        val slots = weekSlots(config) ?: return true
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return slots[dayIndex(config, cal)].getOrNull(minute / 30) == '1'
    }

    private fun inWindow(config: JSONObject): Boolean = allowedAt(config, now())

    private fun scheduleText(config: JSONObject): String {
        if (config.optBoolean("allDay")) return "Disponible todo el día"
        val slots = weekSlots(config) ?: return ""
        val labels = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
        val parts = mutableListOf<String>()
        var start = 0
        while (start < 7) {
            var end = start
            while (end + 1 < 7 && slots[end + 1] == slots[start]) end++
            val label = when (end - start) {
                0 -> labels[start]
                1 -> labels[start] + " y " + labels[end]
                else -> labels[start] + " a " + labels[end]
            }
            val ranges = slotRanges(slots[start])
            parts += if (ranges.isEmpty()) "$label: no disponible" else label + ": " + ranges.joinToString(", ")
            start = end + 1
        }
        val text = parts.joinToString("; ")
        return if (text.length <= 80) text else text.take(78) + "…"
    }

    private fun slotRanges(day: String): List<String> {
        fun clock(slot: Int) = "%02d:%02d".format(slot / 2, (slot % 2) * 30)
        val out = mutableListOf<String>()
        var i = 0
        while (i < 48) {
            if (day[i] != '1') { i++; continue }
            var j = i
            while (j + 1 < 48 && day[j + 1] == '1') j++
            out += clock(i) + "–" + clock(j + 1)
            i = j + 1
        }
        return out
    }

    private fun nextAvailable(config: JSONObject, tomorrow: Boolean): Long {
        val currentCal = now()
        val nowMs = currentCal.timeInMillis
        val base = currentCal.clone() as Calendar
        if (tomorrow) {
            base.add(Calendar.DAY_OF_YEAR, 1)
            base.set(Calendar.HOUR_OF_DAY, 0); base.set(Calendar.MINUTE, 0)
        }
        base.set(Calendar.SECOND, 0); base.set(Calendar.MILLISECOND, 0)
        if (config.optBoolean("allDay")) return base.timeInMillis
        val slots = weekSlots(config) ?: return base.timeInMillis
        for (offset in 0..8) {
            val day = (base.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val index = dayIndex(config, day)
            for (slot in 0 until 48) {
                if (slots[index][slot] != '1') continue
                val candidate = (day.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, slot / 2); set(Calendar.MINUTE, (slot % 2) * 30)
                }.timeInMillis
                if (candidate > nowMs) return candidate
            }
        }
        return base.timeInMillis
    }

    private fun scheduleFree(c: JSONObject): Boolean =
        c.optString("scheduleMode", "allow") == "control" && !c.optBoolean("allDay") && !inWindow(c)

    @Synchronized
    fun override(): JSONObject? {
        val raw = store.get("remote_override").takeIf { it.isNotEmpty() } ?: return null
        val o = try { JSONObject(raw) } catch (_: Exception) { store.set("remote_override", ""); return null }
        val until = o.optLong("until")
        val mode = o.optString("mode")
        if (mode == "indefinite") return o.put("remainingSeconds", -1)
        if (until == 0L) return o.put("remainingSeconds", -1)
        val left = if (o.optInt("boot", -2) == bootCount()) o.optLong("untilElapsed") - android.os.SystemClock.elapsedRealtime()
            else until - System.currentTimeMillis()
        if (left <= 0) {
            store.set("remote_override", "")
            store.logEvent("remote", JSONObject().put("action", "expired").put("mode", o.optString("mode")))
            return null
        }
        return o.put("remainingSeconds", (left + 999) / 1000)
    }

    @Synchronized
    fun remote(action: String, minutes: Int, source: String): JSONObject {
        when (action) {
            "credit" -> { day(); time.adjust(minutes, store.config()) }
            "cancel" -> store.set("remote_override", "")
            "indefinite" -> {
                store.set("remote_override", JSONObject().put("mode", "indefinite").put("minutes", 0)
                    .put("at", System.currentTimeMillis()).put("source", source).put("boot", bootCount())
                    .put("until", 0L).put("untilElapsed", 0L).toString())
            }
            "unlock", "lock" -> {
                require(action == "lock" || minutes in 1..24 * 60) { "La duración del desbloqueo debe estar entre 1 minuto y 24 horas" }
                require(minutes in 0..7 * 24 * 60) { "La duración del bloqueo no es correcta" }
                val ms = minutes * 60000L
                store.set("remote_override", JSONObject().put("mode", action).put("minutes", minutes)
                    .put("at", System.currentTimeMillis()).put("source", source).put("boot", bootCount())
                    .put("until", if (ms == 0L) 0L else System.currentTimeMillis() + ms)
                    .put("untilElapsed", if (ms == 0L) 0L else android.os.SystemClock.elapsedRealtime() + ms).toString())
            }
            else -> throw IllegalArgumentException("Operación desconocida")
        }
        flushUsage()
        store.logEvent("remote", JSONObject().put("action", action).put("minutes", minutes).put("source", source))
        return status()
    }

    private fun bootCount(): Int = try {
        android.provider.Settings.Global.getInt(DeviceRuntime.appContext.contentResolver, "boot_count", -1)
    } catch (_: Throwable) { -1 }

    /** 纯刷题背诵模式（supervisionEnabled=false）下不启用时长锁屏，优先于一切管控规则放行。 */
    private fun freeNow(c: JSONObject, point: Window? = activeWindow(c)): Boolean {
        if (!c.optBoolean("supervisionEnabled", true)) return true
        val o = override()
        if (o != null) return o.optString("mode") in listOf("unlock", "indefinite")
        if (point != null) return point.mode != "counted"
        return scheduleFree(c)
    }

    @Synchronized
    fun status(): JSONObject {
        day()
        val inTransaction = store.writableDatabase.inTransaction()
        val c = store.config()
        val supervision = c.optBoolean("supervisionEnabled", true)
        val remaining = usableCredit(c)
        val used = number("used_ms")
        val settledUsageDay = if (inTransaction) store.get("usage_day").toLongOrNull() else null
        val rounds = if (settledUsageDay == null) store.roundsDoneToday() else store.roundsDoneToday(settledUsageDay)
        val o = if (supervision) override() else null
        val point = if (supervision) activeWindow(c) else null
        val limit = if (point?.mode == "counted") JSONObject(c.toString()).put("dailyMinutes", point.dailyMinutes) else c
        val free = freeNow(c, point)
        val freeQuota = usableFree(c)
        val rest = if (supervision) time.restLeft(c) else 0L
        val hold = time.left("feedback_deadline")
        val reason = when {
            !supervision -> if (hold > 0) "feedback" else ""
            o?.optString("mode") == "lock" -> "locked"
            free -> ""
            rest > 0 -> "rest"
            hold > 0 -> "feedback"
            used >= time.dailyLimit(limit) -> "quota"
            c.optString("scheduleMode", "allow") != "control" && !inWindow(c) -> "schedule"
            freeQuota > 0 -> ""
            c.optBoolean("roundsLimitEnabled", true) && rounds >= c.getInt("roundsPerDay") && remaining <= 0 -> "rounds"
            c.optBoolean("roundsLimitEnabled", true) && rounds >= c.getInt("roundsPerDay") -> ""
            remaining > 0 -> ""
            else -> "timeup"
        }
        val nextAt = if (reason == "locked") o?.optLong("until") ?: 0L
            else if (reason in listOf("quota", "rounds", "schedule")) nextAvailable(c, reason != "schedule") else 0L
        // 每日免费、开机免费和奖励额度都可以放行返回键和 Home 键。
        val canExit = !supervision || (reason.isEmpty() && (remaining / 1000 > 0 || freeQuota / 1000 > 0 || free))
        val result = JSONObject().put("remainingSeconds", remaining / 1000).put("usedMinutes", used / 60000.0)
            .put("supervisionEnabled", supervision)
            .put("quizGeneration", number("quiz_generation"))
            .put("extraPool", number("extra_pool")).put("wrongProgress", number("wrong_progress"))
            .put("nextAvailableAt", nextAt).put("nextAtText", if (nextAt > 0) DeviceClock.format(nextAt, store) else "").put("free", free)
            .put("restSeconds", (rest + 999) / 1000).put("feedbackSeconds", (hold + 999) / 1000)
            .put("todayLimitMinutes", if (supervision && c.optBoolean("dailyLimitEnabled", true)) time.dailyLimit(limit) / 60000.0 else JSONObject.NULL)
            .put("bootFreeSeconds", min(number("boot_quota_ms"), freeQuota) / 1000)
            .put("freeQuotaSeconds", freeQuota / 1000)
            .put("roundsDoneToday", rounds).put("reason", reason).put("override", o ?: JSONObject.NULL).put("awaitingReview", active()?.has("pendingReview") == true)
            .put("activePoint", point?.let { JSONObject().put("name", it.name).put("mode", it.mode).put("until", it.until) } ?: JSONObject.NULL)
            .put("canExit", canExit)
            .put("scheduleText", scheduleText(c))
        // A result computed inside a transaction must not expose an uncommitted reward/unlock.
        if (!inTransaction)
            exitSnapshot = LearningExitSnapshot(canExit, android.os.SystemClock.elapsedRealtime())
        return result
    }

    private var pendingUsageMs = 0L

    @Synchronized
    fun consume(milliseconds: Long) {
        try { consumeUsage(milliseconds) } finally { publishNavigation() }
    }

    private fun consumeUsage(milliseconds: Long) {
        day()
        val config = store.config()
        if (!config.optBoolean("supervisionEnabled", true)) { flushUsage(); return }
        if (time.restLeft(config) > 0 || time.left("feedback_deadline") > 0) { flushUsage(); return }
        if (freeNow(config)) { flushUsage(); return }
        if (config.optString("scheduleMode", "allow") != "control" && !inWindow(config)) { flushUsage(); return }
        val available = capacity(config)
        val freeKey = if (number("free_quota_ms") > 0) "free_quota_ms" else "boot_quota_ms"
        val freeQuotaNow = number(freeKey)
        if (freeQuotaNow > 0) {
            val freeConsumed = min(milliseconds.coerceIn(0, 5000), min(freeQuotaNow, available))
            if (freeConsumed > 0) {
                val db = store.writableDatabase
                db.beginTransaction()
                try {
                    put(freeKey, freeQuotaNow - freeConsumed)
                    if (freeKey == "free_quota_ms") put("free_used_ms", number("free_used_ms") + freeConsumed)
                    time.consumed(config, freeConsumed)
                    put("used_ms", number("used_ms") + freeConsumed)
                    pendingUsageMs += freeConsumed
                    if (pendingUsageMs >= 30000 || freeQuotaNow == freeConsumed) flushUsage()
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                return
            }
            flushUsage(); return
        }
        val amount = min(milliseconds.coerceIn(0, 5000), min(number("credit_ms"), available))
        if (amount <= 0) { flushUsage(); return }
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            put("credit_ms", number("credit_ms") - amount)
            put("used_ms", number("used_ms") + amount)
            pendingUsageMs += amount
            time.consumed(config, amount)
            if (pendingUsageMs >= 30000 || number("credit_ms") <= 0) flushUsage()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun consumeRecovered(milliseconds: Long) {
        var left = milliseconds.coerceAtLeast(0)
        while (left > 0) {
            consume(minOf(left, 5000L))
            left -= 5000L
        }
    }

    @Synchronized
    fun flushUsage() {
        if (pendingUsageMs <= 0) return
        store.logEvent("usage", JSONObject().put("milliseconds", pendingUsageMs))
        pendingUsageMs = 0
    }

    private fun active(): JSONObject? = store.get("active_round").takeIf { it.isNotEmpty() }?.let { JSONObject(it) }

    private fun healPendingReview(round: JSONObject?) {
        val id = round?.optString("pendingReview")?.takeIf { it.isNotEmpty() } ?: return
        val review = store.review(id)
        val state = review?.optString("state") ?: "missing"
        if (state == "pending") return
        round.remove("pendingReview")
        store.set("active_round", round.toString())
        if (state == "graded") store.set("review_feedback", id)
        store.logEvent("review_healed", JSONObject().put("id", id).put("state", state))
        Diag.warn("Quiz", "pendingReview 已失效（$state），自动恢复 id=$id")
    }

    /**
     * 家长删题或暂停题目后修补进行中的一轮：孩子手上的题失效时立刻换成可用题目并升高 quiz_generation。
     */
    private fun repairActiveRound(removed: Set<String>, allCleared: Boolean): Boolean {
        val round = active() ?: return false
        val question = round.optJSONObject("question")
        val pendingId = round.optString("pendingReview")
        val reviewQuestionId = pendingId.takeIf { it.isNotEmpty() }?.let { store.review(it) }?.optJSONObject("question")?.optString("id") ?: ""
        val deck = round.optJSONArray("studyDeck")
        val deckHit = deck != null && (allCleared || (0 until deck.length()).any { deck.optJSONObject(it)?.optString("id") in removed })
        val hit = allCleared || deckHit || (question != null && question.optString("id") in removed) || (reviewQuestionId.isNotEmpty() && reviewQuestionId in removed)
        if (!hit) return false
        if (pendingId.isNotEmpty()) {
            store.review(pendingId)?.let { review ->
                if (review.optString("state") == "pending") {
                    review.put("state", "skipped").put("feedback", "El padre ha eliminado esta pregunta")
                    store.saveReview(review)
                }
            }
            round.remove("pendingReview")
        }
        if (deck != null) {
            val filtered = JSONArray()
            if (!allCleared) {
                for (i in 0 until deck.length()) {
                    val card = deck.optJSONObject(i) ?: continue
                    if (card.optString("id") !in removed) filtered.put(card)
                }
            }
            if (filtered.length() == 0) {
                round.remove("studyDeck")
                round.put("studyPhase", "quiz")
            } else {
                round.put("studyDeck", filtered)
                round.put("target", minOf(round.optInt("target", filtered.length()), filtered.length()))
                if (round.optString("studyPhase") == "memorize") {
                    round.put("question", filtered.getJSONObject(0)).put("nonce", UUID.randomUUID().toString())
                    store.set("active_round", round.toString())
                    put("quiz_generation", number("quiz_generation") + 1)
                    return true
                }
            }
        }
        val replaced = try { nextQuestion(round); true } catch (e: Exception) {
            Diag.warn("Quiz", "题目删空或已全部暂停，作废进行中的一轮: ${e.message}")
            false
        }
        if (replaced) {
            store.set("active_round", round.toString())
            store.logEvent("round_replaced", JSONObject().put("reason", "questions_deleted"))
        } else {
            for (key in listOf("active_round", "last_answer", "feedback_result", "feedback_deadline", "review_feedback")) store.set(key, "")
            store.logEvent("round_cleared", JSONObject().put("reason", "questions_deleted"))
        }
        put("quiz_generation", number("quiz_generation") + 1)
        return true
    }

    @Synchronized
    fun deleteQuestions(ids: List<String>): Int {
        val db = store.writableDatabase
        var committed = false
        val deleted: Int
        db.beginTransaction()
        try {
            deleted = store.deleteQuestions(ids)
            repairActiveRound(ids.toSet(), false)
            db.setTransactionSuccessful()
            committed = true
        } finally { db.endTransaction(); publishNavigation() }
        if (committed) store.pruneMedia()
        return deleted
    }

    /** 暂停题目时同样立即检查孩子手上的当前题，避免孩子继续答已经被家长暂停的题。 */
    @Synchronized
    fun setQuestionsEnabled(ids: List<String>, enabled: Boolean): Int {
        val db = store.writableDatabase
        val updated: Int
        db.beginTransaction()
        try {
            updated = store.setEnabled(ids, enabled)
            if (!enabled) repairActiveRound(ids.toSet(), false)
            db.setTransactionSuccessful()
        } finally { db.endTransaction(); publishNavigation() }
        return updated
    }

    private fun scopeValues(config: JSONObject, key: String): List<String> {
        val array = config.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }
    }

    private fun effectiveSubjects(config: JSONObject, round: JSONObject): List<String> {
        val specific = round.optString("subjectFilter").trim()
        return if (specific.isNotEmpty()) listOf(specific) else scopeValues(config, "quizSubjects")
    }

    private fun effectiveBanks(config: JSONObject, round: JSONObject): List<String> {
        val specific = round.optString("bankFilter").trim()
        return if (specific.isNotEmpty()) listOf(specific) else scopeValues(config, "quizBanks")
    }

    private fun nextQuestion(round: JSONObject) {
        val config = store.config()
        val subjects = effectiveSubjects(config, round)
        val levels = scopeValues(config, "quizLevels")
        val selectedLevel = round.optString("level")
        val banks = effectiveBanks(config, round)
        val focus = round.optString("focus")
        val spaced = config.optBoolean("spacedRepetitionEnabled") || focus == "due"
        val spacedWrongOnly = config.optString("spacedScope") == "wrong_only"
        val wrongFirst = config.optBoolean("wrongFirst") || focus == "wrong"
        val coverageFirst = config.optBoolean("coverageFirst")
        val newFirst = config.optBoolean("newFirst")

        val extraEnabled = round.optJSONObject("rules")?.optBoolean("extraEnabled", true) ?: true
        val extraStage = if (round.optString("mode") == "count") false
        else if (round.optString("mode") == "practice") number("extra_pool") > 0
        else extraEnabled && round.optInt("answered") >= round.getInt("target")

        // 「先背再测」进入小测阶段后，按刚背过的卡组逐题考查；只有触发错题加练后才回到题库随机补题
        val deck = round.optJSONArray("studyDeck")
        if (!extraStage && round.optString("studyPhase") == "quiz" && deck != null && deck.length() > 0) {
            var idx = round.optInt("deckIndex", -1) + 1
            while (idx < deck.length()) {
                val candidate = deck.optJSONObject(idx)
                val cid = candidate?.optString("id").orEmpty()
                if (cid.isNotEmpty() && store.questionExists(cid))
