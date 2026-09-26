plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.switchy.intercom"
    compileSdk = 36

    // ทดสอบไอคอน: ติดตั้งเป็นอีกแพ็กเกจเพื่อเลี่ยงแคชไอคอนของระบบ (ใช้เฉพาะตอนตรวจ ไม่ได้ไปกับรุ่นจริง)
    //     ./gradlew assembleDebug -PiconTest=true
    val iconTest = (project.findProperty("iconTest") as String?)?.toBoolean() ?: false

    defaultConfig {
        applicationId = "com.switchy.intercom"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
        if (iconTest) applicationIdSuffix = ".icon"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    // ---- เฟส 2: Oboe + Opus แบบ native (เปิดเมื่อติดตั้ง NDK แล้วเท่านั้น) ----
    // เปิดด้วย:  ./gradlew assembleDebug -PwithNativeAudio=true
    // ต้องมี: sdkmanager "ndk;30.0.16248370" "cmake;4.1.2"
    val withNative = (project.findProperty("withNativeAudio") as String?)?.toBoolean() ?: false
    if (withNative) {
        ndkVersion = "30.0.16248370"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "4.1.2"
            }
        }
        defaultConfig {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        // CMake 4 ต้องมีบรรทัดนี้เมื่อโปรเจกต์ย่อย (Oboe/Opus) ประกาศเวอร์ชันเก่า
                        "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"
                    )
                    cppFlags += "-std=c++17"
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
