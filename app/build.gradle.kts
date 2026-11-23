plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.loes.compras"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.loes.compras"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Assinatura fixa vinda dos segredos do GitHub (KEYSTORE_B64 / KEYSTORE_PASSWORD).
    // Com a mesma chave em todo build, a versão nova instala POR CIMA da antiga sem apagar dados.
    val ksPath = System.getenv("KEYSTORE_FILE")
    val ksPass = System.getenv("KEYSTORE_PASSWORD")
    val temChave = !ksPath.isNullOrBlank() && !ksPass.isNullOrBlank() && file(ksPath).exists()
    if (temChave) {
        signingConfigs {
            create("fixa") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = "compras"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        debug {
            if (temChave) signingConfig = signingConfigs.getByName("fixa")
        }
        release {
            isMinifyEnabled = false
            if (temChave) signingConfig = signingConfigs.getByName("fixa")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // Leitura de texto do PDF da DANFE, 100% offline
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
