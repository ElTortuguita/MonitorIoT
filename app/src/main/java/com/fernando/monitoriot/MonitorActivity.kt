package com.fernando.monitoriot

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

class MonitorActivity : AppCompatActivity() {

    private lateinit var tvConexion: TextView
    private lateinit var tvTemperatura: TextView
    private lateinit var tvEstado: TextView
    private lateinit var clienteMqtt: MqttAsyncClient
    private lateinit var baseDatos: DatabaseHelper

    private val servidorMqtt =
        "ssl://broker.hivemq.com:8883"

    private val temaTemperatura =
        "fernando-ti3042-2026/temperatura"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)

        baseDatos = DatabaseHelper(this)

        tvConexion = findViewById(R.id.tvConexion)
        tvTemperatura = findViewById(R.id.tvTemperatura)
        tvEstado = findViewById(R.id.tvEstado)

        val btnHistorial =
            findViewById<Button>(R.id.btnHistorial)

        val btnCerrarSesion =
            findViewById<Button>(R.id.btnCerrarSesion)

        btnHistorial.setOnClickListener {
            val ventanaHistorial =
                Intent(this, HistorialActivity::class.java)

            startActivity(ventanaHistorial)
        }

        btnCerrarSesion.setOnClickListener {
            finish()
        }

        conectarMqtt()
    }

    private fun conectarMqtt() {
        val identificador =
            "MonitorIoT-${UUID.randomUUID()}"

        clienteMqtt = MqttAsyncClient(
            servidorMqtt,
            identificador,
            MemoryPersistence()
        )

        val opciones = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            connectionTimeout = 10
            keepAliveInterval = 20
        }

        clienteMqtt.setCallback(object : MqttCallback {

            override fun connectionLost(cause: Throwable?) {
                runOnUiThread {
                    tvConexion.text = "● Conexión perdida"
                    tvConexion.setTextColor(
                        getColor(android.R.color.holo_red_dark)
                    )
                }
            }

            override fun messageArrived(
                topic: String?,
                message: MqttMessage?
            ) {
                val mensaje =
                    message?.toString()?.trim() ?: return

                val temperatura =
                    mensaje.toDoubleOrNull() ?: return

                baseDatos.guardarMedicion(temperatura)

                runOnUiThread {
                    actualizarPantalla(temperatura)
                }
            }

            override fun deliveryComplete(
                token: IMqttDeliveryToken?
            ) {
                // La aplicación solamente recibe datos.
            }
        })

        tvConexion.text = "● Conectando a MQTT..."

        clienteMqtt.connect(
            opciones,
            null,
            object : IMqttActionListener {

                override fun onSuccess(
                    asyncActionToken: IMqttToken?
                ) {
                    clienteMqtt.subscribe(
                        temaTemperatura,
                        0
                    )

                    runOnUiThread {
                        tvConexion.text =
                            "● Sensor conectado"

                        tvConexion.setTextColor(
                            getColor(
                                android.R.color.holo_green_dark
                            )
                        )
                    }
                }

                override fun onFailure(
                    asyncActionToken: IMqttToken?,
                    exception: Throwable?
                ) {
                    runOnUiThread {
                        tvConexion.text =
                            "● Error de conexión MQTT"

                        tvConexion.setTextColor(
                            getColor(
                                android.R.color.holo_red_dark
                            )
                        )
                    }
                }
            }
        )
    }

    private fun actualizarPantalla(
        temperatura: Double
    ) {
        tvTemperatura.text =
            String.format("%.1f °C", temperatura)

        if (temperatura > 30.0) {
            tvEstado.text =
                "ALERTA: temperatura elevada"

            tvEstado.setTextColor(
                getColor(android.R.color.holo_red_dark)
            )

            tvEstado.setBackgroundColor(
                getColor(android.R.color.holo_red_light)
            )
        } else {
            tvEstado.text =
                "Estado: temperatura normal"

            tvEstado.setTextColor(
                getColor(android.R.color.holo_green_dark)
            )

            tvEstado.setBackgroundColor(
                getColor(android.R.color.holo_green_light)
            )
        }
    }

    override fun onDestroy() {
        if (::clienteMqtt.isInitialized &&
            clienteMqtt.isConnected
        ) {
            clienteMqtt.disconnect()
        }

        super.onDestroy()
    }
}