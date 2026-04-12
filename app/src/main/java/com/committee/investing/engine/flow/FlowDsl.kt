package com.committee.investing.engine.flow

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  Flow DSL — 把委员会流程从代码变成数据
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  核心思想：
 *    流程 = 数据（JSON），不是代码
 *    调度 = 查表，不是 if-else
 *    状态 = 转移表驱动，不是硬编码
 *
 *  加载优先级：
 *    1) filesDir/flows/{name}.json — 用户自定义
 *    2) assets/flows/{name}.json  — 随 APK 分发
 *    3) 硬编码默认值
 */

// ─── DSL 数据模型 ────────────────────────────────────────────────

data class FlowDefinition(
    val version: Int = 1,
    val name: String = "",
    val roles: List<RoleDef> = emptyList(),
    val states: List<String> = emptyList(),
    val transitions: List<TransitionDef> = emptyList(),
    val phases: Map<String, PhaseDef> = emptyMap(),
    val termination: TerminationDef = TerminationDef(),
)

data class RoleDef(
    val id: String,
    val displayName: String,
    val stance: String,
    val systemPromptKey: String,
)

data class TransitionDef(
    val from: String?,          // null = wildcard（任意状态都能触发）
    val on: String,             // 事件名
    val to: String,             // 目标状态
)

enum class Strategy {
    @SerializedName("ONCE")        ONCE,
    @SerializedName("PARALLEL")    PARALLEL,
    @SerializedName("ROUND_ROBIN") ROUND_ROBIN,
}

data class PhaseDef(
    val strategy: Strategy,
    val agents: List<String>,
    val timeoutMs: Long,
    val task: String,
    val maxRounds: Int = 1,
    val barrierEvent: String? = null,        // PARALLEL 用：等所有 agent 完成的事件名
    val onComplete: String,                  // 正常完成触发的事件
    val onError: String? = null,             // 出错触发的事件
    val onReject: String? = null,            // 被拒绝触发的事件
    val requiresConfirmation: Boolean = false,
    val earlyExit: EarlyExitDef? = null,
    val preHooks: List<PreHookDef> = emptyList(),
)

data class EarlyExitDef(
    val marker: String,                      // 在 agent 回复中检测的标记（如 "CONSENSUS_REACHED"）
    val targetEvent: String,                 // 检测到后触发的事件
)

data class PreHookDef(
    val round: Int,
    val agent: String,
    val task: String,
)

data class TerminationDef(
    val maxRounds: Int = 8,
    val strategy: String = "supervisor_override",
    val fallbackState: String = "FINAL_RATING",
)

// ─── 解析器 ─────────────────────────────────────────────────────

object FlowParser {
    private val gson = Gson()

    fun parse(json: String): FlowDefinition {
        return gson.fromJson(json, FlowDefinition::class.java)
    }

    /**
     * 验证 FlowDefinition 的完整性
     * 返回错误列表，空 = 合法
     */
    fun validate(flow: FlowDefinition): List<String> {
        val errors = mutableListOf<String>()

        // 检查 states
        if (flow.states.isEmpty()) errors += "states 为空"
        if ("IDLE" !in flow.states) errors += "缺少初始状态 IDLE"

        // 检查 transitions 引用的状态都在 states 里
        val stateSet = flow.states.toSet()
        for (t in flow.transitions) {
            if (t.from != null && t.from !in stateSet) errors += "transition from='${t.from}' 不在 states 里"
            if (t.to !in stateSet) errors += "transition to='${t.to}' 不在 states 里"
        }

        // 检查 phases 引用的状态
        for ((state, phase) in flow.phases) {
            if (state !in stateSet) errors += "phase '$state' 不在 states 里"
            for (agent in phase.agents) {
                if (flow.roles.none { it.id == agent }) errors += "phase '$state' 引用了未知 agent '$agent'"
            }
        }

        // 检查 roles 唯一性
        val roleIds = flow.roles.map { it.id }
        val dupes = roleIds.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (dupes.isNotEmpty()) errors += "重复的 role id: $dupes"

        return errors
    }
}
