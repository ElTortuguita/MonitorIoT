package com.fernando.monitoriot

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class HistorialActivity : AppCompatActivity() {

    private lateinit var baseDatos: DatabaseHelper
    private lateinit var tvListaMediciones: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_historial)

        baseDatos = DatabaseHelper(this)

        tvListaMediciones =
            findViewById(R.id.tvListaMediciones)

        val btnVolver =
            findViewById<Button>(R.id.btnVolver)

        btnVolver.setOnClickListener {
            finish()
        }

        cargarHistorial()
    }

    private fun cargarHistorial() {
        val mediciones =
            baseDatos.obtenerMediciones()

        if (mediciones.isEmpty()) {
            tvListaMediciones.text =
                "Todavía no existen mediciones"
        } else {
            tvListaMediciones.text =
                mediciones.joinToString("\n")
        }
    }

    override fun onResume() {
        super.onResume()
        cargarHistorial()
    }
}