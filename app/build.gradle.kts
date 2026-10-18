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
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No release keystore in the repo: sign with the debug key so `assembleRelease` yields an
            // installable APK for personal use. Replace with your own signingConfig to publish.
            signingConfig = signingConfigs.getByName("debug")
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
}
