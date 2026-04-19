package com.znliang.committee.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.hilt.navigation.compose.hiltViewModel
import com.znliang.committee.R
import com.znliang.committee.domain.model.AgentRole
import com.znliang.committee.domain.model.MeetingPresetConfig
import com.znliang.committee.domain.model.MeetingState
import com.znliang.committee.domain.model.PresetRole
import com.znliang.committee.engine.runtime.BoardPhase
import com.znliang.committee.ui.component.ChatBubble
import com.znliang.committee.ui.component.EventBubble
import com.znliang.committee.ui.component.PulsingDot
import com.znliang.committee.ui.component.SectionHeader
import com.znliang.committee.ui.component.StateBadge
import com.znliang.committee.ui.component.SystemBubble
import com.znliang.committee.ui.theme.BorderColor
import com.znliang.committee.ui.theme.BuyColor
import com.znliang.committee.ui.theme.CommitteeGold
import com.znliang.committee.ui.theme.SellColor
import com.znliang.committee.ui.theme.StateErrorColor
import com.znliang.committee.ui.theme.StateWarningColor
import com.znliang.committee.ui.theme.SurfaceCard
import com.znliang.committee.ui.theme.SurfaceDark
import com.znliang.committee.ui.theme.TextMuted
import com.znliang.committee.ui.theme.TextPrimary
import com.znliang.committee.ui.theme.TextSecondary
import com.znliang.committee.ui.theme.committeeTextFieldColors
import com.znliang.committee.ui.viewmodel.AgentMemoryStats
import com.znliang.committee.ui.viewmodel.AgentMemoryViewModel
import com.znliang.committee.ui.viewmodel.MeetingUiState
import com.znliang.committee.ui.viewmodel.MeetingViewModel
import kotlinx.coroutines.launch

