plugins {
    id("com.android.application")
}

android {
    namespace = "dev.echo.app"
    compileSdk = 33

    defaultConfig {
        applicationId = "dev.echo.app"
        minSdk = 26
        targetSdk = 33
        versionCode = 2
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    implementation(files("../native/litertlm/classes.jar"))
    implementation(files("../native/kotlin-stdlib.jar"))
    implementation(files("../native/gson.jar"))
    implementation(files("../native/coroutines.jar"))
    implementation(files("../native/sherpa/aar_unpacked/classes.jar"))
}

// JNI libs 由 build.sh 复制到 app/src/main/jniLibs/arm64-v8a/
