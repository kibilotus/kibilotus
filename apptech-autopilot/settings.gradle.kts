pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "apptech-autopilot"

// 순수 Kotlin 코어(미션 엔진). Android SDK 없이 JVM 단위 테스트 가능.
include(":core")

// Android 앱 모듈은 SDK가 있을 때만 포함 (CI/서버에서 core만 테스트할 수 있도록)
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()
if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("Android SDK not found -> building :core only")
}
