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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.sherpa.onnx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
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
                                    "  получен:  $actual"
                        )
                        failures++
                    }
                }
            }

        if (failures > 0) {
            throw GradleException(
                "Не хватает $failures файлов моделей. Скачайте speech-models-v1.zip " +
                        "из GitHub Releases и распакуйте в speech/src/main/assets/."
            )
        }
        logger.lifecycle("Bundled models: OK ($checked файлов)")
    }
}
