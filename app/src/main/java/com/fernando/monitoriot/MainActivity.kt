package com.fernando.monitoriot

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class MainActivity : AppCompatActivity() {

    private val apiUrl = "https://ssu14lhdt0.execute-api.us-east-1.amazonaws.com/default"

    private data class RespuestaApi(
        val codigo: Int,
        val mensaje: String,
        val datos: JSONObject = JSONObject()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val etUsuario = findViewById<EditText>(R.id.etUsuario)
        val etContrasena = findViewById<EditText>(R.id.etContrasena)
        val btnIngresar = findViewById<Button>(R.id.btnIngresar)
        val btnRegistrar = findViewById<Button>(R.id.btnRegistrar)

        fun habilitarBotones(habilitar: Boolean) {
            btnIngresar.isEnabled = habilitar
            btnRegistrar.isEnabled = habilitar
        }

        btnRegistrar.setOnClickListener {
            val usuario = etUsuario.text.toString().trim()
            val contrasena = etContrasena.text.toString()
            if (usuario.isEmpty() || contrasena.isEmpty()) {
                mostrarMensaje("Complete todos los campos")
            } else if (contrasena.length < 6) {
                mostrarMensaje("La contraseña debe tener al menos 6 caracteres")
            } else {
                habilitarBotones(false)
                Thread {
                    val resultado = enviarSolicitud("/registro", JSONObject().apply {
                        put("nombre", usuario)
                        put("correo", usuario)
                        put("password", contrasena)
                    })
                    runOnUiThread {
                        if (!isDestroyed) {
                            habilitarBotones(true)
                            mostrarMensaje(resultado.mensaje)
                            if (resultado.codigo == 201) etContrasena.text.clear()
                        }
                    }
                }.start()
            }
        }

        btnIngresar.setOnClickListener {
            val usuario = etUsuario.text.toString().trim()
            val contrasena = etContrasena.text.toString()
            if (usuario.isEmpty() || contrasena.isEmpty()) {
                mostrarMensaje("Complete todos los campos")
            } else {
                habilitarBotones(false)
                Thread {
                    val resultado = enviarSolicitud("/login", JSONObject().apply {
                        put("correo", usuario)
                        put("password", contrasena)
                    })
                    runOnUiThread {
                        if (!isDestroyed) {
                            habilitarBotones(true)
                            val token = resultado.datos.optString("token")
                            val id = resultado.datos.optJSONObject("usuario")?.optString("id").orEmpty()
                            if (resultado.codigo == 200 && token.isNotBlank() && id.isNotBlank()) {
                                val ventana = Intent(this, MonitorActivity::class.java)
                                ventana.putExtra("usuario", usuario)
                                ventana.putExtra("usuario_id", id)
                                ventana.putExtra("token_nube", token)
                                startActivity(ventana)
                                etContrasena.text.clear()
                            } else if (resultado.codigo == 200) {
                                mostrarMensaje("Actualice Lambda para habilitar la sincronizacion")
                            } else {
                                mostrarMensaje(resultado.mensaje)
                            }
                        }
                    }
                }.start()
            }
        }
    }

    private fun enviarSolicitud(ruta: String, datos: JSONObject): RespuestaApi {
        var conexion: HttpURLConnection? = null
        return try {
            val solicitud = URL(apiUrl + ruta).openConnection() as HttpURLConnection
            conexion = solicitud
            solicitud.requestMethod = "POST"
            solicitud.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            solicitud.connectTimeout = 15000
            solicitud.readTimeout = 20000
            solicitud.doOutput = true
            solicitud.outputStream.use { it.write(datos.toString().toByteArray(Charsets.UTF_8)) }
            val codigo = solicitud.responseCode
            val entrada = if (codigo in 200..299) solicitud.inputStream else solicitud.errorStream
            val texto = entrada?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val respuesta = try { JSONObject(texto) } catch (error: Exception) { JSONObject() }
            val mensaje = respuesta.optString("mensaje").ifBlank {
                respuesta.optString("message").ifBlank { "AWS respondio HTTP $codigo" }
            }
            RespuestaApi(codigo, mensaje, respuesta)
        } catch (error: UnknownHostException) {
            RespuestaApi(-1, "Sin acceso a Internet o DNS. Revise la conexion del dispositivo")
        } catch (error: SocketTimeoutException) {
            RespuestaApi(-1, "AWS demoro demasiado. Revise el laboratorio e intente nuevamente")
        } catch (error: Exception) {
            RespuestaApi(-1, "No fue posible conectar con AWS")
        } finally {
            conexion?.disconnect()
        }
    }

    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
    }
}
