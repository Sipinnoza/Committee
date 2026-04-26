package com.znliang.committee.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.znliang.committee.R
import com.znliang.committee.engine.runtime.AgentRuntime
import com.znliang.committee.engine.runtime.BoardPhase
import com.znliang.committee.ui.model.UiPhase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FlowVizState(
    val flowName: String = "",                          // 流程名称
    val states: List<VizState> = emptyList(),           // 可视化状态节点列表
    val edges: List<VizEdge> = emptyList(),             // 状态转换边列表
    val currentState: String = "",                      // 当前状态名
    val currentPhase: UiPhase = UiPhase.IDLE,           // 当前会议阶段
    val transitionHistory: List<TransitionRecord> = emptyList(), // 状态转换历史
    /** All agent role IDs from the current preset (dynamic, not hardcoded) */
    val agentRoleIds: List<String> = emptyList(),       // 当前预设的Agent角色ID列表
)

data class VizState(
    val name: String,               // 状态内部名称
    val displayName: String,        // 状态显示名称（国际化）
    val isActive: Boolean,          // 是否为当前活跃状态
    val isCompleted: Boolean,       // 是否已完成
)

data class VizEdge(
    val from: String?,              // 起始状态（null表示初始边）
    val to: String,                 // 目标状态
    val event: String,              // 触发事件名称
)

data class TransitionRecord(
    val from: String,               // 转换前状态
    val to: String,                 // 转换后状态
    val event: String,              // 触发事件
    val timestamp: Long,            // 转换时间戳
)

@HiltViewModel
class FlowVizViewModel @Inject constructor(
    private val runtime: AgentRuntime,
    private val app: android.app.Application,
) : ViewModel() {

    private val _state = MutableStateFlow(FlowVizState())
    val state: StateFlow<FlowVizState> = _state.asStateFlow()

    private var lastRound = 0

    init {
        viewModelScope.launch {
            runtime.board.collect { board ->
                val history = if (board.round != lastRound) {
                    lastRound = board.round
                    val phaseLabel = board.phase.name
                    _state.value.transitionHistory + TransitionRecord(
                        from = app.getString(R.string.flowviz_round, board.round - 1),
                        to = app.getString(R.string.flowviz_round_phase, board.round, phaseLabel),
                        event = "",
                        timestamp = System.currentTimeMillis(),
                    )
                } else {
                    _state.value.transitionHistory
                }

                val phaseOrder = listOf(
                    BoardPhase.IDLE, BoardPhase.ANALYSIS, BoardPhase.DEBATE,
                    BoardPhase.VOTE, BoardPhase.RATING, BoardPhase.EXECUTION, BoardPhase.DONE,
                )
                val currentIdx = phaseOrder.indexOf(board.phase)

                val displayNames = mapOf(
                    "IDLE" to app.getString(com.znliang.committee.R.string.home_state_idle),
                    "ANALYSIS" to app.getString(com.znliang.committee.R.string.home_state_analysis),
                    "DEBATE" to app.getString(com.znliang.committee.R.string.home_state_debate),
                    "VOTE" to app.getString(com.znliang.committee.R.string.home_state_vote),
                    "RATING" to app.getString(com.znliang.committee.R.string.home_state_rating),
                    "EXECUTION" to app.getString(com.znliang.committee.R.string.home_state_execution),
                    "DONE" to app.getString(com.znliang.committee.R.string.home_state_done),
                )

                _state.value = FlowVizState(
                    flowName = app.getString(R.string.flowviz_agent_runtime),
                    states = phaseOrder.map { phase ->
                        val idx = phaseOrder.indexOf(phase)
                        VizState(
                            name = phase.name,
                            displayName = displayNames[phase.name] ?: phase.name,
                            isActive = phase == board.phase,
                            isCompleted = idx < currentIdx,
                        )
                    },
                    edges = phaseOrder.zipWithNext().map { (from, to) ->
                        VizEdge(from = from.name, to = to.name, event = "")
                    },
                    currentState = board.phase.name,
                    currentPhase = board.phase.toUiPhase(),
                    transitionHistory = history,
                    agentRoleIds = runtime.agents.map { it.role },
                )
            }
        }
    }
}
