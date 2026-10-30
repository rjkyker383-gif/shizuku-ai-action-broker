package com.rjkyker.shizukuai.broker.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class ModelStoreTest {
    private val data = ByteArray(1_234_567) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun spec(hash: String = sha, size: Long = data.size.toLong()) =
        ModelSpec("t", "r", "rev", "t.gguf", size, hash, "MIT", 1)
    private fun dir() = Files.createTempDirectory("ms").toFile()

    @Test fun downloadsVerifiesAndRenames() {
        val f = File(dir(), "t.gguf")
        ModelStore.download(f, spec(), { off -> ByteArrayInputStream(data, off.toInt(), data.size - off.toInt()) to (off > 0) })
        assertTrue(ModelStore.isVerifiedPresent(f, spec()))
        assertFalse(File(f.parentFile, "t.gguf.part").exists())
        assertEquals(sha, ModelStore.sha256(f))
    }

    @Test fun shaMismatchDeletesPartialAndThrows() {
        val f = File(dir(), "t.gguf")
        try {
            ModelStore.download(f, spec(hash = "0".repeat(64)), { ByteArrayInputStream(data) to false }); fail()
        } catch (e: ModelStore.VerifyException) { assertTrue(e.message!!.contains("sha256")) }
        assertFalse(f.exists()); assertFalse(File(f.parentFile, "t.gguf.part").exists())
    }

    @Test fun resumesFromPartial() {
        val d = dir(); val f = File(d, "t.gguf")
        File(d, "t.gguf.part").writeBytes(data.copyOfRange(0, 500_000))
        var askedOffset = -1L
        ModelStore.download(f, spec(), { off -> askedOffset = off; ByteArrayInputStream(data, off.toInt(), data.size - off.toInt()) to true })
        assertEquals(500_000L, askedOffset)
        assertTrue(ModelStore.isVerifiedPresent(f, spec()))
    }

    @Test fun serverIgnoringRangeRestartsCleanly() {
        val d = dir(); val f = File(d, "t.gguf")
        File(d, "t.gguf.part").writeBytes(ByteArray(1000) { 7 })
        ModelStore.download(f, spec(), { ByteArrayInputStream(data) to false })
        assertTrue(ModelStore.isVerifiedPresent(f, spec()))
    }

    @Test fun oversizeStreamRejected() {
        val f = File(dir(), "t.gguf")
        try { ModelStore.download(f, spec(size = 1000), { ByteArrayInputStream(data) to false }); fail() }
        catch (e: ModelStore.VerifyException) { assertTrue(e.message!!.contains("more than")) }
    }

    @Test fun markerWithoutMatchingHashIsNotPresent() {
        val d = dir(); val f = File(d, "t.gguf"); f.writeBytes(data)
        File(d, "t.gguf.sha256").writeText("deadbeef")
        assertFalse(ModelStore.isVerifiedPresent(f, spec()))
    }
}
