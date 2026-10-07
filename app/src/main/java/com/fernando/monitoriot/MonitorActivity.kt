package com.fernando.monitoriot

import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var tvHumedad: TextView
    private lateinit var tvEstado: TextView
    private lateinit var clienteMqtt: MqttAsyncClient
    private lateinit var baseDatos: DatabaseHelper

    private var sonidoAlerta: Ringtone? = null
    private val manejador = Handler(Looper.getMainLooper())

    private var ultimaHumedad: Double? = null
    private var alertaActiva = false

    private val servidorMqtt =
        "ssl://broker.hivemq.com:8883"

    private val temaTemperatura =
        "fernando-ti3042-2026/temperatura"

    private val temaHumedad =
        "fernando-ti3042-2026/humedad"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)

        baseDatos = DatabaseHelper(this)

        prepararSonidoAlerta()

        tvConexion = findViewById(R.id.tvConexion)
        tvTemperatura = findViewById(R.id.tvTemperatura)
        tvHumedad = findViewById(R.id.tvHumedad)
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

    private fun prepararSonidoAlerta() {
        var uriAlarma =
            RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_ALARM
            )

        if (uriAlarma == null) {
            uriAlarma =
                RingtoneManager.getDefaultUri(
                    RingtoneManager.TYPE_NOTIFICATION
                )
        }

        sonidoAlerta =
            RingtoneManager.getRingtone(
                applicationContext,
                uriAlarma
            )

        val atributos = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(
                AudioAttributes.CONTENT_TYPE_SONIFICATION
            )
            .build()

        sonidoAlerta?.audioAttributes = atributos
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

                val valor =
                    mensaje.toDoubleOrNull() ?: return

                if (topic == temaHumedad) {
                    ultimaHumedad = valor

                    runOnUiThread {
                        tvHumedad.text =
                            String.format(
                                "Humedad: %.1f %%",
                                valor
                            )
                    }
                }

                if (topic == temaTemperatura) {
                    baseDatos.guardarMedicion(
                        valor,
                        ultimaHumedad
                    )

                    runOnUiThread {
                        actualizarTemperatura(valor)
                    }
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
                        temaHumedad,
                        0
                    )

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

    private fun actualizarTemperatura(
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

            if (!alertaActiva) {
                reproducirAlarma()
                alertaActiva = true
            }
        } else {
            tvEstado.text =
                "Estado: temperatura normal"

            tvEstado.setTextColor(
                getColor(android.R.color.holo_green_dark)
            )

            tvEstado.setBackgroundColor(
                getColor(android.R.color.holo_green_light)
            )

            alertaActiva = false
        }
    }

    private fun reproducirAlarma() {
        sonidoAlerta?.play()

        manejador.postDelayed(
            {
                if (sonidoAlerta?.isPlaying == true) {
                    sonidoAlerta?.stop()
                }
            },
            3000
        )
    }

    override fun onDestroy() {
        manejador.removeCallbacksAndMessages(null)

        if (sonidoAlerta?.isPlaying == true) {
            sonidoAlerta?.stop()
        }

        if (::clienteMqtt.isInitialized &&
            clienteMqtt.isConnected
        ) {
            clienteMqtt.disconnect()
        }

        super.onDestroy()
    }
}