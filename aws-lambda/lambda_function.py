import base64
import hashlib
import hmac
import json
import os
import secrets

import pymysql


def respuesta(codigo, datos):
    return {
        "statusCode": codigo,
        "headers": {
            "Content-Type": "application/json",
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Headers": "content-type",
            "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
        },
        "body": json.dumps(datos, ensure_ascii=False, default=str),
    }


def conectar():
    return pymysql.connect(
        host=os.environ["DB_HOST"],
        user=os.environ["DB_USER"],
        password=os.environ["DB_PASSWORD"],
        database=os.environ["DB_NAME"],
        port=int(os.environ.get("DB_PORT", "3306")),
        cursorclass=pymysql.cursors.DictCursor,
        connect_timeout=8,
    )


def crear_hash(password):
    sal = secrets.token_bytes(16)
    resultado = hashlib.pbkdf2_hmac("sha256", password.encode(), sal, 120000)
    return "pbkdf2_sha256$120000$" + base64.b64encode(sal).decode() + "$" + base64.b64encode(resultado).decode()


def verificar_hash(password, valor_guardado):
    try:
        algoritmo, iteraciones, sal64, hash64 = valor_guardado.split("$")
        if algoritmo != "pbkdf2_sha256":
            return False
        sal = base64.b64decode(sal64)
        esperado = base64.b64decode(hash64)
        calculado = hashlib.pbkdf2_hmac("sha256", password.encode(), sal, int(iteraciones))
        return hmac.compare_digest(calculado, esperado)
    except (ValueError, TypeError):
        return False


def obtener_solicitud(event):
    contexto_http = event.get("requestContext", {}).get("http", {})
    metodo = contexto_http.get("method") or event.get("httpMethod", "GET")
    ruta = event.get("rawPath") or event.get("path", "/")
    cuerpo = event.get("body") or "{}"
    if event.get("isBase64Encoded"):
        cuerpo = base64.b64decode(cuerpo).decode()
    try:
        datos = json.loads(cuerpo)
    except json.JSONDecodeError:
        datos = {}
    ruta = ruta.rstrip("/") or "/"

    for prefijo in ("/default", "/monitor-iot-api"):
        if ruta == prefijo:
            ruta = "/"
        elif ruta.startswith(prefijo + "/"):
            ruta = ruta[len(prefijo):]

    return metodo.upper(), ruta, datos


def lambda_handler(event, context):
    metodo, ruta, datos = obtener_solicitud(event)

    if metodo == "OPTIONS":
        return respuesta(200, {})

    if metodo == "GET" and ruta == "/":
        return respuesta(200, {"mensaje": "API Monitor IoT funcionando"})

    try:
        conexion = conectar()
        with conexion:
            with conexion.cursor() as cursor:
                if metodo == "POST" and ruta == "/registro":
                    nombre = str(datos.get("nombre", "")).strip()
                    correo = str(datos.get("correo", "")).strip().lower()
                    password = str(datos.get("password", ""))
                    if not nombre or not correo or len(password) < 4:
                        return respuesta(400, {"mensaje": "Nombre, correo y contraseña son obligatorios"})
                    cursor.execute("SELECT id FROM usuarios WHERE correo=%s", (correo,))
                    if cursor.fetchone():
                        return respuesta(409, {"mensaje": "El correo ya está registrado"})
                    cursor.execute(
                        "INSERT INTO usuarios (nombre, correo, password_hash) VALUES (%s, %s, %s)",
                        (nombre, correo, crear_hash(password)),
                    )
                    conexion.commit()
                    return respuesta(201, {"mensaje": "Usuario registrado correctamente"})

                if metodo == "POST" and ruta == "/login":
                    correo = str(datos.get("correo", "")).strip().lower()
                    password = str(datos.get("password", ""))
                    cursor.execute(
                        "SELECT id, nombre, correo, password_hash FROM usuarios WHERE correo=%s",
                        (correo,),
                    )
                    usuario = cursor.fetchone()
                    if not usuario or not verificar_hash(password, usuario["password_hash"]):
                        return respuesta(401, {"mensaje": "Correo o contraseña incorrectos"})
                    return respuesta(200, {
                        "mensaje": "Inicio de sesión correcto",
                        "usuario": {"id": usuario["id"], "nombre": usuario["nombre"], "correo": usuario["correo"]},
                    })

                if metodo == "POST" and ruta == "/mediciones":
                    temperatura = datos.get("temperatura")
                    humedad = datos.get("humedad")
                    dispositivo = str(datos.get("dispositivo", "ESP32-Wokwi"))
                    if temperatura is None or humedad is None:
                        return respuesta(400, {"mensaje": "Faltan temperatura o humedad"})
                    cursor.execute(
                        "INSERT INTO mediciones (dispositivo, temperatura, humedad) VALUES (%s, %s, %s)",
                        (dispositivo, temperatura, humedad),
                    )
                    conexion.commit()
                    return respuesta(201, {"mensaje": "Medición guardada"})

                if metodo == "GET" and ruta == "/mediciones":
                    cursor.execute(
                        "SELECT id, dispositivo, temperatura, humedad, fecha_medicion FROM mediciones ORDER BY id DESC LIMIT 50"
                    )
                    return respuesta(200, {"mediciones": cursor.fetchall()})

                return respuesta(404, {"mensaje": "Ruta no encontrada"})

    except pymysql.MySQLError as error:
        print("Error MySQL:", error)
        return respuesta(500, {"mensaje": "Error al conectar con la base de datos"})
    except Exception as error:
        print("Error:", error)
        return respuesta(500, {"mensaje": "Error interno del servidor"})
