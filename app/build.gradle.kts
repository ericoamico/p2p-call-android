plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// No GitHub Actions: -PbuildNumber=<run_number> (versão 1.0.N, igual à tag da release).
val buildNumber = (findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
// Repositório consultado pelo atualizador; no CI vem de GITHUB_REPOSITORY.
val releasesRepo = System.getenv("GITHUB_REPOSITORY") ?: "ericoamico/p2p-call-android"

android {
    namespace = "io.github.ericoamico.p2pcall"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ericoamico.p2pcall"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "RELEASES_API", "\"https://api.github.com/repos/$releasesRepo/releases/latest\"")
    }

    // A mesma chave em todas as builds, para que a atualização instale por cima da versão anterior.
    // Pode ser trocada pelos secrets P2PCALL_KEYSTORE_* (veja o workflow).
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("P2PCALL_KEYSTORE_FILE") ?: "release.keystore")
            storePassword = System.getenv("P2PCALL_KEYSTORE_PASSWORD") ?: "p2pcall"
            keyAlias = System.getenv("P2PCALL_KEY_ALIAS") ?: "p2pcall"
            keyPassword = System.getenv("P2PCALL_KEY_PASSWORD") ?: "p2pcall"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // WebRTC nativo (libwebrtc) e Socket.IO, o mesmo protocolo do cliente web e do desktop.
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("io.socket:socket.io-client:2.1.1") {
        exclude(group = "org.json", module = "json") // já existe no Android
    }

    testImplementation("junit:junit:4.13.2")
}
