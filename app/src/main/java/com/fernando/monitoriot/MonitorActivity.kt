package com.fernando.monitoriot

import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import org.json.JSONObject

class MonitorActivity : AppCompatActivity() {

    private lateinit var tvSincronizacion: TextView
    private var sincronizadorNube: SincronizadorNube? = null
    private lateinit var tvConexion: TextView
    private lateinit var tvTemperatura: TextView
    private lateinit var tvHumedad: TextView
    private lateinit var tvEstado: TextView
    private lateinit var tvEstadoLed: TextView
    private lateinit var btnEncenderLed: Button
    private lateinit var btnApagarLed: Button
    private lateinit var btnConsultarLed: Button
    private lateinit var clienteMqtt: MqttAsyncClient
    private lateinit var baseDatos: DatabaseHelper

    private val manejador = Handler(Looper.getMainLooper())
    private var sonidoAlerta: Ringtone? = null
    private var ultimaHumedad: Double? = null
    private var alertaActiva = false
    @Volatile private var cerrada = false
    private var mqttListo = false
    private var comandoPendiente: String? = null
    private var numeroSolicitud = 0
    private var tiempoConfirmacion: Runnable? = null

    private val servidorMqtt = "ssl://broker.hivemq.com:8883"
    private val temaTemperatura = "fernando-ti3042-2026/temperatura"
    private val temaHumedad = "fernando-ti3042-2026/humedad"
    private val temaComando = "fernando-ti3042-2026/led/comando"
    private val temaEstadoLed = "fernando-ti3042-2026/led/estado"
    private val temaLecturas = "fernando-ti3042-2026/mediciones"
    private val temaConfirmacion = "fernando-ti3042-2026/mediciones/ack"

    private val opciones = MqttConnectOptions().apply {
        isCleanSession = true
        isAutomaticReconnect = true
        connectionTimeout = 10
        keepAliveInterval = 20
    }

    private val reintentarInicio = Runnable { conectarMqtt() }
    private val reintentarSuscripcion = Runnable { suscribirTemas() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)
        baseDatos = DatabaseHelper(this)
        prepararSonidoAlerta()

        tvSincronizacion = findViewById(R.id.tvSincronizacion)
        tvConexion = findViewById(R.id.tvConexion)
        tvTemperatura = findViewById(R.id.tvTemperatura)
        tvHumedad = findViewById(R.id.tvHumedad)
        tvEstado = findViewById(R.id.tvEstado)
        tvEstadoLed = findViewById(R.id.tvEstadoLed)
        btnEncenderLed = findViewById(R.id.btnEncenderLed)
        btnApagarLed = findViewById(R.id.btnApagarLed)
        btnConsultarLed = findViewById(R.id.btnConsultarLed)

