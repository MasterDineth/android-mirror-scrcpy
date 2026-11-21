plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mirror.server"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mirror.server"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}

afterEvaluate {
    tasks.findByName("assembleDebug")?.doLast {
        val src = file("build/intermediates/apk/debug/server-debug.apk")
        val dest = file("build/outputs/apk/debug/server-debug.apk")
        if (src.exists()) {
            dest.parentFile.mkdirs()
            src.copyTo(dest, overwrite = true)
        }
    }
}
