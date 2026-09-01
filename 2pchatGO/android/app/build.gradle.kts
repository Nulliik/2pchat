import java.security.MessageDigest

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val pluggableTransportBinaries by configurations.creating
val generatedBridgeJniLibs = layout.buildDirectory.dir("generated/jniLibs/bridgeTransport")
val expectedLyrebirdSha256 = "2d70a38393ee6f1760a65a33dd971210efa06b5a355ebea829196b61fd9fd11a"
val unpackBridgeTransportBinaries by tasks.registering(Sync::class) {
    from({ pluggableTransportBinaries.map { zipTree(it) } })
    into(generatedBridgeJniLibs)
    doFirst {
        val artifacts = pluggableTransportBinaries.files
        check(artifacts.size == 1) { "Expected exactly one Lyrebird artifact" }
        val artifact = artifacts.single()
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expectedLyrebirdSha256) {
            "Lyrebird artifact checksum mismatch"
        }
    }
}

val buildGoCoreBinaries by tasks.registering(Exec::class) {
    val envNdk = System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_NDK_ROOT")
    val defaultWindowsSdk = file("${System.getProperty("user.home")}/AppData/Local/Android/Sdk")
    val sdkDir = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: defaultWindowsSdk.takeIf { it.exists() }?.absolutePath
    val ndkDir = when {
        envNdk != null && file(envNdk).exists() -> file(envNdk)
        sdkDir != null && file("$sdkDir/ndk/26.3.11579264").exists() -> file("$sdkDir/ndk/26.3.11579264")
        sdkDir != null && file("$sdkDir/ndk/android-ndk-r26d").exists() -> file("$sdkDir/ndk/android-ndk-r26d")
        else -> file("/Users/kodzy/Library/Android/sdk/ndk/26.3.11579264")
    }
    workingDir = file("../core-go")
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        val clang = ndkDir.resolve("toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe").absolutePath
        val jniLibs = file("src/main/jniLibs").absolutePath.replace("\\", "/")
        val buildScript = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}env:CGO_ENABLED = '1'
            ${'$'}env:CGO_CFLAGS = '-fno-omit-frame-pointer -O2'
            ${'$'}env:CGO_LDFLAGS = '-Wl,-z,max-page-size=16384'
            ${'$'}targets = @(
                @{ Abi = 'arm64-v8a'; Arch = 'arm64'; Target = 'aarch64-linux-android24' },
                @{ Abi = 'x86_64'; Arch = 'amd64'; Target = 'x86_64-linux-android24' },
                @{ Abi = 'armeabi-v7a'; Arch = 'arm'; Target = 'armv7a-linux-androideabi24' }
            )
            foreach (${ '$' }target in ${ '$' }targets) {
                ${'$'}outputDir = '${jniLibs}/' + ${'$'}target.Abi
                New-Item -ItemType Directory -Force -Path ${'$'}outputDir | Out-Null
                ${'$'}env:GOOS = 'android'
                ${'$'}env:GOARCH = ${'$'}target.Arch
                ${'$'}env:GOARM = if (${ '$' }target.Arch -eq 'arm') { '7' } else { '' }
                ${'$'}env:CC = '${clang} --target=' + ${'$'}target.Target
                & go build '-ldflags=-s -w -extldflags=-Wl,-z,max-page-size=16384' '-buildmode=c-shared' '-o' (${ '$' }outputDir + '/lib2pcore.so') './cmd/lib2pcore'
                if (${ '$' }LASTEXITCODE -ne 0) { exit ${ '$' }LASTEXITCODE }
                Remove-Item -LiteralPath (${ '$' }outputDir + '/lib2pcore.h') -ErrorAction SilentlyContinue
            }
        """.trimIndent()
        commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", buildScript)
    } else {
        commandLine("make", "NDK_DIR=${ndkDir.absolutePath}", "android-all")
    }
    onlyIf {
        file("../core-go/Makefile").exists() && ndkDir.exists()
    }
}

tasks.matching { it.name.startsWith("preBuild") }.configureEach {
    dependsOn(buildGoCoreBinaries)
}

android {
    namespace = "com.example.twopchat"
    compileSdk = 37
    ndkVersion = "26.3.11579264"
    defaultConfig {
        applicationId = "com.example.twopchat.go"
        minSdk = 24
        targetSdk = 36
        versionCode = 19
        versionName = "0.0.8.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    testOptions {
      unitTests.isReturnDefaultValues = true
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
      jniLibs {
        useLegacyPackaging = true
        keepDebugSymbols.add("**/libgojni.so")
        keepDebugSymbols.add("**/liblyrebird.so")
        keepDebugSymbols.add("**/lib2pcore.so")
      }
    }
    sourceSets.getByName("main").jniLibs.directories.add(
        generatedBridgeJniLibs.get().asFile.absolutePath
    )
}

tasks.named("preBuild") {
    dependsOn(unpackBridgeTransportBinaries)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  // Reproducible standalone Tor managed transport. Keeping this executable
  // outside the runtime classpath avoids a second gomobile/libgojni runtime.
  add(pluggableTransportBinaries.name, "org.briarproject:lyrebird-android:0.6.2")

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
  implementation(libs.coil.compose)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation("org.json:json:20240303")

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
