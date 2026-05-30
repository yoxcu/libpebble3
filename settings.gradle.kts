import java.util.Properties

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
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "libpebbleroot"

include(":libpebble3")
include(":blobdbgen")
include(":blobannotations")

// Modules that require Android SDK — skip them when building for JVM/Linux only
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    properties.getProperty("sdk.dir") != null
if (hasAndroidSdk) {
    include(":mcp")
    include(":index-ai")
    include(":cactus")
    include(":libindex")
    include(":krisp-stubs")
}
