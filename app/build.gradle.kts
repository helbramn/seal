plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "com.cerrojo"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.cerrojo"
        minSdk = 29
        targetSdk = 34
        versionCode = 17
        versionName = "1.7"
    }
    // Firma fija. Sin esto, Gradle firma con una clave de depuracion nueva en
    // cada maquina y cada compilacion, y entonces una version no se instala
    // encima de la anterior: Android lo ve como otra app suplantando a la tuya.
    // La clave viaja como secreto de GitHub, nunca en el repositorio.
    signingConfigs {
        create("sello") {
            val fichero = System.getenv("RUTA_DEL_SELLO")
            if (fichero != null) {
                storeFile = file(fichero)
                storePassword = System.getenv("CLAVE_DEL_SELLO")
                keyAlias = "seal"
                keyPassword = System.getenv("CLAVE_DEL_SELLO")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Solo si el secreto esta presente: asi una compilacion local o un
            // fork sin la clave siguen funcionando en vez de romperse.
            if (System.getenv("RUTA_DEL_SELLO") != null) {
                signingConfig = signingConfigs.getByName("sello")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
}
