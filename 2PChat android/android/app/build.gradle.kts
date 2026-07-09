plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.chaquopy)
}

val chaquopyRuntimePython = providers.gradleProperty("chaquopyRuntimePython")
    .orElse(providers.environmentVariable("CHAQUOPY_PYTHON_VERSION"))
    .getOrElse("3.11")
val chaquopyBuildPython = providers.gradleProperty("chaquopyBuildPython")
    .orElse(providers.environmentVariable("CHAQUOPY_BUILD_PYTHON"))
    .orNull

android {
    namespace = "com.example.twopchat"
    compileSdk = 36
    ndkVersion = "26.1.10909125"
    defaultConfig {
        applicationId = "com.example.twopchat"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Yggdrasil dependencies
  implementation(files("libs/yggdrasil.aar"))
  implementation("androidx.preference:preference-ktx:1.2.1")
  implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
}


chaquopy {
    defaultConfig {
        version = chaquopyRuntimePython
        if (!chaquopyBuildPython.isNullOrBlank()) {
            buildPython(chaquopyBuildPython)
        }
        pip {
            install("pynacl")
            install("cbor2")
            install("argon2-cffi")
        }
    }
}
