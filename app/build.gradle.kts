import com.android.build.api.variant.HostTestBuilder

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
}

val profileAbi = if (providers.gradleProperty("onwinProfileX86").orNull == "true") "x86_64" else "arm64-v8a"

android {
    namespace = "com.example.onwin"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.onwin"
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
        }
        // Instrumentation must not replace an installed copy of the app or its data.
        create("nonMinifiedRelease") {
            applicationIdSuffix = ".profile"
            matchingFallbacks += "release"
            optimization { enable = false }
        }
        create("benchmarkRelease") {
            applicationIdSuffix = ".profile"
            matchingFallbacks += "release"
            optimization { enable = true }
        }
        configureEach {
            //noinspection ChromeOsAbiSupport
            ndk.abiFilters += if (name == "nonMinifiedRelease") profileAbi else "arm64-v8a"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    androidResources {
        noCompress += listOf("onnx", "txt")
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

ksp {
    // Схема Room хранится в репозитории: по ней пишутся и проверяются миграции.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":speech"))
    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.profileinstaller)
    implementation(platform(libs.androidx.compose.bom))
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
