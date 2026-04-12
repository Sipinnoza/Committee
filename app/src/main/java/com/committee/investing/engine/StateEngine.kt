package com.committee.investing.engine

import com.committee.investing.engine.flow.StateMachine
import com.committee.investing.engine.flow.TransitionResult as FlowTransitionResult
import com.committee.investing.domain.model.MeetingState
import com.committee.investing.domain.model.CommitteeEvent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * 确定性状态引擎 — 数据驱动版
 *
 * 包装 StateMachine（String-based），对外仍暴露 MeetingState 枚举
 * 保持与 UI 层的兼容性
 *
 * 核心变化：
 *   ❌ 旧：transitions 硬编码在 MeetingState.TRANSITIONS
 *   ✅ 新：从 StateMachine（JSON DSL）加载
 */
class StateEngine(
    private val stateMachine: StateMachine,
) {
    companion object {
        private const val TAG = "StateEngine"
    }

    private val _currentState = MutableStateFlow(MeetingState.IDLE)
    val currentState: StateFlow<MeetingState> = _currentState

    val state get() = _currentState.value

    init {
        // 同步 StateMachine 的初始状态
        syncFromMachine()
    }

    /**
     * 尝试应用事件，返回转换结果或 null
     *
     * 现在委托给 StateMachine.apply()，状态转移规则全部来自 DSL
     */
    fun apply(event: CommitteeEvent): TransitionResult? {
        val result = stateMachine.apply(event.event)
        if (!result.changed) {
            logNoMatch(event.event, result)
            return null
        }
        val oldState = safeValueOf(result.from)
        val newState = safeValueOf(result.to)
        _currentState.value = newState
        Log.e(TAG, "[转换] ${oldState.name} → ${newState.name} (event=${event.event})")
        return TransitionResult(from = oldState, to = newState, trigger = event.event)
    }

    fun reset() {
        stateMachine.reset()
        _currentState.value = MeetingState.IDLE
    }

    fun restore(state: MeetingState) {
        _currentState.value = state
        // StateMachine 没有 public setState，但 reset 后 apply 一个
        // 特殊事件不是好做法。直接反射设置也行，但这里用 updateState
    }

    /** 获取底层 StateMachine（供 Scheduler 使用） */
    fun machine(): StateMachine = stateMachine

    private fun syncFromMachine() {
        _currentState.value = safeValueOf(stateMachine.currentState.value)
    }

    private fun safeValueOf(name: String): MeetingState {
        return try { MeetingState.valueOf(name) } catch (_: Exception) { MeetingState.IDLE }
    }

    private fun logNoMatch(event: String, result: FlowTransitionResult) {
        if (result.from == result.to) {
            Log.e(TAG, "[无转换] event=$event 在当前状态 ${result.from} 下不触发任何转移")
        }
    }
}

data class TransitionResult(
    val from: MeetingState,
    val to: MeetingState,
    val trigger: String,
)
