package com.committee.investing.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.committee.investing.engine.flow.PhaseDef
import com.committee.investing.engine.flow.StateMachine
import com.committee.investing.domain.model.MeetingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 流程可视化数据
 *
 * 自动监听 StateMachine.currentState，无需外部驱动
 */
data class FlowVizState(
    val flowName: String = "",
    val states: List<VizState> = emptyList(),
    val edges: List<VizEdge> = emptyList(),
    val currentState: String = "IDLE",
    val currentPhase: PhaseDef? = null,
    val transitionHistory: List<TransitionRecord> = emptyList(),
)

data class VizState(
    val name: String,
    val displayName: String,
    val isActive: Boolean,
    val isCompleted: Boolean,
)

data class VizEdge(
    val from: String?,
    val to: String,
    val event: String,
)

data class TransitionRecord(
    val from: String,
    val to: String,
    val event: String,
    val timestamp: Long,
)

@HiltViewModel
class FlowVizViewModel @Inject constructor(
    private val stateMachine: StateMachine,
) : ViewModel() {

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<FlowVizState> = _state.asStateFlow()

    private var lastState: String = "IDLE"

    init {
        // 自动监听状态机变化
        viewModelScope.launch {
            stateMachine.currentState.collect { newState ->
                if (newState != lastState) {
                    val oldState = lastState
                    lastState = newState
                    val history = _state.value.transitionHistory + TransitionRecord(
                        from = oldState,
                        to = newState,
                        event = "",  // event 名在 Looper 里才能拿到
                        timestamp = System.currentTimeMillis(),
                    )
                    _state.value = buildState().copy(transitionHistory = history)
                }
            }
        }
    }

    /** 外部记录转场（由 CommitteeLooper 调用，传入 event 名） */
    fun recordTransition(from: String, to: String, event: String) {
        val record = TransitionRecord(from, to, event, System.currentTimeMillis())
        _state.value = _state.value.copy(
            transitionHistory = _state.value.transitionHistory + record,
        )
    }

    private fun buildState(): FlowVizState {
        val flow = stateMachine.flow
        val current = stateMachine.currentState.value

        val displayNames = mapOf(
            "IDLE" to "待机", "VALIDATING" to "入场评估", "REJECTED" to "已拒绝",
            "PREPPING" to "并行准备", "PHASE1_DEBATE" to "多方辩论",
            "PHASE1_ADJUDICATING" to "仲裁", "PHASE2_ASSESSMENT" to "风险评估",
            "FINAL_RATING" to "发布评级", "APPROVED" to "已批准", "COMPLETED" to "完成",
        )

        return FlowVizState(
            flowName = flow.name,
            states = flow.states.map { name ->
                VizState(
                    name = name,
                    displayName = displayNames[name] ?: name,
                    isActive = name == current,
                    isCompleted = isStateCompleted(name, current),
                )
            },
            edges = flow.transitions.map { t ->
                VizEdge(from = t.from, to = t.to, event = t.on)
            },
            currentState = current,
            currentPhase = stateMachine.phaseDefOf(current),
        )
    }

    private fun isStateCompleted(stateName: String, currentState: String): Boolean {
        val flow = stateMachine.flow
        val idx = flow.states.indexOf(stateName)
        val curIdx = flow.states.indexOf(currentState)
        return idx >= 0 && curIdx >= 0 && idx < curIdx
    }
}
