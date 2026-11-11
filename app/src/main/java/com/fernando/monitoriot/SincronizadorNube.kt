package com.fernando.monitoriot

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SincronizadorNube(
    private val baseDatos: DatabaseHelper,
    private val usuarioId: String,
    private val token: String,
    private val informar: (String, Boolean) -> Unit
) {
    private val servidor = "https://ssu14lhdt0.execute-api.us-east-1.amazonaws.com/default/sincronizar"
    private val ejecutor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var cerrado = false
    @Volatile private var conexionActual: HttpURLConnection? = null
    private var espera = 15000L
    private var sesionValida = true

    private class SesionVencida : Exception()
    private class DatosEnConflicto : Exception()

    fun iniciar() {
        if (usuarioId.isBlank() || token.isBlank()) {
            informar("Nube: vuelva a iniciar sesion", false)
            return
        }
        ejecutor.execute { ejecutarCiclo() }
    }

    private fun ejecutarCiclo() {
        if (cerrado) return
        var proximaEspera = 15000L
        try {
            var lotes = 0
            while (!cerrado && lotes < 4) {
                val lecturas = baseDatos.obtenerPendientesNube(usuarioId)
                if (lecturas.isEmpty()) break
                val cantidad = baseDatos.contarPendientesNube(usuarioId)
                informar("Nube: enviando | Pendientes: $cantidad", false)
                val enviados = lecturas.map { it.getString("id") }.toSet()
                val respuesta = enviarLote(JSONArray(lecturas))
                if (cerrado) return
                val lista = respuesta.optJSONArray("confirmados")
                    ?: throw IllegalStateException("AWS no confirmo las lecturas")
                val confirmados = mutableSetOf<String>()
                for (indice in 0 until lista.length()) {
                    val id = lista.getString(indice)
                    if (id !in enviados) throw IllegalStateException("Confirmacion inesperada")
                    confirmados.add(id)
                }
                baseDatos.confirmarEnNube(confirmados, usuarioId)
                val conflictos = respuesta.optJSONArray("conflictos")
                if ((conflictos?.length() ?: 0) > 0) throw DatosEnConflicto()
                if (confirmados.isEmpty()) throw IllegalStateException("Sin confirmaciones")
                lotes++
            }
            if (!cerrado) {
                val pendientes = baseDatos.contarPendientesNube(usuarioId)
                if (pendientes == 0) {
                    informar("Nube: al dia | Pendientes: 0", true)
                } else {
                    informar("Nube: sincronizando | Pendientes: $pendientes", false)
                    proximaEspera = 1000L
                }
            }
            espera = 15000L
        } catch (error: SesionVencida) {
            sesionValida = false
            if (!cerrado) informar("Nube: sesion vencida. Vuelva a ingresar", false)
        } catch (error: DatosEnConflicto) {
            if (!cerrado) informar("Nube: hay lecturas pendientes de revisar", false)
            proximaEspera = 120000L
        } catch (error: Exception) {
            if (!cerrado) {
                Log.w("MonitorIoT", "La sincronizacion se reintentara", error)
                val pendientes = try { baseDatos.contarPendientesNube(usuarioId) } catch (fallo: Exception) { -1 }
                informar("Nube: sin sincronizar | Pendientes: $pendientes", false)
            }
            espera = (espera * 2).coerceAtMost(120000L)
            proximaEspera = espera
        }
        if (!cerrado && sesionValida) {
            ejecutor.schedule({ ejecutarCiclo() }, proximaEspera, TimeUnit.MILLISECONDS)
        }
    }

    private fun enviarLote(lecturas: JSONArray): JSONObject {
        val conexion = URL(servidor).openConnection() as HttpURLConnection
        conexionActual = conexion
        try {
            if (cerrado) throw IllegalStateException("Sincronizador cerrado")
            conexion.requestMethod = "POST"
            conexion.connectTimeout = 15000
            conexion.readTimeout = 20000
            conexion.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conexion.setRequestProperty("Authorization", "Bearer $token")
            conexion.doOutput = true
            val cuerpo = JSONObject().put("mediciones", lecturas)
            conexion.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
            val codigo = conexion.responseCode
            val entrada = if (codigo in 200..299) conexion.inputStream else conexion.errorStream
            val texto = entrada?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (codigo == 401) throw SesionVencida()
            if (codigo !in 200..299) throw IllegalStateException("AWS respondio HTTP $codigo")
            return JSONObject(texto)
        } finally {
            conexion.disconnect()
            conexionActual = null
        }
    }

    fun cerrar() {
        cerrado = true
        conexionActual?.disconnect()
        ejecutor.shutdownNow()
    }
}
