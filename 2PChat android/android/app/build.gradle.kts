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

// messenger/ at the repository root is the only source of truth. The Android
// package is generated under build/ and can never drift as a committed copy.
val generatedPythonRoot = layout.buildDirectory.dir("generated/python/main")
val syncCanonicalPythonCore by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("../../messenger")) {
        exclude("tests/**", "**/__pycache__/**", "**/*.pyc")
        into("messenger")
    }
    from(rootProject.layout.projectDirectory.file("../../messenger/discovery_bridge.py"))
    from(rootProject.layout.projectDirectory.file("../../messenger/bootstrap.py"))
    into(generatedPythonRoot)
}

val forbidDuplicatedPythonCore by tasks.registering {
    val pythonDir = layout.projectDirectory.dir("src/main/python")
    inputs.dir(pythonDir)
    doLast {
        val committedSources = pythonDir.asFile.takeIf { it.exists() }
            ?.walkTopDown()
            ?.any { it.isFile && it.extension == "py" } == true
        check(!committedSources) {
            "Do not commit Python files in src/main/python; edit the canonical repository messenger/ directory"
        }
    }
}

android {
    namespace = "com.example.twopchat"
    compileSdk = 37
    ndkVersion = "26.1.10909125"
    defaultConfig {
        applicationId = "com.example.twopchat"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "0.0.7"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
      jniLibs {
        useLegacyPackaging = true
        doNotStrip("**/libgojni.so")
      }
    }
}

tasks.named("preBuild") {
    dependsOn(syncCanonicalPythonCore, forbidDuplicatedPythonCore)
}

tasks.configureEach {
    if (name.contains("Python", ignoreCase = true) &&
        name !in setOf("syncCanonicalPythonCore", "forbidDuplicatedPythonCore")) {
        dependsOn(syncCanonicalPythonCore, forbidDuplicatedPythonCore)
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
  implementation("androidx.lifecycle:lifecycle-process:2.8.7")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  implementation("androidx.work:work-runtime:2.11.2")
  implementation("com.github.penfeizhou.android.animation:awebp:3.0.5")

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-core")
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
  implementation("androidx.security:security-crypto:1.1.0")
  implementation("net.zetetic:sqlcipher-android:4.6.1")
  implementation("androidx.sqlite:sqlite:2.3.1")

  // Media3 ExoPlayer for Video Player
  implementation("androidx.media3:media3-exoplayer:1.5.1")
  implementation("androidx.media3:media3-ui:1.5.1")

  // QR Code Generation & Scanning
  implementation("com.google.zxing:core:3.5.3")
  implementation("com.google.mlkit:barcode-scanning:17.3.0")
  implementation("androidx.camera:camera-camera2:1.4.1")
  implementation("androidx.camera:camera-lifecycle:1.4.1")
  implementation("androidx.camera:camera-view:1.4.1")

  // Embedded Tor & NetCipher dependencies
  implementation("info.guardianproject:tor-android:0.4.9.11")
  implementation("info.guardianproject:jtorctl:0.4.5.7")
  implementation("info.guardianproject.netcipher:netcipher:2.1.0")
}


chaquopy {
    sourceSets {
        getByName("main") {
            srcDir(generatedPythonRoot)
        }
    }
    defaultConfig {
        version = chaquopyRuntimePython
        if (!chaquopyBuildPython.isNullOrBlank()) {
            buildPython(chaquopyBuildPython)
        }
        pip {
            install("pynacl")
            install("cbor2")
            install("argon2-cffi")
            install("PySocks")
        }
    }
}
