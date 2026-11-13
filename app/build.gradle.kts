import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Имя и версия — из version.properties (одно место для правки)
val appVersion = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val appName: String = appVersion.getProperty("appName")
val appVersionName: String = appVersion.getProperty("versionName")
val appVersionCode: Int = appVersion.getProperty("versionCode").toInt()

android {
    namespace = "com.vhscam.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vhscam.app"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        resValue("string", "app_name", appName)
        buildConfigField("String", "APP_NAME", "\"$appName\"")
    }

    // Подпись release: свой ключ из keystore.properties (лежит вне git, см. README), иначе — debug-ключ Android SDK
    // (~/.android/debug.keystore, у каждого свой). Смена ключа требует переустановки приложения.
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        val ks = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(ks.getProperty("storeFile"))
                storePassword = ks.getProperty("storePassword")
                keyAlias = ks.getProperty("keyAlias")
                keyPassword = ks.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

// Готовый release-APK (без отладочных подмен из src/debug) сразу копируется в output/ под именем
// Mylar-Cam-<версия>.apk; старые файлы не трогаем. Debug-сборка остаётся для проверок.
val copyApkToOutput by tasks.registering(Copy::class) {
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("output"))
    rename { "${appName.replace(' ', '-')}-$appVersionName.apk" }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(copyApkToOutput) }

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.video)
    implementation(libs.camera.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // Понадобятся на следующих этапах (VHS-эффекты и экспорт)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
}
