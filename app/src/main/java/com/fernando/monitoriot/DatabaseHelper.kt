package com.fernando.monitoriot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteException
import android.util.Log
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, "monitor_iot.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE usuarios (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                usuario TEXT UNIQUE NOT NULL,
                salt TEXT NOT NULL,
                contrasena_hash TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE mediciones (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                temperatura REAL NOT NULL,
                humedad REAL,
                fecha TEXT NOT NULL,
                id_externo TEXT,
                fecha_epoch INTEGER,
                dispositivo TEXT,
                uptime_ms INTEGER NOT NULL DEFAULT 0,
                sincronizado_usuario TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX idx_medicion_externa ON mediciones(id_externo)")
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE mediciones ADD COLUMN id_externo TEXT")
            db.execSQL("ALTER TABLE mediciones ADD COLUMN fecha_epoch INTEGER")
            db.execSQL("ALTER TABLE mediciones ADD COLUMN dispositivo TEXT")
            db.execSQL("CREATE UNIQUE INDEX idx_medicion_externa ON mediciones(id_externo)")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE mediciones ADD COLUMN uptime_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE mediciones ADD COLUMN sincronizado_usuario TEXT")
            db.execSQL(
                "UPDATE mediciones SET id_externo = 'local-' || lower(hex(randomblob(16))) || '-' || id, " +
                        "dispositivo = 'Android-historial', fecha_epoch = 0 " +
                        "WHERE id_externo IS NULL AND humedad IS NOT NULL"
            )
        }
    }

    fun guardarMedicionRecibida(
        idExterno: String,
        temperatura: Double,
        humedad: Double,
        fechaEpoch: Long,
        tiempoEncendido: Long,
        dispositivo: String
    ): Boolean {
        if (!temperatura.isFinite() || temperatura !in -40.0..80.0 ||
            !humedad.isFinite() || humedad !in 0.0..100.0 ||
            !idExterno.matches(Regex("[A-Za-z0-9:-]{1,80}")) ||
            dispositivo.length !in 1..64 || tiempoEncendido < 0 ||
            (fechaEpoch != 0L && fechaEpoch !in 1735689600L..4102444800L)
        ) return false

        val fecha = if (fechaEpoch > 0) {
            SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                .format(Date(fechaEpoch * 1000))
        } else {
            "Sin hora de Internet; arranque +${tiempoEncendido / 1000}s"
        }
        val valores = ContentValues().apply {
            put("id_externo", idExterno)
            put("temperatura", temperatura)
            put("humedad", humedad)
            put("fecha", fecha)
            put("fecha_epoch", fechaEpoch)
            put("dispositivo", dispositivo)
            put("uptime_ms", tiempoEncendido)
        }
        return try {
            val db = writableDatabase
            val insertado = db.insertWithOnConflict(
                "mediciones", null, valores, SQLiteDatabase.CONFLICT_IGNORE
            )
            if (insertado != -1L) {
                true
            } else {
                // Una retransmision se confirma solo si el contenido coincide.
                db.query(
                    "mediciones",
                    arrayOf("temperatura", "humedad", "fecha_epoch", "dispositivo", "fecha"),
                    "id_externo = ?", arrayOf(idExterno), null, null, null
                ).use { cursor ->
                    cursor.moveToFirst() &&
                            cursor.getDouble(0) == temperatura &&
                            !cursor.isNull(1) && cursor.getDouble(1) == humedad &&
                            cursor.getLong(2) == fechaEpoch &&
                            cursor.getString(3) == dispositivo &&
                            (fechaEpoch != 0L || cursor.getString(4) == fecha)
                }
            }
        } catch (error: SQLiteException) {
            Log.e("MonitorIoT", "No se pudo guardar la lectura; se solicitara de nuevo", error)
            false
        }
    }

    fun obtenerPendientesNube(usuarioId: String): List<JSONObject> {
        val lecturas = mutableListOf<JSONObject>()
        readableDatabase.query(
            "mediciones",
            arrayOf("id_externo", "temperatura", "humedad", "fecha_epoch", "uptime_ms", "dispositivo", "fecha"),
            "id_externo IS NOT NULL AND humedad IS NOT NULL AND " +
                    "(sincronizado_usuario IS NULL OR sincronizado_usuario <> ?)",
            arrayOf(usuarioId), null, null, "id ASC", "25"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                lecturas.add(JSONObject().apply {
                    put("id", cursor.getString(0))
                    put("temperatura", cursor.getDouble(1))
                    put("humedad", cursor.getDouble(2))
                    put("fechaEpoch", cursor.getLong(3))
                    put("uptimeMs", cursor.getLong(4))
                    put("dispositivo", cursor.getString(5) ?: "ESP32-Wokwi")
                    put("fechaTexto", cursor.getString(6))
                })
            }
        }
        return lecturas
    }

    fun contarPendientesNube(usuarioId: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM mediciones WHERE id_externo IS NOT NULL AND humedad IS NOT NULL " +
                    "AND (sincronizado_usuario IS NULL OR sincronizado_usuario <> ?)",
            arrayOf(usuarioId)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun confirmarEnNube(identificadores: Set<String>, usuarioId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val valores = ContentValues().apply { put("sincronizado_usuario", usuarioId) }
            for (id in identificadores) {
                db.update("mediciones", valores, "id_externo = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun registrarUsuario(
        usuario: String,
        contrasena: String
    ): Boolean {

        val salt = generarSalt()
        val hash = generarHash(contrasena, salt)

        val valores = ContentValues().apply {
            put("usuario", usuario)
            put("salt", salt)
            put("contrasena_hash", hash)
        }

        return writableDatabase.insert(
            "usuarios",
            null,
            valores
        ) != -1L
    }

    fun validarUsuario(
        usuario: String,
        contrasena: String
    ): Boolean {

        val cursor = readableDatabase.query(
            "usuarios",
            arrayOf("salt", "contrasena_hash"),
            "usuario = ?",
            arrayOf(usuario),
            null,
            null,
            null
        )

        var accesoCorrecto = false

        if (cursor.moveToFirst()) {
            val salt =
                cursor.getString(
                    cursor.getColumnIndexOrThrow("salt")
                )

            val hashGuardado =
                cursor.getString(
                    cursor.getColumnIndexOrThrow(
                        "contrasena_hash"
                    )
                )

            val hashIngresado =
                generarHash(contrasena, salt)

            accesoCorrecto =
                hashIngresado == hashGuardado
        }

        cursor.close()
        return accesoCorrecto
    }

    fun guardarMedicion(
        temperatura: Double,
        humedad: Double? = null
    ): Boolean {

        val formatoFecha =
            SimpleDateFormat(
                "dd/MM/yyyy HH:mm:ss",
                Locale.getDefault()
            )

        val valores = ContentValues().apply {
            put("temperatura", temperatura)

            if (humedad != null) {
                put("humedad", humedad)
            } else {
                putNull("humedad")
            }

            put("fecha", formatoFecha.format(Date()))
        }

        return writableDatabase.insert(
            "mediciones",
            null,
            valores
        ) != -1L
    }

    fun obtenerMediciones(): List<String> {
        val mediciones = mutableListOf<String>()

        val cursor = readableDatabase.query(
            "mediciones",
            arrayOf("temperatura", "humedad", "fecha"),
            null,
            null,
            null,
            null,
            "id DESC",
            "50"
        )

        while (cursor.moveToNext()) {
            val temperatura =
                cursor.getDouble(
                    cursor.getColumnIndexOrThrow(
                        "temperatura"
                    )
                )

            val fecha =
                cursor.getString(
                    cursor.getColumnIndexOrThrow("fecha")
                )

            val indiceHumedad = cursor.getColumnIndexOrThrow("humedad")
            val humedad = if (cursor.isNull(indiceHumedad)) {
                "--"
            } else {
                String.format("%.1f", cursor.getDouble(indiceHumedad))
            }
            val texto = "$fecha  |  ${String.format("%.1f", temperatura)} °C  |  $humedad %"

            mediciones.add(texto)
        }

        cursor.close()
        return mediciones
    }

    private fun generarSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)

        return Base64.encodeToString(
            bytes,
            Base64.NO_WRAP
        )
    }

    private fun generarHash(
        contrasena: String,
        salt: String
    ): String {

        val digest =
            MessageDigest.getInstance("SHA-256")

        val contenido = "$salt$contrasena"
        val hash =
            digest.digest(contenido.toByteArray())

        return Base64.encodeToString(
            hash,
            Base64.NO_WRAP
        )
    }
}