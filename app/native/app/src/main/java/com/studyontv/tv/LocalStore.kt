package com.studyontv.tv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Funciones puras de cálculo de intervalos Ebbinghaus / Anki (SM-2 mejorado).
 *
 * Se extrajo a un objeto sin efectos secundarios por dos razones:
 * 1. Las tarjetas de memoria deben mostrar de antemano los intervalos de próxima revisión de los cuatro botones «Otra vez / Difícil / Bien / Fácil»,
 *    y deben coincidir exactamente con el valor que realmente se persiste al pulsar;
 * 2. Las pruebas unitarias de JVM puro pueden verificar directamente la evolución de cada nivel de calificación, sin depender de Android SQLite.
 */
object SpacedRepetition {
    data class State(val repetitions: Int = 0, val intervalDays: Long = 0L, val ease: Double = 2.5)
    data class Step(val repetitions: Int, val intervalDays: Long, val ease: Double, val dueAt: Long)

    fun next(current: State, quality: Int, nowMs: Long): Step {
        val q = quality.coerceIn(0, 5)
        var repetitions = current.repetitions
        var interval = current.intervalDays
        val dueAt: Long
        if (q < 3) {
            repetitions = 0
            interval = 0L
            dueAt = nowMs + 10 * 60 * 1000L
        } else {
            repetitions++
            val standardGood = when (repetitions) {
                1 -> 1L
                2 -> 6L
                else -> kotlin.math.round(current.intervalDays * current.ease).toLong().coerceIn(1L, 36500L)
            }
            interval = when (q) {
                3 -> if (current.repetitions == 0) 1L
                else maxOf(1L, kotlin.math.round(current.intervalDays * 1.2).toLong()).coerceIn(1L, 36500L)
                5 -> when (repetitions) {
                    1 -> 4L
                    2 -> maxOf(7L, kotlin.math.round(current.intervalDays.coerceAtLeast(1L) * current.ease * 1.3).toLong()).coerceIn(1L, 36500L)
                    else -> maxOf(standardGood + 1L, kotlin.math.round(current.intervalDays * current.ease * 1.3).toLong()).coerceIn(1L, 36500L)
                }
                else -> standardGood
            }
            dueAt = nowMs + interval * 86_400_000L
        }
        val nextEase = (current.ease + 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)).coerceAtLeast(1.3)
        return Step(repetitions, interval, nextEase, dueAt)
    }

    fun previewLabels(current: State): JSONObject {
        val hardDays = next(current, 3, 0L).intervalDays.coerceAtLeast(1L)
        val goodDays = next(current, 4, 0L).intervalDays.coerceAtLeast(1L)
        val easyDays = maxOf(goodDays + 1L, next(current, 5, 0L).intervalDays)
        return JSONObject()
            .put("again", "10 minutos")
            .put("hard", "$hardDays días")
            .put("good", "$goodDays días")
            .put("easy", "$easyDays días")
    }
}

