package com.committee.investing.engine

import com.committee.investing.domain.model.MeetingState
import com.committee.investing.domain.model.CommitteeEvent
import com.committee.investing.domain.model.Transition
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 确定性状态引擎（FSM）
 * 规格文档 §4.2 — 纯 FSM，确定性转换，无 LLM 参与
 */
class StateEngine(
    initialState: MeetingState = MeetingState.IDLE,
    private val transitions: List<Transition> = MeetingState.TRANSITIONS,
) {
    companion object {
        private const val TAG = "StateEngine"
    }

    private val _currentState = MutableStateFlow(initialState)
    val currentState: StateFlow<MeetingState> = _currentState.asStateFlow()

    val state get() = _currentState.value

    /**
     * 尝试应用事件，返回转换结果或 null（事件不触发转换）
     */
    fun apply(event: CommitteeEvent): TransitionResult? {
        val evt = event.event
        for (t in transitions) {
            val fromMatch = t.from == null || t.from == _currentState.value
            if (t.on == evt && fromMatch) {
                val old = _currentState.value
                _currentState.value = t.to
                Log.e(TAG, "[转换] ${old.name} → ${t.to.name} (event=$evt)")
                return TransitionResult(from = old, to = t.to, trigger = evt)
            }
        }
        // 没有匹配的转换 — 记录原因
        val matchingByEvent = transitions.filter { it.on == evt }
        if (matchingByEvent.isEmpty()) {
            Log.e(TAG, "[无转换] event=$evt 不在任何转换规则的 on 字段中")
        } else {
            val blocked = matchingByEvent.filter { it.from != null && it.from != _currentState.value }
            if (blocked.isNotEmpty()) {
                Log.e(TAG, "[无转换] event=$evt 有 ${blocked.size} 条规则但 from 不匹配: 当前=${_currentState.value.name}, 需要=${blocked.map { it.from?.name }}")
            }
        }
        return null
    }

    fun reset() {
        _currentState.value = MeetingState.IDLE
    }

    fun restore(state: MeetingState) {
        _currentState.value = state
    }
}

data class TransitionResult(
    val from: MeetingState,
    val to: MeetingState,
    val trigger: String,
)
