# Liquid Calendar

**English** | [Español](README.es.md)

Installs on the phone as **Calendar** (*Calendario* in Spanish).

A free CalDAV calendar for Android, made for a self-hosted server such as
[Radicale](https://radicale.org). Same liquid-glass interface as
[Liquid Clock](https://github.com/mrsalvaa/liquid-clock) and
[Liquid Gallery](https://github.com/mrsalvaa/liquid-gallery). Licensed under GPL-3.0.

<p align="center">
  <img src="docs/screenshots/mes.png" width="200" alt="Month">
  <img src="docs/screenshots/semana.png" width="200" alt="Week">
  <img src="docs/screenshots/agenda.png" width="200" alt="Agenda">
  <img src="docs/screenshots/detalle-serie.png" width="200" alt="Event in a series">
</p>
<p align="center">
  <img src="docs/screenshots/resumen-serie.png" width="200" alt="Summary before changing a series">
  <img src="docs/screenshots/calendarios.png" width="200" alt="Calendars">
  <img src="docs/screenshots/ajustes-recordatorios.png" width="200" alt="Reminder settings">
</p>

> Work in progress: there's no release yet. Screenshots show test data (`scripts/demo-data.py`)
> on a local Radicale.

## What it does

- Month, week, day and agenda views; in week and day, pinch to change the height of the hours.
- Calendars stored on the device (never uploaded) and, optionally, a CalDAV account.
- Create, edit and delete events; in a series, this one, this and following, or all of them,
  with a summary before confirming.
- Reminders as local notifications. By default they don't show the event on the lock screen.
- Works offline: changes wait in a queue and never overwrite a newer version on the server.

Known gaps are listed (in Spanish) in [docs/LIMITACIONES.md](docs/LIMITACIONES.md).

## Privacy

- No ads, analytics, crash reporting or telemetry, and no Google Play Services.
- The app only talks to your server and to the calendar subscriptions you add yourself.
- HTTPS only, with normal certificate checks. Plain `http://` will need an explicit opt-in.
- Nothing is backed up to the cloud or copied to a new phone.

## Dependencies

Versions are pinned in `gradle/libs.versions.toml`. Besides AndroidX/Compose and
[Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) (same as the other Liquid apps), `core` uses:

| Library | Version | License | What for |
|---|---|---|---|
| OkHttp + Okio | 5.3.2 / 3.16.4 | Apache-2.0 | HTTP to the CalDAV server |
| ical4j | 4.4.0 | BSD-3-Clause | iCalendar parsing and recurrences |
| commons-lang3, commons-codec | 3.18.0, 1.17.0 | Apache-2.0 | required by ical4j |
| threeten-extra | 1.8.0 | BSD-3-Clause | required by ical4j |
| slf4j-api | 2.0.12 | MIT | required by ical4j (no backend: logs nothing) |

**OkHttp is held at 5.3.2 on purpose.** From 5.4 on, its Android artifact requires
`compileSdk` 36 (5.5 requires 37), and AGP 8.9.3, shared with Liquid Clock and Liquid Gallery,
can't build against those. Move to a newer OkHttp together with the AGP/`compileSdk` upgrade.
Until then, **before every release check that 5.3.2 (and Okio) have no known vulnerabilities**,
for example on [osv.dev](https://osv.dev/list?ecosystem=Maven&q=com.squareup.okhttp3) and
OkHttp's [changelog](https://square.github.io/okhttp/changelogs/changelog/).

ical4j never downloads time zones: it's disabled in `ical4j.properties`, and R8 removes that code
from the release build.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleRelease
```

Tests and lint:

```bash
./gradlew :core:test :app:lintDebug
```

`core` is plain Kotlin with no Android dependencies (server URL checks, log redaction, and
later CalDAV and iCalendar), so its tests run on the JVM.

Before each commit, `scripts/check-secrets.sh` looks for domains, home paths, keys and passwords
in the staged files. Personal patterns go in `.secret-patterns`, which git ignores.
