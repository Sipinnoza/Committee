package com.znliang.committee.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.znliang.committee.R
import com.znliang.committee.domain.model.PresetRole
import com.znliang.committee.ui.model.ContributionInfo
import com.znliang.committee.ui.model.VoteInfo
import com.znliang.committee.ui.component.resolveRoleColor
import com.znliang.committee.ui.theme.BorderColor
import com.znliang.committee.ui.theme.BuyColor
import com.znliang.committee.ui.theme.CommitteeGold
import com.znliang.committee.ui.theme.SellColor
import com.znliang.committee.ui.theme.SurfaceCard
import com.znliang.committee.ui.theme.TextMuted
import com.znliang.committee.ui.theme.TextPrimary
import com.znliang.committee.ui.theme.TextSecondary

/**
 * 会议摘要卡片 — 会议结束后显示
 */
@Composable
fun MeetingSummaryCard(
    rating: String?,
    summary: String,
    subject: String,
    votes: Map<String, VoteInfo> = emptyMap(),
    ratingScale: List<String> = emptyList(),
    contribScores: Map<String, ContributionInfo> = emptyMap(),
    roles: List<PresetRole> = emptyList(),
    confidence: Int = 0,
    confidenceBreakdown: String = "",
    userOverride: String? = null,
    userOverrideReason: String = "",
    onOverride: (String, String) -> Unit = { _, _ -> },
    onNewMeeting: () -> Unit,
    onShare: () -> Unit = {},
    onExportReport: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, CommitteeGold.copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.Assignment, null, tint = CommitteeGold, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_minutes), style = MaterialTheme.typography.titleMedium,
                    color = CommitteeGold, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))

            // 标的
            if (subject.isNotBlank()) {
                Text("${stringResource(R.string.home_subject_label)}$subject", style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }

            // 评级
            if (rating != null) {
                val ratingIdx = ratingScale.indexOf(rating)
                val ratingColor = when {
                    ratingScale.size < 3 -> CommitteeGold
                    ratingIdx in 0..1 -> BuyColor
                    ratingIdx >= ratingScale.size - 2 -> SellColor
                    else -> CommitteeGold
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_final_rating_label), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Text(rating, style = MaterialTheme.typography.titleLarge,
                        color = ratingColor, fontWeight = FontWeight.ExtraBold)
                }
                // 置信度指标
                if (confidence > 0) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.confidence_label), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        val confColor = when {
                            confidence >= 75 -> BuyColor
                            confidence >= 50 -> CommitteeGold
                            else -> SellColor
                        }
                        // Confidence bar
                        val animatedConfidence by animateFloatAsState(
                            targetValue = confidence / 100f,
                            animationSpec = tween(800, easing = FastOutSlowInEasing),
                            label = "confidence",
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(BorderColor)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(animatedConfidence)
                                    .fillMaxHeight()
                                    .background(confColor, RoundedCornerShape(3.dp))
                            )
                        }
                        Text("$confidence%", style = MaterialTheme.typography.labelSmall,
                            color = confColor, fontWeight = FontWeight.Bold)
                    }
                    // ── 置信度分解（3 轴展开/折叠） ──
                    if (confidenceBreakdown.isNotBlank()) {
                        ConfidenceBreakdownSection(confidenceBreakdown)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 投票结果可视化
            if (votes.isNotEmpty()) {
                VoteResultsBar(votes = votes)
                Spacer(Modifier.height(4.dp))
            }

            // Agent 贡献度评分
            if (contribScores.isNotEmpty()) {
                HorizontalDivider(color = BorderColor, modifier = Modifier.padding(vertical = 4.dp))
                Text(stringResource(R.string.contrib_scores_title), style = MaterialTheme.typography.labelMedium,
                    color = CommitteeGold, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                contribScores.values.sortedByDescending { it.overall }.forEach { score ->
                    val presetRole = roles.find { it.id == score.roleId }
                    val roleColor = resolveRoleColor(presetRole)
                    val roleName = presetRole?.displayName ?: score.roleId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier.size(6.dp).clip(CircleShape).background(roleColor)
                        )
                        Text(roleName, style = MaterialTheme.typography.labelSmall, color = roleColor,
                            fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp))
                        // Score bars
                        val barWidth = 40.dp
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.contrib_info_label), fontSize = 9.sp, color = TextMuted)
                                Box(Modifier.width(barWidth * score.informationGain / 5f).height(3.dp)
                                    .background(CommitteeGold.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
                                Text(stringResource(R.string.contrib_logic_label), fontSize = 9.sp, color = TextMuted)
                                Box(Modifier.width(barWidth * score.logicQuality / 5f).height(3.dp)
                                    .background(CommitteeGold.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
                                Text(stringResource(R.string.contrib_collab_label), fontSize = 9.sp, color = TextMuted)
                                Box(Modifier.width(barWidth * score.interactionQuality / 5f).height(3.dp)
                                    .background(CommitteeGold.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
                            }
                        }
                        Text(
                            "${"%.1f".format(score.overall)}",
                            fontSize = 11.sp,
                            color = when {
                                score.overall >= 4f -> BuyColor
                                score.overall >= 3f -> CommitteeGold
                                else -> SellColor
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (score.brief.isNotBlank()) {
                        Text(score.brief, style = MaterialTheme.typography.labelSmall, color = TextMuted,
                            fontSize = 9.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // 摘要 — 结构化决策要点
            if (summary.isNotBlank()) {
                HorizontalDivider(color = BorderColor, modifier = Modifier.padding(vertical = 4.dp))
                Spacer(Modifier.height(8.dp))
                DecisionKeyPointsView(summary = summary)
            }

            Spacer(Modifier.height(16.dp))

            // ── 用户覆写区域 ──
            if (userOverride != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CommitteeGold.copy(alpha = 0.08f)),
                    border = BorderStroke(1.dp, CommitteeGold.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = CommitteeGold, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.summary_your_decision, userOverride), style = MaterialTheme.typography.labelMedium,
                                color = CommitteeGold, fontWeight = FontWeight.Bold)
                        }
                        if (userOverrideReason.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(userOverrideReason, style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            } else if (rating != null) {
                // 覆写按钮
                var showOverrideDialog by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { showOverrideDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = BorderStroke(1.dp, BorderColor),
                ) {
                    Icon(Icons.Default.HowToVote, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.vote_override_title), fontSize = 12.sp)
                }
                if (showOverrideDialog) {
                    OverrideDecisionDialog(
                        currentRating = rating,
                        ratingScale = ratingScale,
                        onDismiss = { showOverrideDialog = false },
                        onConfirm = { newRating, reason ->
                            onOverride(newRating, reason)
                            showOverrideDialog = false
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // 决策报告操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 分享按钮
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = BorderStroke(1.dp, BorderColor),
                ) {
                    Icon(Icons.Default.Share, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.report_share), fontSize = 12.sp)
                }
                // 导出报告按钮
                OutlinedButton(
                    onClick = onExportReport,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CommitteeGold),
                    border = BorderStroke(1.dp, CommitteeGold.copy(alpha = 0.4f)),
                ) {
                    Icon(Icons.Default.Description, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.report_export), fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            // 开始新会议按钮
            OutlinedButton(
                onClick = onNewMeeting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CommitteeGold),
                border = BorderStroke(1.dp, CommitteeGold.copy(alpha = 0.4f)),
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.home_new_meeting))
            }
        }
    }
}

/**
 * 将会议摘要解析为结构化决策要点展示。
 * 支持 Markdown 风格的分段（## 标题 / - 列表）和纯文本回退。
 */
@Composable
fun DecisionKeyPointsView(summary: String) {
    val sections = remember(summary) { parseKeyPoints(summary) }

    if (sections.isEmpty()) {
        // Fallback: show raw summary
        Text(
            stringResource(R.string.home_discussion_summary),
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(summary, style = MaterialTheme.typography.bodySmall, color = TextPrimary, lineHeight = 20.sp)
    } else {
        // Expandable key points
        var expanded by remember { mutableStateOf(true) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Assignment, null,
                tint = CommitteeGold, modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.keypoints_title),
                style = MaterialTheme.typography.labelMedium,
                color = CommitteeGold,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.keypoints_count, sections.sumOf { it.points.size }),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                null,
                tint = TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.height(4.dp))
                sections.forEach { section ->
                    KeyPointSection(section)
                }
            }
        }
    }
}

