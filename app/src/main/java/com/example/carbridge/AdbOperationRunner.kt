package com.example.carbridge

import java.io.Closeable
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A process-owned queue: rotating or closing a screen never abandons an ADB activation. */
internal class AdbOperationRunner(
    private val cancelTransport: () -> Unit,
    private val onState: (State) -> Unit,
    private val log: (String) -> Unit = {},
) : Closeable {
    data class State(
        val revision: Long = 0,
        val message: String = "Подготовьте TLS-подключение, затем активируйте офлайн-режим.",
        val busy: Boolean = false,
        val operationId: Long = 0,
        val dialogId: Long = 0,
        val dialogTitle: String = "",
    )

    inner class Context internal constructor(private val task: Task) {
        fun ensureActive() = synchronized(lock) { requireActive(task) }

        /** Publish the current automated setup step without ending its bounded operation. */
        fun reportProgress(message: String) {
            val next = synchronized(lock) {
                requireActive(task)
                snapshot.copy(revision = ++serial, message = message).also { snapshot = it }
            }
            onState(next)
        }

        /** Prevent a timed-out operation from saving stale connection preferences. */
        fun commit(action: () -> Unit) = synchronized(lock) {
            requireActive(task)
            action()
        }
    }

    internal class Task(
        val id: Long,
        val name: String,
        val deadlineNanos: Long,
        val timeoutMessage: String,
        val dialogTitle: String,
    ) {
        var active = true
        var future: Future<*>? = null
        var deadline: ScheduledFuture<*>? = null
    }

    private val lock = Any()
    private var serial = 0L
    private var current: Task? = null
    private var snapshot = State()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CarBridge-setup-operation").apply { isDaemon = true }
    }
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "CarBridge-setup-deadline").apply { isDaemon = true }
    }
    private val cancellation = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CarBridge-setup-cancel").apply { isDaemon = true }
    }

    fun state(): State = synchronized(lock) { snapshot }

    fun feedback(message: String, dialogTitle: String = "") {
        val next = synchronized(lock) {
            snapshot.copy(revision = ++serial, message = message,
                dialogId = if (dialogTitle.isNotEmpty()) serial else 0L,
                dialogTitle = dialogTitle).also { snapshot = it }
        }
        onState(next)
    }

    fun acknowledgeDialog(dialogId: Long) {
        val next = synchronized(lock) {
            if (snapshot.dialogId != dialogId) return
            snapshot.copy(revision = ++serial, dialogId = 0, dialogTitle = "").also { snapshot = it }
        }
        onState(next)
    }

    fun start(
        name: String,
        message: String,
        timeoutMs: Long,
        timeoutMessage: String,
        failureMessage: String,
        secret: String? = null,
        dialogTitle: String = "",
        operation: (Context) -> String,
    ): Boolean {
        require(timeoutMs > 0)
        val task: Task
        val startState: State
        synchronized(lock) {
            if (current != null) {
                val existing = snapshot.message
                feedback("Операция ADB уже выполняется. $existing")
                return false
            }
            val id = ++serial
            task = Task(id, name, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs),
                timeoutMessage, dialogTitle)
            current = task
            startState = State(id, message, busy = true, operationId = id)
            snapshot = startState
        }
        // The first callback runs on the caller's thread, before network work is submitted.
        onState(startState)
        log("ADB setup #${task.id} ${task.name}: started")
        task.future = worker.submit {
            val result = try {
                val context = Context(task)
                context.ensureActive()
                operation(context)
            } catch (error: Exception) {
                val detail = error.message.orEmpty().let {
                    if (secret.isNullOrEmpty()) it else it.replace(secret, "[код скрыт]")
                }.take(160)
                "Ошибка ADB${if (detail.isBlank()) "" else ": $detail"}. $failureMessage"
            } catch (_: LinkageError) {
                "Не удалось загрузить библиотеку ADB на этом телефоне. $failureMessage"
            }
            finish(task, result)
        }
        task.deadline = timer.schedule({ timeOut(task) }, timeoutMs, TimeUnit.MILLISECONDS)
        return true
    }

    private fun requireActive(task: Task) {
        if (current !== task || !task.active || Thread.currentThread().isInterrupted ||
            System.nanoTime() >= task.deadlineNanos) {
            throw InterruptedIOException("Операция ADB остановлена или время ожидания истекло")
        }
    }

    private fun finish(task: Task, message: String) {
        var expired = false
        val next = synchronized(lock) {
            if (current !== task || !task.active) return
            if (System.nanoTime() >= task.deadlineNanos) {
                expired = true
                null
            } else {
                task.active = false
                current = null
                task.deadline?.cancel(false)
                State(++serial, message, operationId = task.id,
                    dialogId = if (task.dialogTitle.isNotEmpty()) serial else 0L,
                    dialogTitle = task.dialogTitle).also { snapshot = it }
            }
        }
        if (expired) timeOut(task)
        else if (next != null) {
            log("ADB setup #${task.id} ${task.name}: completed")
            onState(next)
        }
    }

    private fun timeOut(task: Task) {
        val cancellingState = synchronized(lock) {
            if (current !== task || !task.active) return
            task.active = false
            State(++serial, "${task.timeoutMessage}\nЗакрываю незавершённое подключение ADB…",
                busy = true, operationId = task.id,
                dialogId = if (task.dialogTitle.isNotEmpty()) serial else 0L,
                dialogTitle = task.dialogTitle).also { snapshot = it }
        }
        log("ADB setup #${task.id} ${task.name}: deadline exceeded")
        onState(cancellingState)
        // Neither a blocked socket close nor future cancellation is ever run on the UI thread.
        cancellation.execute {
            task.future?.cancel(true)
            try { cancelTransport() }
            catch (_: Exception) { log("ADB setup #${task.id}: transport close failed") }
            finally {
                val next = synchronized(lock) {
                    if (current !== task) return@execute
                    current = null
                    snapshot.copy(revision = ++serial, message = task.timeoutMessage, busy = false)
                        .also { snapshot = it }
                }
                // Keep controls busy until disconnect has finished, so it cannot close a new session.
                onState(next)
            }
        }
    }

    /** Keep the slot occupied until transport cleanup ends, so it cannot close a later session. */
    fun cancel(message: String) {
        val task = synchronized(lock) {
            val active = current ?: return
            if (!active.active) return
            active.active = false
            snapshot = snapshot.copy(revision = ++serial, message = "Отменяю подготовку…", busy = true)
            active
        }
        onState(state())
        cancellation.execute {
            task.deadline?.cancel(false)
            task.future?.cancel(true)
            try { cancelTransport() }
            finally {
                val next = synchronized(lock) {
                    if (current !== task) return@execute
                    current = null
                    snapshot.copy(revision = ++serial, message = message, busy = false).also { snapshot = it }
                }
                onState(next)
            }
        }
    }

    override fun close() {
        worker.shutdownNow()
        timer.shutdownNow()
        cancellation.shutdownNow()
    }
}
