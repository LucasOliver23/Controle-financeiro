plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Assinatura: o GitHub Actions grava o .keystore e passa a senha por variável de ambiente.
// É a MESMA chave do APK anterior, então o Android instala por cima sem perder nada.
val arquivoChave = System.getenv("CF_KEYSTORE")
val temChave = !arquivoChave.isNullOrBlank() && file(arquivoChave).exists()

// O número da versão vem do GitHub Actions (sempre maior a cada build); no PC fica 100.
val codigoVersao = System.getenv("CF_VERSION_CODE")?.toIntOrNull() ?: 100
val nomeVersao = System.getenv("CF_VERSION_NAME") ?: "2.0"

android {
    namespace = "com.lucasoliver23.controlefinanceiro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lucasoliver23.controlefinanceiro"
        minSdk = 26
        targetSdk = 35
        versionCode = codigoVersao
        versionName = nomeVersao
    }

    signingConfigs {
        if (temChave) {
            create("publicacao") {
                storeFile = file(arquivoChave!!)
                storePassword = System.getenv("CF_KEYSTORE_SENHA")
                keyAlias = "controlefinanceiro"
                keyPassword = System.getenv("CF_KEYSTORE_SENHA")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (temChave) signingConfigs.getByName("publicacao") else signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // Desbloqueio do app com a digital
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    // Login com Google nativo (o Google bloqueia login dentro de WebView)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
