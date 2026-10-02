# Política de privacidad — SMS Tech

_Última actualización: 2 de octubre de 2026_ · 🇬🇧 [English](PRIVACY.md) · 🇫🇷 [Français](PRIVACY.fr.md) · 🇩🇪 [Deutsch](PRIVACY.de.md) · 🇮🇹 [Italiano](PRIVACY.it.md)

> **Traducción.** Esta traducción al español se facilita a título informativo. Solo las versiones
> [inglesa](PRIVACY.md) y [francesa](PRIVACY.fr.md) de esta política hacen fe; en caso de
> discrepancia, prevalecen las versiones inglesa y francesa.

SMS Tech (`com.filestech.sms`) forma parte de la suite **Files Tech**, editada por **Patrice
Haltaya**. Véanse también las [condiciones de uso](TERMS.es.md).

## Lo que recopilamos

**Nada.** SMS Tech no recopila, no transmite ni agrega ningún dato personal.

Ningún SDK de analítica, ningún notificador de fallos, ningún endpoint de telemetría, ningún
identificador publicitario, ninguna biblioteca de fingerprinting. El binario no contiene ningún
código de rastreo de terceros.

## Lo que permanece en su dispositivo

- Sus SMS y MMS, en una base de datos Room cifrada (SQLCipher) protegida por una clave envuelta por
  el AndroidKeyStore.
- Los metadatos de las conversaciones (borradores, fijación, archivado, caja fuerte, preferencias
  por conversación).
- Los ajustes, en Android DataStore Preferences.
- Un hash con sal PBKDF2-HMAC-SHA512 de su código PIN (el PIN nunca se almacena en claro).
- Los archivos adjuntos MMS que pueda haber, en `<files>/mms_attachments/`.
- Los PDF de conversación que se hayan generado localmente, en `<files>/exports/`.
- Si los configura, sus contactos de emergencia y los ajustes del modo emergencia y de Safety call.

Como toda aplicación de SMS predeterminada, SMS Tech también escribe sus mensajes en el almacén de
mensajes del sistema de Android, que los conserva con independencia de la aplicación.

La copia de seguridad del sistema de Android está **desactivada**: estos datos no se envían ni a
Google Drive ni en una transferencia de dispositivo sin su consentimiento explícito.

## Red

SMS Tech no realiza ninguna solicitud de red. Desde la 1.28.13, ni siquiera tiene ya el permiso
`INTERNET`: su proceso no puede abrir ninguna conexión de red. Los MMS se encaminan a través del
servicio MMS de Android, que se comunica con el MMSC de su operador cuando usted envía o recibe uno.
Ninguna comprobación de actualizaciones, ninguna configuración remota, ningún ping analítico.

Los mensajes que usted envía pasan por la red de su operador, como con cualquier aplicación de SMS.
El desarrollador nunca los recibe.

## Funciones de emergencia (inactivas mientras usted no las active)

- **Modo emergencia.** Cuando mantiene pulsado el botón de emergencia tres segundos, SMS Tech envía
  un SMS a los contactos de emergencia que **usted** ha elegido. Si ha concedido el permiso de
  ubicación, precisa o aproximada, y ha dejado «incluir mi ubicación» (activado de forma
  predeterminada en este modo), la aplicación pide a Android **una** posición en ese momento:
  reutiliza una posición de menos de cinco minutos de antigüedad si Android dispone de ella; si no,
  espera una nueva lectura durante ocho segundos como máximo y, en su defecto, reutiliza la última
  posición conocida si tiene menos de treinta minutos de antigüedad. Ningún seguimiento en segundo
  plano ni continuo. La posición se añade al SMS en forma de enlace `https://maps.google.com/?q=…`;
  cuando solo es aproximada, Android la desplaza unos dos kilómetros y el SMS lo indica con
  «(+/-N km)» después del enlace. Si un contacto abre ese enlace, es su navegador el que se comunica
  con Google Maps; la aplicación, por su parte, no envía nada a Google. Si la ubicación se deniega o
  no está disponible, el SMS lo indica y se envía sin coordenadas.
- **Safety call.** Si no utiliza la aplicación antes del plazo que ha fijado, SMS Tech envía un SMS
  de comprobación a los mismos contactos. No contiene ninguna posición.
- **Llamada de emergencia.** Los botones de llamada de la pantalla de emergencia proponen los
  números de emergencia del país en el que su teléfono está registrado en la red (el 112 figura
  siempre). Cuando toca uno, la aplicación solicita el permiso de llamada (`CALL_PHONE`) y, si lo
  concede, realiza la llamada ella misma; si lo deniega, abre el marcador telefónico ya rellenado,
  que no requiere ningún permiso. El acceso directo en la pantalla de bloqueo abre siempre el
  marcador telefónico.
- Ninguna de estas funciones se ejecuta en la sesión señuelo abierta por el código de pánico.
- Estos mensajes son SMS ordinarios: su operador puede cobrarlos según su plan.

## Permisos

Consulte [PERMISSIONS.md](PERMISSIONS.md) para ver la justificación de cada permiso.

## Sus derechos

Como ningún dato personal sale de su dispositivo, no hay nada que consultar, rectificar o suprimir en
ningún sistema remoto. Para borrar sus datos en SMS Tech: desinstale la aplicación (Android elimina
sus datos automáticamente) o utilice **Ajustes → Eliminar todos mis datos**, que borra la base de
datos cifrada, los alias del Keystore, los archivos adjuntos en caché y los PDF exportados. Los
mensajes conservados por el almacén de mensajes del sistema de Android se gestionan desde Android o
desde cualquier aplicación de SMS.

## Contacto

Para cualquier pregunta: **contact@files-tech.com**.
