package com.fernando.monitoriot

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private val apiUrl =
        "https://ssu14lhdt0.execute-api.us-east-1.amazonaws.com/default"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etUsuario = findViewById<EditText>(R.id.etUsuario)
        val etContrasena = findViewById<EditText>(R.id.etContrasena)
        val btnIngresar = findViewById<Button>(R.id.btnIngresar)
        val btnRegistrar = findViewById<Button>(R.id.btnRegistrar)

        btnRegistrar.setOnClickListener {
            val usuario = etUsuario.text.toString().trim()
            val contrasena = etContrasena.text.toString()

            if (usuario.isEmpty() || contrasena.isEmpty()) {
                mostrarMensaje("Complete todos los campos")
            } else if (contrasena.length < 6) {
                mostrarMensaje("La contraseña debe tener al menos 6 caracteres")
            } else {
                btnRegistrar.isEnabled = false

                Thread {
                    val resultado = enviarSolicitud(
                        ruta = "/registro",
                        datos = JSONObject().apply {
                            put("nombre", usuario)
                            put("correo", usuario)
                            put("password", contrasena)
                        }
                    )

                    runOnUiThread {
                        btnRegistrar.isEnabled = true
                        mostrarMensaje(resultado.second)

                        if (resultado.first == 201) {
                            etContrasena.text.clear()
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
                btnIngresar.isEnabled = false

                Thread {
                    val resultado = enviarSolicitud(
                        ruta = "/login",
                        datos = JSONObject().apply {
                            put("correo", usuario)
                            put("password", contrasena)
                        }
                    )

                    runOnUiThread {
                        btnIngresar.isEnabled = true
                        mostrarMensaje(resultado.second)

                        if (resultado.first == 200) {
                            val ventanaMonitor =
                                Intent(this, MonitorActivity::class.java)

                            ventanaMonitor.putExtra("usuario", usuario)
                            startActivity(ventanaMonitor)
                            etContrasena.text.clear()
                        }
                    }
                }.start()
            }
        }
    }

    private fun enviarSolicitud(
        ruta: String,
        datos: JSONObject
    ): Pair<Int, String> {
        return try {
            val conexion =
                URL(apiUrl + ruta).openConnection() as HttpURLConnection

            conexion.requestMethod = "POST"
            conexion.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )
            conexion.connectTimeout = 10000
            conexion.readTimeout = 10000
            conexion.doOutput = true

            conexion.outputStream.use { salida ->
                salida.write(datos.toString().toByteArray(Charsets.UTF_8))
            }

            val codigo = conexion.responseCode

            val entrada = if (codigo in 200..299) {
                conexion.inputStream
            } else {
                conexion.errorStream
            }

            val textoRespuesta = entrada
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: "{}"

            conexion.disconnect()

            val mensaje = try {
                JSONObject(textoRespuesta)
                    .optString("mensaje", "Respuesta recibida")
            } catch (error: Exception) {
                "Respuesta recibida"
            }

            Pair(codigo, mensaje)

        } catch (error: Exception) {
            Pair(-1, "No fue posible conectar con AWS")
        }
    }

    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(
            this,
            mensaje,
            Toast.LENGTH_SHORT
        ).show()
    }
}