#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <time.h>
#include <esp_system.h>
#include <PubSubClient.h>
#include <DHT.h>

#define DHTPIN 15
#define DHTTYPE DHT22
#define LEDPIN 18

const float UMBRAL_TEMPERATURA = 30.0;

DHT dht(DHTPIN, DHTTYPE);
WiFiClientSecure espClient;
PubSubClient client(espClient);

const char* ssid = "Wokwi-GUEST";
const char* password = "";
// MQTT cifrado con validacion del certificado del servidor.
const char* mqttServer = "broker.hivemq.com";
const int mqttPort = 8883;
const char* temaTemperatura = "fernando-ti3042-2026/temperatura";
const char* temaHumedad = "fernando-ti3042-2026/humedad";
const char* temaComando = "fernando-ti3042-2026/led/comando";
const char* temaEstado = "fernando-ti3042-2026/led/estado";
const char* temaLecturas = "fernando-ti3042-2026/mediciones";
const char* temaConfirmacion = "fernando-ti3042-2026/mediciones/ack";

// Autoridades publicas de confianza: Amazon Root CA 1 e ISRG Root X1.
const char* certificadosRaiz = R"CERT(
-----BEGIN CERTIFICATE-----
MIIDQTCCAimgAwIBAgITBmyfz5m/jAo54vB4ikPmljZbyjANBgkqhkiG9w0BAQsF
ADA5MQswCQYDVQQGEwJVUzEPMA0GA1UEChMGQW1hem9uMRkwFwYDVQQDExBBbWF6
b24gUm9vdCBDQSAxMB4XDTE1MDUyNjAwMDAwMFoXDTM4MDExNzAwMDAwMFowOTEL
MAkGA1UEBhMCVVMxDzANBgNVBAoTBkFtYXpvbjEZMBcGA1UEAxMQQW1hem9uIFJv
b3QgQ0EgMTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALJ4gHHKeNXj
ca9HgFB0fW7Y14h29Jlo91ghYPl0hAEvrAIthtOgQ3pOsqTQNroBvo3bSMgHFzZM
9O6II8c+6zf1tRn4SWiw3te5djgdYZ6k/oI2peVKVuRF4fn9tBb6dNqcmzU5L/qw
IFAGbHrQgLKm+a/sRxmPUDgH3KKHOVj4utWp+UhnMJbulHheb4mjUcAwhmahRWa6
VOujw5H5SNz/0egwLX0tdHA114gk957EWW67c4cX8jJGKLhD+rcdqsq08p8kDi1L
93FcXmn/6pUCyziKrlA4b9v7LWIbxcceVOF34GfID5yHI9Y/QCB/IIDEgEw+OyQm
jgSubJrIqg0CAwEAAaNCMEAwDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMC
AYYwHQYDVR0OBBYEFIQYzIU07LwMlJQuCFmcx7IQTgoIMA0GCSqGSIb3DQEBCwUA
A4IBAQCY8jdaQZChGsV2USggNiMOruYou6r4lK5IpDB/G/wkjUu0yKGX9rbxenDI
U5PMCCjjmCXPI6T53iHTfIUJrU6adTrCC2qJeHZERxhlbI1Bjjt/msv0tadQ1wUs
N+gDS63pYaACbvXy8MWy7Vu33PqUXHeeE6V/Uq2V8viTO96LXFvKWlJbYK8U90vv
o/ufQJVtMVT8QtPHRh8jrdkPSHCa2XV4cdFyQzR1bldZwgJcJmApzyMZFo6IQ6XU
5MsI+yMRQ+hDKXJioaldXgjUkK642M4UwtBV8ob2xJNDd2ZhwLnoQdeXeGADbkpy
rqXRfboQnoZsG4q5WTP468SQvvG5
-----END CERTIFICATE-----
-----BEGIN CERTIFICATE-----
MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
-----END CERTIFICATE-----
)CERT";

