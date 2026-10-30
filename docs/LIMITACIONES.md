# Limitaciones conocidas

Lo que todavía no hace la app o hace de forma parcial. Se actualiza en cada etapa.

## Resueltas en la etapa 6

- Calendarios en el servidor: crear (MKCALENDAR con nombre, color y "eventos" o "eventos y
  tareas"), renombrar y recolorear (PROPPATCH) y borrar (DELETE). Cada calendario se identifica
  por su href; el slug de uno nuevo es un UUID, así que renombrarlo no cambia nada más.
- Servidores sin MKCALENDAR (405, 415, 501 o 403): mensaje claro, sin crear nada local.
- Borrar un calendario pide escribir su nombre exacto, muestra cuántos eventos se pierden y si
  se borra también del servidor, y ofrece "Exportar primero".
- Un calendario que desaparece del servidor no se borra: se marca y se pregunta si conservarlo en
  el dispositivo, exportarlo o descartarlo (esto último, con la misma confirmación fuerte).
- Suscripciones webcal/ICS: solo en el dispositivo y de solo lectura; nombre, color e intervalo
  editables (1 h, 6 h, 12 h, cada día, cada semana o solo a mano; por defecto 12 h).
  webcal:// pasa a https://; http://, credenciales en la URL y bajadas a http se rechazan;
  un cambio de servidor se muestra y se pide confirmar antes de contactarlo. Máximo 5 MB y
  30 s; ETag, If-Modified-Since y Retry-After; espera exponencial (15 min a 24 h). Sin cookies,
  credenciales ni caché, con un User-Agent genérico. La URL nunca va a los registros.
- Importar .ics con el selector del sistema (sin permisos): vista previa, destino con su origen,
  máximo 5 MB y 5000 eventos, ninguna URL del archivo se visita, UID repetidos se saltean.
- Exportar un calendario o un evento con el selector del sistema, siempre con el aviso de que el
  archivo no va cifrado. El nombre sale del calendario o del evento y la fecha (solo ASCII).

## Probado en el celu (etapa 6)

Solo con la .debug, el Radicale local y datos inventados; la .debug se desinstaló al final.

- Crear "Gimnasio" en el servidor (eventos y tareas): Radicale lo guardó con slug UUID,
  `VEVENT,VTODO` y el color elegido.
- Renombrar a "Gimnasio y pileta" y cambiar el color: PROPPATCH aplicado; al sincronizar, el
  mismo calendario (sin duplicados).
- Borrar "facultad" con curl desde afuera: la app avisó, exportó el .ics y lo conservó como local
  con sus 2 eventos; la siguiente sincronización no lo volvió a subir.
- Suscripción a un feed local: 5 eventos con una sola descarga; "Actualizar ahora" mandó la
  petición condicional y el servidor respondió 304.
- Feed que redirige a otro host: la app preguntó antes de contactarlo y, al aceptar, lo siguió.
- Importar un .ics con 3 eventos válidos, uno sin UID, una serie, URL y ATTACH: 3 importados en
  un calendario sincronizado y subidos; reimportar mostró "3 ya están" y no permitió importar.
- Borrar "Gimnasio y pileta": el botón se habilitó solo con el nombre exacto; se borró del
  servidor. Con otro nombre escrito, el botón siguió deshabilitado.
- Exportar un evento: nombre de archivo `Turno-dentista-importado-2026-10-30.ics`.

## Resueltas en la etapa 5

- Las series se editan y se borran (solo esta, esta y las siguientes, todas), con resumen previo.
- Los eventos con hora se escriben con TZID y su VTIMEZONE (antes, en UTC).
- Las zonas que existen solo dentro del archivo (TZID no IANA) se resuelven también en Android.
- Un evento flotante editado sigue flotante.
- Recordatorios: se leen, se muestran, se crean y se editan, con notificaciones locales.

## Probado en el celu (etapa 5)

Galaxy S24 FE, Android 16, contra Radicale 3.8.3 local con datos de prueba.

- Serie de 10 clases: "solo esta" (título), "esta y las siguientes" (título), "todas" (ubicación)
  y borrar "solo esta". En el servidor quedaron dos archivos: la serie original cortada con UNTIL
  (con el EXDATE, la excepción y la ubicación nueva) y la serie nueva con COUNT=6. Sin duplicados
  ni huérfanos; el VALARM y la propiedad X- de "otro cliente" se conservaron en las dos.
- Recordatorio inexacto: avisó dentro de la ventana que dio Android (unos 60 s).
- Recordatorio con "Hora exacta": alarma exacta (ventana 0); avisó a los 0,85 s.
- Pantalla bloqueada: la notificación es privada; la versión pública dice solo "Recordatorio de
  un evento".
- Reinicio: la alarma se reprogramó sola al desbloquear (sin abrir la app) y el aviso sonó.
- Cambio de zona a Europe/Madrid: la alarma siguió en el mismo instante (17:45 en Buenos Aires =
  21:45 en Madrid). La zona se volvió a dejar como estaba.

## Abiertas

| # | Limitación | Plan |
|---|---|---|
| 1 | Con "todas", mover la serie a otro día de la semana corre el DTSTART pero no cambia el BYDAY de la regla: en una serie "todos los lunes", las ocurrencias siguen siendo los lunes (cambia la hora, no el día). | Pendiente |
| 2 | Una serie no puede pasar de todo el día a con horario (ni al revés). | Pendiente |
| 3 | Con "todas", los cambios de título, ubicación o descripción van al evento principal; las excepciones ya existentes conservan los suyos. | Por diseño (como otros clientes) |
| 4 | No se interpreta RANGE=THISANDFUTURE en el RECURRENCE-ID de excepciones creadas por otros clientes. | Pendiente |
| 5 | Los recordatorios suenan solo para los calendarios visibles. | Por diseño |
| 6 | Los recordatorios que crea la app son de tipo aviso (ACTION:DISPLAY). Los de sonido o correo de otros clientes se conservan en el archivo, pero en el dispositivo se avisan como una notificación común. | Pendiente |
| 7 | Sin "Hora exacta" (permiso que el usuario concede en los ajustes del sistema), Android puede demorar un recordatorio unos minutos. | Por diseño |
| 8 | No se puede mover un evento a otro calendario. | Pendiente |
| 9 | El selector de hora va de 5 en 5 minutos (si se abre sobre las 10:07, redondea a 10:05; si no se abre, no cambia). | Pendiente |
| 10 | Los archivos de ejemplo de DAVx5 y Odysseus de los tests son reconstruidos a partir del formato típico, no capturados de esos clientes. | Sumar ejemplos reales |
| 11 | Las tareas (VTODO) no se muestran ni se editan: "eventos y tareas" solo crea el calendario con ese tipo. | Pendiente |
| 12 | Importar no actualiza eventos que ya existen (mismo UID): se saltean, nunca se pisan. | Por diseño |
| 13 | Un .ics importado con zonas propias (TZID no IANA) conserva su VTIMEZONE, pero la descarga de zonas desde la web sigue apagada. | Por diseño |
| 14 | Las suscripciones a feeds que piden usuario y contraseña no están soportadas (no se mandan credenciales). | Por diseño |
| 15 | Si un feed redirige a otro servidor durante una actualización automática, la suscripción queda en error hasta que se abra y se acepte con "Actualizar ahora". | Por diseño |
| 16 | El aviso de IP de cada suscripción se muestra en su pantalla; en la lista hay uno general. | Por diseño |
| 17 | Al exportar un evento de una serie se exporta la serie entera (con sus excepciones). | Por diseño |
