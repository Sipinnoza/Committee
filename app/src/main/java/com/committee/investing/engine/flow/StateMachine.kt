package com.committee.investing.engine.flow

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  通用状态机引擎
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  核心原则：
 *    1. 所有推进都通过 event → state transition
 *    2. 状态机本身是纯函数，无 side-effect
 *    3. side-effect 由外部（Looper）根据 transition result 执行
 *
 *  用法：
 *    val sm = StateMachine(flow, initialState = "IDLE")
 *    val result = sm.apply("meeting_requested")
 *    // result = TransitionResult(from="IDLE", to="VALIDATING", trigger="meeting_requested")
 *
 *    // 查询当前 phase 配置
 *    val phase = sm.currentPhaseDef()
 *    // phase = PhaseDef(strategy=ONCE, agents=["analyst"], ...)
 */

data class TransitionResult(
    val from: String,
    val to: String,
    val trigger: String,
    val changed: Boolean,   // false = 没有匹配的 transition，状态未变
)

class StateMachine(
    private val flow: FlowDefinition,
    initialState: String = "IDLE",
) {
    private val _currentState = MutableStateFlow(initialState)
    val currentState: StateFlow<String> = _currentState.asStateFlow()

    // 构建转移查找表：Map<eventName, List<TransitionDef>>
    // 按 from=null（wildcard）排在后面，确保具体匹配优先
    private val transitionIndex: Map<String, List<TransitionDef>> =
        flow.transitions
            .groupBy { it.on }
            .mapValues { (_, list) ->
                list.sortedBy { if (it.from == null) 1 else 0 }
            }

    /**
     * 纯函数：尝试应用一个事件，返回转移结果
     * 如果匹配到 transition，更新 currentState
     * 如果没有匹配，返回 changed=false
     */
    fun apply(event: String): TransitionResult {
        val candidates = transitionIndex[event] ?: return TransitionResult(
            from = _currentState.value,
            to = _currentState.value,
            trigger = event,
            changed = false,
        )

        val current = _currentState.value
        // 优先匹配 from == currentState，其次匹配 wildcard (from=null)
        val matched = candidates.firstOrNull { it.from == current }
            ?: candidates.firstOrNull { it.from == null }

        if (matched != null) {
            _currentState.value = matched.to
            return TransitionResult(
                from = current,
                to = matched.to,
                trigger = event,
                changed = true,
            )
        }

        return TransitionResult(
            from = current,
            to = current,
            trigger = event,
            changed = false,
        )
    }

    /** 当前状态是否匹配 */
    fun isIn(state: String): Boolean = _currentState.value == state

    /** 获取当前 state 对应的 phase 配置，无则 null */
    fun currentPhaseDef(): PhaseDef? = flow.phases[_currentState.value]

    /** 获取指定 state 的 phase 配置 */
    fun phaseDefOf(state: String): PhaseDef? = flow.phases[state]

    /** 获取所有 role 定义 */
    fun roles(): List<RoleDef> = flow.roles

    /** 按 id 查 role */
    fun roleOf(id: String): RoleDef? = flow.roles.find { it.id == id }

    /** 重置到初始状态 */
    fun reset() {
        _currentState.value = "IDLE"
    }

    /** 终止配置 */
    fun termination(): TerminationDef = flow.termination

    override fun toString(): String = "StateMachine(state=${_currentState.value})"
}
