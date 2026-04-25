package com.znliang.committee.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.znliang.committee.domain.model.MeetingPresetConfig
import com.znliang.committee.domain.model.PresetRole
import com.znliang.committee.domain.model.SpeechRecord
import com.znliang.committee.ui.theme.*
import androidx.compose.ui.res.stringResource
import com.znliang.committee.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())

/**
 * 群聊对话气泡 — 左侧头像 + 名称 + 时间，右侧气泡内容
 */
@Composable
fun ChatBubble(
    speech: SpeechRecord,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    presetRole: PresetRole? = null,
    onFollowUp: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isHuman = speech.agent == "human"
    val agentColor = remember(presetRole?.colorHex, isHuman) {
        if (isHuman) Color(0xFF4FC3F7.toInt()) // Light blue for human
        else resolveRoleColor(presetRole)
    }
    val agentName = if (isHuman) {
        stringResource(R.string.human_speaker_name)
    } else if (presetRole != null) {
        val resId = presetRole.displayNameRes()
        if (resId != 0) stringResource(resId) else presetRole.displayName
    } else {
        speech.agent
    }
    val stanceText = if (isHuman) "" else if (presetRole != null) {
        val resId = presetRole.stanceRes()
        if (resId != 0) stringResource(resId) else presetRole.stance
    } else ""
    val avatarLetter = agentName.first()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .animateContentSize(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // ── Avatar ─────────────────────────────────────────
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(agentColor.copy(alpha = 0.15f))
                .border(1.5.dp, agentColor.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$avatarLetter",
                color = agentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }

        Spacer(Modifier.width(8.dp))

        // ── Bubble Content ─────────────────────────────────
        Column(modifier = Modifier.weight(1f)) {
            // Name + Time + Round
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = agentName,
                    style = MaterialTheme.typography.labelLarge,
                    color = agentColor,
                    fontWeight = FontWeight.Bold,
                )
                // Stance tag
                if (stanceText.isNotBlank()) {
                    Text(
                        text = stanceText,
                        style = MaterialTheme.typography.labelSmall,
                        color = agentColor.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                    )
                }
                Text(
                    text = "R${speech.round}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontSize = 10.sp,
                )
                Text(
                    text = timeFormatter.format(speech.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontSize = 10.sp,
                )
                // P0-1: Inline vote tag chip
                if (speech.voteLabel.isNotBlank()) {
                    val isPositive = speech.voteLabel.contains("Agree", ignoreCase = true)
                        || speech.voteLabel.let { lbl ->
                            lbl.startsWith("Score=") && (lbl.removePrefix("Score=").toIntOrNull() ?: 0) >= 6
                        }
                    val voteChipColor = if (isPositive) BuyColor else SellColor
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(voteChipColor.copy(alpha = 0.15f))
                            .border(0.5.dp, voteChipColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = speech.voteLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = voteChipColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                // Follow-up question button
                if (onFollowUp != null && !speech.isStreaming && !isHuman) {
                    var showFollowUp by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { showFollowUp = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.QuestionAnswer,
                            contentDescription = stringResource(R.string.speech_ask_followup),
                            tint = agentColor.copy(alpha = 0.4f),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (showFollowUp) {
                        FollowUpDialog(
                            agentName = agentName,
                            agentColor = agentColor,
                            onDismiss = { showFollowUp = false },
                            onSubmit = { question ->
                                onFollowUp(question)
                                showFollowUp = false
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Message bubble
            Column(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 2.dp,
                            topEnd = 12.dp,
                            bottomStart = 12.dp,
                            bottomEnd = 12.dp,
                        )
                    )
                    .background(SurfaceCard)
                    .border(1.dp, agentColor.copy(alpha = 0.12f),
                        RoundedCornerShape(
                            topStart = 2.dp,
                            topEnd = 12.dp,
                            bottomStart = 12.dp,
                            bottomEnd = 12.dp,
                        )
                    )
                    .clickable(enabled = !speech.isStreaming) { onToggle() }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .animateContentSize()
            ) {
                val displayText = if (speech.isStreaming || isExpanded) {
                    speech.content
                } else {
                    speech.content.take(120) + if (speech.content.length > 120) "…" else ""
                }

                if (speech.isStreaming && displayText.isBlank()) {
                    // CONTENT 尚未出现，显示"思考中..."脉冲点动画
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "thinkingDots")
                        repeat(3) { index ->
                            val dotAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.2f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(durationMillis = 600, delayMillis = index * 200),
                                    repeatMode = RepeatMode.Reverse,
                                ),
                                label = "dot$index",
                            )
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        agentColor.copy(alpha = dotAlpha),
                                        CircleShape,
                                    )
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.agents_thinking),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                } else {
                    MarkdownText(
                        text = displayText,
                    )
                }

                // Streaming cursor: blinking block
                if (speech.isStreaming) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "cursor")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 0.2f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 600),
                                repeatMode = RepeatMode.Reverse,
                            ),
                            label = "cursorAlpha",
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(agentColor.copy(alpha = alpha), RoundedCornerShape(2.dp))
                        )
                    }
                } else if (speech.content.length > 120) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = agentColor.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                // ── Reasoning trace (thinking process) ──
                if (speech.reasoning.isNotBlank() && !speech.isStreaming) {
                    var showReasoning by remember { mutableStateOf(false) }
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = agentColor.copy(alpha = 0.1f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showReasoning = !showReasoning }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = agentColor.copy(alpha = 0.5f),
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = if (showReasoning) stringResource(R.string.speech_hide_reasoning) else stringResource(R.string.speech_show_reasoning),
                            style = MaterialTheme.typography.labelSmall,
                            color = agentColor.copy(alpha = 0.5f),
                            fontSize = 10.sp,
                        )
                    }
                    AnimatedVisibility(
                        visible = showReasoning,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Text(
                            text = speech.reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Default,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    agentColor.copy(alpha = 0.04f),
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 系统消息气泡 — 居中灰色小字，用于状态变化、调度通知等
 */
@Composable
fun SystemBubble(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextMuted,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontSize = 11.sp,
        )
    }
}

/**
 * 事件型系统气泡 — 居中带底色胶囊，用于阶段转换等关键节点
 */
@Composable
fun EventBubble(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = CommitteeGold,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.1f))
                .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * 会前议程卡片 — 展示会议结构化信息
 */
@Composable
fun AgendaCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, IntelColor.copy(alpha = 0.3f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            text.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank()) {
                    Text(
                        text = trimmed,
                        style = if (trimmed.startsWith("📋")) MaterialTheme.typography.titleSmall
                        else MaterialTheme.typography.bodySmall,
                        color = if (trimmed.startsWith("📋")) IntelColor else TextSecondary,
                        fontWeight = if (trimmed.startsWith("📋")) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * 追问对话框 — 用户针对特定 Agent 的发言追问
 */
@Composable
private fun FollowUpDialog(
    agentName: String,
    agentColor: Color,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var questionText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.speech_ask_agent, agentName), color = agentColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        text = {
            OutlinedTextField(
                value = questionText,
                onValueChange = { questionText = it },
                placeholder = { Text(stringResource(R.string.speech_question_hint), color = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (questionText.isNotBlank()) onSubmit(questionText) },
                enabled = questionText.isNotBlank(),
            ) {
                Text(stringResource(R.string.speech_ask_btn), color = if (questionText.isNotBlank()) agentColor else TextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = TextMuted)
            }
        },
    )
}
