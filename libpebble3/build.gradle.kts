import java.util.Properties
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // android.library applied conditionally after this block; androidx.room always applied
}

// Android SDK is optional — when absent (e.g. Linux-only builds) we skip all Android targets.
val enableAndroid = System.getenv("ANDROID_HOME") != null ||
    (rootProject.file("local.properties").exists() &&
        Properties().also {
            it.load(rootProject.file("local.properties").inputStream())
        }.getProperty("sdk.dir") != null)

apply(plugin = "androidx.room")

if (enableAndroid) {
    apply(plugin = "com.android.library")
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

configure<androidx.room.gradle.RoomExtension> {
    schemaDirectory("schema")
}

if (enableAndroid) {
    configure<com.android.build.gradle.LibraryExtension> {
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        namespace = "io.rebble.libpebblecommon"
        defaultConfig {
            minSdk = 26
            lint.targetSdk = compileSdk
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions {
            sourceCompatibility = JavaVersion.valueOf("VERSION_${libs.versions.jvm.toolchain.get()}")
            targetCompatibility = JavaVersion.valueOf("VERSION_${libs.versions.jvm.toolchain.get()}")
        }
        buildTypes {
            release {
                consumerProguardFiles("consumer-rules.pro")
            }
        }
    }
}

tasks.register("buildFrameworkLibPebbleSwift", BuildSwiftFramework::class) {
    group = "build"
    description = "Builds the Swift framework for libpebble-swift"
}

// Non-mac machines cannot build iOS targets, so disable them
val enableIosTarget = System.getProperty("os.name").contains("mac", ignoreCase = true)

kotlin {
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
        androidTarget {
            publishLibraryVariants("release", "debug")
            instrumentedTestVariant {
                sourceSetTree.set(KotlinSourceSetTree.test)
            }
        }
    }

    jvm()

    val xcodeExists by lazy {
        project.providers.exec {
            isIgnoreExitValue = true
            commandLine("which", "xcode-select")
        }.result.get().exitValue == 0
    }
    val xcodeDir by lazy {
        if (xcodeExists) {
            project.providers.exec {
                commandLine("xcode-select", "-p")
            }.standardOutput.asText.get().trim()
        } else {
            ""
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target ->
        if (enableIosTarget) {
            val osName = when (target.name) {
                "iosX64" -> "iphonesimulator"
                "iosArm64" -> "iphoneos"
                "iosSimulatorArm64" -> "iphonesimulator"
                else -> throw IllegalStateException("Unknown target: ${target.name}")
            }
            val dir = tasks.getByName("buildFrameworkLibPebbleSwift").outputs.files.singleFile.resolve(osName)
            target.binaries.framework {
                baseName = "libpebble3"
            }
            target.compilations.getByName("main") {
                val libPebbleSwift by cinterops.creating {
                    compilerOpts("-framework", "LibPebbleSwift", "-F" + dir.absolutePath)
                }
            }
            target.binaries.all {
                linkerOpts("-framework", "LibPebbleSwift", "-F" + dir.absolutePath)
                if (xcodeExists) {
                    linkerOpts("-L$xcodeDir/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/$osName")
                }
            }
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
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
            api(libs.kotlinx.datetime)
            implementation(libs.koin.core)
            implementation(compose.ui)
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
            // AF_BLUETOOTH RFCOMM socket for the BT Classic transport (JVM has no native BT sockets).
            implementation("net.java.dev.jna:jna:5.14.0")
            implementation("org.graalvm.polyglot:polyglot:24.2.1")
            implementation("org.graalvm.polyglot:js-community:24.2.1")
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.test.junit)
            implementation(libs.ktor.websockets)
            implementation(libs.ktor.cio)
            implementation(libs.ktor.client.okhttp)
        }

        if (enableAndroid) {
            androidInstrumentedTest.dependencies {
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.rules)
                implementation(libs.androidx.monitor)
            }

            getByName("androidUnitTest").dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
                implementation(libs.coroutines.test)
            }
        }
    }
}

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
        tasks.named("kspDebugKotlinAndroid") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
        tasks.named("kspReleaseKotlinAndroid") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
    }

    if (enableIosTarget) {
        tasks.named("kspKotlinIosArm64") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
        tasks.named("kspKotlinIosX64") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
        tasks.named("kspKotlinIosSimulatorArm64") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
        tasks.named("cinteropLibPebbleSwiftIosArm64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
        tasks.named("cinteropLibPebbleSwiftIosX64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
        tasks.named("cinteropLibPebbleSwiftIosSimulatorArm64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
        tasks.named("compileKotlinIosArm64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
        tasks.named("compileKotlinIosX64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
        tasks.named("compileKotlinIosSimulatorArm64") {
            dependsOn("buildFrameworkLibPebbleSwift")
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
        add("kspIosX64", libs.room.compiler)
        add("kspIosArm64", libs.room.compiler)
        add("kspIosSimulatorArm64", libs.room.compiler)
    }
}

abstract class BuildSwiftFramework : DefaultTask() {
    @Inject
    abstract fun getExecOperations(): ExecOperations

    @get:InputFiles
    val inputFiles = project.objects.fileCollection().from(project.fileTree("libpebble-swift") {
        include("LibPebbleSwift.xcodeproj/project.pbxproj")
        include("LibPebbleSwift/*.swift")
        include("LibPebbleSwift/*.h")
        include("LibPebbleSwift/*.m")
    })

    @get:OutputDirectory
    val outputDir =
        project.objects.directoryProperty().convention(project.layout.buildDirectory.dir("libpebble-swift/"))

    @TaskAction
    fun buildSwiftFramework() {
        logging.captureStandardOutput(LogLevel.INFO)
        logging.captureStandardError(LogLevel.ERROR)
        getExecOperations().exec {
            commandLine(
                "xcodebuild", "-project", "LibPebbleSwift.xcodeproj",
                "-scheme", "LibPebbleSwift",
                "-configuration", "Release",
                "-sdk", "iphoneos",
                "CONFIGURATION_BUILD_DIR=${outputDir.get().asFile.resolve("iphoneos/").absolutePath}",
                "ARCHS=arm64",
                "SUPPORTS_MACCATALYST=NO",
            )
            workingDir = project.file("libpebble-swift")
            standardOutput = System.out
            errorOutput = System.err
        }
        getExecOperations().exec {
            commandLine(
                "xcodebuild", "-project", "LibPebbleSwift.xcodeproj",
                "-scheme", "LibPebbleSwift",
                "-configuration", "Release",
                "-sdk", "iphonesimulator",
                "CONFIGURATION_BUILD_DIR=${outputDir.get().asFile.resolve("iphonesimulator/").absolutePath}",
            )
            workingDir = project.file("libpebble-swift")
            standardOutput = System.out
            errorOutput = System.err
        }
    }
}

abstract class PlatformFatFramework : DefaultTask() {
    @get:Input
    abstract val platform: Property<String>

    @get:InputFiles
    val inputFrameworks = project.objects.fileCollection()

    @get:InputFiles
    val inputFrameworkDSYMs = project.objects.fileCollection()

    @Internal
    val platformOutputDir: Provider<Directory> =
        platform.map { project.layout.buildDirectory.dir("platform-fat-framework/${it}").get() }

    @get:OutputDirectory
    val outputDir = project.objects.directoryProperty().convention(platformOutputDir)

    @get:OutputDirectories
    val outputFiles: Provider<Array<File>> = platformOutputDir.map {
        arrayOf(
            it.asFile.toPath().resolve(inputFrameworks.files.first().name).toFile(),
            it.asFile.toPath().resolve(inputFrameworkDSYMs.files.first().name).toFile()
        )
    }

    private fun copyFramework() {
        val file = inputFrameworks.files.first()
        project.copy {
            from(file)
            into(outputDir.get().asFile.toPath().resolve(file.name))
        }
    }

    private fun copyFrameworkDSYM() {
        val file = inputFrameworkDSYMs.first()
        project.copy {
            from(file)
            into(outputDir.get().asFile.toPath().resolve(file.name))
        }
    }

    private fun lipoMergeFrameworks() {
        val inputs = mutableListOf<String>()
        inputFrameworks.forEach {
            inputs.add(it.toPath().resolve("libpebble3").toString())
        }
        val out = outputDir.get().asFile.toPath()
            .resolve(inputFrameworks.files.first().name + "/libpebble3").toString()
        project.providers.exec {
            commandLine("lipo", "-create", *inputs.toTypedArray(), "-output", out)
        }.result.get()
    }

    private fun lipoMergeFrameworkDSYMs() {
        val inputs = mutableListOf<String>()
        inputFrameworkDSYMs.forEach {
            inputs.add(it.toPath().resolve("Contents/Resources/DWARF/libpebble3").toString())
        }
        val out = outputDir.get().asFile.toPath()
            .resolve(inputFrameworkDSYMs.files.first().name + "/Contents/Resources/DWARF/libpebble3").toString()
        project.providers.exec {
            commandLine("lipo", "-create", *inputs.toTypedArray(), "-output", out)
        }.result.get()
    }

    @TaskAction
    fun createPlatformFatFramework() {
        copyFramework()
        copyFrameworkDSYM()
        lipoMergeFrameworks()
        lipoMergeFrameworkDSYMs()
    }
}
