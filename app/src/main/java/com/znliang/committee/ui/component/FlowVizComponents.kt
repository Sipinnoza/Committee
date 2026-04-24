package com.znliang.committee.ui.component

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.znliang.committee.R
import com.znliang.committee.ui.theme.*
import com.znliang.committee.ui.viewmodel.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 顶层常量：线程安全的 DateTimeFormatter（替代 SimpleDateFormat） */
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  流程可视化组件
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  三部分：
 *    1. FlowGraph — 状态机拓扑图（节点 + 连线 + 箭头）
 *    2. PhaseInfoCard — 当前 phase 详情（策略/agents/task）
 *    3. TransitionTimeline — 转场历史时间线
 */

// ── 主入口：嵌入式流程卡片 ──────────────────────────────────────

@Composable
fun FlowVisualizationCard(
    vizState: FlowVizState,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = ButtonDefaults.outlinedButtonBorder,
    ) {
        Column(Modifier.padding(12.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(stringResource(R.string.home_flow_status))
                Text(
                    vizState.flowName,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
            Spacer(Modifier.height(8.dp))

            // 状态机拓扑图
            FlowGraph(vizState)
            Spacer(Modifier.height(8.dp))

            // 当前 Phase 信息
            vizState.currentPhase?.let { phase ->
                PhaseInfoCard(phase, vizState.currentState)
                Spacer(Modifier.height(8.dp))
            }

            // 转场历史（最近 5 条）
            if (vizState.transitionHistory.isNotEmpty()) {
                TransitionTimeline(vizState.transitionHistory.takeLast(5))
            }
        }
    }
}

// ── 状态机拓扑图 ────────────────────────────────────────────────

/**
 * 布局：把 10 个状态排成 3 行的有向图
 *
 *   Row 0:  IDLE ──→ VALIDATING ──→ REJECTED
 *                              └──→ PREPPING
 *   Row 1:  PHASE1_DEBATE ←─────┘
 *           ↓ (consensus)        ↓ (max_rounds)
 *           PHASE1_ADJUDICATING → PHASE2_ASSESSMENT
 *   Row 2:  FINAL_RATING ←──────┘
 *           ↓
 *           APPROVED → COMPLETED
 */
@Composable
fun FlowGraph(flowState: FlowVizState, modifier: Modifier = Modifier) {
    // 节点位置映射（归一化坐标 0~1）
    val nodePositions = remember {
        mapOf(
            "IDLE"                 to Offset(0.06f, 0.12f),
            "VALIDATING"           to Offset(0.35f, 0.12f),
            "REJECTED"             to Offset(0.65f, 0.12f),
            "PREPPING"             to Offset(0.90f, 0.30f),
            "PHASE1_DEBATE"        to Offset(0.08f, 0.50f),
            "PHASE1_ADJUDICATING"  to Offset(0.40f, 0.50f),
            "PHASE2_ASSESSMENT"    to Offset(0.72f, 0.50f),
            "FINAL_RATING"         to Offset(0.30f, 0.85f),
            "APPROVED"             to Offset(0.60f, 0.85f),
            "COMPLETED"            to Offset(0.90f, 0.85f),
        )
    }

    // 脉冲动画（当前状态）
    val infiniteTransition = rememberInfiniteTransition(label = "flowPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f, label = "pulse",
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        )
    )

    val textMeasurer = rememberTextMeasurer()

    // 在所有 Compose scope 外部提取，避免 'transitions' 属性名冲突
    val edgeData = flowState.edges
    val nodeData = flowState.states
    val curState = flowState.currentState

    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(200.dp)) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        Canvas(modifier = Modifier.fillMaxSize()) {
            val nodeW = 80.dp.toPx()
            val nodeH = 28.dp.toPx()

            // 1) 画连线
            for (i in edgeData.indices) {
                val t = edgeData[i]
                val fromKey: String? = t.from
                val toKey: String = t.to
                val toPos = nodePositions[toKey] ?: continue

                if (fromKey == null) {
                    // wildcard 画虚线箭头（从 IDLE 区域出发）
                    val startX = width * 0.06f + nodeW / 2
                    val startY = height * 0.12f + nodeH + 4.dp.toPx()
                    val endX = width * toPos.x + nodeW / 2
                    val endY = height * toPos.y
                    drawLine(
                        color = TextMuted.copy(alpha = 0.3f),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                    )
                } else {
                    val fromPos = nodePositions[fromKey] ?: continue
                    val startX = width * fromPos.x + nodeW / 2
                    val startY = height * fromPos.y + nodeH
                    val endX = width * toPos.x + nodeW / 2
                    val endY = height * toPos.y

                    // 判断这条边是否已经经过
                    val isTraversed = nodeData.any { it.name == toKey && it.isCompleted }
                        || toKey == curState
                        || fromKey == curState

                    drawLine(
                        color = if (isTraversed) CommitteeGold.copy(alpha = 0.5f) else TextMuted.copy(alpha = 0.2f),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = if (isTraversed) 1.5.dp.toPx() else 1.dp.toPx(),
                    )

                    // 箭头
                    if (isTraversed) {
                        val arrowSize = 5.dp.toPx()
                        val angle = Math.atan2(
                            (endY - startY).toDouble(),
                            (endX - startX).toDouble()
                        )
                        drawLine(
                            color = CommitteeGold.copy(alpha = 0.5f),
                            start = Offset(endX, endY),
                            end = Offset(
                                (endX - arrowSize * Math.cos(angle - 0.5)).toFloat(),
                                (endY - arrowSize * Math.sin(angle - 0.5)).toFloat(),
                            ),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    }
                }
            }

            // 2) 画节点
            for (j in nodeData.indices) {
                val node = nodeData[j]
                val pos = nodePositions[node.name] ?: continue
                val x = width * pos.x
                val y = height * pos.y
                val isActive = node.isActive
                val isCompleted = node.isCompleted

                val bgColor = when {
                    isActive    -> CommitteeGold.copy(alpha = pulseAlpha * 0.25f)
                    isCompleted -> CommitteeGold.copy(alpha = 0.1f)
                    else        -> SurfaceCard
                }
                val borderColor = when {
                    isActive    -> CommitteeGold
                    isCompleted -> CommitteeGold.copy(alpha = 0.4f)
                    else        -> BorderColor
                }
                val textColor = when {
                    isActive    -> CommitteeGold
                    isCompleted -> CommitteeGold.copy(alpha = 0.7f)
                    else        -> TextMuted
                }

                // 背景
                drawRoundRect(
                    color = bgColor,
                    topLeft = Offset(x, y),
                    size = Size(nodeW, nodeH),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
                // 边框
                drawRoundRect(
                    color = borderColor,
                    topLeft = Offset(x, y),
                    size = Size(nodeW, nodeH),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(width = if (isActive) 2.dp.toPx() else 1.dp.toPx()),
                )

                // 文字
                val textLayoutResult = textMeasurer.measure(
                    text = AnnotatedString(node.displayName),
                    style = TextStyle(
                        color = textColor,
                        fontSize = 10.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    ),
                )
                drawText(
                    textLayoutResult = textLayoutResult,
                    topLeft = Offset(
                        x + (nodeW - textLayoutResult.size.width) / 2f,
                        y + (nodeH - textLayoutResult.size.height) / 2f,
                    ),
                )
            }
        }
    }
}