bool horaSolicitada = false;
unsigned long ultimoAvisoHora = 0;
float ultimaTemperatura = NAN;
bool ledEncendido = false;
bool estadoPendiente = true;
unsigned long ultimoWiFi = 0;
unsigned long ultimoMQTT = 0;
unsigned long ultimaLectura = 0;
char entradaSerial[17];
unsigned int longitudSerial = 0;
bool entradaDemasiadoLarga = false;
String identificador;
String sesion;

const unsigned int CAPACIDAD_COLA = 120;
float temperaturasGuardadas[CAPACIDAD_COLA];
float humedadesGuardadas[CAPACIDAD_COLA];
uint32_t fechasGuardadas[CAPACIDAD_COLA];
uint32_t tiemposGuardados[CAPACIDAD_COLA];
uint32_t numerosGuardados[CAPACIDAD_COLA];
unsigned int inicioCola = 0;
unsigned int pendientes = 0;
uint32_t secuencia = 0;
unsigned long descartadas = 0;
unsigned long ultimoEnvio = 0;
bool esperandoConfirmacion = false;
bool enlaceHabilitado = true;

String idPrimeraLectura() {
  return sesion + "-" + String(numerosGuardados[inicioCola]);
}

void mostrarBuffer() {
  Serial.print("BUFFER: ");
  Serial.print(pendientes);
  Serial.print(" / ");
  Serial.print(CAPACIDAD_COLA);
  Serial.print(" lecturas pendientes | Descartadas: ");
  Serial.println(descartadas);
}

void guardarLectura(float temperatura, float humedad) {
  secuencia++;
  if (pendientes == CAPACIDAD_COLA) {
    descartadas++;
    Serial.println("BUFFER LLENO: se descarta la lectura nueva.");
    mostrarBuffer();
    return;
  }
  unsigned int posicion = (inicioCola + pendientes) % CAPACIDAD_COLA;
  temperaturasGuardadas[posicion] = temperatura;
  humedadesGuardadas[posicion] = humedad;
  time_t ahora = time(nullptr);
  fechasGuardadas[posicion] = ahora >= 1735689600 ? (uint32_t)ahora : 0;
  tiemposGuardados[posicion] = millis();
  numerosGuardados[posicion] = secuencia;
  pendientes++;
  mostrarBuffer();
}

void confirmarLectura(const String& id) {
  if (pendientes == 0 || id != idPrimeraLectura()) return;
  inicioCola = (inicioCola + 1) % CAPACIDAD_COLA;
  pendientes--;
  esperandoConfirmacion = false;
  Serial.print("guardado confirmado: ");
  Serial.println(id);
  mostrarBuffer();
}

void enviarPendientes() {
  if (!client.connected() || pendientes == 0) return;
  unsigned long intervalo = esperandoConfirmacion ? 3000 : 200;
  if (millis() - ultimoEnvio < intervalo) return;
  ultimoEnvio = millis();

  String id = idPrimeraLectura();
  char mensaje[320];
  int longitud = snprintf(
    mensaje, sizeof(mensaje),
    "{\"id\":\"%s\",\"temperatura\":%.1f,\"humedad\":%.1f,"
    "\"fechaEpoch\":%lu,\"uptimeMs\":%lu,\"dispositivo\":\"ESP32-Wokwi\"}",
    id.c_str(), temperaturasGuardadas[inicioCola], humedadesGuardadas[inicioCola],
    (unsigned long)fechasGuardadas[inicioCola], (unsigned long)tiemposGuardados[inicioCola]
  );
  if (longitud <= 0 || longitud >= (int)sizeof(mensaje)) return;
  if (client.publish(temaLecturas, mensaje, false)) {
    esperandoConfirmacion = true;
    Serial.print("Lectura enviada; esperando guardado: ");
    Serial.println(id);
  }
}

