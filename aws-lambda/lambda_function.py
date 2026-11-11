import base64
import hashlib
import hmac
import json
import os
import secrets
import time
import re
import math
from decimal import Decimal

import boto3
from botocore.exceptions import ClientError, BotoCoreError

import pymysql


def respuesta(codigo, datos):
    return {
        "statusCode": codigo,
        "headers": {
            "Content-Type": "application/json",
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Headers": "content-type,authorization",
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


NOMBRE_TABLA = os.environ.get("MEDICIONES_TABLE", "MonitorIoT_Mediciones")
_tabla = None


def tabla_nube():
    global _tabla
    if _tabla is None:
        _tabla = boto3.resource("dynamodb").Table(NOMBRE_TABLA)
    return _tabla


def nueva_sesion(usuario_id):
    token = secrets.token_urlsafe(32)
    vencimiento = int(time.time()) + 12 * 60 * 60
    clave = "sesion:" + hashlib.sha256(token.encode()).hexdigest()
    tabla_nube().put_item(Item={
        "id": clave, "tipo": "sesion", "usuario_id": str(usuario_id),
        "vencimiento": vencimiento,
    })
    return token, vencimiento


def usuario_autorizado(event):
    cabeceras = {str(k).lower(): str(v) for k, v in (event.get("headers") or {}).items()}
    autorizacion = cabeceras.get("authorization", "")
    esquema, _, token = autorizacion.partition(" ")
    if esquema.lower() != "bearer" or not 20 <= len(token) <= 256:
        return None
    clave = "sesion:" + hashlib.sha256(token.encode()).hexdigest()
    sesion = tabla_nube().get_item(Key={"id": clave}, ConsistentRead=True).get("Item")
    if not sesion or sesion.get("tipo") != "sesion":
        return None
    if int(sesion.get("vencimiento", 0)) <= int(time.time()):
        return None
    return str(sesion["usuario_id"])


def normalizar_lectura(datos, usuario_id):
    if not isinstance(datos, dict):
        raise ValueError("Lectura invalida")
    identificador = datos.get("id")
    if not isinstance(identificador, str) or not re.fullmatch(r"[A-Za-z0-9:-]{1,80}", identificador):
        raise ValueError("Identificador de lectura invalido")
    valores = {}
    for campo, minimo, maximo in (("temperatura", -40, 80), ("humedad", 0, 100)):
        valor = datos.get(campo)
        if isinstance(valor, bool) or not isinstance(valor, (int, float)):
            raise ValueError("Temperatura o humedad invalida")
        if not math.isfinite(valor) or not minimo <= valor <= maximo:
            raise ValueError("Temperatura o humedad fuera de rango")
        valores[campo] = Decimal(str(valor))
    fecha = datos.get("fechaEpoch", 0)
    tiempo = datos.get("uptimeMs", 0)
    if isinstance(fecha, bool) or not isinstance(fecha, int) or (fecha != 0 and not 1735689600 <= fecha <= 4102444800):
        raise ValueError("Fecha invalida")
    if isinstance(tiempo, bool) or not isinstance(tiempo, int) or not 0 <= tiempo <= 4294967295:
        raise ValueError("Tiempo de lectura invalido")
    dispositivo = datos.get("dispositivo", "ESP32-Wokwi")
    if not isinstance(dispositivo, str) or not 1 <= len(dispositivo) <= 64:
        raise ValueError("Dispositivo invalido")
    fecha_texto = datos.get("fechaTexto", "")
    if not isinstance(fecha_texto, str) or len(fecha_texto) > 120:
        raise ValueError("Texto de fecha invalido")
    return {
        "id": "medicion:" + usuario_id + ":" + identificador,
        "tipo": "medicion", "id_externo": identificador, "usuario_id": usuario_id,
        **valores, "fecha_epoch": fecha, "uptime_ms": tiempo,
        "fecha_texto": fecha_texto, "dispositivo": dispositivo,
    }


def guardar_en_nube(lectura):
    tabla = tabla_nube()
    try:
        tabla.put_item(
            Item=lectura, ConditionExpression="attribute_not_exists(id)"
        )
        return True
    except ClientError as error:
        if error.response.get("Error", {}).get("Code") != "ConditionalCheckFailedException":
            raise
        anterior = tabla.get_item(Key={"id": lectura["id"]}, ConsistentRead=True).get("Item", {})
        campos = ("usuario_id", "id_externo", "temperatura", "humedad", "fecha_epoch", "uptime_ms", "dispositivo")
        return all(anterior.get(campo) == lectura[campo] for campo in campos)


def lambda_handler(event, context):
    try:
        metodo, ruta, datos = obtener_solicitud(event)
    except (ValueError, UnicodeError):
        return respuesta(400, {"mensaje": "Solicitud invalida"})
    if not isinstance(datos, dict):
        return respuesta(400, {"mensaje": "El cuerpo debe ser un objeto JSON"})

    if metodo == "OPTIONS":
        return respuesta(200, {})

    if metodo == "GET" and ruta == "/":
        return respuesta(200, {"mensaje": "API Monitor IoT funcionando"})

    try:
        if metodo == "GET" and ruta == "/estado-nube":
            informacion = tabla_nube().meta.client.describe_table(TableName=NOMBRE_TABLA)
            return respuesta(200, {
                "mensaje": "DynamoDB disponible", "tabla": NOMBRE_TABLA,
                "estado": informacion["Table"]["TableStatus"],
            })

        if metodo == "POST" and ruta == "/sincronizar":
            usuario_id = usuario_autorizado(event)
            if usuario_id is None:
                return respuesta(401, {"mensaje": "Sesion vencida o invalida. Inicie sesion nuevamente"})
            registros = datos.get("mediciones")
            if not isinstance(registros, list) or not 1 <= len(registros) <= 25:
                return respuesta(400, {"mensaje": "Envie entre 1 y 25 mediciones"})
            # Validar el lote completo antes de realizar escrituras.
            lecturas = [normalizar_lectura(registro, usuario_id) for registro in registros]
            confirmados = []
            conflictos = []
            for lectura in lecturas:
                if guardar_en_nube(lectura):
                    confirmados.append(lectura["id_externo"])
                else:
                    conflictos.append(lectura["id_externo"])
            return respuesta(200, {"confirmados": confirmados, "conflictos": conflictos})

        # La app actual usa /sincronizar; la antigua API SQL queda retirada.
        if ruta == "/mediciones":
            if usuario_autorizado(event) is None:
                return respuesta(401, {"mensaje": "Sesion vencida o invalida. Inicie sesion nuevamente"})
            return respuesta(410, {"mensaje": "Ruta antigua deshabilitada. Use /sincronizar"})

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
                    token, vencimiento = nueva_sesion(usuario["id"])
                    return respuesta(200, {
                        "mensaje": "Inicio de sesión correcto",
                        "token": token, "vencimiento": vencimiento,
                        "usuario": {"id": usuario["id"], "nombre": usuario["nombre"], "correo": usuario["correo"]},
                    })

                return respuesta(404, {"mensaje": "Ruta no encontrada"})

    except ValueError as error:
        return respuesta(400, {"mensaje": str(error)})
    except ClientError as error:
        codigo = error.response.get("Error", {}).get("Code", "")
        print("Error DynamoDB:", codigo)
        if "AccessDenied" in codigo:
            mensaje = "Lambda no tiene permisos para la tabla DynamoDB"
        elif codigo == "ResourceNotFoundException":
            mensaje = "No se encontro la tabla DynamoDB configurada"
        else:
            mensaje = "No se pudo guardar en DynamoDB. Se reintentara"
        return respuesta(503, {"mensaje": mensaje})
    except BotoCoreError:
        return respuesta(503, {"mensaje": "No se pudo conectar con DynamoDB"})
    except pymysql.MySQLError as error:
        print("Error MySQL:", error)
        return respuesta(500, {"mensaje": "Error al conectar con la base de datos"})
    except Exception as error:
        print("Error:", error)
        return respuesta(500, {"mensaje": "Error interno del servidor"})
