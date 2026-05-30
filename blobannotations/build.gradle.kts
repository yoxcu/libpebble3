import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // com.android.kotlin.multiplatform.library is applied below, only when the Android gate is on
}

// Android SDK is optional — when absent (e.g. Linux-only builds) we skip the Android target.
// Decided once in settings.gradle.kts (an SDK is configured and Gradle is 9 or newer).
val enableAndroid = gradle.extra["enableAndroid"] as Boolean

if (enableAndroid) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())

    if (enableAndroid) {
        // No type-safe `android {}` accessor exists for a plugin applied outside plugins {}, so
        // configure the target through the extension the plugin registers on `kotlin`.
        extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "coredevices.blobannotations"
            compileSdk = libs.versions.android.compileSdk.get().toInt()
            minSdk = libs.versions.android.minSdk.get().toInt()

            compilerOptions {
                jvmTarget.set(JvmTarget.valueOf("JVM_${libs.versions.jvm.toolchain.get()}"))
            }
        }
    }

    jvm()

    val xcfName = "libpebble-annotations"

    iosArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }
}
