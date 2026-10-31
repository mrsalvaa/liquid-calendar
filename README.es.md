# Liquid Calendar

[English](README.md) | **Español**

Se instala en el celu como **Calendario** (o *Calendar*, según el idioma).

Calendario CalDAV libre para Android, pensado para un servidor propio como
[Radicale](https://radicale.org). Tiene la misma interfaz de cristal líquido que
[Liquid Clock](https://github.com/mrsalvaa/liquid-clock) y
[Liquid Gallery](https://github.com/mrsalvaa/liquid-gallery): superficies que refractan lo que
tienen detrás (gracias a [Backdrop](https://github.com/Kyant0/AndroidLiquidGlass)) y botones que
se aplastan como una gota. Si preferís algo más sobrio, en Ajustes podés pasar al estilo Sólido:
tarjetas opacas, esquinas chicas e íconos de líneas.

Sin publicidad, sin telemetría y sin servicios de Google. Solo se conecta a tu servidor y a las
suscripciones que agregues vos. Licencia GPL-3.0.

<p align="center"><b>Liquid Glass</b></p>
<p align="center">
  <img src="docs/screenshots/mes.png" width="200" alt="Mes">
  <img src="docs/screenshots/semana.png" width="200" alt="Semana">
  <img src="docs/screenshots/dia.png" width="200" alt="Día">
  <img src="docs/screenshots/agenda.png" width="200" alt="Agenda">
</p>
<p align="center"><b>Sólido</b></p>
<p align="center">
  <img src="docs/screenshots/mes-solido.png" width="200" alt="Mes">
  <img src="docs/screenshots/semana-solido.png" width="200" alt="Semana">
  <img src="docs/screenshots/dia-solido.png" width="200" alt="Día">
  <img src="docs/screenshots/agenda-solido.png" width="200" alt="Agenda">
</p>

Las capturas son de datos de prueba inventados (`scripts/demo-data.py`) en un Radicale local.

## Funciones

**Vistas**: mes, semana, día y agenda. En semana y día, pellizcar cambia la altura de las horas.
El primer día de la semana se puede elegir; las fechas se muestran como día/mes/año.

**Eventos**
- Crear, editar y borrar eventos, de todo el día o con horario, en cualquier zona horaria.
- Series: cambiar o borrar solo este, este y los siguientes, o todos, con un resumen de cuántas
  ocurrencias cambian antes de confirmar.
- Recordatorios como notificaciones locales. Por defecto, con el celu bloqueado solo se ve
  "Recordatorio de un evento", no el título.

**Calendarios**
- Calendarios en el dispositivo (nunca se suben) y, opcional, una cuenta CalDAV. Cada calendario
  muestra siempre dónde vive: en este dispositivo, sincronizado con un servidor o suscripción.
- Crear, renombrar, recolorear y borrar calendarios en el servidor. Para borrar hay que escribir
  el nombre del calendario; se muestra cuántos eventos se pierden y se ofrece exportar antes.
- Si un calendario desaparece del servidor no se borra nada: elegís conservarlo en el
  dispositivo, exportarlo o descartarlo.

**Widget de agenda**: los eventos de hoy y de los 13 días siguientes en la pantalla de inicio,
agrupados por día, con la estética de la app (tema, estilo de fondo y color de énfasis). Tocar un
evento lo abre, y "+" crea uno para hoy. Se actualiza solo después de cada cambio o
sincronización.

**Sincronización**
- Funciona sin conexión: los cambios esperan en una cola y se suben después. Cada subida es
  condicional (ETag), así que nunca pisa una versión más nueva; ante un conflicto ves las dos y
  elegís.
- Sincronización en segundo plano con WorkManager, configurable o solo a mano.

**Suscripciones y archivos**
- Suscripciones webcal/ICS de solo lectura que quedan en el dispositivo, actualizadas cada 1 h,
  6 h, 12 h, día o semana, o solo a mano (12 h por defecto).
- Importar archivos `.ics` con vista previa y elección del calendario. Exportar un calendario o un
  evento.

## Requisitos

- Android 12 o posterior.
- Para sincronizar: un servidor CalDAV con HTTPS y un certificado de una autoridad pública (por
  ejemplo Let's Encrypt). Probado con Radicale 3.8. Sin servidor, los calendarios quedan en el
  dispositivo.

## Descargar e instalar

Los cambios de cada versión están en el [registro de cambios](CHANGELOG.es.md).

Bajá el APK desde [Releases](../../releases) e instalalo.

Comprobá que el archivo no fue alterado:

```bash
sha256sum -c liquid-calendar-v1.3.0.apk.sha256
```

```
40da191f1a62709e53a8cae4fdaec7555535499a2ba36be64533981f5398a365  liquid-calendar-v1.3.0.apk
```

Y que está firmado con la clave del proyecto (huella SHA-256 del certificado):

```
55:E9:13:E2:5A:F7:8B:01:1A:CD:1E:4E:16:83:E3:4C:EC:1D:60:05:91:0D:BE:94:08:8B:F0:1D:4B:0F:BC:05
```

```bash
apksigner verify --print-certs liquid-calendar-v1.3.0.apk
```

## Configurar el servidor CalDAV

En **Ajustes → Cuenta**, completá:

- **Dirección del servidor**: `https://` más el host y el puerto, por ejemplo
  `https://cal.example.org:8443/`. La app encuentra tus calendarios sola (pregunta en la dirección
  que pusiste y después en `/.well-known/caldav`). También sirve la URL completa de un calendario
  o del principal.
- **Usuario** y **contraseña**. Tocá **Probar conexión** antes de guardar.

Consejos:

- Solo se acepta `https://`, y el certificado tiene que ser válido para ese nombre. No se aceptan
  certificados autofirmados ni autoridades instaladas por el usuario.
- Algunos servidores (o un fail2ban delante) bloquean tu IP tras unas pocas contraseñas
  incorrectas. La app se detiene en el primer `401` y nunca reintenta sola, y corta "Probar
  conexión" después de 3 contraseñas incorrectas en 10 minutos.
- La primera vez conviene usar un usuario y un calendario de prueba.

Con Radicale, la dirección es la raíz del servidor (`https://host:puerto/`). Los calendarios que
crea la app llevan un identificador aleatorio en el servidor, así que renombrarlos nunca rompe la
sincronización.

## Seguridad y privacidad

**Qué guarda y dónde.** Todo está en el almacenamiento privado de la app, que otras apps no
pueden leer:

| Dato | Dónde | Protección |
|---|---|---|
| Eventos, calendarios y cambios pendientes | Base SQLite `calendar.db` | Aislamiento de apps de Android (sin cifrado propio) |
| Ajustes | `settings.json` | Aislamiento de apps |
| Contraseña de CalDAV | Carpeta `no_backup` | AES-256-GCM con una clave del Android Keystore (en StrongBox si el celu lo tiene), que no se puede extraer |

No se hace copia de seguridad en la nube ni se pasan los datos a otro celu
(`allowBackup="false"`).

**Qué no hace**
- Sin publicidad, analíticas, reportes de fallos ni telemetría.
- Sin Google Play Services ni ningún otro servicio de Google.
- Sin permisos de almacenamiento: los archivos se abren y se guardan solo con el selector del
  sistema.
- Sin conexiones aparte de tu servidor y las suscripciones que agregues.

**Conexiones**
- Solo HTTPS, verificado con las autoridades de certificación del sistema. Sin fijar
  certificados (los de Let's Encrypt cambian cada pocos meses) y sin forma de desactivar la
  verificación.
- Las redirecciones se siguen solo dentro del mismo servidor, y nunca hacia `http`.
- **Contraseña incorrecta (401)**: el primer `401` corta todas las peticiones, en primer y segundo
  plano, y la cuenta espera hasta que vuelvas a escribir la contraseña.
- Servidor ocupado (`429`/`503`): la app espera lo que diga `Retry-After`, o cada vez más.
- Las **suscripciones** se bajan sin cookies, credenciales ni cabeceras que te identifiquen, y con
  un User-Agent genérico. Cada descarga le muestra tu IP al servidor del feed; la app lo avisa
  junto a cada suscripción. Si un feed se muda a otro servidor, la app pregunta antes de seguirlo.

Los **registros** tienen solo nombres de eventos fijos y tipos de error: nunca URLs (tampoco las
de las suscripciones), usuarios, cabeceras ni contenido de eventos.

**Exportar**: un archivo `.ics` no va cifrado. Cualquiera que lo tenga puede leer esos eventos;
la app lo avisa cada vez.

## Permisos

| Permiso | Para qué |
|---|---|
| `INTERNET` | Hablar con tu servidor CalDAV y con tus suscripciones. |
| `ACCESS_NETWORK_STATE` | Sincronizar en segundo plano solo cuando hay conexión. |
| `WAKE_LOCK` | Mantener el celu despierto mientras corre una sincronización (WorkManager). |
| `RECEIVE_BOOT_COMPLETED` | Reprogramar recordatorios y la sincronización después de reiniciar. |
| `POST_NOTIFICATIONS` | Mostrar recordatorios. Se pide la primera vez que agregás uno. |
| `SCHEDULE_EXACT_ALARM` | Opcional: recordatorios en el minuto exacto. Se concede en los ajustes del sistema; sin él, Android puede demorarlos unos minutos. |

`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` lo agrega AndroidX y es interno de la app.

## Dependencias

Las versiones están fijadas en `gradle/libs.versions.toml`. Todas son compatibles con GPL-3.0.

| Librería | Versión | Licencia | Para qué |
|---|---|---|---|
| AndroidX (Core, Activity, Lifecycle, Compose BOM 2025.10.01, Material 3) | ver `libs.versions.toml` | Apache-2.0 | Interfaz |
| AndroidX WorkManager (y Room, que usa) | 2.10.5 | Apache-2.0 | Sincronización en segundo plano |
| Kotlin stdlib, kotlinx-coroutines, kotlinx-serialization | 2.2.21, 1.8.1, 1.8.1 | Apache-2.0 | Lenguaje y archivo de ajustes |
| Backdrop | 1.0.0 | Apache-2.0 | Efectos de cristal líquido |
| OkHttp + Okio | 5.3.2 / 3.16.4 | Apache-2.0 | HTTP al servidor CalDAV y a las suscripciones |
| ical4j | 4.4.0 | BSD-3-Clause | Lectura de iCalendar y repeticiones |
| commons-lang3, commons-codec | 3.18.0, 1.17.0 | Apache-2.0 | Las pide ical4j |
| threeten-extra | 1.8.0 | BSD-3-Clause | La pide ical4j |
| slf4j-api | 2.0.12 | MIT | La pide ical4j (sin backend: no registra nada) |
| JetBrains annotations, JSpecify, ListenableFuture | 23.0.0, 1.0.0, 1.0 | Apache-2.0 | Anotaciones que usan las anteriores |

La lista completa con los avisos de copyright está en [docs/THIRD-PARTY.md](docs/THIRD-PARTY.md).

**OkHttp está fijado en 5.3.2 a propósito.** Desde la 5.4, su artefacto de Android pide
`compileSdk` 36 (la 5.5 pide 37), y AGP 8.9.3, compartido con Liquid Clock y Liquid Gallery, no
compila contra esas versiones. Hay que subir OkHttp junto con AGP y `compileSdk`. Mientras tanto,
**antes de cada release hay que revisar que 5.3.2 (y Okio) no tengan vulnerabilidades
conocidas**, por ejemplo en [osv.dev](https://osv.dev/list?ecosystem=Maven&q=com.squareup.okhttp3)
y en el [changelog](https://square.github.io/okhttp/changelogs/changelog/) de OkHttp.

ical4j nunca descarga zonas horarias: está desactivado en `ical4j.properties`, y R8 quita ese
código de la release.

## Limitaciones conocidas

- Las tareas (VTODO) no se muestran ni se editan.
- No hay suscripciones a feeds que pidan usuario y contraseña.
- Importar nunca pisa un evento que ya existe (mismo UID): se saltea.
- No se puede mover un evento a otro calendario.
- Una sola cuenta CalDAV.
- La interfaz está en inglés y en español.

La lista completa y al día está en [docs/LIMITACIONES.md](docs/LIMITACIONES.md).

## Compilar

Requisitos: JDK 17 y el SDK de Android (plataforma 35 y build-tools).

```bash
echo "sdk.dir=/opt/android-sdk" > local.properties
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleRelease
```

`assembleRelease` genera un APK minificado. Se firma con la clave descrita en
`~/.android-keys/liquid-calendar.properties` (o la ruta de `-PsigningProps=...` o de la variable
`LIQUID_CALENDAR_SIGNING`); si ese archivo no existe, se usa la clave de debug. La clave y sus
contraseñas nunca van al repositorio:

```properties
storeFile=/ruta/a/la/clave.jks
storePassword=...
keyAlias=...
keyPassword=...
```

La compilación debug se instala como `dev.liquidcalendar.debug`, junto a la release. Solo las
compilaciones debug y `profile` aceptan `http` hacia el propio celu (127.0.0.1), para probar contra
un Radicale local reenviado con `adb reverse`; la release nunca.

Tests y lint:

```bash
./gradlew :core:test :app:lintDebug
scripts/radicale-test.sh start && ./gradlew :core:integrationTest   # Radicale local con uv
```

`core` es Kotlin puro, sin dependencias de Android (validación de URLs, CalDAV, iCalendar, cola
de cambios), así que sus tests corren en la JVM.

Antes de cada commit, `scripts/check-secrets.sh` busca dominios, rutas personales, claves y
contraseñas en los archivos preparados (`--history` revisa todos los commits). Los patrones
personales van en `.secret-patterns`, que git ignora.

## Estructura del proyecto

```
core/src/main/kotlin/dev/liquidcalendar/core/
├── dav/        Cliente CalDAV/WebDAV y lectura segura de XML
├── ics/        Lectura y escritura de iCalendar, series, zonas horarias, importar/exportar
├── feed/       Suscripciones webcal/ICS
└── sync/       Cola de cambios, reintentos, freno de contraseñas
app/src/main/java/dev/liquidcalendar/
├── data/       Caché SQLite, ajustes, contraseña cifrada con el Keystore
├── sync/       Motor de sincronización, WorkManager, operaciones de calendarios, suscripciones
├── notify/     Recordatorios
├── widget/     Widget de agenda
├── ui/glass/   Componentes de cristal líquido
└── ui/screens/ Pantallas
```

## Licencia

[GPL-3.0](LICENSE)
