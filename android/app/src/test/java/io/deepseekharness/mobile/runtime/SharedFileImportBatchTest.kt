package io.deepseekharness.mobile.runtime

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedFileImportBatchTest {
    @Test
    fun sourceFailureKeepsTheAcceptedCountAndDeliveryResult() {
        val operations = mutableListOf<String>()
        val result = SharedFileImportBatch.run(listOf("first", "oversized", "unattempted"), importOne = { _, name ->
            operations += "import:$name"
            if (name == "first") SharedFileImportBatch.Status.SUCCESS else SharedFileImportBatch.Status.TOO_LARGE
        }, deliver = {
            operations += "deliver"
            true
        })
        assertEquals(listOf("import:first", "deliver", "import:oversized"), operations)
        assertEquals(SharedFileImportBatch.Status.TOO_LARGE, result.status)
        assertEquals(1, result.acceptedCount)
        assertTrue(result.deliveryComplete)
    }

    @Test
    fun sourceFailureReportsWhenAcceptedFilesRemainStaged() {
        val result = SharedFileImportBatch.run(listOf("first", "unreadable"), importOne = { _, name ->
            if (name == "first") SharedFileImportBatch.Status.SUCCESS else throw IOException("unavailable")
        }, deliver = { false })
        assertEquals(SharedFileImportBatch.Status.SOURCE_UNREADABLE, result.status)
        assertEquals(1, result.acceptedCount)
        assertFalse(result.deliveryComplete)
    }

    @Test
    fun interruptionStopsTheNextSourceAfterSuccessfulDelivery() {
        val imported = mutableListOf<String>()
        val result = try {
            SharedFileImportBatch.run(listOf("first", "unattempted"), importOne = { _, name ->
                imported += name
                SharedFileImportBatch.Status.SUCCESS
            }, deliver = {
                Thread.currentThread().interrupt()
                true
            })
        } finally {
            Thread.interrupted()
        }
        assertEquals(listOf("first"), imported)
        assertEquals(SharedFileImportBatch.Status.SOURCE_UNREADABLE, result.status)
        assertEquals(1, result.acceptedCount)
        assertTrue(result.deliveryComplete)
    }

    @Test
    fun successfulBatchCanFinishWithAllFilesSafelyStaged() {
        val result = SharedFileImportBatch.run(listOf("first"), importOne = { _, _ -> SharedFileImportBatch.Status.SUCCESS }, deliver = { false })
        assertEquals(SharedFileImportBatch.Status.STAGED, result.status)
        assertEquals(1, result.acceptedCount)
        assertFalse(result.deliveryComplete)
    }

    @Test
    fun errorFailureStillKeepsTheAcceptedCount() {
        val result = SharedFileImportBatch.run(listOf("first", "broken"), importOne = { _, name ->
            if (name == "first") SharedFileImportBatch.Status.SUCCESS else throw OutOfMemoryError("simulated")
        }, deliver = { true })
        assertEquals(SharedFileImportBatch.Status.SOURCE_UNREADABLE, result.status)
        assertEquals(1, result.acceptedCount)
        assertTrue(result.deliveryComplete)
    }
}
