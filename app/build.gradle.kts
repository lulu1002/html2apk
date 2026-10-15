plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// versionCode sobe a cada execução do GitHub Actions (necessário para atualizar o app)
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.vpsmanager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vpsmanager"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "0.1.$runNumber"
    }

    // Chave FIXA (keystore/app.jks): sem ela cada build sai com uma assinatura diferente
    // e o Android não aceita instalar por cima do app anterior.
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("keystore/app.jks")
            storePassword = "vpsmanager-fixed-key"
            keyAlias = "app"
            keyPassword = "vpsmanager-fixed-key"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/**"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.mwiede:jsch:0.2.20")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
}
