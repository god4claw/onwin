package com.example.llm

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LlmModelDirectoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun completeDirectory_isRecognized() {
        val dir = tmp.newFolder("model")
        File(dir, LlmModelDirectory.CONFIG_FILE).writeText("{}")
        File(dir, "model.onnx").writeText("stub")

        assertTrue(LlmModelDirectory.isComplete(dir))
    }

    @Test
    fun quantizedModelFile_isEnough() {
        val dir = tmp.newFolder("model")
        File(dir, LlmModelDirectory.CONFIG_FILE).writeText("{}")
        File(dir, "model_q4.onnx").writeText("stub")

        assertTrue(LlmModelDirectory.isComplete(dir))
    }

    @Test
    fun missingConfig_isRejected() {
        val dir = tmp.newFolder("model")
        File(dir, "model.onnx").writeText("stub")

        assertFalse(LlmModelDirectory.isComplete(dir))
    }

    @Test
    fun configWithoutOnnx_isRejected() {
        val dir = tmp.newFolder("model")
        File(dir, LlmModelDirectory.CONFIG_FILE).writeText("{}")

        assertFalse(LlmModelDirectory.isComplete(dir))
    }

    @Test
    fun missingDirectoryAndNull_areRejected() {
        assertFalse(LlmModelDirectory.isComplete(File(tmp.root, "absent")))
        assertFalse(LlmModelDirectory.isComplete(null))
    }
}
