package com.znliang.committee.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val flowName: String = "",
    val states: List<VizState> = emptyList(),
    val edges: List<VizEdge> = emptyList(),
    val currentState: String = "",
    val currentPhase: UiPhase = UiPhase.IDLE,
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
                        from = "Round ${board.round - 1}",
                        to = "Round ${board.round} ($phaseLabel)",
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
                    flowName = "Agent Runtime",
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
                )
            }
        }
    }
}
