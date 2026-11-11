import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "ai.familyroute.demo"
    compileSdk = 34
    useLibrary("android.car")

    defaultConfig {
        applicationId = "ai.familyroute.demo"
        minSdk = 28 // Pleos 요구사항
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-phase1"
        val localRoomConfig = Properties().apply {
            rootProject.file("family-server.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
        }
        val roomUrl = providers.gradleProperty("familyServerUrl")
            .orElse(localRoomConfig.getProperty("familyServerUrl", "http://10.0.2.2:8789")).get()
        require(roomUrl == "http://10.0.2.2:8789" || roomUrl.startsWith("https://")) { "familyServerUrl must use HTTPS" }
        buildConfigField("String", "FAMILY_SERVER_URL", "\"${roomUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        val inviteBase = providers.gradleProperty("familyInviteUrl")
            .orElse(localRoomConfig.getProperty("familyInviteUrl", if (roomUrl.startsWith("https://")) roomUrl else "")).get().trimEnd('/')
        require(inviteBase.isEmpty() || inviteBase.matches(Regex("https://[a-zA-Z0-9.-]+(:[0-9]+)?"))) { "familyInviteUrl must be an HTTPS origin" }
        buildConfigField("String", "FAMILY_INVITE_URL", "\"$inviteBase\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}

// 단위 테스트가 assets/scenario.json을 직접 읽으므로 바뀌면 다시 돌린다
tasks.withType<Test>().configureEach {
    inputs.file("src/main/assets/scenario.json")
}
