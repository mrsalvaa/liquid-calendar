# kotlinx.serialization trae sus propias reglas; mantenemos los modelos por claridad.
-keep,includedescriptorclasses class dev.liquidcalendar.data.**$$serializer { *; }
-keepclassmembers class dev.liquidcalendar.data.** {
    *** Companion;
}

# ical4j: fábricas de componentes, propiedades y parámetros que carga por ServiceLoader, y la caché
# de zonas horarias que se elige en ical4j.properties.
-keep class * implements net.fortuna.ical4j.model.ComponentFactory { <init>(); }
-keep class * implements net.fortuna.ical4j.model.PropertyFactory { <init>(); }
-keep class * implements net.fortuna.ical4j.model.ParameterFactory { <init>(); }
-keep class net.fortuna.ical4j.util.MapTimeZoneCache { <init>(); }

# Nunca descargar zonas horarias: además de desactivarlo en ical4j.properties, R8 asume que la
# actualización está apagada y elimina ese código del APK.
-assumevalues class net.fortuna.ical4j.model.TimeZoneUpdater {
    public boolean isEnabled() return false;
}

# ical4j intenta registrar un java.time.zone.ZoneRulesProvider, que Android no expone: lo envuelve
# en try/catch (LinkageError) y usa las zonas del sistema. threeten-extra referencia anotaciones
# opcionales de Joda-Convert que no hacen falta.
-dontwarn java.time.zone.ZoneRulesProvider
-dontwarn org.joda.convert.**
