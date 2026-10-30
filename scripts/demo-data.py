"""Datos de prueba inventados para el Radicale local (capturas del README y pruebas en el celu).

Solo habla con 127.0.0.1 y con el usuario de prueba de test/radicale/make-users.py.
Uso: scripts/radicale-test.sh reset && scripts/radicale-test.sh start && python3 -I scripts/demo-data.py
"""
import base64
import datetime as dt
import os
import urllib.request

BASE = "http://127.0.0.1:5232/"
USER = os.environ.get("RADICALE_TEST_USER", "test")
PASSWORD = os.environ.get("RADICALE_TEST_PASSWORD", "local-test-pass")  # secret-check: ignore
AUTH = "Basic " + base64.b64encode(f"{USER}:{PASSWORD}".encode()).decode()
TZ = "America/Argentina/Buenos_Aires"
VTIMEZONE = [
    "BEGIN:VTIMEZONE", f"TZID:{TZ}", "BEGIN:STANDARD", "DTSTART:19700101T000000",
    "TZOFFSETFROM:-0300", "TZOFFSETTO:-0300", "TZNAME:-03", "END:STANDARD", "END:VTIMEZONE",
]


def request(method, path, body=None, content_type="application/xml"):
    req = urllib.request.Request(BASE + path, data=body.encode() if body else None, method=method)
    req.add_header("Authorization", AUTH)
    if body:
        req.add_header("Content-Type", content_type)
    with urllib.request.urlopen(req) as r:
        return r.status


def calendar(slug, name, color):
    body = (
        '<?xml version="1.0"?><C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" '
        'xmlns:I="http://apple.com/ns/ical/"><D:set><D:prop>'
        f"<D:displayname>{name}</D:displayname><I:calendar-color>{color}</I:calendar-color>"
        '<C:supported-calendar-component-set><C:comp name="VEVENT"/></C:supported-calendar-component-set>'
        "</D:prop></D:set></C:mkcalendar>"
    )
    print("MKCALENDAR", slug, request("MKCALENDAR", f"{USER}/{slug}/", body))


def event(slug, name, lines):
    ics = "\r\n".join(["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Datos de prueba//ES"] + VTIMEZONE + lines + ["END:VCALENDAR", ""])
    print("PUT", f"{slug}/{name}", request("PUT", f"{USER}/{slug}/{name}.ics", ics, "text/calendar; charset=utf-8"))


def local(d, h, m=0):
    return f"TZID={TZ}:{d:%Y%m%d}T{h:02d}{m:02d}00"


today = dt.date.today()
monday = today + dt.timedelta(days=(7 - today.weekday()) % 7 or 7)  # el lunes que viene
stamp = "DTSTAMP:20261001T120000Z"

calendar("trabajo", "Trabajo", "#F97316FF")
calendar("facultad", "Facultad", "#8B5CF6FF")

# Serie con un recordatorio "de otro cliente" (para probar que se conserve).
event("facultad", "clase-analisis", [
    "BEGIN:VEVENT", "UID:clase-analisis@datos-de-prueba", stamp,
    f"DTSTART;{local(monday, 18)}", f"DTEND;{local(monday, 20)}",
    "RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10", "SUMMARY:Clase de Análisis", "LOCATION:Aula 12",
    "X-OTRO-CLIENTE:conservar",
    "BEGIN:VALARM", "TRIGGER:-PT15M", "ACTION:DISPLAY", "DESCRIPTION:Clase de Análisis", "X-WR-ALARMUID:alarma-de-otro-cliente", "END:VALARM",
    "END:VEVENT",
])
event("facultad", "parcial-fisica", [
    "BEGIN:VEVENT", "UID:parcial-fisica@datos-de-prueba", stamp,
    f"DTSTART;{local(monday + dt.timedelta(days=9), 9)}", f"DTEND;{local(monday + dt.timedelta(days=9), 12)}",
    "SUMMARY:Parcial de Física", "LOCATION:Aula Magna",
    "BEGIN:VALARM", "TRIGGER:-P1D", "ACTION:DISPLAY", "DESCRIPTION:Parcial de Física", "END:VALARM",
    "END:VEVENT",
])
event("trabajo", "reunion-equipo", [
    "BEGIN:VEVENT", "UID:reunion-equipo@datos-de-prueba", stamp,
    f"DTSTART;{local(monday + dt.timedelta(days=1), 10)}", f"DTEND;{local(monday + dt.timedelta(days=1), 10, 30)}",
    "RRULE:FREQ=WEEKLY;BYDAY=TU", "SUMMARY:Reunión de equipo", "END:VEVENT",
])
event("trabajo", "entrega-informe", [
    "BEGIN:VEVENT", "UID:entrega-informe@datos-de-prueba", stamp,
    f"DTSTART;VALUE=DATE:{monday + dt.timedelta(days=4):%Y%m%d}", f"DTEND;VALUE=DATE:{monday + dt.timedelta(days=5):%Y%m%d}",
    "SUMMARY:Entrega del informe", "END:VEVENT",
])
event("trabajo", "almuerzo", [
    "BEGIN:VEVENT", "UID:almuerzo@datos-de-prueba", stamp,
    f"DTSTART;{local(monday + dt.timedelta(days=3), 13)}", f"DTEND;{local(monday + dt.timedelta(days=3), 14)}",
    "SUMMARY:Almuerzo con Laura", "LOCATION:Café del centro", "END:VEVENT",
])
