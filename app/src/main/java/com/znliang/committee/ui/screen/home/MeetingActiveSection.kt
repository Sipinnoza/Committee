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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.znliang.committee.R
import com.znliang.committee.domain.model.PresetRole
import com.znliang.committee.ui.model.UiPhase
import com.znliang.committee.ui.component.resolveRoleColor
import com.znliang.committee.ui.theme.BorderColor
import com.znliang.committee.ui.theme.BuyColor
import com.znliang.committee.ui.theme.CommitteeGold
import com.znliang.committee.ui.theme.SellColor
import com.znliang.committee.ui.theme.SurfaceCard
import com.znliang.committee.ui.theme.SurfaceDark
import com.znliang.committee.ui.theme.TextMuted
import com.znliang.committee.ui.theme.TextPrimary
import com.znliang.committee.ui.theme.TextSecondary
import com.znliang.committee.ui.theme.committeeTextFieldColors
import com.znliang.committee.ui.viewmodel.BoardState
import com.znliang.committee.ui.viewmodel.MeetingViewModel

@Composable
fun ExecutionConfirmBar(viewModel: MeetingViewModel) {
    Surface(
        color = SurfaceCard,
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, CommitteeGold.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.CheckCircle, null, tint = CommitteeGold, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.home_rating_approved), style = MaterialTheme.typography.titleSmall,
                color = CommitteeGold, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(
                onClick = { viewModel.confirmExecution() },
                colors = ButtonDefaults.buttonColors(containerColor = BuyColor, contentColor = SurfaceDark),
            ) {
                Icon(Icons.Default.Done, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.home_confirm_execution), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 会议中底部输入栏 — 暂停/恢复 + 文本输入 + 发言 + 投票
 */
@Composable
fun HumanInputBar(
    viewModel: MeetingViewModel,
    isPaused: Boolean,
) {
    var inputText by remember { mutableStateOf("") }
    var showVoteDialog by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    Surface(
        color = SurfaceCard,
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Pause / Resume button
            IconButton(
                onClick = { if (isPaused) viewModel.resumeMeeting() else viewModel.pauseMeeting() },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = stringResource(if (isPaused) R.string.human_resume else R.string.human_pause),
                    tint = if (isPaused) BuyColor else CommitteeGold,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Text input
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text(stringResource(R.string.human_input_hint), style = MaterialTheme.typography.bodySmall, color = TextMuted) },
                singleLine = true,
                modifier = Modifier.weight(1f).height(48.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (inputText.isNotBlank()) {
                        viewModel.injectHumanMessage(inputText.trim())
                        inputText = ""
                        keyboard?.hide()
                    }
                }),
                colors = committeeTextFieldColors(),
            )

            // Send button
            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.injectHumanMessage(inputText.trim())
                        inputText = ""
                        keyboard?.hide()
                    }
                },
                enabled = inputText.isNotBlank(),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = stringResource(R.string.human_send),
                    tint = if (inputText.isNotBlank()) CommitteeGold else TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Vote button
            IconButton(
                onClick = { showVoteDialog = true },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Default.HowToVote,
                    contentDescription = stringResource(R.string.human_vote),
                    tint = CommitteeGold,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    if (showVoteDialog) {
        VoteDialog(
            onDismiss = { showVoteDialog = false },
            onVote = { agree, reason ->
                viewModel.injectHumanVote(agree, reason)
                showVoteDialog = false
            },
        )
    }
}

/**
 * 会议中的紧凑状态条 — 可折叠
 * 默认只显示一行（当前阶段 + 轮次 + 共识），点击展开看详情
 */
