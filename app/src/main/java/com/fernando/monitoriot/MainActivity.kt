package com.fernando.monitoriot

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var baseDatos: DatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        baseDatos = DatabaseHelper(this)

        val etUsuario = findViewById<EditText>(R.id.etUsuario)
        val etContrasena = findViewById<EditText>(R.id.etContrasena)
        val btnIngresar = findViewById<Button>(R.id.btnIngresar)
        val btnRegistrar = findViewById<Button>(R.id.btnRegistrar)

        btnRegistrar.setOnClickListener {
            val usuario = etUsuario.text.toString().trim()
            val contrasena = etContrasena.text.toString()

            if (usuario.isEmpty() || contrasena.isEmpty()) {
                Toast.makeText(
                    this,
                    "Complete todos los campos",
                    Toast.LENGTH_SHORT
                ).show()
            } else if (contrasena.length < 6) {
                Toast.makeText(
                    this,
                    "La contraseña debe tener al menos 6 caracteres",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                val registrado =
                    baseDatos.registrarUsuario(usuario, contrasena)

                if (registrado) {
                    Toast.makeText(
                        this,
                        "Cuenta creada correctamente",
                        Toast.LENGTH_SHORT
                    ).show()

                    etContrasena.text.clear()
                } else {
                    Toast.makeText(
                        this,
                        "El usuario ya existe",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        btnIngresar.setOnClickListener {
            val usuario = etUsuario.text.toString().trim()
            val contrasena = etContrasena.text.toString()

            if (usuario.isEmpty() || contrasena.isEmpty()) {
                Toast.makeText(
                    this,
                    "Complete todos los campos",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                val accesoCorrecto =
                    baseDatos.validarUsuario(usuario, contrasena)

                if (accesoCorrecto) {
                    Toast.makeText(
                        this,
                        "Inicio de sesión correcto",
                        Toast.LENGTH_SHORT
                    ).show()

                    val ventanaMonitor =
                        Intent(this, MonitorActivity::class.java)

                    ventanaMonitor.putExtra("usuario", usuario)
                    startActivity(ventanaMonitor)

                    etContrasena.text.clear()
                } else {
                    Toast.makeText(
                        this,
                        "Usuario o contraseña incorrectos",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}