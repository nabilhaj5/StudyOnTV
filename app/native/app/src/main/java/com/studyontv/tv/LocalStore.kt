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
     * usa `LIKE '%data:image/%'` para saltarse la inmensa mayoría de filas de solo texto, y lee una sola fila con `LIMIT 1` cada vez, evitando que varias imágenes grandes entren a la vez en el CursorWindow de 2 MB.
     */
    private fun migrateInlineMedia(db: SQLiteDatabase) {
        val updateQ = db.compileStatement("UPDATE questions SET details=? WHERE id=?")
        try {
            var lastRow = -1L
            while (true) {
                var rowId = -1L
                var id = ""
                var raw = ""
                db.rawQuery(
                    "SELECT rowid,id,details FROM questions WHERE rowid>? AND details LIKE '%data:image%' ORDER BY rowid LIMIT 1",
                    arrayOf(lastRow.toString())
                ).use { c ->
                    if (c.moveToFirst()) {
                        rowId = c.getLong(0)
                        id = c.getString(1) ?: ""
                        raw = c.getString(2) ?: ""
                    }
                }
                if (rowId < 0) break
                lastRow = rowId
                val updated = migrateQuestionDetailsJson(raw)
                if (updated != null && updated != raw) {
                    updateQ.clearBindings()
                    updateQ.bindString(1, updated)
                    updateQ.bindString(2, id)
                    updateQ.executeUpdateDelete()
                }
            }
        } finally {
            updateQ.close()
        }

        val updateR = db.compileStatement("UPDATE reviews SET payload=? WHERE id=?")
        try {
            var lastRow = -1L
            while (true) {
                var rowId = -1L
                var id = ""
                var raw = ""
                db.rawQuery(
                    "SELECT rowid,id,payload FROM reviews WHERE rowid>? AND payload LIKE '%data:image%' ORDER BY rowid LIMIT 1",
                    arrayOf(lastRow.toString())
                ).use { c ->
                    if (c.moveToFirst()) {
                        rowId = c.getLong(0)
                        id = c.getString(1) ?: ""
                        raw = c.getString(2) ?: ""
                    }
                }
                if (rowId < 0) break
                lastRow = rowId
                val updated = migrateReviewPayloadJson(raw)
                if (updated != null && updated != raw) {
                    updateR.clearBindings()
                    updateR.bindString(1, updated)
                    updateR.bindString(2, id)
                    updateR.executeUpdateDelete()
                }
            }
        } finally {
            updateR.close()
        }
    }

    private fun migrateDataUrlField(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("data:image/", ignoreCase = true)) return raw
        return try {
            MediaStore.saveDataUrl(mediaDir, trimmed, 2_000_000)
        } catch (_: Exception) {
            // Si un dato histórico corrupto no se puede decodificar, se vacía la referencia a la imagen rota; nunca se deja que la actualización de la base de datos lance una excepción y bloquee el arranque
            ""
        }
    }

    private fun migrateQuestionObjectMedia(obj: JSONObject): Boolean {
        var changed = false
        val image = obj.optString("image")
        if (image.startsWith("data:image/", ignoreCase = true)) {
            obj.put("image", migrateDataUrlField(image))
            changed = true
        }
        val images = obj.optJSONArray("images")
        if (images != null) {
            val next = JSONArray()
            var arrChanged = false
            for (i in 0 until images.length()) {
                val v = images.optString(i)
                val m = migrateDataUrlField(v)
                if (m != v) arrChanged = true
                if (m.isNotEmpty()) next.put(m)
            }
            if (arrChanged) {
                obj.put("images", next)
                changed = true
            }
        }
        val optImgs = obj.optJSONArray("optionImages")
        if (optImgs != null) {
            val next = JSONArray()
            var arrChanged = false
            for (i in 0 until optImgs.length()) {
                val v = optImgs.optString(i)
                val m = migrateDataUrlField(v)
                if (m != v) arrChanged = true
                next.put(m)
            }
            if (arrChanged) {
                obj.put("optionImages", next)
                changed = true
            }
        }
        return changed
    }

    private fun migrateQuestionDetailsJson(raw: String): String? = try {
        val obj = JSONObject(raw)
        if (migrateQuestionObjectMedia(obj)) obj.toString() else null
    } catch (_: Exception) { null }

    private fun migrateReviewPayloadJson(raw: String): String? = try {
        val obj = JSONObject(raw)
        var changed = false
        obj.optJSONObject("input")?.let { input ->
            val drawing = input.optString("drawing")
            if (drawing.startsWith("data:image/", ignoreCase = true)) {
                val migrated = migrateDataUrlField(drawing)
                if (migrated.isEmpty()) input.remove("drawing") else input.put("drawing", migrated)
                changed = true
            }
        }
        obj.optJSONObject("question")?.let { q ->
            if (migrateQuestionObjectMedia(q)) changed = true
        }
        if (changed) obj.toString() else null
    } catch (_: Exception) { null }

    /**
     * Las versiones antiguas escribían un evento usage cada segundo, hasta decenas de miles de filas al día, lo que hacía que /api/state y el historial de crecimiento fueran cada vez más lentos (Issue #2: el panel tarda en cargar).
     * Al actualizar se fusionan por hora en una sola fila.
     */
    private fun compactUsage(db: SQLiteDatabase) {
        val buckets = LinkedHashMap<Long, Long>()
        db.rawQuery("SELECT payload,ts FROM events WHERE type='usage'", null).use { c ->
            while (c.moveToNext()) {
                val ms = try { JSONObject(c.getString(0)).optLong("milliseconds") } catch (_: Exception) { 0L }
                val hour = c.getLong(1) / 3600000 * 3600000
                buckets[hour] = (buckets[hour] ?: 0L) + ms
            }
        }
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM events WHERE type='usage'")
            buckets.forEach { (hour, ms) ->
                if (ms > 0) db.execSQL("INSERT INTO events(type,payload,ts) VALUES(?,?,?)", arrayOf<Any>("usage", JSONObject().put("milliseconds", ms).toString(), hour))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun createQuestionStats(db: SQLiteDatabase) {
        // IF NOT EXISTS: si el proceso de actualización se interrumpe / se repite, no debe fallar toda la base de datos; de lo contrario /api/state y el banco de preguntas no se podrían leer
        // last_correct: si la última respuesta fue correcta; -1 = aún no respondida (el banco de errores lo usa para decidir si es una pregunta fallada)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS question_stats(" +
                "qid TEXT NOT NULL, " +
                "child TEXT NOT NULL DEFAULT '', " +
                "presented INTEGER NOT NULL DEFAULT 0, " +
                "answered INTEGER NOT NULL DEFAULT 0, " +
                "correct INTEGER NOT NULL DEFAULT 0, " +
                "last_at INTEGER NOT NULL DEFAULT 0, " +
                "last_correct INTEGER NOT NULL DEFAULT -1, " +
                "PRIMARY KEY(qid, child))"
        )
    }

    fun presented(id: String, child: String = activeChild()) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO question_stats(qid,child) VALUES(?,?)", arrayOf(id, child))
        writableDatabase.execSQL(
            "UPDATE question_stats SET presented=presented+1,last_at=? WHERE qid=? AND child=?",
            arrayOf<Any>(clockReading().millis, id, child)
        )
    }

    fun answered(id: String, correct: Boolean, child: String = activeChild()) {
        val flag = if (correct) 1 else 0
        writableDatabase.execSQL("INSERT OR IGNORE INTO question_stats(qid,child) VALUES(?,?)", arrayOf(id, child))
        writableDatabase.execSQL(
            "UPDATE question_stats SET answered=answered+1,correct=correct+?,last_correct=?,last_at=? WHERE qid=? AND child=?",
            arrayOf<Any>(flag, flag, clockReading().millis, id, child)
        )
    }

    /** IDs de pregunta del banco: se obtienen antes de borrar el banco, para decidir si la ronda en curso del niño ha sido eliminada. */
    fun idsInBank(bank: String): List<String> {
        require(bank.isNotBlank()) { "Elige un banco de preguntas" }
        val ids = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT id FROM questions WHERE bank=?", arrayOf(bank)).use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getString(0))
        }
        return ids
    }

    fun deleteBank(bank: String): Int = deleteQuestions(idsInBank(bank))

    /** La comprobación de duplicados de generación de preguntas por IA solo necesita la cadena del enunciado, evitando leer toda la tabla details y estadísticas. */
    fun allStems(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT stem FROM questions", null).use { c ->
            while (c.moveToNext()) c.getString(0)?.let { out.add(it) }
        }
        return out
    }

    /** Verifica si la pregunta sigue existiendo al generar / liquidar: el padre puede haberla borrado justo antes en la página de administración. */
    fun questionExists(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM questions WHERE id=? LIMIT 1", arrayOf(id)).use {
        it.moveToFirst()
    }

    private fun createProgress(db: SQLiteDatabase) {
        // Cada niño tiene su propio conjunto de progreso de repaso (#132); la base de datos antigua se traslada en migrateProgressForChildren, child='' es el primer niño
        db.execSQL("CREATE TABLE IF NOT EXISTS question_progress(qid TEXT NOT NULL, child TEXT NOT NULL DEFAULT '', repetitions INTEGER NOT NULL, interval_days INTEGER NOT NULL, ease REAL NOT NULL, due_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(qid, child))")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_question_due ON question_progress(due_at)")
    }

    fun progressState(id: String, child: String = activeChild()): SpacedRepetition.State =
        readableDatabase.rawQuery(
            "SELECT repetitions,interval_days,ease FROM question_progress WHERE qid=? AND child=?",
            arrayOf(id, child)
        ).use {
            if (it.moveToFirst()) SpacedRepetition.State(it.getInt(0), it.getLong(1), it.getDouble(2))
            else SpacedRepetition.State()
        }

    fun previewIntervals(id: String, child: String = activeChild()): JSONObject =
        SpacedRepetition.previewLabels(progressState(id, child))

    fun recordProgress(id: String, quality: Int) {
        val child = activeChild()
        val current = progressState(id, child)
        val now = clockReading().millis
        val step = SpacedRepetition.next(current, quality, now)
        writableDatabase.insertWithOnConflict("question_progress", null, ContentValues().apply {
            put("qid", id); put("child", child); put("repetitions", step.repetitions)
            put("interval_days", step.intervalDays); put("ease", step.ease)
            put("due_at", step.dueAt); put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @JvmOverloads
    fun repetitionStats(child: String = activeChild()): JSONObject {
        val wrongOnly = config(child).optString("spacedScope") == "wrong_only"
        val nowStr = clockReading().millis.toString()
        val sql = if (wrongOnly) {
            "SELECT COUNT(*),COALESCE(SUM(CASE WHEN question_progress.due_at<=? AND (stats.last_correct=0 OR COALESCE(stats.answered,0)>COALESCE(stats.correct,0)) THEN 1 ELSE 0 END),0) " +
                "FROM question_progress JOIN questions ON questions.id=question_progress.qid " +
                "LEFT JOIN question_stats stats ON stats.qid=questions.id AND stats.child=question_progress.child " +
                "WHERE questions.enabled=1 AND question_progress.child=?"
        } else {
            "SELECT COUNT(*),COALESCE(SUM(CASE WHEN due_at<=? THEN 1 ELSE 0 END),0) " +
                "FROM question_progress JOIN questions ON questions.id=question_progress.qid " +
                "WHERE questions.enabled=1 AND question_progress.child=?"
        }
        return readableDatabase.rawQuery(sql, arrayOf(nowStr, child)).use {
            if (it.moveToFirst()) JSONObject().put("studied", it.getInt(0)).put("due", it.getInt(1))
            else JSONObject().put("studied", 0).put("due", 0)
        }
    }

    private fun createReviews(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE reviews(id TEXT PRIMARY KEY, nonce TEXT UNIQUE, state TEXT NOT NULL, payload TEXT NOT NULL, ts INTEGER NOT NULL)")
    }

    fun saveReview(value: JSONObject) {
        writableDatabase.insertWithOnConflict("reviews", null, ContentValues().apply {
            put("id", value.getString("id")); put("nonce", value.getJSONObject("input").getString("nonce"))
            put("state", value.optString("state", "pending")); put("payload", value.toString()); put("ts", value.getLong("ts"))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun noteReview(value: JSONObject) {
        writableDatabase.update("reviews", ContentValues().apply { put("payload", value.toString()) },
            "id=? AND state='pending'", arrayOf(value.getString("id")))
    }

    fun review(id: String): JSONObject? = readableDatabase.rawQuery("SELECT payload FROM reviews WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }

    fun reviews(state: String, child: String? = null): JSONArray {
        val out = JSONArray()
        val filter = if (state == "all") "" else "WHERE state=?"
        readableDatabase.rawQuery("SELECT payload FROM reviews $filter ORDER BY ts DESC LIMIT 200", if (state == "all") null else arrayOf(state)).use {
            while (it.moveToNext()) {
                val obj = JSONObject(it.getString(0))
                if (child == null || obj.optString("child", DEFAULT_CHILD) == child) out.put(obj)
            }
        }
        return out
    }

    /** Niño actual: el niño predeterminado usa cadena vacía, reutilizando las claves de almacenamiento de la versión antigua; no hay que mover datos al actualizar */
    @Volatile private var cachedChild: String? = null
    private val childLock = Any()
    /** Al corregir preguntas subjetivas de otro niño se vincula temporalmente el hilo actual a ese niño, evitando cambiar el active_child global y afectar al niño que está estudiando en primer plano en la TV. */
    private val childOverride = ThreadLocal<String?>()

    fun childExists(id: String): Boolean =
        id == DEFAULT_CHILD || readableDatabase.rawQuery("SELECT 1 FROM children WHERE id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }

    fun <T> withChild(childId: String, block: () -> T): T {
        val prev = childOverride.get()
        childOverride.set(childId)
        return try {
            block()
        } finally {
            if (prev == null) childOverride.remove() else childOverride.set(prev)
        }
    }

    fun activeChild(): String {
        childOverride.get()?.let { return it }
        cachedChild?.let { return it }
        synchronized(childLock) {
            cachedChild?.let { return it }
            val stored = rawGet("active_child")
            val valid = when {
                stored.isEmpty() -> DEFAULT_CHILD
                readableDatabase.rawQuery("SELECT 1 FROM children WHERE id=? LIMIT 1", arrayOf(stored)).use { it.moveToFirst() } -> stored
                else -> { rawSet("active_child", DEFAULT_CHILD); DEFAULT_CHILD }
            }
            cachedChild = valid
            return valid
        }
    }

    /** Las claves relacionadas con el estudio se aíslan por niño; ajustes de dispositivo como lectura / corrección / banco remoto siguen siendo compartidos */
    private fun scoped(key: String, child: String = activeChild()): String {
        if (key in SHARED_KEYS) return key
        return if (child == DEFAULT_CHILD) key else "child:$child:$key"
    }

    private fun rawGet(key: String, default: String = ""): String =
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) ?: default else default
        }

    private fun rawSet(key: String, value: String) {
        writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply {
            put("k", key); put("v", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (key == "config" || key.endsWith(":config")) {
            configCache.clear()
        }
    }

    fun get(key: String, default: String = ""): String = rawGet(scoped(key), default)

    fun set(key: String, value: String) = rawSet(scoped(key), value)

    // ---- Perfiles de varios niños (#132) ----

    fun children(): JSONArray {
        ensureDefaultChild()
        val active = activeChild()
        val out = JSONArray()
        readableDatabase.rawQuery("SELECT id,name,created_at,pin_hash FROM children ORDER BY created_at,id", null).use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val hasPin = !(it.getString(3).isNullOrEmpty())
                out.put(childSummaryJson(id, it.getString(1), it.getLong(2), id == active, hasPin))
            }
        }
        return out
    }

    private fun ensureDefaultChild() {
        val exists = readableDatabase.rawQuery("SELECT 1 FROM children WHERE id=? LIMIT 1", arrayOf(DEFAULT_CHILD)).use { it.moveToFirst() }
        if (!exists) writableDatabase.insertWithOnConflict("children", null, ContentValues().apply {
            put("id", DEFAULT_CHILD); put("name", "Niño 1"); put("created_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun validName(raw: String, except: String? = null): String {
        val name = raw.trim()
        require(name.length in 1..12) { "El nombre del niño necesita 1–12 caracteres" }
        val taken = readableDatabase.rawQuery("SELECT id FROM children WHERE name=?", arrayOf(name)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        require(taken == null || taken == except) { "Ya existe un niño con ese nombre" }
        return name
    }

    fun createChild(raw: String, pin: String? = null): JSONObject {
        ensureDefaultChild()
        val name = validName(raw)
        val normalizedPin = normalizeChildPin(pin)
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM children", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        require(count < MAX_CHILDREN) { "Solo se pueden añadir $MAX_CHILDREN niños como máximo" }
        val id = "c" + UUID.randomUUID().toString().replace("-", "").take(8)
        val salt = if (!normalizedPin.isNullOrEmpty()) generatePinSalt() else ""
        val hash = if (!normalizedPin.isNullOrEmpty()) hashChildPin(normalizedPin, salt) else ""
        // El nuevo niño hereda las reglas actuales (tiempo, franjas, recompensas…), sin que el padre tenga que configurarlas de nuevo; el progreso de estudio empieza desde cero
        val inherited = rawGet(scoped("config"))
        writableDatabase.insertWithOnConflict("children", null, ContentValues().apply {
            put("id", id); put("name", name); put("created_at", System.currentTimeMillis())
            put("pin_hash", hash); put("pin_salt", salt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (inherited.isNotEmpty()) rawSet("child:$id:config", inherited)
        return JSONObject().put("id", id).put("name", name).put("hasPin", hash.isNotEmpty())
    }

    fun updateChild(id: String, raw: String, pin: String? = null): JSONObject {
        ensureDefaultChild()
        val name = validName(raw, id)
        val normalizedPin = normalizeChildPin(pin)
        val cv = ContentValues().apply {
            put("name", name)
            when {
                normalizedPin == null -> Unit // No modificar el PIN actual
                normalizedPin.isEmpty() -> {
                    put("pin_hash", "")
                    put("pin_salt", "")
                }
                else -> {
                    val salt = generatePinSalt()
                    put("pin_hash", hashChildPin(normalizedPin, salt))
                    put("pin_salt", salt)
                }
            }
        }
        val updated = writableDatabase.update("children", cv, "id=?", arrayOf(id))
        require(updated > 0) { "El perfil del niño no existe" }
        return JSONObject().put("id", id).put("name", name).put("hasPin", childHasPin(id))
    }

    fun renameChild(id: String, raw: String): JSONObject = updateChild(id, raw, null)

    fun childHasPin(childId: String): Boolean {
        ensureDefaultChild()
        return readableDatabase.rawQuery("SELECT pin_hash FROM children WHERE id=? LIMIT 1", arrayOf(childId)).use {
            it.moveToFirst() && !(it.getString(0).isNullOrEmpty())
        }
    }

    fun verifyChildPin(childId: String, pin: String): Boolean {
        ensureDefaultChild()
        val candidate = pin.trim()
        if (candidate.isEmpty()) return false
        return readableDatabase.rawQuery("SELECT pin_hash,pin_salt FROM children WHERE id=? LIMIT 1", arrayOf(childId)).use {
            if (!it.moveToFirst()) return false
            verifyPinHash(candidate, it.getString(1).orEmpty(), it.getString(0).orEmpty())
        }
    }

    /** Elimina el perfil del niño: junto con su historial de estudio, estadísticas de errores, progreso de repaso, correcciones de preguntas subjetivas e imágenes manuscritas huérfanas; las preguntas en sí se conservan */
    fun deleteChild(id: String): Boolean {
        require(id != DEFAULT_CHILD) { "El primer niño no se puede eliminar, pero sí renombrar" }
        val db = writableDatabase
        // Orden de bloqueo unificado: siempre primero childLock, luego la transacción de escritura SQLite, evitando un interbloqueo AB-BA con switchChild()/activeChild()
        synchronized(childLock) {
            db.beginTransaction()
            try {
                db.delete("children", "id=?", arrayOf(id))
                db.delete("kv", "k LIKE ?", arrayOf("child:$id:%"))
                db.delete("events", "child=?", arrayOf(id))
                db.delete("question_progress", "child=?", arrayOf(id))
                db.delete("question_stats", "child=?", arrayOf(id))
                // El SQLite integrado en Android 5+ no admite JSON_EXTRACT; se filtran en Kotlin los registros de corrección de ese niño
                val reviewIds = ArrayList<String>()
                db.rawQuery("SELECT id,payload FROM reviews", null).use { c ->
                    while (c.moveToNext()) {
                        val rid = c.getString(0) ?: continue
                        val payload = c.getString(1) ?: continue
                        val owner = try { JSONObject(payload).optString("child", DEFAULT_CHILD) } catch (_: Exception) { DEFAULT_CHILD }
                        if (owner == id) reviewIds.add(rid)
                    }
                }
                for (rid in reviewIds) db.delete("reviews", "id=?", arrayOf(rid))
                configCache.remove(id)
                if (activeChild() == id) switchChild(DEFAULT_CHILD)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        if (!db.inTransaction()) pruneMedia()
        return true
    }

    fun switchChild(id: String) {
        synchronized(childLock) {
            ensureDefaultChild()
            val exists = readableDatabase.rawQuery("SELECT 1 FROM children WHERE id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }
            require(exists) { "El perfil del niño no existe" }
            rawSet("active_child", id)
            cachedChild = id
        }
    }

    fun adminToken(): String = get("admin_token").ifEmpty {
        UUID.randomUUID().toString().also { set("admin_token", it) }
    }

    @JvmOverloads
    fun config(child: String = activeChild()): JSONObject {
        // Cada vez se devuelve una copia nueva de JSONObject, evitando que el llamador modifique en sitio el objeto devuelto y contamine la caché
        configCache[child]?.let { return JSONObject(it) }
        val defaults = JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 0).put("sessionMinutes", 10)
            .put("questionsPerRound", 5).put("roundsPerDay", 3).put("passRatio", 0.6)
            .put("windowStart", "18:30").put("windowEnd", "20:00").put("allDay", true)
            .put("supervisionEnabled", true)
            .put("spacedRepetitionEnabled", false).put("memorizeFirst", false).put("spacedScope", "all")
            // Prioridad de cobertura: primero las preguntas nunca acertadas, garantizando que todas las del banco se vean al menos una vez (el panel de padres puede desactivarlo)
            .put("coverageFirst", true)
            // Prioridad a nuevas: las preguntas nunca presentadas van delante (#230, desactivado por defecto)
            .put("newFirst", false)
            .put("playMode", "round").put("extraEnabled", true).put("shuffleOptions", false).put("showCountdown", true)
            .put("wrongThreshold", 3).put("extraQuestions", 2)
            .put("difficultyRewardEnabled", false)
            .put("difficultyRewards", JSONObject().put("1", 1).put("2", 2).put("3", 3).put("4", 4).put("5", 5))
            .put("praisePhrases", JSONArray(DEFAULT_PRAISE)).put("encouragePhrases", JSONArray(DEFAULT_ENCOURAGE))
            .put("autoNextSeconds", 0).put("scheduleMode", "allow").put("blockSettingsDuringControl", true).put("theme", if (BuildConfig.FLAVOR == "tv") "dark" else "system")
            .put("holidayMode", "sunday")
            .put("bootFreeMinutes", 0).put("bootFreeIntervalMinutes", 0)
            .put("dailyLimitEnabled", true).put("roundsLimitEnabled", true)
            .put("restEnabled", false).put("restWatchMinutes", 40).put("restMinutes", 10)
            .put("wrongReviewSeconds", 0).put("fontScale", 100).put("childSelectLevel", false)
            .put("trafficAssistEnabled", false).put("backgroundAnchorEnabled", true)
            .put("forceFullscreenGate", false)
            .put("watchWhitelist", JSONArray()).put("allowQuizUnlock", false)
            .put("learningContent", "quiz").put("educationalVideos", JSONArray()).put("videoMinutes", 1)
            .put("quizBanks", JSONArray())
            .put("quizSubjects", JSONArray()).put("quizLevels", JSONArray())
            .put("gradeSubjects", JSONObject())
            .put("timePoints", JSONArray()).put("exemptWindows", JSONArray())
            .put("timezone", "")
            .put("version", 1)
        val saved = try { JSONObject(rawGet(scoped("config", child), "{}")) } catch (_: Exception) { JSONObject() }
        saved.keys().forEach { defaults.put(it, saved.get(it)) }
        defaults.put("theme", when (defaults.optString("theme")) { "light", "daylight" -> "light"; "system" -> "system"; "dark", "midnight", "nord", "amber" -> "dark"; else -> if (BuildConfig.FLAVOR == "tv") "dark" else "system" })
        val timePattern = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")
        val validStart = timePattern.matches(defaults.optString("windowStart"))
        val validEnd = timePattern.matches(defaults.optString("windowEnd"))
        if (!validStart) defaults.put("windowStart", "18:30")
        if (!validEnd) defaults.put("windowEnd", "20:00")
        if (!saved.has("allDay") && validStart && validEnd && saved.optString("windowStart") == saved.optString("windowEnd")) defaults.put("allDay", true)
        val migrated = normalizeWeek(saved.optJSONArray("weekSlots"))
            ?: if (saved.has("allDay") || saved.has("windowStart") || saved.has("windowEnd")) {
                val oldDefault = !defaults.optBoolean("allDay") &&
                    defaults.optString("windowStart") == "18:30" && defaults.optString("windowEnd") == "20:00"
                if (oldDefault) { defaults.put("allDay", true); fullWeek() }
                else weekFromWindow(defaults.optBoolean("allDay"), defaults.optString("windowStart"), defaults.optString("windowEnd"))
            } else fullWeek()
        defaults.put("weekSlots", migrated)
        if (defaults.optString("holidayMode") !in listOf("sunday", "normal")) {
            defaults.put("holidayMode", "sunday")
        }
        if (defaults.optString("spacedScope") !in listOf("all", "wrong_only")) {
            defaults.put("spacedScope", "all")
        }
        defaults.put("gradeSubjects", normalizeGradeSubjects(defaults.optJSONObject("gradeSubjects")))
        val snapshot = defaults.toString()
        configCache[child] = snapshot
        return JSONObject(snapshot)
    }

    private fun weekFromWindow(allDay: Boolean, start: String, end: String): JSONArray {
        fun minutes(value: String): Int? = try { value.split(":").let { it[0].toInt() * 60 + it[1].toInt() } } catch (_: Exception) { null }
        val from = minutes(start) ?: 18 * 60 + 30
        val to = minutes(end) ?: 20 * 60
        val chars = CharArray(48) { index ->
            val minute = index * 30
            val on = allDay || if (from < to) minute in from until to else minute >= from || minute < to
            if (on) '1' else '0'
        }
        val day = String(chars)
        return JSONArray().apply { repeat(7) { put(day) } }
    }

    private fun fullWeek(): JSONArray = JSONArray().apply { repeat(7) { put("1".repeat(48)) } }

    private fun normalizeWeek(raw: JSONArray?): JSONArray? {
        if (raw == null || raw.length() != 7) return null
        val out = JSONArray()
        for (i in 0 until 7) {
            val day = raw.optString(i)
            if (day.length != 48 || day.any { it != '0' && it != '1' }) return null
            out.put(day)
        }
        return out
    }

    /**
     * Validación de franjas de clase en línea / exención de adultos (Issue #112). Precisión al minuto; end < start indica cruce de medianoche.
     * days usa el día de la semana real: 0 = lunes … 6 = domingo, no afectado por holidayMode.
     */
    private fun normalizeWindows(raw: JSONArray?, counted: Boolean, label: String): JSONArray {
        val out = JSONArray()
        val source = raw ?: return out
        require(source.length() <= 50) { "$label: máximo 50 entradas" }
        val pattern = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: throw IllegalArgumentException("Formato de $label incorrecto")
            val name = item.optString("name").trim()
            require(name.isNotEmpty() && name.length <= 20) { "$label: el nombre necesita 1–20 caracteres" }
            val start = item.optString("start")
            val end = item.optString("end")
            require(pattern.matches(start) && pattern.matches(end)) { "$label: se requieren hora de inicio y fin válidas (precisión al minuto)" }
            require(start != end) { "$label: la hora de inicio y fin no pueden ser iguales" }
            val days = JSONArray()
            val seen = mutableSetOf<Int>()
            val rawDays = item.optJSONArray("days") ?: JSONArray()
            for (d in 0 until rawDays.length()) {
                val day = rawDays.optInt(d, -1)
                require(day in 0..6) { "$label: configuración de día de la semana incorrecta" }
                if (seen.add(day)) days.put(day)
            }
            require(days.length() > 0) { "Selecciona los días de la semana para $label" }
            val clean = JSONObject()
                .put("id", item.optString("id").trim().takeIf { it.isNotEmpty() && it.length <= 64 }
                    ?: ((if (counted) "tp_" else "ex_") + UUID.randomUUID()))
                .put("name", name).put("days", days).put("start", start).put("end", end)
            if (counted) {
                val mode = item.optString("mode", "free")
                require(mode in listOf("free", "counted")) { "$label: el uso debe ser uso libre o computar en el límite" }
                clean.put("mode", mode)
                if (mode == "counted") {
                    val minutes = item.optInt("dailyMinutes", 0)
                    require(minutes in 1..480) { "$label computado en el límite: se necesitan 1–480 minutos" }
                    clean.put("dailyMinutes", minutes)
                }
            }
            out.put(clean)
        }
        return out
    }

    fun saveConfig(input: JSONObject): JSONObject {
        val next = config()
        val ranges = mapOf("dailyMinutes" to 1..480, "dailyFreeMinutes" to 0..120, "sessionMinutes" to 1..120,
            "questionsPerRound" to 1..100, "roundsPerDay" to 1..100,
            "wrongThreshold" to 1..100, "extraQuestions" to 1..100,
            "bootFreeMinutes" to 0..120, "bootFreeIntervalMinutes" to 0..1440,
            "restWatchMinutes" to 1..480, "restMinutes" to 1..120,
            "wrongReviewSeconds" to 0..300, "fontScale" to 75..200, "videoMinutes" to 1..30)
        ranges.forEach { (key, range) ->
            if (input.has(key)) {
                val value = input.getDouble(key)
                require(value.isFinite() && value == value.toInt().toDouble() && value.toInt() in range) { "$key fuera de rango" }
                next.put(key, value.toInt())
            }
        }
        val wantsAllDay = if (input.has("allDay")) input.getBoolean("allDay") else next.optBoolean("allDay")
        listOf("windowStart", "windowEnd").forEach { key ->
            if (input.has(key)) {
                val valid = Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(input.optString(key))
                require(wantsAllDay || valid) { "Introduce una hora de inicio y fin válidas" }
                if (valid) next.put(key, input.getString(key))
            }
        }
        if (input.has("timezone")) {
            val zone = input.optString("timezone").trim()
            require(zone in DeviceClock.ZONES) { "Zona horaria no admitida" }
            next.put("timezone", zone)
        }
        if (input.has("passRatio")) {
            val ratio = input.getDouble("passRatio")
            require(ratio.isFinite() && ratio > 0 && ratio <= 1) { "La proporción de aciertos debe ser mayor que 0 y no superar el 100 %" }
            next.put("passRatio", ratio)
        }
        if (input.has("learningContent")) {
            require(input.getString("learningContent") in listOf("quiz", "video", "alternate")) { "Modo de contenido de aprendizaje desconocido" }
            next.put("learningContent", input.getString("learningContent"))
        }
        if (input.has("educationalVideos")) {
            val urls = input.getJSONArray("educationalVideos")
            require(urls.length() <= 50) { "Máximo 50 vídeos educativos" }
            for (i in 0 until urls.length()) {
                val url = urls.getString(i)
                require(url.length <= 2048 && (url.startsWith("https://") || url.startsWith("http://"))) { "Usa enlaces directos de vídeo HTTP/HTTPS" }
                if (LocalOnlyGuard.enabled) {
                    require(LocalOnlyGuard.isLocalUrl(url)) { "La versión de privacidad solo local (EU Local-Only) solo permite configurar vídeos educativos del equipo o de la red local" }
                }
            }
            next.put("educationalVideos", urls)
        }
        if (input.has("watchWhitelist")) {
            val raw = input.optJSONArray("watchWhitelist") ?: throw IllegalArgumentException("Formato de lista blanca incorrecto")
            val clean = JSONArray()
            WatchWhitelist.normalize((0 until raw.length()).map { raw.optString(it) }).forEach { clean.put(it) }
            next.put("watchWhitelist", clean)
        }
        require(next.optString("learningContent", "quiz") == "quiz" || next.getJSONArray("educationalVideos").length() > 0) { "El modo de aprendizaje con vídeo requiere añadir direcciones de vídeo" }
        if (input.has("playMode")) {
            require(input.getString("playMode") in listOf("round", "streak", "practice", "count")) { "Modo de juego desconocido" }
            next.put("playMode", input.getString("playMode"))
        }
        if (input.has("spacedScope")) {
            val scope = input.optString("spacedScope")
            require(scope in listOf("all", "wrong_only")) { "Ámbito de repaso desconocido" }
            next.put("spacedScope", scope)
        }
        listOf(
            "supervisionEnabled", "memorizeFirst", "extraEnabled", "coverageFirst", "newFirst",
            "difficultyRewardEnabled", "allDay", "spacedRepetitionEnabled", "blockSettingsDuringControl",
            "shuffleOptions", "showCountdown", "dailyLimitEnabled", "roundsLimitEnabled", "restEnabled",
            "childSelectLevel", "trafficAssistEnabled", "backgroundAnchorEnabled", "forceFullscreenGate",
            "wrongFirst", "allowQuizUnlock"
        ).forEach {
            if (input.has(it)) next.put(it, input.getBoolean(it))
        }
        for (key in listOf("praisePhrases", "encouragePhrases")) if (input.has(key)) {
            val raw = input.getJSONArray(key)
            val clean = JSONArray()
            for (i in 0 until raw.length()) raw.optString(i).trim().takeIf { it.isNotEmpty() }?.let {
                require(it.length <= 40) { "Cada frase de ánimo no debe superar los 40 caracteres" }
                clean.put(it)
            }
            require(clean.length() <= 30) { "Máximo 30 frases de ánimo" }
            next.put(key, if (clean.length() == 0) JSONArray(if (key == "praisePhrases") DEFAULT_PRAISE else DEFAULT_ENCOURAGE) else clean)
        }
        if (input.has("scheduleMode")) {
            require(input.getString("scheduleMode") in listOf("allow", "control")) { "Uso de franja desconocido" }
            next.put("scheduleMode", input.getString("scheduleMode"))
        }
        for (key in listOf("quizSubjects", "quizLevels", "quizBanks")) if (input.has(key)) {
            val raw = input.optJSONArray(key) ?: JSONArray()
            val clean = JSONArray()
            for (i in 0 until minOf(raw.length(), 50)) raw.optString(i).trim().takeIf { it.isNotEmpty() && it.length <= (if (key.endsWith("Banks")) 64 else 32) }?.let { value ->
                if (!(0 until clean.length()).any { clean.optString(it) == value }) clean.put(value)
            }
            next.put(key, clean)
        }
        if (input.has("gradeSubjects")) {
            next.put("gradeSubjects", normalizeGradeSubjects(input.optJSONObject("gradeSubjects")))
        }
        if (input.has("weekSlots")) {
            val clean = normalizeWeek(input.optJSONArray("weekSlots")) ?: throw IllegalArgumentException("Formato de franjas disponibles incorrecto")
            next.put("weekSlots", clean)
        }
        if (input.has("timePoints")) next.put("timePoints", normalizeWindows(input.optJSONArray("timePoints"), true, "franja de clase en línea"))
        if (input.has("exemptWindows")) next.put("exemptWindows", normalizeWindows(input.optJSONArray("exemptWindows"), false, "franja de exención de adultos"))
        if (input.has("holidayMode")) {
            require(input.getString("holidayMode") in listOf("sunday", "normal")) { "Regla de festivos desconocida" }
            next.put("holidayMode", input.getString("holidayMode"))
        }
        if (input.has("theme")) {
            require(input.getString("theme") in listOf("dark", "light", "system")) { "La apariencia debe ser oscura, clara o seguir el sistema" }
            next.put("theme", input.getString("theme"))
        }
        if (input.has("autoNextSeconds")) {
            val value = input.getInt("autoNextSeconds")
            require(value in 0..30) { "El avance automático debe ser de 0–30 segundos; 0 significa manual" }
            next.put("autoNextSeconds", value)
        }
        if (input.has("difficultyRewards")) {
            val rewards = input.getJSONObject("difficultyRewards")
            for (star in 1..5) {
                val minutes = rewards.getDouble(star.toString())
                require(minutes.isFinite() && minutes in 0.1..120.0) { "La recompensa por pregunta debe ser de 0.1–120 minutos" }
            }
            next.put("difficultyRewards", rewards)
        }
        next.put("version", next.optInt("version", 1) + 1)
        val snapshot = next.toString()
        set("config", snapshot)
        configCache[activeChild()] = snapshot
        return JSONObject(snapshot)
    }

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")

    private fun appendScopeFilters(
        where: StringBuilder,
        args: MutableList<String>,
        subjects: List<String>,
        levels: List<String>,
        selectedLevel: String,
        banks: List<String>,
        gradeSubjects: Map<String, List<String>> = emptyMap(),
        explicitSubjectFilter: Boolean = false
    ) {
        val filter = buildScopeFilter(
            subjects = subjects,
            levels = levels,
            selectedLevel = selectedLevel,
            banks = banks,
            gradeSubjects = gradeSubjects,
            explicitSubjectFilter = explicitSubjectFilter
        )
        where.append(filter.clause)
        args.addAll(filter.args)
    }

    private fun buildQuestionOrder(
        spaced: Boolean,
        sortBy: String,
        wrongFirst: Boolean,
        coverageFirst: Boolean,
        newFirst: Boolean
    ): String {
        val base = if (spaced) {
            "CASE WHEN progress.due_at<=${clockReading().millis} THEN 0 WHEN progress.qid IS NULL THEN 1 ELSE 2 END, progress.due_at ASC, RANDOM()"
        } else when (sortBy) {
            "newest" -> "questions.created_at DESC, questions.rowid DESC"
            "oldest" -> "questions.created_at ASC, questions.rowid ASC"
            else -> "RANDOM()"
        }
        val tiers = mutableListOf<String>()
        if (wrongFirst) tiers.add("CASE WHEN stats.last_correct=0 THEN 0 ELSE 1 END")
        if (newFirst) tiers.add("CASE WHEN COALESCE(stats.presented,0)=0 THEN 0 ELSE 1 END")
        if (coverageFirst) tiers.add("CASE WHEN COALESCE(stats.correct,0)=0 THEN 0 ELSE 1 END, COALESCE(stats.presented,0) ASC")
        return (tiers + base).joinToString(", ")
    }

    /**
     * Consulta de una sola pregunta: el filtrado de ámbito, la exclusión de la anterior, el filtro de errores y la ordenación se hacen en SQL; solo se lee la 1 pregunta coincidente,
     * evitando que en bancos grandes se escaneen decenas de miles de filas JSON en memoria en cada cambio de pregunta.
     */
    fun pickQuestion(
        subjects: List<String> = emptyList(),
        levels: List<String> = emptyList(),
        selectedLevel: String = "",
        banks: List<String> = emptyList(),
        spaced: Boolean = false,
        wrongFirst: Boolean = false,
        coverageFirst: Boolean = false,
        newFirst: Boolean = false,
        excludeId: String? = null,
        onlyWrong: Boolean = false,
        missedOnly: Boolean = false,
        child: String = activeChild(),
        gradeSubjects: Map<String, List<String>> = emptyMap(),
        explicitSubjectFilter: Boolean = false
    ): JSONObject? {
        val items = pickDeck(
            count = 1, subjects = subjects, levels = levels, selectedLevel = selectedLevel,
            banks = banks, spaced = spaced, wrongFirst = wrongFirst, coverageFirst = coverageFirst,
            newFirst = newFirst, excludeId = excludeId, onlyWrong = onlyWrong, missedOnly = missedOnly,
            child = child, gradeSubjects = gradeSubjects, explicitSubjectFilter = explicitSubjectFilter
        )
        return if (items.length() > 0) items.getJSONObject(0) else null
    }

    /**
     * Extrae de una vez un conjunto de preguntas sin duplicados para «primero memorizar y luego evaluar» o el aprendizaje con tarjetas.
     */
    fun pickDeck(
        count: Int,
        subjects: List<String> = emptyList(),
        levels: List<String> = emptyList(),
        selectedLevel: String = "",
        banks: List<String> = emptyList(),
        spaced: Boolean = false,
        wrongFirst: Boolean = false,
        coverageFirst: Boolean = false,
        newFirst: Boolean = false,
        excludeId: String? = null,
        onlyWrong: Boolean = false,
        missedOnly: Boolean = false,
        child: String = activeChild(),
        gradeSubjects: Map<String, List<String>> = emptyMap(),
        explicitSubjectFilter: Boolean = false
    ): JSONArray {
        val where = StringBuilder("questions.enabled=1")
        val args = mutableListOf<String>()
        appendScopeFilters(where, args, subjects, levels, selectedLevel, banks, gradeSubjects, explicitSubjectFilter)
        if (!excludeId.isNullOrEmpty()) {
            where.append(" AND questions.id!=?")
            args.add(excludeId)
        }
        if (onlyWrong) {
            where.append(" AND stats.last_correct=0")
        } else if (missedOnly) {
            where.append(" AND (stats.last_correct=0 OR COALESCE(stats.answered,0)>COALESCE(stats.correct,0))")
        }
        val joinArgs = mutableListOf<String>()
        val progressJoin = if (spaced) {
            joinArgs.add(child)
            " LEFT JOIN question_progress progress ON questions.id=progress.qid AND progress.child=?"
        } else ""
        joinArgs.add(child)
        val source = "questions$progressJoin LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=?"
        val order = buildQuestionOrder(spaced, "random", wrongFirst, coverageFirst, newFirst)
        return readQuestions(
            "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
                "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct " +
                "FROM $source WHERE $where ORDER BY $order LIMIT ${count.coerceIn(1, 100)}",
            (joinArgs + args).toTypedArray()
        )
    }

    /** Cuando el niño elige el grado, solo se consultan los grados no vacíos deduplicados, sin cargar todo el banco de preguntas. */
    fun distinctLevels(
        allowedLevels: List<String> = emptyList(),
        subjects: List<String> = emptyList(),
        banks: List<String> = emptyList(),
        gradeSubjects: Map<String, List<String>> = emptyMap(),
        explicitSubjectFilter: Boolean = false
    ): List<String> {
        val where = StringBuilder("questions.enabled=1 AND TRIM(COALESCE(questions.level,''))!=''")
        val args = mutableListOf<String>()
        appendScopeFilters(where, args, subjects, allowedLevels, "", banks, gradeSubjects, explicitSubjectFilter)
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT questions.level FROM questions WHERE $where",
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val lvl = c.getString(0)?.trim().orEmpty()
                if (lvl.isNotEmpty()) out.add(lvl)
            }
        }
        return out.distinct().sorted()
    }

    /** Lee una sola pregunta por número (usado para dar prioridad en la generación desde la cola de práctica extra precisa). */
    fun questionById(id: String, enabledOnly: Boolean = true, child: String = activeChild()): JSONObject? {
        if (id.isBlank()) return null
        val enabledClause = if (enabledOnly) " AND questions.enabled=1" else ""
        val arr = readQuestions(
            "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
                "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct " +
                "FROM questions LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? " +
                "WHERE questions.id=?$enabledClause LIMIT 1",
            arrayOf(child, id)
        )
        return if (arr.length() > 0) arr.getJSONObject(0) else null
    }

    /** Cola de práctica extra precisa del niño actual (array JSON en KV, deduplicado y con orden conservado). */
    fun extraQueue(child: String = activeChild()): List<String> =
        parseExtraQueue(rawGet(scoped("extra_queue", child), "[]"))

    /** Añade sin duplicados los números de pregunta válidos y activos a la cola de práctica extra del niño actual, devolviendo el número real de añadidos. */
    fun addToExtraQueue(ids: List<String>, child: String = activeChild()): Int {
        val cleaned = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return 0
        val valid = LinkedHashSet<String>()
        val chunkSize = 500
        for (start in cleaned.indices step chunkSize) {
            val chunk = cleaned.subList(start, minOf(start + chunkSize, cleaned.size))
            readableDatabase.rawQuery(
                "SELECT id FROM questions WHERE enabled=1 AND id IN (${placeholders(chunk.size)})",
                chunk.toTypedArray()
            ).use { c ->
                while (c.moveToNext()) c.getString(0)?.let { valid.add(it) }
            }
        }
        val orderedValid = cleaned.filter { it in valid }
        if (orderedValid.isEmpty()) return 0
        val existing = extraQueue(child)
        val (merged, added) = mergeExtraQueue(existing, orderedValid)
        if (added > 0) {
            rawSet(scoped("extra_queue", child), JSONArray(merged).toString())
        }
        return added
    }

    /** Quita el número de pregunta indicado de la cola de práctica extra del niño actual; devuelve true si existía y se ha quitado. */
    fun popExtraQueue(id: String, child: String = activeChild()): Boolean {
        val target = id.trim()
        if (target.isEmpty()) return false
        val existing = extraQueue(child)
        if (target !in existing) return false
        val remaining = existing.filter { it != target }
        rawSet(scoped("extra_queue", child), JSONArray(remaining).toString())
        return true
    }

    /** Vacía la cola de práctica extra precisa del niño actual. */
    fun clearExtraQueue(child: String = activeChild()) {
        rawSet(scoped("extra_queue", child), "[]")
    }

    /**
     * Consulta del banco de errores (ST-601 / #159): usa question_stats.last_correct=0 como única fuente de verdad, aislada por niño.
     */
    fun listWrongQuestions(
        subject: String? = null,
        level: String? = null,
        bank: String? = null,
        q: String? = null,
        child: String = activeChild()
    ): JSONObject {
        val where = StringBuilder("questions.enabled=1 AND stats.last_correct=0")
        val args = mutableListOf(child)
        if (!subject.isNullOrBlank()) {
            where.append(" AND questions.subject=?")
            args.add(subject.trim())
        }
        if (!level.isNullOrBlank()) {
            where.append(" AND questions.level=?")
            args.add(level.trim())
        }
        if (!bank.isNullOrBlank()) {
            val b = bank.trim()
            if (b == "默认题库") {
                where.append(" AND (questions.bank=? OR questions.bank='')")
            } else {
                where.append(" AND questions.bank=?")
            }
            args.add(b)
        }
        val keyword = q?.trim()?.takeIf { it.isNotEmpty() }
        if (keyword != null) {
            val like = "%${escapeLike(keyword)}%"
            where.append(" AND (questions.stem LIKE ? ESCAPE '\\' OR questions.tip LIKE ? ESCAPE '\\' OR questions.options LIKE ? ESCAPE '\\' OR questions.subject LIKE ? ESCAPE '\\' OR questions.level LIKE ? ESCAPE '\\')")
            repeat(5) { args.add(like) }
        }
        val items = JSONArray()
        readableDatabase.rawQuery(
            "SELECT questions.*,COALESCE(stats.answered,0) AS answered_count," +
                "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_at,0) AS last_at," +
                "COALESCE(stats.last_correct,-1) AS last_correct " +
                "FROM questions JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? " +
                "WHERE $where ORDER BY stats.last_at DESC, (stats.answered - stats.correct) DESC, questions.rowid DESC LIMIT 500",
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val details = c.getString(c.getColumnIndexOrThrow("details"))
                val full = if (details.isNullOrEmpty()) JSONObject() else try { JSONObject(details) } catch (_: Exception) { JSONObject() }
                val id = c.getString(c.getColumnIndexOrThrow("id"))
                val stem = c.getString(c.getColumnIndexOrThrow("stem"))
                val options = try { JSONArray(c.getString(c.getColumnIndexOrThrow("options"))) } catch (_: Exception) { JSONArray() }
                val k = c.getInt(c.getColumnIndexOrThrow("answer"))
                val tip = c.getString(c.getColumnIndexOrThrow("tip")) ?: ""
                val subj = c.getString(c.getColumnIndexOrThrow("subject")) ?: ""
                val lvl = c.getString(c.getColumnIndexOrThrow("level")) ?: ""
                val bankName = (c.getString(c.getColumnIndexOrThrow("bank")) ?: "").ifBlank { full.optString("bank").ifBlank { "默认题库" } }
                val type = (c.getString(c.getColumnIndexOrThrow("type")) ?: "").ifBlank { full.optString("type").ifBlank { "single" } }
                full.put("a", options).put("k", k).put("type", type)
                val answered = c.getInt(c.getColumnIndexOrThrow("answered_count"))
                val correct = c.getInt(c.getColumnIndexOrThrow("correct_count"))
                val lastAt = c.getLong(c.getColumnIndexOrThrow("last_at"))
                val lastCorrect = c.getInt(c.getColumnIndexOrThrow("last_correct"))
                val wrongCount = (answered - correct).coerceAtLeast(if (lastCorrect == 0) 1 else 0)
                items.put(
                    JSONObject()
                        .put("id", id)
                        .put("q", stem)
                        .put("type", type)
                        .put("subject", subj)
                        .put("level", lvl)
                        .put("bank", bankName)
                        .put("options", options)
                        .put("answer", LearningEngine.formatAnswerDisplay(full))
                        .put("explain", tip)
                        .put("answered", answered)
                        .put("correct", correct)
                        .put("wrongCount", wrongCount)
                        .put("lastAt", lastAt)
                        .put("lastCorrect", lastCorrect == 1)
                )
            }
        }
        val extraPool = rawGet(scoped("extra_pool", child), "0").toLongOrNull() ?: 0L
        val extraQueueIds = extraQueue(child)
        return JSONObject()
            .put("items", items)
            .put("total", items.length())
            .put("extraPool", extraPool)
            .put("extraQueueIds", JSONArray(extraQueueIds))
    }

    /**
     * «Marcar como dominada» no destructivo: no elimina la pregunta original; solo pone question_stats.last_correct a 1 y alinea correct>=answered,
     * de modo que wrongCount y las consultas onlyWrong/missedOnly dejan de coincidir, y se quita de extra_queue.
     */
    fun markWrongMastered(ids: List<String> = emptyList(), all: Boolean = false, child: String = activeChild()): Int {
        val db = writableDatabase
        var updated = 0
        db.beginTransaction()
        try {
            if (all) {
                val masteredIds = ArrayList<String>()
                db.rawQuery(
                    "SELECT qid FROM question_stats WHERE child=? AND (last_correct=0 OR answered>correct)",
                    arrayOf(child)
                ).use { c ->
                    while (c.moveToNext()) c.getString(0)?.let { masteredIds.add(it) }
                }
                val stmt = db.compileStatement(
                    "UPDATE question_stats SET last_correct=1, correct=CASE WHEN correct<answered THEN answered ELSE correct END " +
                        "WHERE child=? AND (last_correct=0 OR answered>correct)"
                )
                try {
                    stmt.bindString(1, child)
                    updated = stmt.executeUpdateDelete()
                } finally {
                    stmt.close()
                }
                if (masteredIds.isNotEmpty()) {
                    val remaining = extraQueue(child).filter { it !in masteredIds.toSet() }
                    rawSet(scoped("extra_queue", child), JSONArray(remaining).toString())
                }
            } else {
                val cleaned = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (cleaned.isNotEmpty()) {
                    val stmt = db.compileStatement(
                        "UPDATE question_stats SET last_correct=1, correct=CASE WHEN correct<answered THEN answered ELSE correct END " +
                            "WHERE qid=? AND child=? AND (last_correct=0 OR answered>correct)"
                    )
                    try {
                        for (qid in cleaned) {
                            stmt.clearBindings()
                            stmt.bindString(1, qid)
                            stmt.bindString(2, child)
                            updated += stmt.executeUpdateDelete()
                        }
                    } finally {
                        stmt.close()
                    }
                    val cleanedSet = cleaned.toSet()
                    val existing = extraQueue(child)
                    if (existing.any { it in cleanedSet }) {
                        rawSet(scoped("extra_queue", child), JSONArray(existing.filter { it !in cleanedSet }).toString())
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return updated
    }

    /** Muestra de preguntas débiles típicas extraídas para que la IA genere variantes a partir de errores (primero errores actuales, luego preguntas con más fallos acumulados). */
    fun wrongQuestionsSample(subject: String? = null, limit: Int = 8, child: String = activeChild()): List<JSONObject> {
        val where = StringBuilder("questions.enabled=1 AND (stats.last_correct=0 OR COALESCE(stats.answered,0)>COALESCE(stats.correct,0))")
        val args = mutableListOf(child)
        if (!subject.isNullOrBlank()) {
            where.append(" AND questions.subject=?")
            args.add(subject.trim())
        }
        val arr = readQuestions(
            "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
                "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct " +
                "FROM questions JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? " +
                "WHERE $where ORDER BY CASE WHEN stats.last_correct=0 THEN 0 ELSE 1 END, " +
                "(stats.answered-stats.correct) DESC, stats.last_at DESC LIMIT ${limit.coerceIn(1, 20)}",
            args.toTypedArray()
        )
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun questions(
        subject: String? = null, level: String? = null, limit: Int = 200, spaced: Boolean = false,
        enabledOnly: Boolean = true, sortBy: String = "random", wrongFirst: Boolean = false,
        coverageFirst: Boolean = false, newFirst: Boolean = false, child: String = activeChild()
    ): JSONArray {
        val where = StringBuilder(if (enabledOnly) "questions.enabled=1" else "1=1")
        val args = mutableListOf<String>()
        if (!subject.isNullOrEmpty()) { where.append(" AND questions.subject=?"); args.add(subject) }
        if (!level.isNullOrEmpty()) { where.append(" AND questions.level=?"); args.add(level) }
        val joinArgs = mutableListOf<String>()
        val progressJoin = if (spaced) {
            joinArgs.add(child)
            " LEFT JOIN question_progress progress ON questions.id=progress.qid AND progress.child=?"
        } else ""
        joinArgs.add(child)
        val source = "questions$progressJoin LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=?"
        val order = buildQuestionOrder(spaced, sortBy, wrongFirst, coverageFirst, newFirst)
        return readQuestions(
            "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
                "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct " +
                "FROM $source WHERE $where ORDER BY $order LIMIT ${limit.coerceIn(1, 50000)}",
            (joinArgs + args).toTypedArray()
        )
    }

    /** Mapeo unificado de filas de pregunta: usado por generación, página de banco de preguntas y exportación, garantizando campos coherentes. */
    private fun readQuestions(sql: String, args: Array<String>): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val details = c.getString(c.getColumnIndexOrThrow("details"))
                val item = if (details.isNullOrEmpty()) JSONObject() else JSONObject(details)
                item.put("id", c.getString(c.getColumnIndexOrThrow("id")))
                    .put("q", c.getString(c.getColumnIndexOrThrow("stem")))
                    .put("a", JSONArray(c.getString(c.getColumnIndexOrThrow("options"))))
                    .put("k", c.getInt(c.getColumnIndexOrThrow("answer")))
                    .put("difficulty", c.getInt(c.getColumnIndexOrThrow("difficulty")))
                    .put("tip", c.getString(c.getColumnIndexOrThrow("tip")) ?: "")
                    .put("subject", c.getString(c.getColumnIndexOrThrow("subject")) ?: "")
                    .put("level", c.getString(c.getColumnIndexOrThrow("level")) ?: "")
                    .put("bank", (c.getString(c.getColumnIndexOrThrow("bank")) ?: "").ifBlank { item.optString("bank").ifBlank { "默认题库" } })
                    .put("enabled", c.getInt(c.getColumnIndexOrThrow("enabled")) != 0)
                    .put("stats", JSONObject().put("presented", c.getInt(c.getColumnIndexOrThrow("presented")))
                        .put("answered", c.getInt(c.getColumnIndexOrThrow("answered_count")))
                        .put("correct", c.getInt(c.getColumnIndexOrThrow("correct_count")))
                        .put("lastCorrect", c.getInt(c.getColumnIndexOrThrow("last_correct")).let { if (it < 0) JSONObject.NULL else it == 1 }))
                if (!item.has("type")) item.put("type", (c.getString(c.getColumnIndexOrThrow("type")) ?: "").ifBlank { "single" })
                out.put(item)
            }
        }
        return out
    }

    /** Los comodines de LIKE deben escaparse; de lo contrario, una búsqueda del padre por «100%» se trataría como comodín y escanearía toda la base. */
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * Consulta paginada para la página de banco de preguntas del panel de padres: filtrado, búsqueda, ordenación y paginación todo en SQL, tomando solo una página cada vez.
     * `inlineMedia = true` se usa para restaurar las rutas de medios locales a Data URL importables entre dispositivos al exportar el JSON del banco de preguntas.
     */
    fun questionPage(
        subject: String? = null, level: String? = null, bank: String? = null, search: String? = null,
        type: String? = null, difficulty: Int? = null, wrongOnly: Boolean = false, enabledOnly: Boolean = true,
        sortBy: String = "newest", limit: Int = 50, offset: Int = 0, inlineMedia: Boolean = false,
        child: String = activeChild()
    ): JSONObject {
        val where = StringBuilder(if (enabledOnly) "questions.enabled=1" else "1=1")
        val args = mutableListOf<String>()
        if (!subject.isNullOrEmpty()) { where.append(" AND questions.subject=?"); args.add(subject) }
        if (!level.isNullOrEmpty()) { where.append(" AND questions.level=?"); args.add(level) }
        if (!bank.isNullOrEmpty()) { where.append(" AND questions.bank=?"); args.add(bank) }
        if (!type.isNullOrEmpty()) { where.append(" AND questions.type=?"); args.add(type) }
        if (difficulty != null) { where.append(" AND questions.difficulty=?"); args.add(difficulty.toString()) }
        if (wrongOnly) where.append(" AND stats.last_correct=0")
        val keyword = search?.trim()?.takeIf { it.isNotEmpty() }
        if (keyword != null) {
            val like = "%${escapeLike(keyword)}%"
            where.append(" AND (questions.stem LIKE ? ESCAPE '\\' OR questions.tip LIKE ? ESCAPE '\\' OR questions.options LIKE ? ESCAPE '\\' OR questions.subject LIKE ? ESCAPE '\\' OR questions.level LIKE ? ESCAPE '\\')")
            repeat(5) { args.add(like) }
        }
        val source = "questions LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=?"
        val queryArgs = (listOf(child) + args).toTypedArray()
        val total = readableDatabase.rawQuery("SELECT COUNT(*) FROM $source WHERE $where", queryArgs).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        val order = when (sortBy) {
            "random" -> "RANDOM()"
            "oldest" -> "questions.created_at ASC, questions.rowid ASC"
            "diff", "difficulty" -> "questions.difficulty DESC, questions.rowid DESC"
            "type" -> "questions.type ASC, questions.difficulty DESC, questions.rowid DESC"
            else -> "questions.created_at DESC, questions.rowid DESC"
        }
        val page = limit.coerceIn(1, 50000)
        val start = offset.coerceAtLeast(0)
        val rawItems = readQuestions(
            "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
                "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct " +
                "FROM $source WHERE $where ORDER BY $order LIMIT $page OFFSET $start",
            queryArgs
        )
        val items = if (!inlineMedia) rawItems else JSONArray().also { arr ->
            for (i in 0 until rawItems.length()) arr.put(MediaStore.inlineQuestionMedia(mediaDir, rawItems.getJSONObject(i)))
        }
        return JSONObject().put("items", items).put("total", total).put("offset", start).put("limit", page)
    }

    /** Filtros y recuentos de la página de banco de preguntas: nombre de banco / asignatura / grado + total de preguntas, activas, pausadas y errores del niño actual. */
    fun questionMeta(child: String = activeChild()): JSONObject {
        val banks = sortedSetOf<String>()
        val subjects = sortedSetOf<String>()
        val levels = sortedSetOf<String>()
        var total = 0
        var enabled = 0
        var wrong = 0
        readableDatabase.rawQuery(
            "SELECT questions.bank,questions.subject,questions.level,questions.enabled," +
                "CASE WHEN stats.last_correct=0 THEN 1 ELSE 0 END AS wrong " +
                "FROM questions LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=?",
            arrayOf(child)
        ).use { c ->
            while (c.moveToNext()) {
                total++
                (c.getString(0) ?: "").ifBlank { "默认题库" }.let { banks.add(it) }
                (c.getString(1) ?: "").takeIf { it.isNotBlank() }?.let { subjects.add(it) }
                (c.getString(2) ?: "").takeIf { it.isNotBlank() }?.let { levels.add(it) }
                if (c.getInt(3) != 0) enabled++
                if (c.getInt(4) == 1) wrong++
            }
        }
        return JSONObject()
            .put("banks", JSONArray(banks.toList()))
            .put("subjects", JSONArray(subjects.toList()))
            .put("levels", JSONArray(levels.toList()))
            .put("total", total).put("enabled", enabled).put("paused", total - enabled).put("wrong", wrong)
    }

    fun countQuestions(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM questions WHERE enabled=1", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    /**
     * Cobertura de respuestas: el criterio es que el niño actual «haya acertado al menos una vez» (stats.correct>0), usando el mismo criterio que la prioridad de cobertura al generar.
     */
    fun coverage(
        sample: Int = 20,
        child: String = activeChild(),
        gradeSubjects: Map<String, List<String>> = emptyMap()
    ): JSONObject {
        val banks = LinkedHashMap<String, IntArray>()
        val pending = ArrayList<JSONObject>()
        var total = 0
        var covered = 0
        var untouched = 0
        val where = StringBuilder("questions.enabled=1")
        val args = mutableListOf(child)
        val filter = buildScopeFilter(gradeSubjects = gradeSubjects)
        where.append(filter.clause)
        args.addAll(filter.args)
        readableDatabase.rawQuery(
            "SELECT questions.id,questions.stem,questions.subject,questions.level,questions.bank," +
                "COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
                "COALESCE(stats.correct,0) AS correct_count " +
                "FROM questions LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? WHERE $where",
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val presented = c.getInt(5)
                val answered = c.getInt(6)
                val correct = c.getInt(7)
                val bank = (c.getString(4) ?: "").ifBlank { "默认题库" }
                val bucket = banks.getOrPut(bank) { intArrayOf(0, 0) }
                total++
                bucket[0]++
                if (correct > 0) {
                    covered++
                    bucket[1]++
                } else {
                    if (presented == 0) untouched++
                    pending.add(JSONObject().put("id", c.getString(0) ?: "")
                        .put("q", (c.getString(1) ?: "").take(80))
                        .put("bank", bank)
                        .put("subject", c.getString(2) ?: "")
                        .put("level", c.getString(3) ?: "")
                        .put("presented", presented)
                        .put("answered", answered))
                }
            }
        }
        pending.sortWith(compareBy<JSONObject>({ it.optInt("presented") }, { it.optInt("answered") }))
        val items = JSONArray()
        for (i in 0 until minOf(pending.size, sample)) items.put(pending[i])
        val bankList = JSONArray()
        banks.entries.sortedByDescending { it.value[0] }.forEach { entry ->
            val counts = entry.value
            bankList.put(JSONObject().put("name", entry.key).put("total", counts[0]).put("covered", counts[1])
                .put("percent", if (counts[0] == 0) 0 else Math.round(counts[1] * 100f / counts[0])))
        }
        return JSONObject().put("total", total).put("covered", covered)
            .put("percent", if (total == 0) 0 else Math.round(covered * 100f / total))
            .put("pending", pending.size).put("untouched", untouched)
            .put("banks", bankList).put("items", items)
    }

    /** Días consecutivos de estudio: hasta hoy (si hoy aún no ha estudiado, continúa desde ayer) con al menos 1 respuesta o tarjeta de memoria registrada cada día. */
    private fun computeStreakDays(child: String): Int {
        val todayStart = startOfToday(child)
        val cutoff = todayStart - 366L * 86_400_000L
        val activeDays = HashSet<Int>()
        val cal = calendarFor(child)
        readableDatabase.rawQuery(
            "SELECT ts FROM events WHERE child=? AND ts>=? AND type IN ('answer','flashcard')",
            arrayOf(child, cutoff.toString())
        ).use { c ->
            while (c.moveToNext()) {
                cal.timeInMillis = c.getLong(0)
                val key = cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
                activeDays.add(key)
            }
        }
        if (activeDays.isEmpty()) return 0
        val cursor = calendarFor(child).apply { timeInMillis = todayStart }
        fun dayKey(c: Calendar) = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
        if (dayKey(cursor) !in activeDays) {
            cursor.add(Calendar.DAY_OF_YEAR, -1)
            if (dayKey(cursor) !in activeDays) return 0
        }
        var streak = 0
        while (dayKey(cursor) in activeDays) {
            streak++
            cursor.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    /** Tarjetas de resumen de la página principal de estudio (estilo Anki / Duolingo: días de racha, preguntas de hoy, pendientes de repaso, errores, preguntas dominadas). */
    fun studySummary(child: String = activeChild()): JSONObject {
        val nowMs = clockReading().millis
        val wrongOnly = config(child).optString("spacedScope") == "wrong_only"
        var totalQuestions = 0
        var dueCount = 0
        var wrongCount = 0
        var masteredCount = 0
        readableDatabase.rawQuery(
            "SELECT COALESCE(stats.last_correct,-1),COALESCE(stats.answered,0),COALESCE(stats.correct,0)," +
                "progress.qid IS NOT NULL,COALESCE(progress.interval_days,0),COALESCE(progress.due_at,0) " +
                "FROM questions " +
                "LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? " +
                "LEFT JOIN question_progress progress ON questions.id=progress.qid AND progress.child=? " +
                "WHERE questions.enabled=1",
            arrayOf(child, child)
        ).use { c ->
            while (c.moveToNext()) {
                totalQuestions++
                val lastCorrect = c.getInt(0)
                val answered = c.getInt(1)
                val correct = c.getInt(2)
                val hasProgress = c.getInt(3) != 0
                val intervalDays = c.getLong(4)
                val dueAt = c.getLong(5)
                if (lastCorrect == 0) wrongCount++
                if (hasProgress && intervalDays >= 21) masteredCount++
                if (hasProgress && dueAt <= nowMs && inSpacedScope(wrongOnly, lastCorrect, answered, correct)) dueCount++
            }
        }
        var todayAnswered = 0
        var todayCorrect = 0
        readableDatabase.rawQuery(
            "SELECT payload FROM events WHERE child=? AND ts>=? AND type IN ('answer','flashcard')",
            arrayOf(child, startOfToday(child).toString())
        ).use { c ->
            while (c.moveToNext()) {
                todayAnswered++
                val p = try { JSONObject(c.getString(0)) } catch (_: Exception) { JSONObject() }
                if (p.optBoolean("correct")) todayCorrect++
            }
        }
        return JSONObject()
            .put("streakDays", computeStreakDays(child))
            .put("todayAnswered", todayAnswered)
            .put("todayCorrect", todayCorrect)
            .put("dueCount", dueCount)
            .put("wrongCount", wrongCount)
            .put("masteredCount", masteredCount)
            .put("totalQuestions", totalQuestions)
    }

    /** Análisis completo de aprendizaje del panel de padres: distribución de madurez de memoria, tasa de dominio por asignatura/banco, tendencia de los últimos N días, errores frecuentes y previsión de repaso de los próximos 7 días. */
    fun learningAnalytics(days: Int = 14, child: String = activeChild()): JSONObject {
        val windowDays = days.coerceIn(7, 60)
        val nowMs = clockReading().millis
        val wrongOnly = config(child).optString("spacedScope") == "wrong_only"
        val childZone = timeZoneFor(child)
        val todayStart = startOfDayMillis(nowMs, childZone)

        class SubjectStat(val subject: String) {
            var total = 0; var answered = 0; var correct = 0; var wrong = 0; var mastered = 0
            val accuracy: Int get() = if (answered > 0) Math.round(correct * 100f / answered) else 0
        }
        class BankStat(val bank: String) {
            var total = 0; var covered = 0; var answered = 0; var correct = 0; var wrong = 0
            val accuracy: Int get() = if (answered > 0) Math.round(correct * 100f / answered) else 0
        }

        val subjects = LinkedHashMap<String, SubjectStat>()
        val banks = LinkedHashMap<String, BankStat>()
        val hardCandidates = ArrayList<Pair<Long, JSONObject>>()

        // Inicio y fin de cada uno de los próximos 7 días (línea de día dividida según la zona horaria configurada del niño)
        val forecastBounds = Array(7) { offset ->
            val dayStart = Calendar.getInstance(childZone).apply {
                timeInMillis = todayStart
                add(Calendar.DAY_OF_YEAR, offset)
            }
            val start = dayStart.timeInMillis
            val label = when (offset) {
                0 -> "Hoy"
                1 -> "Mañana"
                else -> "${dayStart.get(Calendar.MONTH) + 1}/${dayStart.get(Calendar.DAY_OF_MONTH)}"
            }
            dayStart.add(Calendar.DAY_OF_YEAR, 1)
            Triple(start, dayStart.timeInMillis, label)
        }
        val forecastCounts = IntArray(7)

        var totalQuestions = 0
        var unseen = 0
        var learning = 0
        var young = 0
        var mature = 0
        var studiedQuestions = 0
        var wrongCount = 0
        var dueNow = 0
        var totalAnswered = 0
        var totalCorrect = 0

        readableDatabase.rawQuery(
            "SELECT questions.id,questions.stem,questions.subject,questions.level,questions.bank,questions.type,questions.tip," +
                "COALESCE(stats.presented,0),COALESCE(stats.answered,0),COALESCE(stats.correct,0)," +
                "COALESCE(stats.last_at,0),COALESCE(stats.last_correct,-1)," +
                "progress.qid IS NOT NULL,COALESCE(progress.interval_days,0),COALESCE(progress.due_at,0) " +
                "FROM questions " +
                "LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? " +
                "LEFT JOIN question_progress progress ON questions.id=progress.qid AND progress.child=? " +
                "WHERE questions.enabled=1",
            arrayOf(child, child)
        ).use { c ->
            while (c.moveToNext()) {
                totalQuestions++
                val qid = c.getString(0) ?: ""
                val stem = c.getString(1) ?: ""
                val rawSubject = (c.getString(2) ?: "").trim()
                val subjectKey = rawSubject.ifEmpty { "Sin clasificar" }
                val level = c.getString(3) ?: ""
                val bankKey = (c.getString(4) ?: "").trim().ifEmpty { "默认题库" }
                val type = (c.getString(5) ?: "").ifBlank { "single" }
                val tip = c.getString(6) ?: ""
                val presented = c.getInt(7)
                val answered = c.getInt(8)
                val correct = c.getInt(9)
                val lastAt = c.getLong(10)
                val lastCorrect = c.getInt(11)
                val hasProgress = c.getInt(12) != 0
                val intervalDays = c.getLong(13)
                val dueAt = c.getLong(14)

                totalAnswered += answered
                totalCorrect += correct
                if (lastCorrect == 0) wrongCount++

                val isMastered = hasProgress && intervalDays >= 21
                if (!hasProgress) {
                    unseen++
                } else {
                    when {
                        intervalDays <= 1L -> learning++
                        intervalDays <= 20L -> young++
                        else -> mature++
                    }
                    if (inSpacedScope(wrongOnly, lastCorrect, answered, correct)) {
                        if (dueAt <= nowMs) dueNow++
                        for (d in 0 until 7) {
                            val (fStart, fEnd, _) = forecastBounds[d]
                            val hit = if (d == 0) dueAt < fEnd else dueAt in fStart until fEnd
                            if (hit) { forecastCounts[d]++; break }
                        }
                    }
                }
                if (hasProgress || answered > 0) studiedQuestions++

                subjects.getOrPut(subjectKey) { SubjectStat(subjectKey) }.apply {
                    total++
                    this.answered += answered
                    this.correct += correct
                    if (lastCorrect == 0) wrong++
                    if (isMastered) mastered++
                }
                banks.getOrPut(bankKey) { BankStat(bankKey) }.apply {
                    total++
                    this.answered += answered
                    this.correct += correct
                    if (correct > 0) covered++
                    if (lastCorrect == 0) wrong++
                }

                val wrongTimes = answered - correct
                if (wrongTimes > 0) {
                    val sortScore = wrongTimes.toLong() * 1_000_000_000_000L + (lastAt.coerceAtLeast(0L) / 1000L)
                    hardCandidates.add(
                        sortScore to JSONObject()
                            .put("id", qid).put("q", stem).put("subject", rawSubject).put("level", level)
                            .put("bank", bankKey).put("type", type).put("presented", presented)
                            .put("answered", answered).put("correct", correct).put("wrongTimes", wrongTimes)
                            .put("lastCorrect", if (lastCorrect < 0) JSONObject.NULL else (lastCorrect == 1))
                            .put("tip", tip)
                    )
                }
            }
        }

        // Estadísticas diarias de respuestas, tarjetas y tiempo de visualización de los últimos max(windowDays, 14) días
        val querySpan = maxOf(windowDays, 14)
        class DayBucket(val date: String, val label: String, val daysAgo: Int) {
            var answered = 0; var correct = 0; var flashcards = 0; var usedMs = 0L
        }
        val dayMap = LinkedHashMap<String, DayBucket>()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = childZone
        }
        var earliestStart = todayStart
        for (ago in (querySpan - 1) downTo 0) {
            val dCal = Calendar.getInstance(childZone).apply {
                timeInMillis = todayStart
                add(Calendar.DAY_OF_YEAR, -ago)
            }
            if (ago == querySpan - 1) earliestStart = dCal.timeInMillis
            val dateStr = dayFmt.format(Date(dCal.timeInMillis))
            val label = when (ago) {
                0 -> "Hoy"
                1 -> "Ayer"
                else -> "${dCal.get(Calendar.MONTH) + 1}/${dCal.get(Calendar.DAY_OF_MONTH)}"
            }
            dayMap[dateStr] = DayBucket(dateStr, label, ago)
        }

        readableDatabase.rawQuery(
            "SELECT type,payload,ts FROM events WHERE child=? AND ts>=? AND type IN ('answer','flashcard','usage')",
            arrayOf(child, earliestStart.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val evType = c.getString(0) ?: continue
                val payload = try { JSONObject(c.getString(1) ?: "{}") } catch (_: Exception) { JSONObject() }
                val dateStr = dayFmt.format(Date(c.getLong(2)))
                val bucket = dayMap[dateStr] ?: continue
                when (evType) {
                    "usage" -> bucket.usedMs += payload.optLong("milliseconds")
                    "answer" -> {
                        bucket.answered++
                        if (payload.optBoolean("correct")) bucket.correct++
                    }
                    "flashcard" -> {
                        bucket.flashcards++
                        bucket.answered++
                        if (payload.optBoolean("correct")) bucket.correct++
                    }
                }
            }
        }

        var recent7Ans = 0; var recent7Cor = 0
        var prev7Ans = 0; var prev7Cor = 0
        var todayAnswered = 0; var todayCorrect = 0
        val dailyArr = JSONArray()
        for (bucket in dayMap.values) {
            if (bucket.daysAgo == 0) {
                todayAnswered = bucket.answered
                todayCorrect = bucket.correct
            }
            if (bucket.daysAgo in 0..6) {
                recent7Ans += bucket.answered
                recent7Cor += bucket.correct
            } else if (bucket.daysAgo in 7..13) {
                prev7Ans += bucket.answered
                prev7Cor += bucket.correct
            }
            if (bucket.daysAgo < windowDays) {
                dailyArr.put(
                    JSONObject()
                        .put("date", bucket.date)
                        .put("label", bucket.label)
                        .put("answered", bucket.answered)
                        .put("correct", bucket.correct)
                        .put("accuracy", if (bucket.answered > 0) Math.round(bucket.correct * 100f / bucket.answered) else 0)
                        .put("watchMinutes", Math.round(bucket.usedMs / 6000.0) / 10.0)
                        .put("flashcards", bucket.flashcards)
                )
            }
        }

        val bySubjectArr = JSONArray()
        subjects.values.sortedWith(
            compareByDescending<SubjectStat> { it.wrong }
                .thenBy { if (it.answered > 0) it.accuracy else 101 }
                .thenByDescending { it.total }
        ).forEach { s ->
            bySubjectArr.put(
                JSONObject()
                    .put("subject", s.subject).put("total", s.total)
                    .put("answered", s.answered).put("correct", s.correct)
                    .put("wrong", s.wrong).put("accuracy", s.accuracy).put("mastered", s.mastered)
            )
        }

        val byBankArr = JSONArray()
        banks.values.sortedByDescending { it.total }.forEach { b ->
            byBankArr.put(
                JSONObject()
                    .put("bank", b.bank).put("total", b.total)
                    .put("covered", b.covered).put("wrong", b.wrong).put("accuracy", b.accuracy)
            )
        }

        hardCandidates.sortByDescending { it.first }
        val hardArr = JSONArray()
        for (i in 0 until minOf(20, hardCandidates.size)) hardArr.put(hardCandidates[i].second)

        val forecastArr = JSONArray()
        for (d in 0 until 7) {
            forecastArr.put(
                JSONObject()
                    .put("dayOffset", d)
                    .put("label", forecastBounds[d].third)
                    .put("dueCount", forecastCounts[d])
            )
        }

        val overview = JSONObject()
            .put("totalQuestions", totalQuestions)
            .put("studiedQuestions", studiedQuestions)
            .put("masteredQuestions", mature)
            .put("learningQuestions", (studiedQuestions - mature).coerceAtLeast(0))
            .put("wrongCount", wrongCount)
            .put("dueNow", dueNow)
            .put("streakDays", computeStreakDays(child))
            .put("totalAnswered", totalAnswered)
            .put("totalCorrect", totalCorrect)
            .put("accuracyPercent", if (totalAnswered > 0) Math.round(totalCorrect * 100f / totalAnswered) else 0)
            .put("recent7dAccuracy", if (recent7Ans > 0) Math.round(recent7Cor * 100f / recent7Ans) else 0)
            .put("prev7dAccuracy", if (prev7Ans > 0) Math.round(prev7Cor * 100f / prev7Ans) else 0)
            .put("todayAnswered", todayAnswered)
            .put("todayCorrect", todayCorrect)

        val maturity = JSONObject()
            .put("unseen", unseen)
            .put("learning", learning)
            .put("young", young)
            .put("mature", mature)

        return JSONObject()
            .put("overview", overview)
            .put("maturity", maturity)
            .put("daily", dailyArr)
            .put("bySubject", bySubjectArr)
            .put("byBank", byBankArr)
            .put("hardQuestions", hardArr)
            .put("forecast", forecastArr)
    }

    fun upsertQuestion(item: JSONObject): String {
        val stem = item.optString("q").trim()
        require(stem.isNotEmpty() && stem.length <= 10000) { "Rellena el enunciado" }
        val type = item.optString("type", "single")
        require(type in listOf("single", "multiple", "boolean", "text", "subjective")) { "Tipo de pregunta no admitido" }
        val options = if (type == "boolean") JSONArray(listOf("正确", "错误")) else item.optJSONArray("a") ?: JSONArray()
        val rawOptionImages = item.optJSONArray("optionImages")
        val normalizedOptionImages: JSONArray? = if (rawOptionImages != null) {
            require(rawOptionImages.length() <= 8) { "Las imágenes de opción no pueden superar 8" }
            JSONArray().also { arr ->
                for (i in 0 until rawOptionImages.length()) {
                    val img = rawOptionImages.optString(i).trim()
                    arr.put(if (img.isEmpty()) "" else MediaStore.normalizeImageRef(mediaDir, img, label = "imagen de la opción ${'A' + i}"))
                }
            }
        } else null
        if (type != "text" && type != "subjective") {
            require(options.length() in 2..8) { "Se necesitan 2–8 opciones" }
            for (i in 0 until options.length()) {
                val optImg = normalizedOptionImages?.optString(i).orEmpty()
                require(options.getString(i).isNotBlank() || optImg.isNotEmpty()) { "La opción no puede estar vacía" }
            }
        }
        val
