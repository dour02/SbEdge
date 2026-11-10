plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.dour02.sbedge"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.dour02.sbedge"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            // 固定签名，保证以后的版本可以直接覆盖安装
            storeFile = rootProject.file("signing/release.jks")
            storePassword = "sbedge"
            keyAlias = "sbedge"
            keyPassword = "sbedge"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