void procesarEntradaSerial(String comando) {
  comando.trim();
  if (comando == "OFFLINE") {
    enlaceHabilitado = false;
    client.disconnect();
    WiFi.disconnect(true);
    horaSolicitada = false;
    Serial.println("WiFi desconectado para prueba. El sensor y el LED siguen funcionando.");
  } else if (comando == "ONLINE") {
    enlaceHabilitado = true;
    WiFi.mode(WIFI_STA);
    WiFi.begin(ssid, password);
    Serial.println("Recuperando WiFi y MQTT...");
  } else if (comando == "BUFFER") {
    mostrarBuffer();
  } else {
    procesarComando(comando);
  }
}

void aplicarEstadoLed(bool encender) {
  if (ledEncendido == encender) return;
  ledEncendido = encender;
  digitalWrite(LEDPIN, ledEncendido ? HIGH : LOW);
  estadoPendiente = true;
  Serial.print("LED cambiado a: ");
  Serial.println(ledEncendido ? "ON" : "OFF");
}

void controlarTemperatura() {
  if (isnan(ultimaTemperatura)) return;

  if (ultimaTemperatura > UMBRAL_TEMPERATURA && !ledEncendido) {
    aplicarEstadoLed(true);
  } else if (ultimaTemperatura <= UMBRAL_TEMPERATURA && ledEncendido) {
    aplicarEstadoLed(false);
  }
}

void procesarComando(const String& comando) {
  if (comando == "ON" || comando == "OFF") {
    aplicarEstadoLed(comando == "ON");
    ultimaLectura = millis();
    Serial.println("Orden aplicada. En 5 segundos se revisara la temperatura.");
  } else if (comando == "AUTO") {
    controlarTemperatura();
  } else if (comando != "STATUS") {
    Serial.println("Comando rechazado. Use ON, OFF, AUTO o STATUS.");
    return;
  }

  estadoPendiente = true;
  Serial.print("Estado del LED: ");
  Serial.println(ledEncendido ? "ON" : "OFF");
}

void recibirMQTT(char* topic, byte* payload, unsigned int length) {
  if (length == 0 || length > 80) return;
  char texto[81];
  memcpy(texto, payload, length);
  texto[length] = '\0';
  if (strlen(texto) != length) return;
  String mensaje(texto);
  mensaje.trim();
  if (strcmp(topic, temaConfirmacion) == 0) {
    confirmarLectura(mensaje);
  } else if (strcmp(topic, temaComando) == 0 && length <= 16) {
    procesarComando(mensaje);
  }
}

void revisarSerial() {
  while (Serial.available()) {
    char caracter = Serial.read();
    if (caracter == '\r') continue;
    if (caracter == '\n') {
      entradaSerial[longitudSerial] = '\0';
      if (entradaDemasiadoLarga) {
        Serial.println("Comando demasiado largo.");
      } else if (longitudSerial > 0) {
        String comando(entradaSerial);
        comando.trim();
        procesarEntradaSerial(comando);
      }
      longitudSerial = 0;
      entradaDemasiadoLarga = false;
    } else if (longitudSerial < sizeof(entradaSerial) - 1) {
      entradaSerial[longitudSerial++] = caracter;
    } else {
      entradaDemasiadoLarga = true;
    }
  }
}

