import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.openproxy.client"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openproxy.client"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Go shared objects must stay uncompressed and loadable from the APK.
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/*.kotlin_module",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.register("downloadLibbox") {
    val dest = file("libs/libbox.aar")
    outputs.file(dest)
    doLast {
        if (!dest.exists()) {
            dest.parentFile.mkdirs()
            println("Downloading libbox.aar...")
            java.net.URI("https://github.com/Vauth/sing-box/releases/download/v1.0.0-masque/libbox.aar")
                .toURL()
                .openStream()
                .use { input ->
                    java.nio.file.Files.copy(input, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            println("libbox.aar downloaded successfully")
        }
    }
}
tasks.matching { it.name.startsWith("preBuild") }.configureEach {
    dependsOn("downloadLibbox")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(files("libs/libbox.aar"))

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
