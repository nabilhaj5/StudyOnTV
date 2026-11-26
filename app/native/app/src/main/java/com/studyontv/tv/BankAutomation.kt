package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Sincronización serial en un solo hilo, primero valida el lote completo y luego confirma; si falla, conserva el banco local. */
object BankAutomation {
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var checkedAt = 0L
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
    /** Tipos de pregunta opcionales para el relleno con IA (#125: en la TV es incómodo escribir en preguntas de relleno, los padres pueden rellenar solo con opción múltiple) */
    private val QUESTION_TYPES = listOf("single", "multiple", "boolean", "text", "subjective")
    /** Tipos de pregunta fijados en versiones antiguas; se usan cuando la configuración no tiene types, para evitar que el comportamiento cambie tras la actualización */
    private val LEGACY_TYPES = listOf("single", "text")

    /** Igual que BankMarket / GradingService: el banco remoto público debe usar HTTPS para evitar manipulación; HTTP en claro solo se permite en red local o dirección local. */
    internal fun validateUrl(raw: String): URL {
        val url = try { URL(raw.trim()) } catch (_: Exception) {
            throw IllegalArgumentException("Utilice una dirección HTTP/HTTPS sin usuario ni contraseña")
        }
        require(url.protocol in listOf("http", "https") && url.userInfo == null && url.ref == null && url.host.isNotEmpty() && url.toString().length <= 2048) {
            "Utilice una dirección HTTP/HTTPS sin usuario ni contraseña"
        }
        LocalOnlyGuard.requireLocalUrl(url.toString(), "banco remoto")
        val ok = url.protocol == "https" || (url.protocol == "http" && isPrivateHost(url.host))
        require(ok) { "El banco remoto público debe usar HTTPS; HTTP solo se permite en red local o dirección local" }
        return url
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host.equals("localhost", true) || host.endsWith(".local") || host.endsWith(".lan")) return true
        return try {
            val all = InetAddress.getAllByName(host)
            all.isNotEmpty() && all.all { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress }
        } catch (_: Exception) { false }
    }

    fun settings(store: LocalStore): JSONObject = JSONObject(store.get("bank_automation", "{}"))
        .apply {
            if (!has("sources")) put("sources", JSONArray())
            if (optJSONArray("types") == null || optJSONArray("types")!!.length() == 0) put("types", JSONArray(LEGACY_TYPES))
        }
        .put("running", running.get()).put("status", JSONObject(store.get("bank_automation_status", "{}")))

    fun save(store: LocalStore, input: JSONObject): JSONObject {
        val sources = input.optJSONArray("sources") ?: JSONArray()
        require(sources.length() <= 20) { "Se pueden configurar como máximo 20 bancos remotos" }
        val names = mutableSetOf<String>()
        val urls = mutableSetOf<String>()
        val clean = JSONArray()
        for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)
            val name = source.getString("name").trim()
            require(name.length in 1..40 && names.add(name)) { "El nombre del banco remoto debe ser único, de 1 a 40 caracteres" }
            val url = validateUrl(source.getString("url"))
            require(urls.add(url.toString())) { "La misma dirección remota solo se puede configurar una vez" }
            clean.put(JSONObject().put("name", name).put("url", url.toString()).put("enabled", source.optBoolean("enabled", true)))
        }
        val minimum = input.optInt("minimum", 30)
        val batch = input.optInt("batch", 10)
        val attempts = input.optInt("masteryAttempts", 5)
        val accuracy = input.optInt("masteryAccuracy", 90)
        require(minimum in 1..500 && batch in 1..20 && attempts in 1..100 && accuracy in 50..100) { "Los parámetros del banco automático están fuera de rango" }
        val topic = input.optString("topic").trim()
        require(topic.length <= 2000 && (!input.optBoolean("autoAi") || topic.isNotBlank())) { "El relleno automático requiere indicar el alcance de las preguntas" }
        val types = input.optJSONArray("types")?.let { array ->
            (0 until array.length()).map { array.optString(it) }.filter { it in QUESTION_TYPES }.distinct()
        }
        if (input.has("types")) require(!types.isNullOrEmpty()) { "Seleccione al menos un tipo de pregunta para el relleno automático" }
        val saved = if (types?.isNotEmpty() == true) types
            else settings(store).optJSONArray("types")?.let { array -> (0 until array.length()).map { array.optString(it) }.filter { it in QUESTION_TYPES } }.orEmpty()
                .ifEmpty { LEGACY_TYPES }
        store.set("bank_automation", JSONObject().put("sources", clean).put("autoAi", input.optBoolean("autoAi"))
            .put("minimum", minimum).put("batch", batch).put("masteryAttempts", attempts).put("masteryAccuracy", accuracy)
            .put("topic", topic).put("types", JSONArray(saved)).toString())
        return settings(store)
    }

    @Synchronized fun tick(store: LocalStore) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (checkedAt > 0 && now - checkedAt < 15 * 60000) return
        checkedAt = now
        start(store)
    }

    fun start(store: LocalStore) {
        if (!running.compareAndSet(false, true)) return
        worker.execute {
            val report = JSONObject().put("at", System.currentTimeMillis())
            try {
                val config = settings(store)
                val results = JSONArray()
                val sources = config.getJSONArray("sources")
                for (i in 0 until sources.length()) {
                    val source = sources.getJSONObject(i)
                    if (!source.optBoolean("enabled", true)) continue
                    if (LocalOnlyGuard.enabled && !LocalOnlyGuard.isLocalUrl(source.optString("url"))) continue
                    val entry = JSONObject().put("name", source.getString("name"))
                    try { entry.put("imported", syncSource(store, source)).put("ok", true) }
                    catch (_: Exception) { entry.put("ok", false).put("message", "Error de sincronización; verifique el JSON remoto, la dirección y la red; el banco local se ha conservado") }
                    results.put(entry)
                }
                report.put("sources", results)
                if (config.optBoolean("autoAi")) {
                    val all = store.questions(limit = 50000)
                    val mastered = mutableListOf<String>()
                    for (i in 0 until all.length()) {
                        val q = all.getJSONObject(i); val stats = q.optJSONObject("stats") ?: continue
                        val answered = stats.optInt("answered")
                        if (answered >= config.optInt("masteryAttempts", 5) && stats.optInt("correct") * 100 >= answered * config.optInt("masteryAccuracy", 90)) mastered.add(q.getString("id"))
                    }
                    if (mastered.isNotEmpty()) store.setEnabled(mastered, false)
                    report.put("pausedMastered", mastered.size)
                    if (store.countQuestions() < config.optInt("minimum", 30)) {
                        val result = GradingService.generateQuestions(store, JSONObject().put("topic", config.getString("topic"))
                            .put("count", config.optInt("batch", 10))
                            .put("types", config.optJSONArray("types")?.takeIf { it.length() > 0 } ?: JSONArray(LEGACY_TYPES))
                            .put("difficulty", "mixed").put("withTip", true).put("skipDuplicates", true))
                        val items = result.getJSONArray("items")
                        for (i in 0 until items.length()) items.getJSONObject(i).put("bank", "Relleno IA")
                        if (items.length() > 0) store.importQuestions(items)
                        report.put("generated", items.length())
                    }
                }
                report.put("ok", true)
            } catch (_: Exception) { report.put("ok", false).put("message", "Error en el relleno automático; verifique la configuración de IA y la red; las preguntas existentes se conservan") }
            finally { store.set("bank_automation_status", report.toString()); running.set(false) }
        }
    }

    fun syncSource(store: LocalStore, source: JSONObject): Int {
        var current = validateUrl(source.getString("url"))
        var connection = current.openConnection() as HttpURLConnection
        val text = try {
            var code: Int
            var redirects = 0
            while (true) {
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                code = connection.responseCode
                if (code !in listOf(301, 302, 303, 307, 308)) break
                require(redirects++ < 5) { "Demasiadas redirecciones" }
                val loc = connection.getHeaderField("Location") ?: break
                val next = validateUrl(URL(current, loc).toString())
                connection.disconnect()
                current = next
                connection = current.openConnection() as HttpURLConnection
            }
            require(code in 200..299)
            connection.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (true) { val count = input.read(chunk); if (count < 0) break; buffer.write(chunk, 0, count); require(buffer.size() <= 4_000_000) }
                buffer.toString("UTF-8").removePrefix("\uFEFF").trim()
            }
        } finally { connection.disconnect() }
        val items = if (text.startsWith("[")) JSONArray(text) else JSONObject(text).getJSONArray("items")
        require(items.length() in 1..50000) { "El banco remoto debe tener entre 1 y 50000 preguntas" }
        val sourceId = hash(source.getString("url"))
        val db = store.writableDatabase
        var committed = false
        db.beginTransaction()
        try {
            val old = mutableListOf<String>()
            db.rawQuery("SELECT id,details FROM questions", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val rawDetails = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val remoteSource = try { JSONObject(rawDetails).optString("remoteSource") } catch (_: Exception) { "" }
                    if (remoteSource == sourceId) old.add(cursor.getString(0))
                }
            }
            val keep = mutableSetOf<String>()
            for (i in 0 until items.length()) {
                val q = items.getJSONObject(i)
                val id = "remote_${sourceId}_" + hash(q.optString("id").ifBlank { q.getString("q") })
                require(keep.add(id)) { "El banco remoto contiene ID o enunciados duplicados" }
                q.put("id", id).put("bank", "Remoto·" + source.getString("name")).put("remoteSource", sourceId)
                store.upsertQuestion(q)
            }
            store.deleteQuestions(old.filter { it !in keep })
            db.setTransactionSuccessful()
            committed = true
            return items.length()
        } finally {
            db.endTransaction()
            if (committed) store.pruneMedia()
        }
    }
}
