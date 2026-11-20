import java.time.LocalDate
import java.time.ZoneOffset
import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application) // AGP 9: Kotlin support is built in
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

configure<ApplicationExtension> {
    namespace = "io.github.rudolfhk.audiosaver"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.rudolfhk.audiosaver"
        // 29 (Android 10): first release with MediaStore RELATIVE_PATH/IS_PENDING, so saving to
        // Music/ needs no storage permission on every supported version.
        minSdk {
            version = release(29)
        }
        targetSdk {
            version = release(37)
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The release workflow sets these from the git tag (v1.2 -> "1.2") and the run number; the
        // update check compares versionName with the latest GitHub release.
        versionCode = providers.environmentVariable("AUDIOSAVER_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.environmentVariable("AUDIOSAVER_VERSION_NAME").orNull ?: "1.0"

        // Shown in the About dialog. The date is the build day (UTC) unless CI pins it.
        val buildDate = providers.environmentVariable("AUDIOSAVER_BUILD_DATE").orNull
            ?: LocalDate.now(ZoneOffset.UTC).toString()
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
        val versions = libs.versions
        mapOf(
            "OKHTTP" to versions.okhttp, "KOTLIN" to versions.kotlin, "COROUTINES" to versions.coroutines,
            "SERIALIZATION" to versions.serialization, "COMPOSE" to versions.compose, "MATERIAL3" to versions.material3,
            "JUMP3R" to versions.jump3r, "ANDROIDX_CORE" to versions.core, "ACTIVITY" to versions.activity,
            "LIFECYCLE" to versions.lifecycle, "DESUGAR" to versions.desugar,
        ).forEach { (name, version) -> buildConfigField("String", "V_$name", "\"${version.get()}\"") }
    }

    // Release signing only when a keystore is provided (CI secrets); otherwise the debug key.
    val keystore = providers.environmentVariable("AUDIOSAVER_KEYSTORE").orNull
    if (keystore != null) {
        signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = providers.environmentVariable("AUDIOSAVER_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("AUDIOSAVER_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("AUDIOSAVER_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No release keystore in the repo: without the CI secrets, sign with the debug key so
            // `assembleRelease` still yields an installable APK for personal use.
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    compileOptions {
        // NewPipeExtractor uses java.nio APIs that only exist natively from API 33.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/README.md", "META-INF/CHANGES", "META-INF/COPYRIGHT")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine-newpipe"))

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    coreLibraryDesugaring(libs.android.desugar)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
