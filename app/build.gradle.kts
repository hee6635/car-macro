plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hee.carmacro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hee.carmacro"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.4.1"
    }

    signingConfigs {
        // CI에서 매번 같은 서명으로 빌드되도록 저장소의 고정 키를 사용 (덮어쓰기 설치 가능)
        create("fixed") {
            storeFile = rootProject.file("keystore/carmacro.jks")
            storePassword = "carmacro"
            keyAlias = "carmacro"
            keyPassword = "carmacro"
        }
    }

    buildTypes {
        debug {
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
