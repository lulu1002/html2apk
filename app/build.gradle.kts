plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.wgtunnel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.wgtunnel"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Assinado com a chave de debug para que o APK de release seja instalável direto.
            // Para publicar de verdade, troque por uma keystore sua.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Backend oficial do WireGuard (wireguard-go embutido) para Android
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
