package com.znliang.committee.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.znliang.committee.R
import com.znliang.committee.domain.model.MeetingPresetConfig
import com.znliang.committee.domain.model.MeetingState
import com.znliang.committee.ui.component.ChatBubble
import com.znliang.committee.ui.component.EventBubble
import com.znliang.committee.ui.component.PulsingDot
import com.znliang.committee.ui.component.SectionHeader
import com.znliang.committee.ui.component.StateBadge
import com.znliang.committee.ui.component.SystemBubble
import com.znliang.committee.ui.screen.home.ActionItemsCard
import com.znliang.committee.ui.screen.home.AddActionItemDialog
import com.znliang.committee.ui.screen.home.CompactAgentCard
import com.znliang.committee.ui.screen.home.ExecutionConfirmBar
import com.znliang.committee.ui.screen.home.HumanInputBar
import com.znliang.committee.ui.screen.home.MeetingErrorCard
import com.znliang.committee.ui.screen.home.MeetingInitCard
import com.znliang.committee.ui.screen.home.MeetingStatusBar
import com.znliang.committee.ui.screen.home.MeetingSummaryCard
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
import com.znliang.committee.ui.viewmodel.AgentMemoryViewModel
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
    onAgentClick: (String) -> Unit = {},
) {
    // 4 independent state flows — streaming only recomposes speech area
    val streamState by viewModel.streamingState.collectAsState()
    val boardState by viewModel.boardState.collectAsState()
    val configState by viewModel.configState.collectAsState()
    val actionState by viewModel.actionState.collectAsState()
    // Legacy combined state no longer needed -- using split state flows instead
    val activePreset by presetConfig.activePresetFlow()
        .collectAsState(initial = presetConfig.getActivePreset())

    val memoryViewModel: AgentMemoryViewModel = hiltViewModel()
    val memoryStats by memoryViewModel.stats.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var subjectInput by remember { mutableStateOf("") }
    val attachedUris = remember { mutableListOf<Uri>() }
    var attachedCount by remember { mutableStateOf(0) } // trigger recomposition
    // Track expanded state per speech
    val expandedKeys = remember { mutableStateMapOf<String, Boolean>() }

    val isMeetingActive = streamState.currentState != MeetingState.IDLE && streamState.currentState != MeetingState.REJECTED

    // Auto-scroll to bottom when new speech arrives, only if user is near bottom
    LaunchedEffect(streamState.speeches.size) {
        val itemCount = listState.layoutInfo.totalItemsCount
        if (itemCount > 0) {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val isNearBottom = lastVisibleIndex >= itemCount - 3
            if (isNearBottom) {
                scope.launch { listState.animateScrollToItem(itemCount - 1) }
            }
        }
    }

    // Error snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(configState.error) {
        configState.error?.let {
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
                            Text("\u00B7", color = TextMuted, fontSize = 18.sp)
                            Text(stringResource(streamState.currentState.displayNameRes()),
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
                    if (configState.hasApiKey && !isMeetingActive) {
                        Text(
                            text = configState.llmConfig.displayTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                    StateBadge(streamState.currentState, Modifier.padding(end = 12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceCard,
                    titleContentColor = TextPrimary,
                ),
            )
        },
        bottomBar = {
            if (streamState.currentState == MeetingState.APPROVED) {
                ExecutionConfirmBar(viewModel = viewModel)
            } else if (isMeetingActive && !boardState.boardFinished) {
                HumanInputBar(
                    viewModel = viewModel,
                    isPaused = streamState.isPaused,
                )
            }
        }
        ) { paddingValues ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding(), bottom = paddingValues.calculateBottomPadding()),
                contentPadding = PaddingValues(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ── Meeting active: compact status bar (expandable) ──────────
                if (isMeetingActive) {
                    item(key = "status_bar") {
                        MeetingStatusBar(
                            boardState = boardState,
                            roles = activePreset.roles,
                            onWeightChange = { roleId, w -> viewModel.setAgentWeight(roleId, w) },
                        )
                    }
                }

                // ── IDLE: Show meeting init card + agent roster ──────────────
            if (!isMeetingActive) {
                item(key = "meeting_init") {
                    MeetingInitCard(
                        currentState = streamState.currentState,
                        configState = configState,
                        viewModel = viewModel,
                        presetConfig = presetConfig,
                        activePresetId = activePreset.id,
                        subjectInput = subjectInput,
                        onSubjectChange = { subjectInput = it },
                        attachedUris = attachedUris,
                        attachedCount = attachedCount,
                        onAttachedChanged = { attachedCount = attachedUris.size },
                    )
                }

                // ── Agent roster ──────────────────────────────────────────
                item(key = "agents_header") {
                    SectionHeader(stringResource(R.string.agents_members, if (activePreset.committeeLabelRes() != 0) stringResource(activePreset.committeeLabelRes()) else activePreset.committeeLabel))
                }
                items(activePreset.roles, key = { "agent_${it.id}" }) { presetRole ->
                    CompactAgentCard(
                        role = presetRole,
                        stats = memoryStats[presetRole.id],
                        onClick = {
                            onAgentClick(presetRole.id)
                        },
                    )
                }
            }

            // ── System event: meeting start ──────────────────────────────
            if (streamState.speeches.isNotEmpty()) {
                item(key = "meeting_start_event") {
                    EventBubble(stringResource(R.string.home_meeting_start, streamState.speeches.firstOrNull()?.let { s ->
                        java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                            .withZone(java.time.ZoneId.systemDefault())
                            .format(s.timestamp)
                    } ?: ""))
                }
            }

            // ── Chat messages: speeches + system logs interleaved ─────────
            items(streamState.speeches, key = { it.id }) { speech ->
                // 系统事件（分歧标注等）渲染为 EventBubble
                if (speech.agent == "system") {
                    EventBubble(
                        text = speech.content,
                        color = StateWarningColor,
                    )
                } else {
                    val presetRole = activePreset.findRole(speech.agent)
                    ChatBubble(
                        speech = speech,
                        isExpanded = expandedKeys[speech.id] == true,
                        onToggle = {
                            expandedKeys[speech.id] = expandedKeys[speech.id] != true
                        },
                        presetRole = presetRole,
                        onFollowUp = if (isMeetingActive && speech.agent != "human" && speech.agent != "supervisor") {
                            { question -> viewModel.followUpQuestion(speech.agent, question) }
                        } else null,
                    )
                }
            }

            // ── Error Card (when meeting aborted due to error) ─────────────
            val meetingError = boardState.boardError
            if (boardState.boardFinished && meetingError != null) {
                item(key = "meeting_error") {
                    MeetingErrorCard(
                        errorMessage = meetingError,
                        onRetry = { viewModel.resetToIdle() },
                    )
                }
            }

            // ── Meeting Summary Card (when finished normally) ────────────────────
            if (boardState.boardFinished && boardState.boardError == null) {
                item(key = "meeting_summary") {
                    val context = LocalContext.current
                    MeetingSummaryCard(
                        rating = boardState.boardRating,
                        summary = boardState.boardSummary,
                        subject = boardState.subject,
                        votes = boardState.boardVotes,
                        ratingScale = activePreset.ratingScale,
                        contribScores = boardState.boardContribScores,
                        roles = activePreset.roles,
                        confidence = boardState.boardConfidence,
                        confidenceBreakdown = boardState.boardConfidenceBreakdown,
                        userOverride = boardState.boardUserOverride,
                        userOverrideReason = boardState.boardUserOverrideReason,
                        onOverride = { rating, reason -> viewModel.overrideDecision(rating, reason) },
                        onNewMeeting = {
                            viewModel.resetToIdle()
                        },
                        onShare = {
                            val intent = viewModel.createShareIntent()
                            context.startActivity(Intent.createChooser(intent, context.getString(R.string.report_share)))
                        },
                        onExportReport = {
                            val file = viewModel.generateReport()
                            if (file != null) {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/markdown"
                                    putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(
                                        context, "${context.packageName}.fileprovider", file
                                    ))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, context.getString(R.string.report_export)))
                            }
                        },
                    )
                }
            }

            // ── Action Items (pending decisions to execute) ────────────
            if (actionState.pendingActions.isNotEmpty()) {
                item(key = "action_items") {
                    ActionItemsCard(
                        actions = actionState.pendingActions,
                        onStatusChange = { id, status -> viewModel.updateActionStatus(id, status) },
                        onDelete = { id -> viewModel.deleteAction(id) },
                    )
                }
            }

            // ── Add Action Item (when meeting finished) ───────────────
            if (boardState.boardFinished) {
                item(key = "add_action") {
                    var showAddAction by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { showAddAction = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = BorderStroke(1.dp, BorderColor),
                    ) {
                        Icon(Icons.Default.PlaylistAdd, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_add))
                    }
                    if (showAddAction) {
                        AddActionItemDialog(
                            onDismiss = { showAddAction = false },
                            onAdd = { title, desc ->
                                viewModel.addActionItem(title, desc)
                                showAddAction = false
                            },
                        )
                    }
                }
            }

            // ── Live system logs (thinking / waiting) ─────────────────────
            if (streamState.looperLogs.isNotEmpty()) {
                item(key = "logs_spacer") { Spacer(Modifier.height(4.dp)) }
                // Show last few system logs as system bubbles
                val recentLogs = streamState.looperLogs.takeLast(3)
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
            if (isMeetingActive && streamState.looperLogs.lastOrNull()?.contains("[Thinking]") == true) {
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
