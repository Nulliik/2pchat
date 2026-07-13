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
    }
    into(generatedPythonRoot.map { it.dir("messenger") })
}

val forbidDuplicatedPythonCore by tasks.registering {
    val duplicate = layout.projectDirectory.dir("src/main/python/messenger")
    inputs.dir(layout.projectDirectory.dir("src/main/python"))
    doLast {
        val committedSources = duplicate.asFile.takeIf { it.exists() }
            ?.walkTopDown()
            ?.any { it.isFile && it.extension == "py" } == true
        check(!committedSources) {
            "Do not commit an Android copy of messenger; edit the canonical repository messenger/ directory"
        }
    }
}

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
      jniLibs {
        useLegacyPackaging = true
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
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

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
  implementation("net.zetetic:android-database-sqlcipher:4.5.4")
  implementation("androidx.sqlite:sqlite:2.3.1")
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
        }
    }
}
