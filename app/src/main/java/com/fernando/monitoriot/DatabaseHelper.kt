package com.fernando.monitoriot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, "monitor_iot.db", null, 1) {

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
                fecha TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        db.execSQL("DROP TABLE IF EXISTS usuarios")
        db.execSQL("DROP TABLE IF EXISTS mediciones")
        onCreate(db)
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

            val texto =
                "$fecha  |  ${String.format("%.1f", temperatura)} °C"

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