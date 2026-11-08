import com.android.build.api.variant.HostTestBuilder
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
}

val profileAbi = if (providers.gradleProperty("onwinProfileX86").orNull == "true") "x86_64" else "arm64-v8a"
val releaseAbi = providers.gradleProperty("onwinReleaseAbi").orElse("arm64-v8a").get()
require(releaseAbi in listOf("arm64-v8a", "x86_64")) {
    "Недопустимое значение onwinReleaseAbi: '$releaseAbi'. Допустимые значения — arm64-v8a и x86_64."
}

abstract class PrepareLlmAssets @Inject constructor(
    private val fileSystem: FileSystemOperations,
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archive: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val archiveSha256: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val llmArchive = archive.get().asFile
        check(llmArchive.isFile) {
            "Положите llm-model-v1.zip в корень проекта. Инструкция в INSTALL.md."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        llmArchive.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var count = input.read(buffer)
            while (count != -1) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == archiveSha256.get()) {
            "SHA-256 llm-model-v1.zip не совпадает. Получен $actual. Скачайте архив заново по INSTALL.md."
        }
        fileSystem.sync {
            into(outputDirectory)
            into("llm") {
                from(llmArchive, manifest.get().asFile)
            }
        }
    }
}

val prepareLlmAssets = tasks.register<PrepareLlmAssets>("prepareLlmAssets") {
    archive.set(rootProject.layout.projectDirectory.file("llm-model-v1.zip"))
    manifest.set(rootProject.layout.projectDirectory.file("llm/models.sha256"))
    archiveSha256.set("eafc3c6fd909bdc684510011ba1d043aac7ca2f7ae33be1f9bd78d80f60630c5")
    outputDirectory.set(layout.buildDirectory.dir("generated/llmAssets"))
}

android {
    namespace = "com.example.onwin"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.god4claw.onwin"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // LongInputTest нужны часовые файлы на устройстве (scripts/push_long_audio.sh),
        // поэтому он идёт только с -PlongAudioTests=true.
        if (providers.gradleProperty("longAudioTests").orNull != "true") {
            testInstrumentationRunnerArguments["notClass"] = "com.example.onwin.data.audio.LongInputTest"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            signingConfig = signingConfigs.getByName("debug")
        }
        // Instrumentation must not replace an installed copy of the app or its data.
        create("nonMinifiedRelease") {
            applicationIdSuffix = ".profile"
            matchingFallbacks += "release"
            optimization { enable = false }
            signingConfig = signingConfigs.getByName("debug")
        }
        create("benchmarkRelease") {
            applicationIdSuffix = ".profile"
            matchingFallbacks += "release"
            optimization { enable = true }
            signingConfig = signingConfigs.getByName("debug")
        }
        configureEach {
            //noinspection ChromeOsAbiSupport
            ndk.abiFilters += when (name) {
                "debug" -> listOf("arm64-v8a", "x86_64")
                "release" -> listOf(releaseAbi)
                "nonMinifiedRelease" -> listOf(profileAbi)
                else -> listOf("arm64-v8a")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    androidResources {
        noCompress += listOf("onnx", "txt", "zip")
    }
    packaging {
        jniLibs {
            // 32-битный x86 не нужен: sherpa-onnx и onnxruntime-android
            // везут для него разные libonnxruntime.so с одним именем.
            excludes += listOf("**/x86/**", "**/armeabi-v7a/**")
        }
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        named("test") { kotlin.directories += "src/sharedTest/kotlin" }
        named("androidTest") { kotlin.directories += "src/sharedTest/kotlin" }
    }
}

baselineProfile {
    saveInSrc = true
    automaticGenerationDuringBuild = false
}

androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) {
    it.hostTests.getValue(HostTestBuilder.UNIT_TEST_TYPE).enable = true
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareLlmAssets, PrepareLlmAssets::outputDirectory)
    val mergeAssetsTask = "merge${variant.name.replaceFirstChar(Char::uppercaseChar)}Assets"
    tasks.matching { it.name == mergeAssetsTask }.configureEach {
        dependsOn(":speech:verifyBundledModels")
    }
}

ksp {
    // Схема Room хранится в репозитории: по ней пишутся и проверяются миграции.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":speech"))
    implementation(project(":llm"))
    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.profileinstaller)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.balloon)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
