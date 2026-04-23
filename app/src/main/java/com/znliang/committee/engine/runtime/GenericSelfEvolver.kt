package com.znliang.committee.engine.runtime

import com.znliang.committee.data.repository.EvolutionRepository

/**
 * GenericSelfEvolver -- Universal self-evolution engine for ANY agent role.
 *
 * Replaces the need for per-role evolver classes (AnalystSelfEvolver,
 * RiskSelfEvolver, etc.) by building domain-agnostic reflection prompts
 * that evaluate discussion quality, argument strength, and counter-argument
 * handling -- with NO domain-specific terminology baked in.
 *
 * The role's responsibility description is injected at construction time
 * and used to contextualise the reflection without hard-coding any
 * particular domain vocabulary.
 *
 * Relies on the default [AgentSelfEvolver.getPreMeetingMemory] for
 * pre-meeting memory injection (MISTAKE + STRATEGY experiences).
 */
class GenericSelfEvolver(
    systemLlm: SystemLlmService,
    evolutionRepo: EvolutionRepository,
    private val roleId: String,
    private val roleDisplayName: String,
    private val roleResponsibility: String,
) : AgentSelfEvolver(systemLlm, evolutionRepo) {

    override fun roleName(): String = roleId
    override fun displayName(): String = roleDisplayName

    // ── reflect ─────────────────────────────────────────────────

    override suspend fun reflect(
        board: Blackboard,
        meetingTraceId: String,
    ): AgentReflection {
        // 1. Collect this agent's own messages
        val myMessages = board.messages.filter { it.role == roleId }
        if (myMessages.isEmpty()) return AgentReflection.empty()

        val mySpeeches = myMessages.joinToString("\n\n") { msg ->
            "[Round ${msg.round}] ${msg.content.take(400)}"
        }

        // 2. Collect other agents' messages that reference / challenge this agent
        val challenges = board.messages
            .filter { it.role != roleId }
            .filter { msg ->
                val lower = msg.content.lowercase()
                lower.contains(roleId.lowercase()) ||
                    lower.contains(roleDisplayName.lowercase())
            }
            .joinToString("\n") { msg ->
                "[${msg.role} - Round ${msg.round}] ${msg.content.take(200)}"
            }

        // 3. Build a domain-agnostic outcome summary
        val outcome = buildString {
            append("Discussion subject: ${board.subject}\n")
            append("Total rounds: ${board.round}\n")
            append("Consensus reached: ${board.consensus}\n")
            val myVote = board.votes[roleId]
            if (myVote != null) {
                append("My vote: ${if (myVote.agree) "AGREE" else "DISAGREE"} - ${myVote.reason}\n")
            }
            if (board.finalRating != null) {
                append("Final outcome: ${board.finalRating}\n")
            }
            if (board.summary.isNotBlank()) {
                append("Discussion summary: ${board.summary.take(300)}\n")
            }
        }

        // 4. Build domain-agnostic reflection prompt
        val reflectPrompt = buildReflectionPrompt(mySpeeches, challenges, outcome)

        val raw = systemLlm.quickCall(reflectPrompt)
        if (raw.isBlank()) return AgentReflection.empty()

        return parseReflection(raw, meetingTraceId)
    }

    // ── prompt construction ─────────────────────────────────────

    private fun buildReflectionPrompt(
        mySpeeches: String,
        challenges: String,
        outcome: String,
    ): String = """You are the self-evolution system for the agent "$roleDisplayName" (id: $roleId).

## Role Responsibility
$roleResponsibility

## Your Statements During the Discussion
$mySpeeches

## Challenges / Counter-arguments from Other Agents
${challenges.ifBlank { "(none)" }}

## Discussion Outcome
$outcome

## Task
Reflect on this agent's performance from the perspective of its defined responsibility.
Evaluate the following dimensions:

1. Argument quality: Were your points well-supported with evidence or reasoning? Did you provide concrete, actionable insights rather than vague generalities?
2. Response to challenges: When other agents raised counter-arguments or concerns, did you effectively address them? Which challenges went unanswered?
3. Missing perspectives: Given your role responsibility, what important angles or considerations did you overlook?
4. Strategy improvement: What specific changes in approach, argumentation, or analysis should you make next time to be more effective?

Output format (follow strictly):
QUALITY_SCORE: 1-10 -- (one sentence explaining overall quality)
WEAKNESS: Specific weaknesses or gaps in your argumentation (semicolon-separated if multiple)
LESSON: Key lesson learned from this discussion
STRATEGY_TIP: Concrete strategy or approach to use next time for better results
PROMPT_FIX: Specific improvement suggestion for the agent's system prompt (write "no change needed" if none)
PRIORITY: HIGH / MEDIUM / LOW"""

    // ── response parsing ────────────────────────────────────────

    private suspend fun parseReflection(raw: String, traceId: String): AgentReflection {
        var summary = ""
        var suggestion = ""
        var priority = "MEDIUM"

        for (line in raw.lines()) {
            val t = line.trim()
            when {
                t.startsWith("QUALITY_SCORE:", ignoreCase = true) -> {
                    summary = t.substringAfter(":").trim()
                }

                t.startsWith("WEAKNESS:", ignoreCase = true) -> {
                    val weakness = t.substringAfter(":").trim()
                    if (weakness.isNotBlank() && !isNoneIndicator(weakness)) {
                        saveExperience(
                            category = "MISTAKE",
                            content = "Weakness: $weakness",
                            outcome = "NEGATIVE",
                            priority = "HIGH",
                            traceId = traceId,
                        )
                    }
                }

                t.startsWith("LESSON:", ignoreCase = true) -> {
                    val lesson = t.substringAfter(":").trim()
                    if (lesson.isNotBlank()) {
                        saveExperience(
                            category = "INSIGHT",
                            content = "Lesson: $lesson",
                            traceId = traceId,
                        )
                    }
                }

                t.startsWith("STRATEGY_TIP:", ignoreCase = true) -> {
                    val tip = t.substringAfter(":").trim()
                    if (tip.isNotBlank()) {
                        saveExperience(
                            category = "STRATEGY",
                            content = "Strategy: $tip",
                            outcome = "POSITIVE",
                            priority = "MEDIUM",
                            traceId = traceId,
                        )
                    }
                }

                t.startsWith("PROMPT_FIX:", ignoreCase = true) -> {
                    suggestion = t.substringAfter(":").trim()
                }

                t.startsWith("PRIORITY:", ignoreCase = true) -> {
                    val v = t.substringAfter(":").trim().uppercase()
                    if (v in listOf("HIGH", "MEDIUM", "LOW")) priority = v
                }
            }
        }

        return AgentReflection(
            summary = summary.ifBlank { raw.take(200) },
            suggestion = suggestion,
            priority = priority,
            traceId = traceId,
        )
    }

    /**
     * Check whether the LLM output indicates "nothing" / "none" in either
     * English or Chinese so we avoid persisting empty experiences.
     */
    private fun isNoneIndicator(value: String): Boolean {
        val lower = value.lowercase().trim()
        return lower in listOf("none", "n/a", "na", "no change needed", "-") ||
            lower == "\u65E0" || // 无
            lower == "\u6682\u65E0" // 暂无
    }
}