internal data class KeySection(
    val title: String,
    val icon: String, // emoji or category
    val points: List<String>,
)

/**
 * Parse summary text into structured sections.
 * Handles:
 * - Markdown headers (## Title / **Title**)
 * - Bullet points (- item / * item / • item)
 * - Numbered lists (1. item)
 * Falls back to splitting by newlines with heuristic grouping.
 */
internal fun parseKeyPoints(summary: String): List<KeySection> {
    val lines = summary.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size < 2) return emptyList()

    val sections = mutableListOf<KeySection>()
    var currentTitle = ""
    var currentPoints = mutableListOf<String>()

    for (line in lines) {
        when {
            // Markdown headers
            line.startsWith("##") || line.startsWith("**") && line.endsWith("**") -> {
                if (currentPoints.isNotEmpty()) {
                    sections.add(buildSection(currentTitle, currentPoints))
                    currentPoints = mutableListOf()
                }
                currentTitle = line.removePrefix("##").removePrefix("**").removeSuffix("**")
                    .removeSuffix(":").removeSuffix("：").trim()
            }
            // Bullet points
            line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> {
                currentPoints.add(line.removePrefix("- ").removePrefix("* ").removePrefix("• ").trim())
            }
            // Numbered list
            line.matches(Regex("^\\d+[.、)）]\\s*.*")) -> {
                currentPoints.add(line.replace(Regex("^\\d+[.、)）]\\s*"), "").trim())
            }
            // Colon-delimited section header
            (line.endsWith(":") || line.endsWith("：")) && line.length < 30 -> {
                if (currentPoints.isNotEmpty()) {
                    sections.add(buildSection(currentTitle, currentPoints))
                    currentPoints = mutableListOf()
                }
                currentTitle = line.removeSuffix(":").removeSuffix("：").trim()
            }
            // Regular text → treat as a point under current section
            else -> {
                currentPoints.add(line)
            }
        }
    }
    if (currentPoints.isNotEmpty()) {
        sections.add(buildSection(currentTitle, currentPoints))
    }

    return sections
}

