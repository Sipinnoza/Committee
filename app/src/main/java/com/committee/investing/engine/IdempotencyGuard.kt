package com.committee.investing.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * 幂等性守卫
 * 规格文档 §1.1 — 同一事件处理两次不会产生副作用
 */
class IdempotencyGuard {
    private val processed = ConcurrentHashMap.newKeySet<String>()

    /** 返回 true 表示首次处理（应继续）；false 表示已处理过（跳过） */
    fun tryProcess(eventId: String): Boolean = processed.add(eventId)

    fun markProcessed(eventId: String) { processed.add(eventId) }

    fun isProcessed(eventId: String): Boolean = eventId in processed

    fun restore(eventIds: Set<String>) { processed.addAll(eventIds) }

    fun clear() { processed.clear() }

    val size: Int get() = processed.size
}