/**
 * 多Agent群聊对话主页
 * 会议中：顶部状态条 + 聊天气泡流 + 底部输入栏
 * 待机中：顶部发起会议卡片 + 委员名单
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MeetingViewModel,
    presetConfig: MeetingPresetConfig,
    onNavigateToSettings: () -> Unit = {},
    onAgentClick: (AgentRole) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val activePreset by presetConfig.activePresetFlow()
        .collectAsState(initial = presetConfig.getActivePreset())

    val memoryViewModel: AgentMemoryViewModel = hiltViewModel()
    val memoryStats by memoryViewModel.stats.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var subjectInput by remember { mutableStateOf("") }
    // Track expanded state per speech
    val expandedKeys = remember { mutableStateMapOf<String, Boolean>() }

    val isMeetingActive = uiState.currentState != MeetingState.IDLE && uiState.currentState != MeetingState.REJECTED

    // Auto-scroll to bottom when new speech arrives
    LaunchedEffect(uiState.speeches.size) {
        val itemCount = listState.layoutInfo.totalItemsCount
        if (itemCount > 0) {
            scope.launch { listState.animateScrollToItem(itemCount - 1) }
        }
    }

    // Error snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = SurfaceDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.titleLarge,
                            color = CommitteeGold, fontWeight = FontWeight.ExtraBold)
                        if (isMeetingActive) {
                            Text("·", color = TextMuted, fontSize = 18.sp)
                            Text(uiState.currentState.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                color = CommitteeGold.copy(alpha = 0.8f))
                        }
                    }
                },
                actions = {
                    if (isMeetingActive) {
                        IconButton(onClick = { viewModel.cancelMeeting() }) {
                            Icon(Icons.Default.Close, stringResource(R.string.home_cancel_meeting), tint = StateErrorColor, modifier = Modifier.size(20.dp))
                        }
                    }
                    if (!isMeetingActive) {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, stringResource(R.string.nav_settings), tint = TextMuted, modifier = Modifier.size(20.dp))
                        }
                    }
                    if (uiState.hasApiKey && !isMeetingActive) {
                        Text(
                            text = uiState.llmConfig.displayTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                    StateBadge(uiState.currentState, Modifier.padding(end = 12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceCard,
                    titleContentColor = TextPrimary,
                ),
            )
        },
        bottomBar = {
            if (uiState.currentState == MeetingState.APPROVED) {
                ExecutionConfirmBar(viewModel = viewModel)
            }
        }
        ) { paddingValues ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding(), bottom = 6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ── Meeting active: compact status bar (expandable) ──────────
                if (isMeetingActive) {
                    item(key = "status_bar") {
                        MeetingStatusBar(
                            uiState = uiState,
                        )
                    }
                }

                // ── IDLE: Show meeting init card + agent roster ──────────────
            if (!isMeetingActive) {
                item(key = "meeting_init") {
                    MeetingInitCard(
                        uiState = uiState,
                        viewModel = viewModel,
                        subjectInput = subjectInput,
                        onSubjectChange = { subjectInput = it },
                    )
                }

                // ── Agent roster ──────────────────────────────────────────
                item(key = "agents_header") {
                    SectionHeader(stringResource(R.string.agents_members, activePreset.committeeLabel))
                }
                items(activePreset.roles, key = { "agent_${it.id}" }) { presetRole ->
                    CompactAgentCard(
                        role = presetRole,
                        stats = memoryStats[presetRole.id],
                        onClick = {
                            // Try to find matching AgentRole for navigation
                            AgentRole.entries.find { it.id == presetRole.id }?.let(onAgentClick)
                        },
                    )
                }
            }

            // ── System event: meeting start ──────────────────────────────
            if (uiState.speeches.isNotEmpty()) {
                item(key = "meeting_start_event") {
                    EventBubble(stringResource(R.string.home_meeting_start, uiState.speeches.firstOrNull()?.let { s -> 
                        java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                            .withZone(java.time.ZoneId.systemDefault())
                            .format(s.timestamp) 
                    } ?: ""))
                }
            }

            // ── Chat messages: speeches + system logs interleaved ─────────
            items(uiState.speeches, key = { it.id }) { speech ->
                ChatBubble(
                    speech = speech,
                    isExpanded = expandedKeys[speech.id] == true,
                    onToggle = {
                        expandedKeys[speech.id] = expandedKeys[speech.id] != true
                    },
                )
            }

            // ── Meeting Summary Card (when finished) ────────────────────
            if (uiState.boardFinished) {
                item(key = "meeting_summary") {
                    MeetingSummaryCard(
                        rating = uiState.boardRating,
                        summary = uiState.boardSummary,
                        subject = uiState.subject,
                        onNewMeeting = {
                            viewModel.resetToIdle()
                        },
                    )
                }
            }

            // ── Live system logs (thinking / waiting) ─────────────────────
            if (uiState.looperLogs.isNotEmpty()) {
                item(key = "logs_spacer") { Spacer(Modifier.height(4.dp)) }
                // Show last few system logs as system bubbles
                val recentLogs = uiState.looperLogs.takeLast(3)
                item(key = "recent_logs") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        recentLogs.forEach { line ->
                            val isThinking = line.contains("[Thinking]")
                            val isError = line.contains("[Error]") || line.contains("[API Error]")
                            val isResponse = line.contains("[Response]")
                            when {
                                isThinking -> EventBubble(line.substringAfter("] ").substringAfter("] "),
                                    color = CommitteeGold)
                                isError -> EventBubble(line.substringAfter("] "),
                                    color = SellColor)
                                isResponse -> SystemBubble(line.substringAfter("] "),
                                    color = BuyColor)
                                else -> SystemBubble(line.substringAfter("] "))
                            }
                        }
                    }
                }
            }

            // ── Waiting indicator ─────────────────────────────────────────
            if (isMeetingActive && uiState.looperLogs.lastOrNull()?.contains("[Thinking]") == true) {
                item(key = "thinking_indicator") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PulsingDot(CommitteeGold, modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.home_ai_thinking), style = MaterialTheme.typography.labelSmall,
                            color = CommitteeGold.copy(alpha = 0.7f))
                    }
                }
            }

            item(key = "bottom_spacer") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/**
 * 待机时的发起会议卡片
 */