        btnEncenderLed.setOnClickListener { enviarComando("ON") }
        btnApagarLed.setOnClickListener { enviarComando("OFF") }
        btnConsultarLed.setOnClickListener { enviarComando("STATUS") }
        findViewById<Button>(R.id.btnHistorial).setOnClickListener {
            startActivity(Intent(this, HistorialActivity::class.java))
        }
        findViewById<Button>(R.id.btnCerrarSesion).setOnClickListener { finish() }
        actualizarBotones()
        prepararMqtt()
        val usuarioId = intent.getStringExtra("usuario_id").orEmpty()
        val token = intent.getStringExtra("token_nube").orEmpty()
        sincronizadorNube = SincronizadorNube(baseDatos, usuarioId, token) { texto, alDia ->
            enPantalla {
                tvSincronizacion.text = texto
                tvSincronizacion.setTextColor(getColor(
                    if (alDia) android.R.color.holo_green_dark else android.R.color.holo_orange_dark
                ))
            }
        }.also { it.iniciar() }
    }

    private fun prepararMqtt() {
        try {
            clienteMqtt = MqttAsyncClient(
                servidorMqtt,
                "MonitorIoT-${UUID.randomUUID()}",
                MemoryPersistence()
            )
            clienteMqtt.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    enPantalla {
                        manejador.removeCallbacks(reintentarInicio)
                        suscribirTemas()
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    enPantalla {
                        mqttListo = false
                        ultimaHumedad = null
                        cancelarEspera()
                        tvEstadoLed.text = "LED: sin confirmar (conexion perdida)"
                        mostrarConexion("Conexion perdida. Reconectando...", false)
                        actualizarBotones()
                    }
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (cerrada || message == null || message.payload.size > 512) return
                    val texto = String(message.payload, Charsets.UTF_8).trim()
                    if (topic == temaLecturas) {
                        if (!message.isRetained) recibirLectura(texto)
                        return
                    }
                    // Un mensaje retenido no confirma que el ESP32 este activo ahora.
                    if (topic == temaEstadoLed) {
                        if (!message.isRetained && (texto == "ON" || texto == "OFF")) {
                            enPantalla { recibirEstadoLed(texto) }
                        }
                        return
                    }
                    val valor = texto.toDoubleOrNull() ?: return
                    if (!valor.isFinite()) return
                    enPantalla {
                        when (topic) {
                            temaHumedad -> if (valor in 0.0..100.0) {
                                ultimaHumedad = valor
                                tvHumedad.text = String.format("Humedad: %.1f %%", valor)
                            }
                            temaTemperatura -> if (valor in -40.0..80.0) {
                                actualizarTemperatura(valor)
                            }
                        }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    // Entrega al broker: la confirmacion del LED llega por temaEstadoLed.
                }
            })
            conectarMqtt()
        } catch (error: Exception) {
            Log.e("MonitorIoT", "No se pudo preparar MQTT", error)
            mostrarConexion("No se pudo preparar MQTT", false)
        }
    }

    private fun recibirLectura(texto: String) {
        try {
            val datos = JSONObject(texto)
            val id = datos.getString("id")
            val temperatura = datos.getDouble("temperatura")
            val humedad = datos.getDouble("humedad")
            val fechaEpoch = datos.getLong("fechaEpoch")
            val tiempoEncendido = datos.getLong("uptimeMs")
            val dispositivo = datos.getString("dispositivo")
            val guardada = baseDatos.guardarMedicionRecibida(
                id, temperatura, humedad, fechaEpoch, tiempoEncendido, dispositivo
            )
            if (guardada && !cerrada && clienteMqtt.isConnected) {
                // Confirmar despues de guardar en SQLite, incluso si era un duplicado valido.
                clienteMqtt.publish(
                    temaConfirmacion, id.toByteArray(Charsets.UTF_8), 1, false
                )
            }
        } catch (error: Exception) {
            // Sin confirmacion, el ESP32 conserva la lectura y vuelve a enviarla.
            Log.e("MonitorIoT", "No se pudo guardar o confirmar la lectura", error)
        }
    }

    private fun conectarMqtt() {
        if (cerrada || !::clienteMqtt.isInitialized || clienteMqtt.isConnected) return
        mostrarConexion("Conectando a MQTT...", false)
        try {
            clienteMqtt.connect(opciones, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    // connectComplete prepara las suscripciones en cada conexion.
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    enPantalla { programarReintentoInicial() }
                }
            })
        } catch (error: Exception) {
            programarReintentoInicial()
        }
    }

    private fun programarReintentoInicial() {
        if (cerrada) return
        mostrarConexion("Sin conexion MQTT. Reintentando...", false)
        manejador.removeCallbacks(reintentarInicio)
        manejador.postDelayed(reintentarInicio, 5000)
    }

    private fun suscribirTemas() {
        if (cerrada || !clienteMqtt.isConnected) return
        mqttListo = false
        actualizarBotones()
        mostrarConexion("MQTT conectado. Preparando recepcion...", false)
        try {
            clienteMqtt.subscribe(
                arrayOf(temaHumedad, temaTemperatura, temaEstadoLed, temaLecturas),
                intArrayOf(1, 1, 1, 1),
                null,
                object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        enPantalla {
                            val permisos = asyncActionToken?.grantedQos
                            if (permisos == null || permisos.size != 4 || permisos.any { it == 128 }) {
                                programarReintentoSuscripcion()
                            } else if (clienteMqtt.isConnected) {
                                mqttListo = true
                                mostrarConexion("MQTT conectado", true)
                                actualizarBotones()
                                enviarComando("STATUS")
                            }
                        }
                    }
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        enPantalla { programarReintentoSuscripcion() }
                    }
                }
            )
        } catch (error: Exception) {
            programarReintentoSuscripcion()
        }
    }

    private fun programarReintentoSuscripcion() {
        mostrarConexion("No se pudo preparar la recepcion. Reintentando...", false)
        manejador.removeCallbacks(reintentarSuscripcion)
        manejador.postDelayed(reintentarSuscripcion, 5000)
    }

    private fun enviarComando(comando: String) {
        if (!mqttListo || !clienteMqtt.isConnected || comandoPendiente != null) return
        comandoPendiente = comando
        val solicitud = ++numeroSolicitud
        tvEstadoLed.text = "LED: esperando confirmacion del ESP32..."
        tvEstadoLed.setTextColor(getColor(android.R.color.holo_orange_dark))
        actualizarBotones()
        tiempoConfirmacion = Runnable {
            if (comandoPendiente != null && solicitud == numeroSolicitud) {
                cancelarEspera()
                tvEstadoLed.text = "LED: sin confirmacion. Revise Wokwi y consulte el estado."
                tvEstadoLed.setTextColor(getColor(android.R.color.holo_red_dark))
                actualizarBotones()
            }
        }.also { manejador.postDelayed(it, 8000) }
        try {
            // No retener ordenes: no deben ejecutarse mas tarde al reconectar.
            clienteMqtt.publish(
                temaComando, comando.toByteArray(Charsets.UTF_8), 0, false,
                null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        // Esperar la respuesta del ESP32, no cambiar el estado aqui.
                    }
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        enPantalla { falloComando(solicitud) }
                    }
                }
            )
        } catch (error: Exception) {
            falloComando(solicitud)
        }
    }

    private fun recibirEstadoLed(estado: String) {
        val pendiente = comandoPendiente
        if (pendiente == "STATUS" || pendiente == estado) cancelarEspera()
        val nombre = if (estado == "ON") "ENCENDIDO" else "APAGADO"
        if (comandoPendiente == null) {
            tvEstadoLed.text = "Ultimo estado confirmado del LED: $nombre"
            tvEstadoLed.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            tvEstadoLed.text = "LED reporta $nombre. Esperando la orden solicitada..."
        }
        actualizarBotones()
    }

    private fun falloComando(solicitud: Int) {
        if (solicitud != numeroSolicitud || comandoPendiente == null) return
        cancelarEspera()
        tvEstadoLed.text = "LED: no se pudo enviar la orden. Consulte el estado."
        tvEstadoLed.setTextColor(getColor(android.R.color.holo_red_dark))
        actualizarBotones()
    }

    private fun cancelarEspera() {
        tiempoConfirmacion?.let { manejador.removeCallbacks(it) }
        tiempoConfirmacion = null
        comandoPendiente = null
    }

    private fun actualizarBotones() {
        val habilitados = mqttListo && comandoPendiente == null
        btnEncenderLed.isEnabled = habilitados
        btnApagarLed.isEnabled = habilitados
        btnConsultarLed.isEnabled = habilitados
    }

    private fun mostrarConexion(texto: String, conectada: Boolean) {
        tvConexion.text = texto
        tvConexion.setTextColor(getColor(
            if (conectada) android.R.color.holo_green_dark else android.R.color.holo_orange_dark
        ))
    }

    private fun enPantalla(accion: () -> Unit) {
        runOnUiThread { if (!cerrada) accion() }
    }

    private fun prepararSonidoAlerta() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        sonidoAlerta = RingtoneManager.getRingtone(applicationContext, uri)
        sonidoAlerta?.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    private fun actualizarTemperatura(temperatura: Double) {
        tvTemperatura.text = String.format("%.1f °C", temperatura)
        if (temperatura > 30.0) {
            tvEstado.text = "ALERTA: temperatura elevada"
            tvEstado.setTextColor(getColor(android.R.color.holo_red_dark))
            tvEstado.setBackgroundColor(getColor(android.R.color.holo_red_light))
            if (!alertaActiva) {
                sonidoAlerta?.play()
                manejador.postDelayed({ sonidoAlerta?.stop() }, 3000)
                alertaActiva = true
            }
        } else {
            tvEstado.text = "Estado: temperatura normal"
            tvEstado.setTextColor(getColor(android.R.color.holo_green_dark))
            tvEstado.setBackgroundColor(getColor(android.R.color.holo_green_light))
            alertaActiva = false
        }
    }

    override fun onDestroy() {
        cerrada = true
        sincronizadorNube?.cerrar()
        manejador.removeCallbacksAndMessages(null)
        sonidoAlerta?.stop()
        if (::clienteMqtt.isInitialized) {
            // La liberacion puede esperar a la red; hacerla fuera del hilo de pantalla.
            Thread {
                try {
                    clienteMqtt.disconnectForcibly(0, 1000)
                } catch (error: Exception) {
                    Log.d("MonitorIoT", "Cliente MQTT ya desconectado", error)
                } finally {
                    try {
                        clienteMqtt.close(true)
                    } catch (error: Exception) {
                        Log.d("MonitorIoT", "No se pudo cerrar el cliente MQTT", error)
                    }
                }
            }.start()
        }
        super.onDestroy()
    }
}