private fun buildSection(title: String, points: List<String>): KeySection {
    val lowerTitle = title.lowercase()
    val icon = when {
        lowerTitle.contains("结论") || lowerTitle.contains("conclusion") || lowerTitle.contains("决策") || lowerTitle.contains("decision") -> "verdict"
        lowerTitle.contains("风险") || lowerTitle.contains("risk") || lowerTitle.contains("警告") || lowerTitle.contains("warning") -> "risk"
        lowerTitle.contains("行动") || lowerTitle.contains("action") || lowerTitle.contains("建议") || lowerTitle.contains("recommend") -> "action"
        lowerTitle.contains("论点") || lowerTitle.contains("argument") || lowerTitle.contains("分析") || lowerTitle.contains("analysis") -> "analysis"
        lowerTitle.contains("共识") || lowerTitle.contains("consensus") || lowerTitle.contains("投票") || lowerTitle.contains("vote") -> "consensus"
        else -> "point"
    }
    return KeySection(
        title = title,  // blank title → localized fallback handled in Composable
        icon = icon,
        points = points,
    )
}

@Composable
private fun KeyPointSection(section: KeySection) {
    val (iconVector, iconColor) = when (section.icon) {
        "verdict" -> Icons.Default.CheckCircle to BuyColor
        "risk" -> Icons.Default.Warning to SellColor
        "action" -> Icons.Default.PlayArrow to CommitteeGold
        "consensus" -> Icons.Default.HowToVote to BuyColor
        "analysis" -> Icons.Default.Analytics to Color(0xFF2196F3)
        else -> Icons.Default.ChevronRight to TextSecondary
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(iconVector, null, tint = iconColor, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                section.title.ifBlank { stringResource(R.string.summary_key_points) },
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(4.dp))
        section.points.forEach { point ->
            Row(
                modifier = Modifier.padding(start = 20.dp, bottom = 2.dp),
            ) {
                Text("\u2022", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                Spacer(Modifier.width(6.dp))
                Text(
                    point,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

// ── 置信度分解解析 ──

private data class ConfidenceAxis(val label: String, val score: Int, val maxScore: Int)

private fun parseConfidenceBreakdown(raw: String): List<ConfidenceAxis> {
    val axes = mutableListOf<ConfidenceAxis>()
    for (line in raw.lines()) {
        val t = line.trim()
        val match = Regex("""(\w+):\s*(\d+)/(\d+)""").find(t) ?: continue
        axes.add(ConfidenceAxis(
            label = match.groupValues[1],
            score = match.groupValues[2].toIntOrNull() ?: 0,
            maxScore = match.groupValues[3].toIntOrNull() ?: 1,
        ))
    }
    return axes
}

@Composable
private fun ConfidenceBreakdownSection(breakdown: String) {
    val axes = remember(breakdown) { parseConfidenceBreakdown(breakdown) }
    if (axes.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (expanded) stringResource(R.string.summary_hide_details) else stringResource(R.string.summary_show_details),
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
        Icon(
            if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(14.dp),
        )
    }
    AnimatedVisibility(visible = expanded) {
        Column(modifier = Modifier.padding(start = 4.dp, top = 2.dp)) {
            axes.forEach { axis ->
                ConfidenceSubBar(axis)
            }
        }
    }
}

@Composable
private fun ConfidenceSubBar(axis: ConfidenceAxis) {
    val fraction = if (axis.maxScore > 0) axis.score.toFloat() / axis.maxScore else 0f
    val barColor = when {
        fraction >= 0.75f -> BuyColor
        fraction >= 0.5f -> CommitteeGold
        else -> SellColor
    }
    val animFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "subbar-${axis.label}",
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(axis.label, style = MaterialTheme.typography.labelSmall, color = TextMuted,
            modifier = Modifier.width(80.dp))
        Box(
            Modifier.weight(1f).height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(BorderColor)
        ) {
            Box(
                Modifier.fillMaxWidth(animFraction).fillMaxHeight()
                    .background(barColor, RoundedCornerShape(2.dp))
            )
        }
        Text("${axis.score}/${axis.maxScore}", style = MaterialTheme.typography.labelSmall,
            color = barColor, fontWeight = FontWeight.Bold)
    }
}