// ── Phase 信息卡片 ─────────────────────────────────────────────

@Composable
private fun PhaseInfoCard(phase: com.znliang.committee.ui.model.UiPhase, currentStateName: String) {
    val stateDisplay = mapOf(
        "IDLE" to stringResource(R.string.home_state_idle),
        "ANALYSIS" to stringResource(R.string.home_state_analysis),
        "DEBATE" to stringResource(R.string.home_state_debate),
        "VOTE" to stringResource(R.string.home_state_vote),
        "RATING" to stringResource(R.string.home_state_rating),
        "EXECUTION" to stringResource(R.string.home_state_execution),
        "DONE" to stringResource(R.string.home_state_done),
    )

    val agentsForPhase = when (phase) {
        com.znliang.committee.ui.model.UiPhase.ANALYSIS -> listOf("analyst", "intel")
        com.znliang.committee.ui.model.UiPhase.DEBATE -> listOf("analyst", "risk_officer", "strategist")
        com.znliang.committee.ui.model.UiPhase.VOTE -> listOf("analyst", "risk_officer", "strategist")
        com.znliang.committee.ui.model.UiPhase.RATING -> listOf("supervisor")
        com.znliang.committee.ui.model.UiPhase.EXECUTION -> listOf("executor")
        else -> emptyList()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CommitteeGold.copy(alpha = 0.06f))
            .border(1.dp, CommitteeGold.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                stringResource(R.string.home_current, stateDisplay[currentStateName] ?: currentStateName),
                style = MaterialTheme.typography.labelLarge,
                color = CommitteeGold,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.home_phase_label, phase.name),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
        // Agent 列表
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            agentsForPhase.forEach { agentId ->
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(agentColorFor(agentId).copy(alpha = 0.2f))
                        .border(1.dp, agentColorFor(agentId).copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        agentId.first().uppercaseChar().toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = agentColorFor(agentId),
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

private fun agentColorFor(agentId: String): Color = when (agentId) {
    "analyst"     -> AnalystColor
    "risk_officer" -> RiskColor
    "strategist"  -> StrategistColor
    "executor"    -> ExecutorColor
    "intel"       -> IntelColor
    "supervisor"  -> SupervisorColor
    else          -> TextMuted
}

// ── 转场历史时间线 ──────────────────────────────────────────────

@Composable
private fun TransitionTimeline(history: List<TransitionRecord>) {
    Column {
        SectionHeader(stringResource(R.string.home_transition_history))
        Spacer(Modifier.height(4.dp))
        history.forEachIndexed { i, record ->
            val stateDisplay = mapOf(
                "IDLE" to stringResource(R.string.home_state_idle), "VALIDATING" to stringResource(R.string.home_state_validating), "REJECTED" to stringResource(R.string.home_state_rejected),
                "PREPPING" to stringResource(R.string.home_state_prepping), "PHASE1_DEBATE" to stringResource(R.string.home_state_phase1_debate),
                "PHASE1_ADJUDICATING" to stringResource(R.string.home_state_phase1_adjudicating), "PHASE2_ASSESSMENT" to stringResource(R.string.home_state_phase2_assessment),
                "FINAL_RATING" to stringResource(R.string.home_state_final_rating), "APPROVED" to stringResource(R.string.home_state_approved), "COMPLETED" to stringResource(R.string.home_state_completed),
            )
            val from = stateDisplay[record.from] ?: record.from
            val to = stateDisplay[record.to] ?: record.to
            val time = Instant.ofEpochMilli(record.timestamp)
                .atZone(ZoneId.systemDefault())
                .format(TIME_FORMATTER)

            Row(
                modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 时间线竖线 + 点
                Box(
                    modifier = Modifier.width(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(1.dp).height(16.dp).background(CommitteeGold.copy(alpha = 0.3f)))
                    Box(
                        Modifier.size(4.dp)
                            .clip(CircleShape)
                            .background(CommitteeGold)
                    )
                }
                Text(
                    "$from → $to",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontSize = 10.sp,
                )
                Text(
                    time,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontSize = 9.sp,
                )
            }
        }
    }
}
