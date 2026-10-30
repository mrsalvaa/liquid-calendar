# Limitaciones conocidas

Lo que todavía no hace la app o hace de forma parcial. Se actualiza en cada etapa.

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
| 11 | No se pueden crear, renombrar ni borrar calendarios en el servidor, ni importar/exportar .ics ni suscripciones. | Etapa 6 |
