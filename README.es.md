# Liquid Calendar

[English](README.md) | **Español**

Se instala en el celu como **Calendario** (o *Calendar*, según el idioma).

Calendario CalDAV libre para Android, pensado para un servidor propio como
[Radicale](https://radicale.org). Tiene la misma interfaz de cristal líquido que
[Liquid Clock](https://github.com/mrsalvaa/liquid-clock) y
[Liquid Gallery](https://github.com/mrsalvaa/liquid-gallery). Licencia GPL-3.0.

> En desarrollo: todavía no hay ninguna versión publicada. Por ahora la app tiene la interfaz y los
> ajustes de apariencia; la cuenta CalDAV, las vistas y la sincronización vienen después.

## Privacidad

- Sin publicidad, analíticas, reportes de fallos ni telemetría, y sin Google Play Services.
- La app solo se conecta a tu servidor y a las suscripciones de calendario que agregues vos.
- Solo HTTPS, verificando el certificado como corresponde. `http://` va a requerir activarlo a
  propósito.
- No se hace copia de seguridad en la nube ni se pasan los datos a otro celu.

## Compilar

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleRelease
```

Tests y lint:

```bash
./gradlew :core:test :app:lintDebug
```

`core` es Kotlin puro, sin dependencias de Android (validación de la URL del servidor, borrado de
secretos en los logs y, más adelante, CalDAV e iCalendar), así que sus tests corren en la JVM.

Antes de cada commit, `scripts/check-secrets.sh` busca dominios, rutas personales, claves y
contraseñas en los archivos preparados. Los patrones personales van en `.secret-patterns`, que git
ignora.