class LocalStore(
    context: Context,
    databaseName: String = "studyontv.db",
    private val readClock: () -> DeviceClock.Reading = DeviceClock::read
) : SQLiteOpenHelper(context, databaseName, null, 11) {
    internal fun clockReading(): DeviceClock.Reading = readClock()

    companion object {
        val DEFAULT_PRAISE = listOf("¡Genial!", "¡Correcto!", "¡Totalmente correcto!", "¡Qué bien!", "¡Bien hecho, sigue así!", "¡Excelente! Cada vez lo dominas mejor")
        val DEFAULT_ENCOURAGE = listOf("No pasa nada, recuerda la respuesta correcta", "¡Casi lo tienes, ánimo con la siguiente!", "Equivocarse también es parte de aprender", "¡No te desanimes, sigue intentándolo!")
        /** El primer niño reutiliza las claves de almacenamiento de la versión antigua (sin prefijo); la actualización no requiere mover ningún dato */
        const val DEFAULT_CHILD = ""
        /** Ajustes compartidos a nivel de dispositivo: no cambian al cambiar de niño */
        private val SHARED_KEYS = setOf(
            "admin_token", "speech_settings", "grading_settings", "bank_automation", "bank_automation_status",
            "children", "active_child", "parent_access", "onboarded", "bank_market_sources"
        )
        /** Claves de almacenamiento con secretos / autenticación que deben excluirse al exportar copias de seguridad */
        private val SECRET_KV_KEYS = setOf(
            "admin_token", "speech_settings", "grading_settings", "parent_access"
        )
        /** Claves de almacenamiento transitorias vinculadas al reloj monotónico de este equipo, al contador de reinicios o a sesiones a medias: se omiten siempre al exportar y restaurar para evitar contaminar otros equipos */
        private val EPHEMERAL_KV_KEYS = setOf(
            "active_round", "last_answer", "feedback_result", "feedback_deadline",
            "review_feedback", "remote_override", "rest_deadline", "rest_watched_ms",
            "boot_quota_ms", "video_lesson"
        )
        private val CHILD_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$")
        private val pinRandom = SecureRandom()
        const val MAX_CHILDREN = 8

        internal fun isValidChildId(id: String): Boolean =
            id == DEFAULT_CHILD || CHILD_ID_PATTERN.matches(id)

        /**
         * Normaliza la entrada del PIN de cambio de niño:
         * - `null`: mantener el PIN actual sin cambios
         * - `""` (o solo espacios): borrar el PIN
         * - no vacío: validar longitud 1..16 y devolver el valor sin espacios iniciales/finales
         */
        internal fun normalizeChildPin(raw: String?): String? {
            if (raw == null) return null
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            require(trimmed.length in 1..16) { "El PIN de cambio necesita 1–16 caracteres" }
            return trimmed
        }

        internal fun generatePinSalt(): String =
            ByteArray(16).also { pinRandom.nextBytes(it) }
                .joinToString("") { "%02x".format(it) }

        internal fun hashChildPin(pin: String, salt: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$salt:$pin".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        internal fun verifyPinHash(pin: String, salt: String, expectedHash: String): Boolean {
            val candidate = pin.trim()
            if (candidate.isEmpty() || salt.isEmpty() || expectedHash.isEmpty()) return false
            val actual = hashChildPin(candidate, salt)
            return MessageDigest.isEqual(
                actual.toByteArray(Charsets.UTF_8),
                expectedHash.toByteArray(Charsets.UTF_8)
            )
        }

        /** Serializa el perfil del niño hacia el exterior: nunca envía pin_hash / pin_salt / texto original, solo hasPin. */
        internal fun childSummaryJson(
            id: String,
            name: String,
            createdAt: Long,
            active: Boolean,
            hasPin: Boolean
        ): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("createdAt", createdAt)
            .put("active", active)
            .put("hasPin", hasPin)

        internal fun isEphemeralKvKey(key: String): Boolean {
            val base = if (key.startsWith("child:")) key.substringAfterLast(':') else key
            return base in EPHEMERAL_KV_KEYS
        }

        /** Ámbito permitido para la sincronización recortada entre varios dispositivos */
        val SYNC_SCOPES = setOf("config", "questions", "speech", "grading")

        internal fun normalizeSyncScopes(scopes: Collection<String>): Set<String> =
            scopes.mapTo(LinkedHashSet()) { it.trim().lowercase(Locale.US) }.filterTo(LinkedHashSet()) { it in SYNC_SCOPES }

        /** Solo se permite sincronizar las reglas del plan de estudio (`config` y `child:<id>:config`); queda terminantemente prohibido sincronizar tiempos de ejecución locales, contraseñas o nombres de dispositivo. */
        internal fun isConfigSyncKvKey(key: String): Boolean {
            if (key == "config") return true
            if (key.startsWith("child:") && key.endsWith(":config")) {
                val childId = key.removePrefix("child:").removeSuffix(":config")
                return childId.isNotEmpty() && isValidChildId(childId)
            }
            return false
        }

        /**
         * Función pura: recorta con precisión la carga de sincronización del objeto de copia de seguridad/fuente según la lista blanca `scopes`.
         * Queda terminantemente prohibido incluir `deviceName`, `activeChild`, `events`, `reviews`, `questionStats`, `questionProgress`
         * ni ningún kv de estado de ejecución local (como `used_ms` / `free_used_ms` / `credit_ms` / `active_round` / `admin_token`).
         */
        internal fun buildScopedSyncPayload(source: JSONObject, scopes: Set<String>): JSONObject {
            val validScopes = normalizeSyncScopes(scopes)
            require(validScopes.isNotEmpty()) { "Selecciona al menos un elemento para sincronizar" }
            val out = JSONObject()
                .put("format", "studyontv-backup")
                .put("version", source.optInt("version", 1))
                .put("exportedAt", source.optLong("exportedAt", System.currentTimeMillis()))
                .put("appVersion", source.optString("appVersion", BuildConfig.VERSION_NAME))
                .put("scopes", JSONArray(validScopes.sorted()))
                .put("includeQuestions", "questions" in validScopes)
            if ("config" in validScopes) {
                source.optJSONArray("children")?.let { out.put("children", it) }
                val filteredKv = JSONObject()
                source.optJSONObject("kv")?.let { kv ->
                    val keys = kv.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (isConfigSyncKvKey(k)) {
                            filteredKv.put(k, kv.optString(k, ""))
                        }
                    }
                }
                out.put("kv", filteredKv)
            }
            if ("questions" in validScopes) {
                out.put("questions", source.optJSONArray("questions") ?: JSONArray())
            }
            if ("speech" in validScopes) {
                source.optJSONObject("speech")?.let { out.put("speech", it) }
            }
            if ("grading" in validScopes) {
                source.optJSONObject("grading")?.let { out.put("grading", it) }
            }
            return out
        }

        /** Limpia un elemento kv de copia de seguridad en modo EU Local-Only (elimina vídeos públicos de interés general de config y fuentes públicas de bank_market_sources). */
        internal fun sanitizeKvValueForLocalOnly(key: String, value: String, localOnly: Boolean = LocalOnlyGuard.enabled): String {
            if (!localOnly) return value
            val base = if (key.startsWith("child:")) key.substringAfterLast(':') else key
            return when (base) {
                "config" -> try {
                    val cfg = JSONObject(value)
                    val videos = cfg.optJSONArray("educationalVideos")
                    if (videos != null) {
                        val kept = JSONArray()
                        for (i in 0 until videos.length()) {
                            val raw = videos.optString(i).trim()
                            if (raw.isNotEmpty() && LocalOnlyGuard.isLocalUrl(raw)) kept.put(raw)
                        }
                        cfg.put("educationalVideos", kept)
                        if (kept.length() == 0 && cfg.optString("learningContent", "quiz") != "quiz") {
                            cfg.put("learningContent", "quiz")
                        }
                    }
                    cfg.toString()
                } catch (_: Exception) { value }
                "bank_market_sources" -> try {
                    val arr = JSONArray(value)
                    val kept = JSONArray()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val url = item.optString("url").trim()
                        if (url.isNotEmpty() && LocalOnlyGuard.isLocalUrl(url)) kept.put(item)
                    }
                    kept.toString()
                } catch (_: Exception) { value }
                "bank_automation" -> try {
                    sanitizeBankAutomationForLocalOnly(JSONObject(value), localOnly).toString()
                } catch (_: Exception) { value }
                else -> value
            }
        }

        /** Limpia las fuentes remotas públicas de bankAutomation.sources en la copia de seguridad en modo EU Local-Only. */
        internal fun sanitizeBankAutomationForLocalOnly(raw: JSONObject, localOnly: Boolean = LocalOnlyGuard.enabled): JSONObject {
            val next = JSONObject(raw.toString())
            next.remove("running")
            next.remove("status")
            if (!localOnly) return next
            val sources = next.optJSONArray("sources")
            if (sources != null) {
                val kept = JSONArray()
                for (i in 0 until sources.length()) {
                    val item = sources.optJSONObject(i) ?: continue
                    val url = item.optString("url").trim()
                    if (url.isNotEmpty() && LocalOnlyGuard.isLocalUrl(url)) kept.put(item)
                }
                next.put("sources", kept)
            }
            return next
        }

        /** Juicio de inclusión en el ámbito de repetición espaciada (fuente única de verdad de repetitionStats / studySummary / learningAnalytics). */
        internal fun inSpacedScope(wrongOnly: Boolean, lastCorrect: Int, answered: Int, correct: Int): Boolean =
            !wrongOnly || lastCorrect == 0 || answered > correct

        /** Resuelve la zona horaria por ID IANA; vacío o valor no válido recurre a la zona predeterminada del dispositivo. */
        internal fun resolveTimeZone(rawZoneId: String): TimeZone {
            val id = rawZoneId.trim()
            if (id.isEmpty()) return TimeZone.getDefault()
            val zone = TimeZone.getTimeZone(id)
            return if (zone.id == "GMT" && id != "GMT" && id != "UTC") TimeZone.getDefault() else zone
        }

        /** Calcula la marca de tiempo en milisegundos de las 00:00:00.000 del día natural de la zona horaria indicada en el instante dado. */
        internal fun startOfDayMillis(nowMs: Long, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        /** Resultado unificado del filtro de rango SQL: todos los valores se enlazan con marcadores parametrizados, erradicando la inyección SQL. */
        internal data class SqlFilter(val clause: String, val args: List<String>)

        /** Analiza el array JSON de la cola de práctica extra, filtra valores vacíos y deduplica conservando el orden. */
        internal fun parseExtraQueue(raw: String): List<String> {
            if (raw.isBlank()) return emptyList()
            val arr = try { JSONArray(raw) } catch (_: Exception) { return emptyList() }
            val out = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optString(i).trim()
                if (id.isNotEmpty()) out.add(id)
            }
            return out.toList()
        }

        /** Fusiona los nuevos números de pregunta en la cola de práctica extra (deduplicando y conservando el orden), devolviendo la cola fusionada y el número real de añadidos. */
        internal fun mergeExtraQueue(existing: List<String>, additions: List<String>): Pair<List<String>, Int> {
            val merged = LinkedHashSet<String>()
            existing.forEach { id -> id.trim().takeIf { it.isNotEmpty() }?.let { merged.add(it) } }
            val before = merged.size
            additions.forEach { id -> id.trim().takeIf { it.isNotEmpty() }?.let { merged.add(it) } }
            return merged.toList() to (merged.size - before)
        }

        /** Normaliza config.gradeSubjects: filtra automáticamente grados vacíos/claves demasiado largas, deduplica asignaturas y conserva arrays vacíos (que indican todas las asignaturas de ese grado). */
        internal fun normalizeGradeSubjects(raw: JSONObject?): JSONObject {
            val clean = JSONObject()
            if (raw == null) return clean
            val keys = raw.keys()
            var gradeCount = 0
            while (keys.hasNext() && gradeCount < 50) {
                val rawKey = keys.next()
                val grade = rawKey.trim()
                if (grade.isEmpty() || grade.length > 32) continue
                val arr = raw.optJSONArray(rawKey)
                val cleanSubjects = JSONArray()
                val seen = LinkedHashSet<String>()
                if (arr != null) {
                    for (i in 0 until minOf(arr.length(), 50)) {
                        val sub = arr.optString(i).trim()
                        if (sub.isNotEmpty() && sub.length <= 32 && seen.add(sub)) {
                            cleanSubjects.put(sub)
                        }
                    }
                }
                clean.put(grade, cleanSubjects)
                gradeCount++
            }
            return clean
        }

        /** Analiza config.gradeSubjects a un Map ordenado <grado, lista de asignaturas> (lista vacía indica que ese grado no restringe asignaturas). */
        internal fun parseGradeSubjects(raw: JSONObject?): Map<String, List<String>> {
            val normalized = normalizeGradeSubjects(raw)
            val out = LinkedHashMap<String, List<String>>()
            val keys = normalized.keys()
            while (keys.hasNext()) {
                val grade = keys.next()
                val arr = normalized.optJSONArray(grade)
                val subjects = ArrayList<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val sub = arr.optString(i).trim()
                        if (sub.isNotEmpty()) subjects.add(sub)
                    }
                }
                out[grade] = subjects
            }
            return out
        }

        /** Juicio de coincidencia de ámbito principal-esclavo en memoria (estrictamente coherente con la semántica SQL de buildScopeFilter). */
        internal fun matchesGradeSubjects(
            level: String,
            subject: String,
            gradeSubjects: Map<String, List<String>>,
            selectedLevel: String = ""
        ): Boolean {
            if (gradeSubjects.isEmpty()) return true
            if (selectedLevel.isNotEmpty()) {
                if (level != selectedLevel) return false
                val allowed = gradeSubjects[selectedLevel] ?: return false
                return allowed.isEmpty() || subject in allowed
            }
            val allowed = gradeSubjects[level] ?: return false
            return allowed.isEmpty() || subject in allowed
        }

        /**
         * Construye el fragmento SQL del filtro de ámbito de preguntas (fuente única de verdad de pickQuestion / pickDeck / distinctLevels / coverage).
         * - Si gradeSubjects está vacío, se conserva al 100 % la semántica antigua de producto cartesiano levels × subjects;
         * - Si gradeSubjects no está vacío, se hace coincidencia exacta por asignaturas permitidas de cada grado; si se especifica selectedLevel, solo se aplica a ese grado.
         */
        internal fun buildScopeFilter(
            subjects: List<String> = emptyList(),
            levels: List<String> = emptyList(),
            selectedLevel: String = "",
            banks: List<String> = emptyList(),
            gradeSubjects: Map<String, List<String>> = emptyMap(),
            explicitSubjectFilter: Boolean = false
        ): SqlFilter {
            fun ph(count: Int) = List(count) { "?" }.joinToString(",")
            val where = StringBuilder()
            val args = mutableListOf<String>()

            if (gradeSubjects.isNotEmpty()) {
                if (selectedLevel.isNotEmpty()) {
                    val allowedSubjects = gradeSubjects[selectedLevel]
                    if (allowedSubjects == null) {
                        where.append(" AND 1=0")
                    } else if (allowedSubjects.isEmpty()) {
                        where.append(" AND questions.level=?")
                        args.add(selectedLevel)
                    } else {
                        where.append(" AND (questions.level=? AND questions.subject IN (${ph(allowedSubjects.size)}))")
                        args.add(selectedLevel)
                        args.addAll(allowedSubjects)
                    }
                } else {
                    val branches = mutableListOf<String>()
                    for ((grade, allowedSubjects) in gradeSubjects) {
                        if (allowedSubjects.isEmpty()) {
                            branches.add("questions.level=?")
                            args.add(grade)
                        } else {
                            branches.add("(questions.level=? AND questions.subject IN (${ph(allowedSubjects.size)}))")
                            args.add(grade)
                            args.addAll(allowedSubjects)
                        }
                    }
                    if (branches.isNotEmpty()) {
                        where.append(" AND (${branches.joinToString(" OR ")})")
                    }
                }
                if (explicitSubjectFilter && subjects.isNotEmpty()) {
                    where.append(" AND questions.subject IN (${ph(subjects.size)})")
                    args.addAll(subjects)
                }
            } else {
                if (subjects.isNotEmpty()) {
                    where.append(" AND questions.subject IN (${ph(subjects.size)})")
                    args.addAll(subjects)
                }
                if (levels.isNotEmpty()) {
                    where.append(" AND questions.level IN (${ph(levels.size)})")
                    args.addAll(levels)
                }
                if (selectedLevel.isNotEmpty()) {
                    where.append(" AND questions.level=?")
                    args.add(selectedLevel)
                }
            }

            if (banks.isNotEmpty()) {
                // ⚠️ "默认题库" es un VALOR PERSISTIDO y COMPARADO en SQL: NO traducir
                if ("默认题库" in banks) {
                    where.append(" AND (questions.bank IN (${ph(banks.size)}) OR questions.bank='')")
                } else {
                    where.append(" AND questions.bank IN (${ph(banks.size)})")
                }
                args.addAll(banks)
            }
            return SqlFilter(where.toString(), args)
        }
    }

    private val appContext: Context = context.applicationContext ?: context

    /** Las bases de datos de prueba usan un directorio independiente, para que la limpieza de medios en las pruebas de regresión no dañe las imágenes del banco de preguntas familiar. */
    val mediaDir: File = File(
        appContext.filesDir,
        if (databaseName == "studyontv.db") "media"
        else "media_" + databaseName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    )

    /** Caché por niño del JSON de configuración normalizado, para no consultar la base de datos, ejecutar regex y analizar el plan semanal en cada tick por segundo. */
    private val configCache = ConcurrentHashMap<String, String>()

    override fun onCreate(db: SQLiteDatabase) {
        // bank / type antes solo existían en el campo JSON details; la página de banco de preguntas y las estadísticas de cobertura tenían que escanear toda la tabla y analizar fila por fila.
        // Desde la versión 9 también se escriben como columnas independientes, para que el filtrado y la ordenación puedan usar índices; details sigue siendo la pregunta completa (el formato de exportación no cambia).
        db.execSQL("CREATE TABLE questions(id TEXT PRIMARY KEY, subject TEXT, level TEXT, difficulty INTEGER, stem TEXT NOT NULL, options TEXT NOT NULL, answer INTEGER NOT NULL, tip TEXT, enabled INTEGER DEFAULT 1, created_at INTEGER, details TEXT, bank TEXT NOT NULL DEFAULT '默认题库', type TEXT NOT NULL DEFAULT 'single')")
        createQuestionIndexes(db)
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, payload TEXT, ts INTEGER, child TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts)")
        createReviews(db)
        createProgress(db)
        createQuestionStats(db)
        createChildren(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE questions ADD COLUMN details TEXT")
        if (oldVersion < 3) createReviews(db)
        if (oldVersion < 4) createProgress(db)
        if (oldVersion < 5) compactUsage(db)
        if (oldVersion < 6) createQuestionStats(db)
        if (oldVersion < 7) {
            // Varios niños (#132): historial / tiempo de uso / progreso Ebbinghaus se aíslan por niño; los registros antiguos se asignan al primer niño
            createChildren(db)
            addColumn(db, "events", "child", "TEXT NOT NULL DEFAULT ''")
            migrateProgressForChildren(db)
        }
        // Banco de errores (#136): registra el resultado de la última respuesta de cada pregunta; -1 indica que aún no se ha respondido
        if (oldVersion < 8) addColumn(db, "question_stats", "last_correct", "INTEGER NOT NULL DEFAULT -1")
        if (oldVersion < 9) {
            // Banco / tipo de pregunta se convierten en columnas independientes y se rellenan. El relleno usa Kotlin para analizar details, sin depender de JSON_EXTRACT
            // (el SQLite integrado en Android 5–11 no tiene esta función).
            addColumn(db, "questions", "bank", "TEXT NOT NULL DEFAULT '默认题库'")
            addColumn(db, "questions", "type", "TEXT NOT NULL DEFAULT 'single'")
            backfillQuestionColumns(db)
            createQuestionIndexes(db)
        }
        if (oldVersion < 10) {
            // Versión 10: question_stats pasa a clave primaria compuesta (qid, child) aislada por niño;
            // además, las imágenes incrustadas en preguntas históricas y correcciones manuscritas se migran a archivos multimedia locales, evitando desbordar el CursorWindow.
            migrateQuestionStatsForChildren(db)
            migrateInlineMedia(db)
        }
        if (oldVersion < 11) {
            // Versión 11 (ST-610): PIN de cambio independiente por niño (hash SHA-256 con sal; cadena vacía por defecto indica sin PIN)
            addColumn(db, "children", "pin_hash", "TEXT NOT NULL DEFAULT ''")
            addColumn(db, "children", "pin_salt", "TEXT NOT NULL DEFAULT ''")
        }
    }

    override fun close() {
        super.close()
        configCache.clear()
        synchronized(childLock) { cachedChild = null }
    }

    private fun createQuestionIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_bank ON questions(bank)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_type ON questions(type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_enabled ON questions(enabled)")
    }

    /** Copia bank / type de details de la base de datos antigua a las nuevas columnas; lee por lotes para no meter decenas de miles de preguntas en memoria de una vez. */
    private fun backfillQuestionColumns(db: SQLiteDatabase) {
        val statement = db.compileStatement("UPDATE questions SET bank=?,type=? WHERE id=?")
        try {
            var last = -1L
            while (true) {
                val rows = ArrayList<Triple<String, String, String>>()
                db.rawQuery("SELECT rowid,id,details FROM questions WHERE rowid>? ORDER BY rowid LIMIT 500", arrayOf(last.toString())).use { c ->
                    while (c.moveToNext()) {
                        last = c.getLong(0)
                        val details = try { JSONObject(c.getString(2)?.takeIf { it.isNotBlank() } ?: "{}") } catch (_: Exception) { JSONObject() }
                        rows.add(Triple(c.getString(1), details.optString("bank").ifBlank { "默认题库" }, details.optString("type").ifBlank { "single" }))
                    }
                }
                if (rows.isEmpty()) break
                rows.forEach { (id, bank, type) ->
                    statement.clearBindings()
                    statement.bindString(1, bank)
                    statement.bindString(2, type)
                    statement.bindString(3, id)
                    statement.executeUpdateDelete()
                }
            }
        } finally {
            statement.close()
        }
    }

    private fun addColumn(db: SQLiteDatabase, table: String, column: String, type: String) {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { while (it.moveToNext()) columns.add(it.getString(1)) }
        if (column !in columns) db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
    }

    private fun createChildren(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS children(id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL, pin_hash TEXT NOT NULL DEFAULT '', pin_salt TEXT NOT NULL DEFAULT '')")
    }

    /** La antigua question_progress tenía qid como clave primaria; con varios niños debe pasar a clave primaria compuesta (qid, child) */
    private fun migrateProgressForChildren(db: SQLiteDatabase) {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(question_progress)", null).use { while (it.moveToNext()) columns.add(it.getString(1)) }
        if ("child" in columns) return
        db.execSQL("ALTER TABLE question_progress RENAME TO question_progress_legacy")
        createProgress(db)
        db.execSQL("INSERT INTO question_progress(qid,child,repetitions,interval_days,ease,due_at,updated_at) " +
            "SELECT qid,'',repetitions,interval_days,ease,due_at,updated_at FROM question_progress_legacy")
        db.execSQL("DROP TABLE question_progress_legacy")
    }

    /** La antigua question_stats era compartida por todo el equipo, y en familias con dos hijos los errores y la cobertura de ambos se modificaban mutuamente; se actualiza a clave primaria compuesta (qid, child) y las estadísticas antiguas se asignan al niño predeterminado. */
    private fun migrateQuestionStatsForChildren(db: SQLiteDatabase) {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(question_stats)", null).use { while (it.moveToNext()) columns.add(it.getString(1)) }
        if (columns.isEmpty()) {
            createQuestionStats(db)
            return
        }
        if ("child" in columns) return
        val lastCol = if ("last_correct" in columns) "last_correct" else "-1"
        db.execSQL("DROP TABLE IF EXISTS question_stats_legacy")
        db.execSQL("ALTER TABLE question_stats RENAME TO question_stats_legacy")
        createQuestionStats(db)
        db.execSQL(
            "INSERT OR REPLACE INTO question_stats(qid,child,presented,answered,correct,last_at,last_correct) " +
                "SELECT qid,'',presented,answered,correct,last_at,$lastCol FROM question_stats_legacy"
        )
        db.execSQL("DROP TABLE question_stats_legacy")
    }

    /**
     * Migra fila por fila los `data:image/` incrustados en la base de datos histórica:
     * usa `LIKE '%data:image/%'` para saltarse la inmensa mayoría de filas de solo texto, y lee una sola fila con `LIMIT 1` cada vez, evitando que varias imágenes grandes entren a la vez en el C
