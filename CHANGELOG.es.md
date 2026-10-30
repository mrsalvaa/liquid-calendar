# Registro de cambios

[English](CHANGELOG.md) | **Español**

## Sin publicar

### Novedades
- Estilo de interfaz: además de Liquid Glass (el predeterminado, con todas sus opciones de siempre) está "Sólido", con tarjetas opacas sobre fondo liso y sin efectos de cristal. Es más sobrio: esquinas menos redondeadas, botones rectangulares, íconos de líneas y barra inferior acoplada. Tiene tema claro u oscuro y su propio color de énfasis.
- Al conectar el servidor se le puede poner un nombre. Se muestra en lugar de la dirección, que puede ser larga.

### Mejoras
- En la vista de día, si un evento queda muy bajo (con las horas contraídas), la hora va a la derecha del título en vez de abajo.

## 1.1.1

### Mejoras
- Pellizcar en semana y día ya no salta cuando los dedos se cruzan: se queda en el tamaño mínimo. La altura sigue la distancia entre los dedos desde el comienzo del gesto.
- Con las horas contraídas, la columna muestra una hora por medio (00, 02, 04…); las demás aparecen al agrandar.
- Los números de las horas quedan centrados sobre su línea.

### Cambios
- Los títulos de semana y día ya no muestran el año: "26/10 – 01/11" y "Viernes 30/10". El de mes no cambia.

## 1.1.0

### Novedades
- Widget de agenda para la pantalla de inicio: hoy y los 13 días siguientes, agrupados por día, con la estética de la app (tema claro u oscuro, color de énfasis). Tocar un evento lo abre, y "+" crea uno para hoy.

## 1.0.0

Primera versión.

### Novedades
- Vistas de mes, semana, día y agenda. En semana y día, pellizcar cambia la altura de las horas.
- Calendarios que viven solo en el dispositivo y, opcional, una cuenta CalDAV (probado con Radicale 3.8).
- Crear, editar y borrar eventos. En una serie: solo este, este y los siguientes, o todos, con un resumen antes de confirmar.
- Recordatorios como notificaciones locales, privados en la pantalla de bloqueo por defecto. Hora exacta opcional.
- Funciona sin conexión: los cambios esperan en una cola y nunca pisan una versión más nueva del servidor. Ante un conflicto se ven las dos versiones y se elige.
- Crear, renombrar, recolorear y borrar calendarios en el servidor. Para borrar hay que escribir el nombre del calendario, y se ofrece exportarlo antes.
- Si un calendario desaparece del servidor no se borra nada: se elige conservarlo en el dispositivo, exportarlo o descartarlo.
- Suscripciones webcal/ICS de solo lectura que quedan en el dispositivo, actualizadas cada 1 h, 6 h, 12 h, día o semana, o solo a mano.
- Importar y exportar archivos `.ics` (un calendario entero o un evento) con el selector de archivos del sistema.
- Interfaz de cristal líquido compartida con Liquid Clock y Liquid Gallery: fondo liso, en movimiento o de color de énfasis, tema claro y oscuro, e íconos alternativos.

### Seguridad y privacidad
- Solo HTTPS, con las autoridades de certificación del sistema. Los certificados instalados por el usuario no se aceptan.
- La contraseña se cifra con una clave guardada en el Android Keystore.
- La primera contraseña incorrecta (401) corta todas las peticiones, en primer y segundo plano, hasta que la vuelvas a escribir.
- Sin telemetría, sin servicios de Google, sin permisos de almacenamiento y sin copias de seguridad.
