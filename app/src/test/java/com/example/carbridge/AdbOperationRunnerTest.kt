package com.example.carbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AdbOperationRunnerTest {
    @Test fun timeoutKeepsNewRequestsBlockedUntilCloseAndRejectsOldPreferenceCommit() {
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val newFinished = CountDownLatch(1)
        val oldCommitted = AtomicBoolean(false)
        val closeThread = AtomicReference<String>()
        val runner = AdbOperationRunner(
            cancelTransport = {
                closeThread.set(Thread.currentThread().name)
                closeStarted.countDown()
                check(releaseClose.await(3, TimeUnit.SECONDS))
            },
            onState = { state ->
                if (!state.busy && state.message == "deadline") closeFinished.countDown()
                if (!state.busy && state.message == "new result") newFinished.countDown()
            },
        )
        try {
            runner.start("old", "working", 250, "deadline", "failed") { context ->
                oldStarted.countDown()
                // Model a library call that returns late after ignoring interruption.
                var released = false
                while (!released) {
                    try { released = releaseOld.await(3, TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { }
                }
                context.commit { oldCommitted.set(true) }
                "stale success"
            }
            assertTrue(oldStarted.await(2, TimeUnit.SECONDS))
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            assertTrue(runner.state().busy)
            assertTrue(runner.state().message.contains("deadline"))
            assertFalse(runner.start("too_early", "new", 1000, "deadline", "failed") { "must not run" })
            assertTrue(closeThread.get().contains("setup-cancel"))
            releaseClose.countDown()
            assertTrue(closeFinished.await(2, TimeUnit.SECONDS))
            assertTrue(runner.start("new", "working", 1500, "second deadline", "failed") { context ->
                context.commit { assertFalse(oldCommitted.get()) }
                "new result"
            })
            releaseOld.countDown()
            assertTrue(newFinished.await(2, TimeUnit.SECONDS))
            assertFalse(oldCommitted.get())
            assertEquals("new result", runner.state().message)
        } finally {
            releaseClose.countDown()
            releaseOld.countDown()
            runner.close()
        }
    }

    @Test fun exceptionFeedbackRedactsPairingCodeAndAllowsNextRequest() {
        val finished = CountDownLatch(1)
        val runner = AdbOperationRunner(cancelTransport = {}, onState = {
            if (!it.busy) finished.countDown()
        })
        try {
            runner.start("pair", "pairing", 2000, "deadline", "retry", secret = "123456",
                dialogTitle = "result") { throw IllegalStateException("rejected code 123456") }
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            val result = runner.state()
            assertFalse(result.message.contains("123456"))
            assertTrue(result.message.contains("[код скрыт]"))
            assertTrue(result.dialogId > 0)
            runner.acknowledgeDialog(result.dialogId)
            assertEquals(0L, runner.state().dialogId)
            assertTrue(runner.start("retry", "retry", 2000, "deadline", "failed") { "ok" })
        } finally { runner.close() }
    }
}
