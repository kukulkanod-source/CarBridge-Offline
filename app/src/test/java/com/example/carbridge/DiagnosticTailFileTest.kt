package com.example.carbridge

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiagnosticTailFileTest {
    @Test fun delayedDiagnosticsAppendOnlyToTheCapturedSession() {
        val directory = Files.createTempDirectory("carbridge-tail").toFile()
        try {
            val previous = File(directory, "previous.log").apply { writeText("SESSION_END\n") }
            val current = File(directory, "current.log").apply { writeText("SESSION_START\n") }
            assertTrue(DiagnosticTailFile.append(previous, "POST_SESSION_EVENT port=CLOSED\n".toByteArray(), 1024))
            assertEquals("SESSION_START\n", current.readText())
            assertTrue(previous.readText().contains("SESSION_END\nPOST_SESSION_EVENT"))
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun aPurgedJournalIsNotRecreatedByADelayedResult() {
        val directory = Files.createTempDirectory("carbridge-tail").toFile()
        try {
            val purged = File(directory, "purged.log").apply { writeText("SESSION_END\n"); delete() }
            assertFalse(DiagnosticTailFile.append(purged, "late result\n".toByteArray(), 1024))
            assertFalse(purged.exists())
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun lateResultsRespectTheSameByteLimitAsTheOriginalJournal() {
        val file = Files.createTempFile("carbridge-tail", ".log").toFile()
        try {
            file.writeBytes(ByteArray(100) { 65 })
            assertFalse(DiagnosticTailFile.append(file, ByteArray(11), 110))
            assertEquals(100, file.length())
            assertTrue(DiagnosticTailFile.append(file, ByteArray(10), 110))
            assertEquals(110, file.length())
        } finally { file.delete() }
    }
}
