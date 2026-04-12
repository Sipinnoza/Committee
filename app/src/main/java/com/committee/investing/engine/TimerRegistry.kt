package com.committee.investing.engine

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 定时器注册表
 * 规格文档 §3.2 — 管理所有超时定时器
 */
class TimerRegistry(private val scope: CoroutineScope) {

    private val timers = ConcurrentHashMap<String, Job>()

    /**
     * 注册一个定时器，超时后调用 onTimeout
     * @param timerId    唯一标识，同名新 timer 会取消旧的
     * @param delayMs    延迟毫秒
     * @param onTimeout  超时回调（在协程中执行）
     */
    fun schedule(timerId: String, delayMs: Long, onTimeout: suspend () -> Unit) {
        cancel(timerId)
        val job = scope.launch {
            delay(delayMs)
            onTimeout()
        }
        timers[timerId] = job
    }

    fun cancel(timerId: String) {
        timers.remove(timerId)?.cancel()
    }

    fun cancelAll() {
        timers.values.forEach { it.cancel() }
        timers.clear()
    }

    fun isActive(timerId: String): Boolean = timers[timerId]?.isActive == true

    val activeTimers: Set<String> get() = timers.keys.toSet()
}
