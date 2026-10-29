# kotlinx.serialization trae sus propias reglas; mantenemos los modelos por claridad.
-keep,includedescriptorclasses class dev.liquidcalendar.data.**$$serializer { *; }
-keepclassmembers class dev.liquidcalendar.data.** {
    *** Companion;
}