@Composable
fun MeetingStatusBar(
    boardState: BoardState,
    roles: List<PresetRole> = emptyList(),
    onWeightChange: (String, Float) -> Unit = { _, _ -> },
) {
    var expanded by remember { mutableStateOf(false) }
    val expandRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(300),
        label = "expandArrow",
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, CommitteeGold.copy(alpha = 0.2f)),
    ) {
        Column {
            // ── 第一行：始终可见 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 左侧：阶段进度条
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    // 阶段指示点
                    val phases = listOf(
                        stringResource(R.string.home_nav_analysis),
                        stringResource(R.string.home_nav_debate),
                        stringResource(R.string.home_nav_rating),
                        stringResource(R.string.home_nav_execution),
                    )
                    val currentIdx = when {
                        boardState.boardFinished -> 4
                        boardState.boardPhase == UiPhase.ANALYSIS -> 0
                        boardState.boardPhase == UiPhase.DEBATE -> 1
                        boardState.boardPhase == UiPhase.VOTE -> 1
                        boardState.boardPhase == UiPhase.RATING -> 2
                        boardState.boardPhase == UiPhase.EXECUTION -> 3
                        else -> -1
                    }
                    phases.forEachIndexed { idx, label ->
                        if (idx > 0) {
                            Box(
                                Modifier
                                    .width(12.dp)
                                    .height(2.dp)
                                    .background(
                                        if (idx <= currentIdx) CommitteeGold.copy(alpha = 0.5f)
                                        else BorderColor,
                                        RoundedCornerShape(1.dp)
                                    )
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(if (idx == currentIdx) 8.dp else 6.dp)
                                .background(
                                    when {
                                        idx == currentIdx -> CommitteeGold
                                        idx < currentIdx -> CommitteeGold.copy(alpha = 0.4f)
                                        else -> BorderColor
                                    },
                                    CircleShape
                                ),
                        )
                    }
                }

                // 右侧：轮次 + 共识标记
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "R${boardState.boardRound}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                    if (boardState.boardConsensus) {
                        Text(stringResource(R.string.home_consensus), style = MaterialTheme.typography.labelSmall,
                            color = BuyColor, fontWeight = FontWeight.Bold)
                    }
                    if (boardState.boardRating != null) {
                        Text(
                            boardState.boardRating, style = MaterialTheme.typography.labelSmall,
                            color = CommitteeGold, fontWeight = FontWeight.Bold)
                    }
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.home_collapse) else stringResource(R.string.home_expand),
                        modifier = Modifier.size(16.dp).rotate(expandRotation),
                        tint = TextMuted,
                    )
                }
            }

            // ── 展开区域 ──
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    // 当前阶段详情
                    val phaseLabel = when (boardState.boardPhase) {
                        UiPhase.ANALYSIS -> stringResource(R.string.home_phase_analysis)
                        UiPhase.DEBATE -> stringResource(R.string.home_phase_debate)
                        UiPhase.VOTE -> stringResource(R.string.home_phase_vote)
                        UiPhase.RATING -> stringResource(R.string.home_phase_rating)
                        UiPhase.EXECUTION -> stringResource(R.string.home_phase_execution)
                        UiPhase.DONE -> stringResource(R.string.home_phase_done)
                        else -> boardState.boardPhase.name
                    }
                    Text(
                        phaseLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )

                    // ── 实时讨论要点速览 ──
                    if (boardState.boardSummary.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        LiveKeyPointsPreview(summary = boardState.boardSummary)
                    }

                    // ── 参与者头像条 ──
                    if (roles.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = BorderColor)
                        Spacer(Modifier.height(6.dp))

                        // ── 立场光谱：实时展示各 Agent 投票分布 ──
                        if (boardState.boardVotes.isNotEmpty()) {
                            StanceSpectrum(
                                votes = boardState.boardVotes,
                                roles = roles,
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SmartToy, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.participants_label, roles.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        ParticipantAvatarRow(roles = roles)

                        // ── Agent 话语权重控制 ──
                        if (!boardState.boardFinished) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = BorderColor)
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.agent_influence_label), style = MaterialTheme.typography.labelSmall,
                                color = TextMuted, fontSize = 10.sp)
                            Spacer(Modifier.height(4.dp))
                            roles.filter { it.id != "supervisor" }.forEach { role ->
                                val roleColor = resolveRoleColor(role)
                                val currentWeight = boardState.boardUserWeights[role.id] ?: 1.0f
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(roleColor))
                                    Spacer(Modifier.width(4.dp))
                                    Text(role.displayName.take(4), fontSize = 9.sp, color = roleColor,
                                        fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
                                    Slider(
                                        value = currentWeight,
                                        onValueChange = { onWeightChange(role.id, it) },
                                        valueRange = 0.1f..3.0f,
                                        modifier = Modifier.weight(1f).height(20.dp),
                                        colors = SliderDefaults.colors(
                                            thumbColor = roleColor,
                                            activeTrackColor = roleColor.copy(alpha = 0.5f),
                                        ),
                                    )
                                    Text("${"%.1f".format(currentWeight)}x", fontSize = 9.sp, color = TextMuted)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/**
 * 错误卡片 — 会议因 API 错误终止时显示
 */
@Composable
fun MeetingErrorCard(
    errorMessage: String,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.meeting_error_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.meeting_error_retry))
            }
        }
    }
}

