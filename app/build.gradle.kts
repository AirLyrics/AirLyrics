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
val buildX86_64 = providers.gradleProperty("airlyrics.buildX86_64").orNull == "true"

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
    compileSdk = 37
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.andsi.airlyrics"
        minSdk = 26
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = 10
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a")
            if (buildX86_64) {
                abiFilters += "x86_64"
            }
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
            isMinifyEnabled = true
            isShrinkResources = true
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

    dependenciesInfo {
        // F-Droid rejects the Google Play SDK dependency metadata block as an
        // extra signing block. It is not needed for direct APK distribution.
        includeInApk = false
        includeInBundle = false
    }

    lint {
        disable += setOf(
            // Keep targetSdk changes explicit: floating windows, foreground
            // service behavior, notifications, and storage need device testing.
            "OldTargetApi",
            // Release builds intentionally ship arm64 only. x86_64 Rust/OpenSSL
            // cross-compilation is opt-in via -Pairlyrics.buildX86_64=true.
            "ChromeOsAbiSupport"
        )
    }

    @Suppress("UnstableApiUsage")
    bundle {
        language {
            // The app lets users switch language at runtime. Keep bundled
            // locale resources together instead of relying on Play language splits.
            enableSplit = false
        }
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

    sourceSets {
        getByName("test") {
            resources.directories.add(rootProject.file("lyrics-core/testdata").path)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.documentfile)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}


val lyricsCoreManifest = rootProject.layout.projectDirectory.file("lyrics-core/Cargo.toml")
val lyricsCoreDir = rootProject.layout.projectDirectory.dir("lyrics-core").asFile
val lyricsJniOutputDir = layout.projectDirectory.dir("src/main/jniLibs")
val rustFlagsSeparator = '\u001f'

fun normalizedUnixTimestamp(label: String, value: String?): String? {
    val timestamp = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (timestamp.toLongOrNull()?.let { it >= 0 } != true) {
        throw GradleException("$label must be a non-negative Unix timestamp, but was: $timestamp")
    }
    return timestamp
}

fun gitCommitTimestamp(repositoryDir: File): String? = runCatching {
    val process = ProcessBuilder(
        "git",
        "-C",
        repositoryDir.absolutePath,
        "log",
        "-1",
        "--format=%ct"
    )
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()

    val timestamp = process.inputStream.bufferedReader().use { it.readText() }.trim()
    val exitCode = process.waitFor()
    timestamp.takeIf { exitCode == 0 && it.toLongOrNull()?.let { value -> value >= 0 } == true }
}.getOrNull()

fun resolvedEnvironmentPath(value: Any?, default: File, relativeTo: File): File {
    val configured = value?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return default
    return File(configured).let { if (it.isAbsolute) it else File(relativeTo, configured) }
}

tasks.register<Exec>("buildRustLyrics") {
    group = "build"
    description = "Build the Rust NetEase lyric core for Android ABIs using cargo-ndk."

    // cargo-ndk runs `cargo metadata` from its working directory before passing
    // through cargo build arguments, so it must start inside the Rust crate.
    workingDir = lyricsCoreDir

    doFirst {
        val manifest = lyricsCoreManifest.asFile
        if (!manifest.exists()) {
            throw GradleException("Missing Rust lyric core: ${manifest.absolutePath}")
        }

        // Prefer an explicitly supplied value, otherwise derive it from the exact
        // Git commit being built. Upstream and F-Droid therefore use the same
        // timestamp without a release-specific hard-coded metadata override.
        val sourceDateEpoch = normalizedUnixTimestamp(
            "airlyrics.sourceDateEpoch",
            providers.gradleProperty("airlyrics.sourceDateEpoch").orNull
        ) ?: normalizedUnixTimestamp(
            "SOURCE_DATE_EPOCH",
            environment["SOURCE_DATE_EPOCH"]?.toString()
        ) ?: gitCommitTimestamp(rootProject.projectDir)
        ?: throw GradleException(
            "Unable to determine SOURCE_DATE_EPOCH. Build from a Git checkout, " +
                "set SOURCE_DATE_EPOCH, or pass -Pairlyrics.sourceDateEpoch=<unix-seconds>."
        )

        environment("SOURCE_DATE_EPOCH", sourceDateEpoch)
        environment("CARGO_INCREMENTAL", "0")

        val cargoHome = resolvedEnvironmentPath(
            environment["CARGO_HOME"],
            File(System.getProperty("user.home"), ".cargo"),
            lyricsCoreDir
        )
        val rustupHome = resolvedEnvironmentPath(
            environment["RUSTUP_HOME"],
            File(System.getProperty("user.home"), ".rustup"),
            lyricsCoreDir
        )
        val cargoTargetDir = File("/tmp/airlyrics-cargo-target")

        if (!cargoTargetDir.exists() && !cargoTargetDir.mkdirs()) {
            throw GradleException(
                "Unable to create reproducible Cargo target directory: " +
                        cargoTargetDir.absolutePath
            )
        }

        environment("CARGO_TARGET_DIR", cargoTargetDir.absolutePath)

        // rustc applies the last matching remap. Keep broad roots first and more
        // specific roots last so generated objects never retain machine paths.
        val pathRemaps = linkedMapOf(
            rootProject.projectDir.canonicalFile.path.replace(File.separatorChar, '/') to "/airlyrics",
            rustupHome.canonicalFile.path.replace(File.separatorChar, '/') to "/rustup-home",
            cargoHome.canonicalFile.path.replace(File.separatorChar, '/') to "/cargo-home",
            cargoTargetDir.canonicalFile.path.replace(File.separatorChar, '/') to "/cargo-target"
        )
        val remapFlags = pathRemaps.flatMap { (from, to) ->
            listOf("--remap-path-prefix", "$from=$to")
        }

        val inheritedRustFlags = environment["CARGO_ENCODED_RUSTFLAGS"]
            ?.toString()
            ?.split(rustFlagsSeparator)
            ?.filter { it.isNotEmpty() }
            ?: environment["RUSTFLAGS"]
                ?.toString()
                ?.split(Regex("\\s+"))
                ?.filter { it.isNotEmpty() }
                .orEmpty()

        environment(
            "CARGO_ENCODED_RUSTFLAGS",
            (inheritedRustFlags + remapFlags).joinToString(rustFlagsSeparator.toString())
        )
    }

    val rustAbiArgs = mutableListOf("-t", "arm64-v8a")

    // x86_64 emulator builds pull OpenSSL through the NetEase Rust stack and are
    // much more fragile when cross-compiling on Arch. The release APK only needs
    // arm64-v8a for modern phones, so keep x86_64 opt-in instead of blocking
    // normal device builds. Use -Pairlyrics.buildX86_64=true only when you need it.
    if (buildX86_64) {
        rustAbiArgs += listOf("-t", "x86_64")
    }

    val relativeJniOutputDir = lyricsCoreDir.toPath()
        .relativize(lyricsJniOutputDir.asFile.toPath())
        .toString()

    commandLine(
        listOf("cargo", "ndk") +
            rustAbiArgs +
            listOf(
                "-o", relativeJniOutputDir,
                "build",
                "--release",
                "--locked"
            )
    )
}

tasks.named("preBuild") {
    if (providers.gradleProperty("airlyrics.skipRustBuild").orNull != "true") {
        dependsOn("buildRustLyrics")
    }
}
