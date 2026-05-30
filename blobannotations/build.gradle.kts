import java.util.Properties
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // android.library applied conditionally below
}

val enableAndroid = System.getenv("ANDROID_HOME") != null ||
    (rootProject.file("local.properties").exists() &&
        Properties().also {
            it.load(rootProject.file("local.properties").inputStream())
        }.getProperty("sdk.dir") != null)

if (enableAndroid) {
    apply(plugin = "com.android.library")
    configure<com.android.build.gradle.LibraryExtension> {
        namespace = "coredevices.blobannotations"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        defaultConfig {
            minSdk = 26
            lint.targetSdk = compileSdk
        }
        compileOptions {
            sourceCompatibility = JavaVersion.valueOf("VERSION_${libs.versions.jvm.toolchain.get()}")
            targetCompatibility = JavaVersion.valueOf("VERSION_${libs.versions.jvm.toolchain.get()}")
        }
    }
}

kotlin {
    if (enableAndroid) {
        androidTarget {
            publishLibraryVariants("release", "debug")
        }
    }

    jvm()

    val xcfName = "libpebble-annotations"

    iosX64 {
        binaries.framework { baseName = xcfName }
    }
    iosArm64 {
        binaries.framework { baseName = xcfName }
    }
    iosSimulatorArm64 {
        binaries.framework { baseName = xcfName }
    }

    sourceSets {
        commonMain.dependencies {}
        commonTest.dependencies {}
        if (enableAndroid) {
            androidMain {}
        }
        iosMain {}
        jvmMain {}
    }
}