/**
 * 实时讨论要点速览 — 从 summary 中提取正/反方论点
 */
@Composable
internal fun LiveKeyPointsPreview(summary: String) {
    val proPoints = mutableListOf<String>()
    val conPoints = mutableListOf<String>()
    var currentSection = ""

    for (line in summary.lines()) {
        val trimmed = line.trim()
        when {
            trimmed.contains("正方") || trimmed.contains("赞成") || trimmed.contains("Pro") -> currentSection = "pro"
            trimmed.contains("反方") || trimmed.contains("反对") || trimmed.contains("Con") -> currentSection = "con"
            trimmed.contains("分歧") || trimmed.contains("共识") || trimmed.contains("Divergence") -> currentSection = ""
            trimmed.startsWith("-") || trimmed.startsWith("·") || trimmed.matches(Regex("^\\d+\\..*")) -> {
                val point = trimmed.removePrefix("-").removePrefix("·").trim()
                    .replace(Regex("^\\d+\\.\\s*"), "")
                if (point.isNotBlank()) {
                    when (currentSection) {
                        "pro" -> proPoints.add(point)
                        "con" -> conPoints.add(point)
                    }
                }
            }
        }
    }

    if (proPoints.isEmpty() && conPoints.isEmpty()) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 正方论点
        if (proPoints.isNotEmpty()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.vote_agree_label), fontSize = 9.sp, color = BuyColor, fontWeight = FontWeight.Bold)
                proPoints.take(3).forEach { point ->
                    Text("· ${point.take(40)}", fontSize = 9.sp, color = TextMuted, maxLines = 1)
                }
            }
        }
        // 反方论点
        if (conPoints.isNotEmpty()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.vote_disagree_label), fontSize = 9.sp, color = SellColor, fontWeight = FontWeight.Bold)
                conPoints.take(3).forEach { point ->
                    Text("· ${point.take(40)}", fontSize = 9.sp, color = TextMuted, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 立场光谱 — 实时展示各 Agent 的投票分布
 * 赞成(绿)和反对(红)双色渐变条 + 每个投票者的头像标记
 */
@Composable
internal fun StanceSpectrum(
    votes: Map<String, com.znliang.committee.ui.model.VoteInfo>,
    roles: List<PresetRole>,
) {
    val agreeVotes = votes.filter { it.value.agree }
    val disagreeVotes = votes.filter { !it.value.agree }
    val total = votes.size
    val agreeRatio = if (total > 0) agreeVotes.size.toFloat() / total else 0.5f
    val animatedAgreeRatio by animateFloatAsState(
        targetValue = agreeRatio,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "stanceAgree",
    )

    Column {
        // Label row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Agree ${agreeVotes.size}",
                style = MaterialTheme.typography.labelSmall,
                color = BuyColor,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            )
            Text(
                "${(agreeRatio * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontSize = 10.sp,
            )
            Text(
                "Disagree ${disagreeVotes.size}",
                style = MaterialTheme.typography.labelSmall,
                color = SellColor,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        // Spectrum bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            if (agreeRatio > 0f) {
                Box(
                    Modifier
                        .weight(animatedAgreeRatio.coerceAtLeast(0.05f))
                        .fillMaxHeight()
                        .background(BuyColor.copy(alpha = 0.7f))
                )
            }
            if (agreeRatio < 1f) {
                Box(
                    Modifier
                        .weight((1f - animatedAgreeRatio).coerceAtLeast(0.05f))
                        .fillMaxHeight()
                        .background(SellColor.copy(alpha = 0.7f))
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // Voter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            votes.entries.forEach { (roleId, vote) ->
                val presetRole = roles.find { it.id == roleId }
                val color = resolveRoleColor(presetRole)
                val name = presetRole?.displayName ?: roleId
                val voteColor = if (vote.agree) BuyColor else SellColor
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(voteColor.copy(alpha = 0.15f))
                        .border(1.5.dp, color.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.first().toString(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                }
            }
        }
    }
}
