plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.andsi.airlyrics"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.andsi.airlyrics"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("androidx.documentfile:documentfile:1.0.1")
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
