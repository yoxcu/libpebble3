import java.util.Properties
import org.gradle.util.GradleVersion

val properties = Properties()
if (file("local.properties").exists()) {
    file("local.properties").inputStream().use { properties.load(it) }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositories {
        if (properties.getProperty("USE_MAVEN_LOCAL") == "true") {
            mavenLocal()
        }
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
        mavenLocal()
    }
}

rootProject.name = "libpebbleroot"

include(":libpebble3")
include(":blobdbgen")
include(":blobannotations")

// Modules that require Android SDK — skip them when building for JVM/Linux only.
// The app modules (:composeApp, :androidApp, :pebble, :util, :resampler, :experimental) are never
// included: stoandl only builds the library, and :androidApp depends on :composeApp and :util.
// :cactus-native is the NDK half of :cactus, so it goes wherever :cactus goes.
// This is the one Android gate: :libpebble3 and :blobannotations read it from gradle.extra to
// decide whether to apply the Android plugin at all.
// It needs a standalone build of this repo: included in stoandl's composite, this build runs on
// stoandl's Gradle, and an ANDROID_HOME exported by a CI runner image or an sdk.dir Android Studio
// wrote to local.properties must never switch Android on there, whatever that Gradle is. It also
// needs a Gradle the applied AGP runs on (AGP 9.3.1 fails configuration below 9.5.0; keep this in
// step with `agp` in libs.versions.toml). The gate is runtime-only: both scripts still import AGP's
// KotlinMultiplatformAndroidLibraryTarget, compiled against the AGP that the root build.gradle.kts
// `apply false` lines put on the classpath.
val enableAndroid = gradle.parent == null &&
    GradleVersion.current().baseVersion >= GradleVersion.version("9.5") &&
    (!System.getenv("ANDROID_HOME").isNullOrBlank() || properties.getProperty("sdk.dir") != null)
gradle.extra["enableAndroid"] = enableAndroid
if (enableAndroid) {
    include(":mcp")
    include(":index-ai")
    include(":cactus")
    include(":cactus-native")
    include(":libindex")
    include(":krisp-stubs")
}
