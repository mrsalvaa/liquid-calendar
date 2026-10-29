import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Núcleo sin dependencias de Android (CalDAV, iCalendar, sincronización): se prueba en la JVM y
// podría reutilizarse en una versión de escritorio.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.kotlin.test)
}
