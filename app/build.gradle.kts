import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Clave de release fuera del repo. Ruta configurable con -PsigningProps=... o la variable
// de entorno LIQUID_CALENDAR_SIGNING; si no existe, la release se firma con la clave de debug.
val signingProps = (findProperty("signingProps") as String?)
    ?: System.getenv("LIQUID_CALENDAR_SIGNING")
    ?: "${System.getProperty("user.home")}/.android-keys/liquid-calendar.properties"
val releaseSigning = file(signingProps).takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use(::load) }
}

android {
    namespace = "dev.liquidcalendar"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.liquidcalendar"
        minSdk = 31
        targetSdk = 35
        versionCode = 6
        versionName = "1.4.0"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "LOCALHOST_HTTP", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // La release nunca habla http, ni siquiera con el propio celu.
            buildConfigField("boolean", "LOCALHOST_HTTP", "false")
        }
        // Como la release (optimizada, sin depuración) pero instalable junto a ella como
        // dev.liquidcalendar.debug: para probar en el celu con el rendimiento real.
        create("profile") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-profile"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "LOCALHOST_HTTP", "true")
        }
    }

    // Debug y profile permiten http solo hacia 127.0.0.1/localhost (Radicale local por adb reverse).
    sourceSets["debug"].res.srcDir("src/localTest/res")
    sourceSets["profile"].res.srcDir("src/localTest/res")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkDependencies = true
        // Avisos de "hay una versión nueva": las versiones (y targetSdk, igual que en Liquid Clock y
        // Liquid Gallery) se suben a mano y a propósito.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    // Sin blob de metadatos de dependencias (requisito de F-Droid).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.backdrop)
    implementation(libs.work.runtime)
}
