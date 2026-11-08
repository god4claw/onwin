import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.llm"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 29

        // sherpa-onnx и onnxruntime-android оба везут libonnxruntime.so для x86;
        // 32-битные ABI проекту не нужны, и без них нет конфликта имён.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs {
            // 32-битные ABI не поддерживаются: sherpa-onnx и onnxruntime-android
            // везут разные libonnxruntime.so для x86, и они конфликтуют по имени.
            excludes += listOf("**/x86/**", "**/armeabi-v7a/**")
        }
    }
}

dependencies {
    // Только контракт TextGenerator/TextCorrector: рантайм живёт здесь, а не в :speech.
    api(project(":speech"))

    // GenAI v0.19.0 с нативным ядром без телеметрии и выравниванием ELF на 16 КБ.
    implementation("ai.onnxruntime.genai:onnxruntime-genai-android:0.19.0")
    // GenAI грузит ядро ORT отдельной библиотекой; та же версия, что в релизе GenAI.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

/**
 * Проверяет, что в `llm/models/<model>/` лежит комплект ONNX GenAI и хеши файлов
 * совпадают с `models.sha256`. Этот комплект используется в отдельных тестах :llm.
 * APK приложения включает архив, который проверяет `:app:prepareLlmAssets`.
 */
tasks.register("verifyLlmModel") {
    group = "verification"
    description = "Проверяет наличие и SHA-256 LLM-модели в llm/models по models.sha256."

    val manifestFile = project.file("models.sha256")
    val modelsDir = project.file("models")

    doLast {
        require(manifestFile.isFile) { "Не найден манифест: ${manifestFile.path}" }

        var checked = 0
        var failures = 0
        manifestFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                require(parts.size == 2) { "Плохая строка в models.sha256: $line" }

                val expected = parts[0].lowercase()
                val relativePath = parts[1]
                val file = File(modelsDir, relativePath)
                checked++

                if (!file.isFile) {
                    logger.error("Нет файла: ${file.path}")
                    failures++
                } else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                    if (actual != expected) {
                        logger.error("SHA-256 не совпал: ${file.path}\n  ожидался: $expected\n  получен:  $actual")
                        failures++
                    }
                }
            }

        if (failures > 0) {
            throw GradleException(
                "Не хватает $failures файлов модели. Запустите llm/scripts/fetch-model.ps1.",
            )
        }
        logger.lifecycle("LLM model: OK ($checked файлов)")
    }
}
