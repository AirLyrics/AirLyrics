import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun signingValue(vararg keys: String): String? {
    for (key in keys) {
        providers.gradleProperty(key).orNull?.takeIf { it.isNotBlank() }?.let { return it }
        providers.environmentVariable(key).orNull?.takeIf { it.isNotBlank() }?.let { return it }
        localProperties.getProperty(key)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

val releaseStoreFile = signingValue(
    "airlyrics.release.storeFile",
    "AIRLYRICS_RELEASE_STORE_FILE",
    "RELEASE_STORE_FILE",
    "STORE_FILE"
)
val releaseStorePassword = signingValue(
    "airlyrics.release.storePassword",
    "AIRLYRICS_RELEASE_STORE_PASSWORD",
    "RELEASE_STORE_PASSWORD",
    "STORE_PASSWORD"
)
val releaseKeyAlias = signingValue(
    "airlyrics.release.keyAlias",
    "AIRLYRICS_RELEASE_KEY_ALIAS",
    "RELEASE_KEY_ALIAS",
    "KEY_ALIAS"
)
val releaseKeyPassword = signingValue(
    "airlyrics.release.keyPassword",
    "AIRLYRICS_RELEASE_KEY_PASSWORD",
    "RELEASE_KEY_PASSWORD",
    "KEY_PASSWORD"
)
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
)
val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }
val hasPartialReleaseSigning = releaseSigningValues.any { !it.isNullOrBlank() } && !hasReleaseSigning

fun releaseSigningStoreFile(path: String) = file(path).takeIf { it.isAbsolute } ?: rootProject.file(path)

fun isReleaseTaskName(name: String): Boolean {
    val simpleName = name.substringAfterLast(":")
    return simpleName.contains("Release", ignoreCase = true)
}

gradle.taskGraph.whenReady {
    val requiresReleaseSigning = allTasks.any { isReleaseTaskName(it.name) }

    if (requiresReleaseSigning && hasPartialReleaseSigning) {
        throw GradleException(
            "Incomplete release signing configuration. Please set all four values: " +
                "airlyrics.release.storeFile, airlyrics.release.storePassword, " +
                "airlyrics.release.keyAlias, airlyrics.release.keyPassword."
        )
    }


    if (requiresReleaseSigning && hasReleaseSigning) {
        val resolvedStoreFile = releaseSigningStoreFile(releaseStoreFile!!)
        if (!resolvedStoreFile.isFile) {
            throw GradleException("Release keystore not found: ${resolvedStoreFile.absolutePath}")
        }
    }
}

android {
    namespace = "com.andsi.airlyrics"
    compileSdk = 36
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.andsi.airlyrics"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                val resolvedStoreFile = releaseSigningStoreFile(releaseStoreFile!!)
                storeFile = resolvedStoreFile
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("androidx.documentfile:documentfile:1.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260522")
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(libs.androidx.test.core)
}


val lyricsCoreManifest = rootProject.layout.projectDirectory.file("lyrics-core/Cargo.toml")
val lyricsJniOutputDir = layout.projectDirectory.dir("src/main/jniLibs")

tasks.register<Exec>("buildRustLyrics") {
    group = "build"
    description = "Build the Rust NetEase lyric core for Android ABIs using cargo-ndk."

    // cargo-ndk runs `cargo metadata` from its working directory before passing
    // through cargo build arguments, so it must start inside the Rust crate.
    workingDir = rootProject.layout.projectDirectory.dir("lyrics-core").asFile

    doFirst {
        val manifest = lyricsCoreManifest.asFile
        if (!manifest.exists()) {
            throw GradleException("Missing Rust lyric core: ${manifest.absolutePath}")
        }
    }

    val rustAbiArgs = mutableListOf("-t", "arm64-v8a")

    // x86_64 emulator builds pull OpenSSL through the NetEase Rust stack and are
    // much more fragile when cross-compiling on Arch. The release APK only needs
    // arm64-v8a for modern phones, so keep x86_64 opt-in instead of blocking
    // normal device builds. Use -Pairlyrics.buildX86_64=true only when you need it.
    if (providers.gradleProperty("airlyrics.buildX86_64").orNull == "true") {
        rustAbiArgs += listOf("-t", "x86_64")
    }

    commandLine(
        listOf("cargo", "ndk") +
            rustAbiArgs +
            listOf(
                "-o", lyricsJniOutputDir.asFile.absolutePath,
                "build",
                "--release"
            )
    )
}

tasks.named("preBuild") {
    if (providers.gradleProperty("airlyrics.skipRustBuild").orNull != "true") {
        dependsOn("buildRustLyrics")
    }
}
