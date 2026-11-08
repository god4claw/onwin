import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.speech"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 29

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // JNI-классы sherpa-onnx нельзя переименовывать в release-сборке приложения.
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    androidResources {
        noCompress += listOf("onnx", "txt")
    }
}

dependencies {
    implementation("com.k2fsa.sherpa.onnx:sherpa-onnx:1.13.0")

    implementation(libs.androidx.core.ktx)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

tasks.register("verifyBundledModels") {
    group = "verification"
    description = "Проверяет наличие и SHA-256 весов в src/main/assets по models.sha256."

    val manifestFile = project.file("models.sha256")
    val assetsDir = project.file("src/main/assets")

    doLast {
        require(manifestFile.isFile) {
            "Не найден манифест: ${manifestFile.path}"
        }

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
                val file = File(assetsDir, relativePath)
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
                    val actual = digest.digest()
                        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                    if (actual != expected) {
                        logger.error(
                            "SHA-256 не совпал: ${file.path}\n" +
                                "  ожидался: $expected\n" +
                                "  получен:  $actual",
                        )
                        failures++
                    }
                }
            }

        if (failures > 0) {
            throw GradleException(
                "Не хватает $failures файлов моделей. Скачайте веса по INSTALL.md " +
                    "(раздел «2. Веса») в speech/src/main/assets/.",
            )
        }
        logger.lifecycle("Bundled models: OK ($checked файлов)")
    }
}
