import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
    alias(libs.plugins.kotlin.serialization)
    // com.android.kotlin.multiplatform.library is applied below, only when the Android gate is on
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlinx.atomicfu)
}

// Android SDK is optional — when absent (e.g. Linux-only builds) we skip all Android targets.
// Decided once in settings.gradle.kts (an SDK is configured and Gradle is 9 or newer).
val enableAndroid = gradle.extra["enableAndroid"] as Boolean

if (enableAndroid) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/pebble-dev/libpebblecommon")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

room {
    schemaDirectory("schema")
}

// Non-mac machines cannot build iOS targets, so disable them
val enableIosTarget = System.getProperty("os.name").contains("mac", ignoreCase = true)

kotlin {
    // Compile this module *against* a JDK 25 toolchain (auto-detected; the Gradle 8.14 daemon stays
    // on JDK 21). The BT Classic RFCOMM socket (BluezRfcommSocket.jvm.kt, jvmMain) uses
    // java.lang.foreign, only on the bootclasspath from JDK 22 on — without a JDK 25 jdkHome the
    // daemon's JDK 21 is used and the FFM API won't resolve. Pinned to a literal 25, NOT the
    // `jvm-toolchain` catalog value (21): that value drives :blobdbgen, a KSP processor the JDK-21
    // KSP worker must load, so it has to stay 21.
    //
    // The emitted bytecode is still JDK 21 (jvmTarget below), NOT 25 — see the jvm{} block.
    jvmToolchain(25)

    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    if (enableAndroid) {
        // No type-safe `android {}` accessor exists for a plugin applied outside plugins {}, so
        // configure the target through the extension the plugin registers on `kotlin`.
        extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "io.rebble.libpebblecommon"
            compileSdk = libs.versions.android.compileSdk.get().toInt()
            minSdk = libs.versions.android.minSdk.get().toInt()

            compilerOptions {
                jvmTarget.set(JvmTarget.valueOf("JVM_${libs.versions.jvm.toolchain.get()}"))
            }

            androidResources {
                enable = true
            }

            withHostTestBuilder {}

            withDeviceTestBuilder {
                sourceSetTreeName = "test"
            }.configure {
                instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            optimization {
                consumerKeepRules.file("consumer-rules.pro")
            }
        }
    }

    jvm {
        compilerOptions {
            // Emit JDK 21 bytecode (major 65) even though we compile on the JDK 25 toolchain above.
            // KSP (and the kotlinx-atomicfu post-compile transform, whenever gradle.properties doesn't
            // set kotlinx.atomicfu.enableJvmIrTransformation=true) run in the Gradle daemon JVM
            // (JDK 21) and load these classes reflectively — major-69 (JDK 25) bytecode throws
            // UnsupportedClassVersionError there. major-65 loads fine for those tools, runs fine on
            // the JDK 25 runtime, and the java.lang.foreign calls still resolve against JDK 25 at
            // run time (a JDK 25 runtime is required regardless — see packaging/). jdkHome (25, for
            // the FFM API at compile time) and jvmTarget (21, the bytecode level) are independent.
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target ->
        target.binaries.framework {
            baseName = "libpebble3"
        }
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.ExperimentalUnsignedTypes")
                optIn("kotlin.ExperimentalStdlibApi")
                optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("kotlin.time.ExperimentalTime")
                optIn("kotlinx.coroutines.FlowPreview")
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                optIn("kotlinx.cinterop.BetaInteropApi")
            }
        }
        commonMain {
            kotlin {
                // Include ksp-generated common code (from our :blobdgen processor)
                srcDir("build/generated/ksp/metadata/commonMain/kotlin")
            }
        }
        commonMain.dependencies {
            api(libs.coroutines)
            implementation(libs.serialization)
            implementation(libs.kermit)
            implementation(libs.room.runtime)
            api(libs.room.paging)
            implementation(libs.sqlite.bundled)
            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.io.okio)
            implementation(libs.okio)
            implementation(libs.kable)
            implementation(libs.kmpio)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.network)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
            api(libs.kotlinx.datetime)
            implementation(libs.koin.core)
            implementation(libs.compose.ui)
            implementation(project(":blobannotations"))
            implementation(libs.settings)
            implementation(libs.settings.serialization)
            implementation(libs.uri)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }

        if (enableAndroid) {
            androidMain.dependencies {
                implementation(libs.androidx.core.ktx)
                implementation(libs.pebblekit)
            }
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.ktor.websockets)
        }

        jvmMain.dependencies {
            implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
            implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.0")
            implementation("org.graalvm.polyglot:polyglot:25.0.3")
            implementation("org.graalvm.polyglot:js-community:25.0.3")
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.test.junit)
            implementation(libs.ktor.websockets)
            implementation(libs.ktor.cio)
            implementation(libs.ktor.client.okhttp)
        }

        if (enableAndroid) {
            getByName("androidDeviceTest").dependencies {
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.rules)
                implementation(libs.androidx.monitor)
            }

            getByName("androidHostTest").dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
                implementation(libs.coroutines.test)
            }
        }
    }
}

// Otherwise it doesn't trigger our blobdbgen processor when compiling code
// https://github.com/google/ksp/issues/567
tasks.withType<KotlinCompilationTask<*>>().all {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

afterEvaluate {
    tasks.named("kspKotlinJvm") {
        dependsOn("kspCommonMainKotlinMetadata")
    }

    if (enableAndroid) {
        tasks.named("kspAndroidMain") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
    }

    if (enableIosTarget) {
        tasks.named("kspKotlinIosArm64") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
        tasks.named("kspKotlinIosSimulatorArm64") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
    }
}

dependencies {
    add("kspCommonMainMetadata", project(":blobdbgen"))
    add("kspJvm", libs.room.compiler)
    if (enableAndroid) {
        add("kspAndroid", libs.room.compiler)
    }

    if (enableIosTarget) {
        add("kspIosArm64", libs.room.compiler)
        add("kspIosSimulatorArm64", libs.room.compiler)
    }
}
