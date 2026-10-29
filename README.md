# Liquid Calendar

**English** | [Español](README.es.md)

Installs on the phone as **Calendar** (*Calendario* in Spanish).

A free CalDAV calendar for Android, made for a self-hosted server such as
[Radicale](https://radicale.org). Same liquid-glass interface as
[Liquid Clock](https://github.com/mrsalvaa/liquid-clock) and
[Liquid Gallery](https://github.com/mrsalvaa/liquid-gallery). Licensed under GPL-3.0.

> Work in progress: there's no release yet. Right now the app has the interface and the
> appearance settings; the CalDAV account, views and sync come next.

## Privacy

- No ads, analytics, crash reporting or telemetry, and no Google Play Services.
- The app only talks to your server and to the calendar subscriptions you add yourself.
- HTTPS only, with normal certificate checks. Plain `http://` will need an explicit opt-in.
- Nothing is backed up to the cloud or copied to a new phone.

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