void revisarConexion() {
  if (!enlaceHabilitado) return;
  unsigned long ahora = millis();
  if (WiFi.status() != WL_CONNECTED) {
    horaSolicitada = false;
    if (client.connected()) client.disconnect();
    if (ahora - ultimoWiFi >= 10000) {
      ultimoWiFi = ahora;
      Serial.println("Reintentando WiFi...");
      WiFi.disconnect();
      WiFi.begin(ssid, password);
    }
    return;
  }

  if (!horaSolicitada) {
    configTime(0, 0, "pool.ntp.org", "time.google.com", "time.nist.gov");
    horaSolicitada = true;
    Serial.println("Sincronizando hora para validar el certificado TLS...");
  }

  if (time(nullptr) < 1735689600) {
    if (ahora - ultimoAvisoHora >= 5000) {
      ultimoAvisoHora = ahora;
      Serial.println("sincronizando hora. La conexion MQTT aun no se inicia.");
    }
    return;
  }

  if (!client.connected() && ahora - ultimoMQTT >= 5000) {
    ultimoMQTT = ahora;
    Serial.println("Conectando a MQTT con TLS");
    if (client.connect(identificador.c_str())) {
      if (client.subscribe(temaComando, 1) && client.subscribe(temaConfirmacion, 1)) {
        Serial.println("MQTT conectado con TLS. Certificado validado.");
        estadoPendiente = true;
        esperandoConfirmacion = false;
      } else {
        Serial.println("No se pudo enviar la suscripcion. Reintentando...");
        client.disconnect();
      }
    } else {
      Serial.print("Error MQTT: ");
      Serial.println(client.state());
      char errorTLS[120];
      int codigoTLS = espClient.lastError(errorTLS, sizeof(errorTLS));
      if (codigoTLS != 0) {
        Serial.print("Detalle TLS: ");
        Serial.println(errorTLS);
      }
    }
  }
}

void leerSensor() {
  if (millis() - ultimaLectura < 5000) return;
  ultimaLectura = millis();
  float temperatura = dht.readTemperature();
  float humedad = dht.readHumidity();
  if (isnan(temperatura) || isnan(humedad)) {
    Serial.println("Error al leer el DHT22");
    return;
  }

  ultimaTemperatura = temperatura;
  controlarTemperatura();
  guardarLectura(temperatura, humedad);

  Serial.print("Temperatura: ");
  Serial.print(temperatura);
  Serial.print(" C | Humedad: ");
  Serial.print(humedad);
  Serial.println(" % | ");

  if (client.connected()) {
    char textoTemperatura[16];
    char textoHumedad[16];
    snprintf(textoTemperatura, sizeof(textoTemperatura), "%.1f", temperatura);
    snprintf(textoHumedad, sizeof(textoHumedad), "%.1f", humedad);
    bool envioHumedad = client.publish(temaHumedad, textoHumedad);
    bool envioTemperatura = client.publish(temaTemperatura, textoTemperatura);
    if (!envioHumedad || !envioTemperatura) Serial.println("Fallo de envio MQTT");
  } else {
    Serial.println("Sin MQTT: lectura guardada en la cola local.");
  }
}

void setup() {
  Serial.begin(115200);
  pinMode(LEDPIN, OUTPUT);
  digitalWrite(LEDPIN, LOW);
  dht.begin();
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);
  identificador = "MonitorIoT-" + WiFi.macAddress();
  String mac = WiFi.macAddress();
  mac.replace(":", "");
  sesion = mac + "-" + String(esp_random(), HEX) + "-" + String(esp_random(), HEX);
  espClient.setCACert(certificadosRaiz);
  espClient.setHandshakeTimeout(10);
  client.setServer(mqttServer, mqttPort);
  client.setCallback(recibirMQTT);
  client.setSocketTimeout(5);
  client.setBufferSize(512);
  client.setKeepAlive(20);
  Serial.println("Monitor IoT iniciado. Control por temperatura siempre activo. Umbral: mas de 30 C.");
  Serial.println("Comandos: ON, OFF, AUTO, STATUS. Prueba de cola: OFFLINE, ONLINE, BUFFER.");
  Serial.println("Cola RAM: 120 lecturas. Se pierde si se reinicia el ESP32 o la simulacion.");
}

void loop() {
  revisarSerial();
  revisarConexion();
  client.loop();
  if (client.connected() && estadoPendiente) {
    if (client.publish(temaEstado, ledEncendido ? "ON" : "OFF")) {
      estadoPendiente = false;
    }
  }
  leerSensor();
  enviarPendientes();
  delay(5);
}
