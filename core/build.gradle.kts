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

// Pruebas contra un Radicale local (scripts/radicale-test.sh start), aparte de las unitarias.
val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
    // Los mismos .ics de ejemplo que las pruebas unitarias.
    resources.srcDir("src/test/resources")
}
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    implementation(libs.okhttp)
    implementation(libs.ical4j)
    // XmlPullParser: Android ya lo trae; en la JVM (tests) lo da kxml2.
    compileOnly(libs.kxml2)
    testImplementation(libs.kxml2)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.register<Test>("integrationTest") {
    description = "Pruebas de integración contra el Radicale local (127.0.0.1:5232)."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    // Siempre se vuelven a ejecutar: dependen del servidor, no solo del código.
    outputs.upToDateWhen { false }
}
