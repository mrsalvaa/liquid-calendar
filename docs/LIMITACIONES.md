# Limitaciones conocidas

Lo que todavía no hace la app o hace de forma parcial. Se actualiza en cada etapa.

## Abiertas después de la etapa 4

| # | Limitación | Plan |
|---|---|---|
| 1 | Las series (RRULE/RDATE o con excepciones) son de solo lectura: no se editan ni se borran. | Etapa 5 |
| 2 | Los eventos con hora nuevos se guardan en UTC, sin TZID. Es correcto (no cambian de hora), pero otros clientes no ven la zona de origen. | Etapa 5: TZID + VTIMEZONE |
| 3 | Una zona que existe solo dentro de la VTIMEZONE del archivo (TZID no IANA) no se resuelve en Android: ical4j no puede registrar zonas propias allí. | Etapa 5 |
| 4 | Un evento con hora flotante (sin zona) que se edita cambiando el horario pasa a guardarse en UTC. Si no se toca el horario, queda flotante. | Revisar en etapa 5 |
| 5 | No se puede mover un evento a otro calendario; al editar, el calendario se muestra pero no se cambia. | Pendiente |
| 6 | El selector de hora va de 5 en 5 minutos: si se abre sobre un evento a las 10:07, se redondea a 10:05 (si no se abre, no cambia). | Pendiente |
| 7 | Los archivos de ejemplo de DAVx5 y Odysseus de los tests son reconstruidos a partir del formato típico, no capturados de esos clientes. | Sumar ejemplos reales |
| 8 | No hay recordatorios (VALARM se conservan pero no se muestran ni avisan). | Etapa 5 |
| 9 | No se pueden crear, renombrar ni borrar calendarios en el servidor, ni importar/exportar .ics ni suscripciones. | Etapa 6 |