@Composable
private fun MeetingInitCard(
    uiState: MeetingUiState,
    viewModel: MeetingViewModel,
    subjectInput: String,
    onSubjectChange: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val isRejected = uiState.currentState == MeetingState.REJECTED

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, if (isRejected) StateWarningColor.copy(alpha = 0.3f) else BorderColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Model info
            if (uiState.hasApiKey) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(CommitteeGold.copy(alpha = 0.08f))
                        .border(1.dp, CommitteeGold.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.SmartToy, null, tint = CommitteeGold, modifier = Modifier.size(14.dp))
                    Text(
                        text = uiState.llmConfig.displayTag,
                        style = MaterialTheme.typography.labelMedium,
                        color = CommitteeGold,
                    )
                }
            }

            // API Key warning
            if (!uiState.hasApiKey) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(StateWarningColor.copy(alpha = 0.1f))
                        .border(1.dp, StateWarningColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Warning, null, tint = StateWarningColor, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.home_config_apikey), style = MaterialTheme.typography.bodyMedium,
                        color = StateWarningColor)
                }
            }

            if (isRejected) {
                Text(stringResource(R.string.home_entry_failed), style = MaterialTheme.typography.bodyMedium,
                    color = StateWarningColor)
            }

            // Subject input + button
            OutlinedTextField(
                value = subjectInput,
                onValueChange = onSubjectChange,
                label = { Text(stringResource(R.string.home_input_topic)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    if (subjectInput.isNotBlank()) viewModel.requestMeeting(subjectInput)
                }),
                colors = committeeTextFieldColors(),
            )

            Button(
                onClick = {
                    keyboard?.hide()
                    viewModel.requestMeeting(subjectInput)
                },
                enabled = subjectInput.isNotBlank() && (uiState.currentState == MeetingState.IDLE || isRejected),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CommitteeGold,
                    contentColor = SurfaceDark,
                ),
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.home_start_meeting), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ExecutionConfirmBar(viewModel: MeetingViewModel) {
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
 * 会议中的紧凑状态条 — 可折叠
 * 默认只显示一行（当前阶段 + 轮次 + 共识），点击展开看详情
 */
@Composable
private fun MeetingStatusBar(
    uiState: MeetingUiState,
) {
    var expanded by remember { mutableStateOf(false) }

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
                        uiState.boardFinished -> 4
                        uiState.boardPhase == BoardPhase.ANALYSIS -> 0
                        uiState.boardPhase == BoardPhase.DEBATE -> 1
                        uiState.boardPhase == BoardPhase.VOTE -> 1
                        uiState.boardPhase == BoardPhase.RATING -> 2
                        uiState.boardPhase == BoardPhase.EXECUTION -> 3
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
                        "R${uiState.boardRound}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                    if (uiState.boardConsensus) {
                        Text(stringResource(R.string.home_consensus), style = MaterialTheme.typography.labelSmall,
                            color = BuyColor, fontWeight = FontWeight.Bold)
                    }
                    if (uiState.boardRating != null) {
                        Text(
                            uiState.boardRating, style = MaterialTheme.typography.labelSmall,
                            color = CommitteeGold, fontWeight = FontWeight.Bold)
                    }
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.home_collapse) else stringResource(R.string.home_expand),
                        modifier = Modifier.size(16.dp),
                        tint = TextMuted,
                    )
                }
            }

            // ── 展开区域 ──
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    // 当前阶段详情
                    val phaseLabel = when (uiState.boardPhase) {
                        BoardPhase.ANALYSIS -> stringResource(R.string.home_phase_analysis)
                        BoardPhase.DEBATE -> stringResource(R.string.home_phase_debate)
                        BoardPhase.VOTE -> stringResource(R.string.home_phase_vote)
                        BoardPhase.RATING -> stringResource(R.string.home_phase_rating)
                        BoardPhase.EXECUTION -> stringResource(R.string.home_phase_execution)
                        BoardPhase.DONE -> stringResource(R.string.home_phase_done)
                        else -> uiState.boardPhase.name
                    }
                    Text(
                        phaseLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/**
 * 会议摘要卡片 — 会议结束后显示
 */
@Composable
private fun MeetingSummaryCard(
    rating: String?,
    summary: String,
    subject: String,
    onNewMeeting: () -> Unit,
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
                val ratingColor = when (rating) {
                    "Buy", "Overweight" -> BuyColor
                    "Sell", "Underweight" -> SellColor
                    else -> CommitteeGold
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_final_rating_label), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Text(rating, style = MaterialTheme.typography.titleLarge,
                        color = ratingColor, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.height(12.dp))
            }

            // 摘要
            if (summary.isNotBlank()) {
                HorizontalDivider(color = BorderColor, modifier = Modifier.padding(vertical = 4.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.home_discussion_summary), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                Text(summary, style = MaterialTheme.typography.bodySmall, color = TextPrimary,
                    lineHeight = 20.sp)
            }

            Spacer(Modifier.height(16.dp))

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

// ── Compact Agent Card for home screen ──────────────────────────────────────

@Composable
private fun CompactAgentCard(role: PresetRole, stats: AgentMemoryStats? = null, onClick: () -> Unit) {
    val agentColor = remember(role.colorHex) {
        runCatching { Color(role.colorHex.toColorInt()) }
            .getOrDefault(Color(0xFF607D8B.toInt()))
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(agentColor.copy(alpha = 0.15f))
                    .border(1.5.dp, agentColor.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${role.displayName.first()}",
                    color = agentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }

            Spacer(Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        role.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        role.stance,
                        style = MaterialTheme.typography.labelSmall,
                        color = agentColor,
                        modifier = Modifier
                            .background(agentColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    role.responsibility,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                )
                // Memory badges
                if (stats != null && (stats.experienceCount > 0 || stats.skillCount > 0)) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (stats.experienceCount > 0) {
                            AgentBadge("🧠 ${stats.experienceCount}", CommitteeGold)
                        }
                        if (stats.skillCount > 0) {
                            AgentBadge("🎓 ${stats.skillCount}", agentColor)
                        }
                        if (stats.changelogCount > 0) {
                            AgentBadge("✨ ${stats.changelogCount}", TextSecondary)
                        }
                    }
                }
            }

            // Arrow
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AgentBadge(text: String, color: Color) {
    Text(
        text,
        fontSize = 10.sp,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
