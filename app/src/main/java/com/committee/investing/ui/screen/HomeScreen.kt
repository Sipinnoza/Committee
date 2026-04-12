package com.committee.investing.ui.screen

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.committee.investing.domain.model.*
import com.committee.investing.ui.component.*
import com.committee.investing.ui.theme.*
import com.committee.investing.ui.viewmodel.MeetingUiState
import com.committee.investing.ui.viewmodel.MeetingViewModel
import kotlinx.coroutines.launch

/**
 * 投委会群聊对话主页
 * 会议中：顶部状态条 + 聊天气泡流 + 底部输入栏
 * 待机中：顶部发起会议卡片 + 委员名单
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MeetingViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var subjectInput by remember { mutableStateOf("") }
    // Track expanded state per speech
    val expandedKeys = remember { mutableStateMapOf<String, Boolean>() }

    val isMeetingActive = uiState.currentState != MeetingState.IDLE && uiState.currentState != MeetingState.REJECTED

    // Auto-scroll to bottom when new content arrives (speech count or streaming content)
    LaunchedEffect(uiState.speeches.size, uiState.speeches.lastOrNull()?.content?.length) {
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
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("投委会", style = MaterialTheme.typography.titleLarge,
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
                                Icon(Icons.Default.Close, "取消会议", tint = StateErrorColor, modifier = Modifier.size(20.dp))
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
                // Progress bar when meeting active
                if (isMeetingActive) {
                    MeetingProgressBar(uiState.currentState, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
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
                .padding(paddingValues),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // ── IDLE: Show meeting init card ──────────────────────────────
            if (!isMeetingActive) {
                item {
                    MeetingInitCard(
                        uiState = uiState,
                        viewModel = viewModel,
                        subjectInput = subjectInput,
                        onSubjectChange = { subjectInput = it },
                    )
                }
                item {
                    AgentRosterCard()
                }
            }

            // ── System event: meeting start ──────────────────────────────
            if (uiState.speeches.isNotEmpty()) {
                item {
                    EventBubble("会议开始 · ${uiState.speeches.firstOrNull()?.let { s -> 
                        java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                            .withZone(java.time.ZoneId.systemDefault())
                            .format(s.timestamp) 
                    } ?: ""}")
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

            // ── Live system logs (thinking / waiting) ─────────────────────
            if (uiState.looperLogs.isNotEmpty()) {
                item { Spacer(Modifier.height(4.dp)) }
                // Show last few system logs as system bubbles
                val recentLogs = uiState.looperLogs.takeLast(3)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        recentLogs.forEach { line ->
                            val isThinking = line.contains("[思考中]")
                            val isError = line.contains("[错误]") || line.contains("[API 错误]")
                            val isResponse = line.contains("[响应]")
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
            if (isMeetingActive && uiState.looperLogs.lastOrNull()?.contains("[思考中]") == true) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PulsingDot(CommitteeGold, modifier = Modifier.size(8.dp))
                        Text("AI 思考中...", style = MaterialTheme.typography.labelSmall,
                            color = CommitteeGold.copy(alpha = 0.7f))
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
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
                    Text("请在设置中配置 API Key", style = MaterialTheme.typography.bodyMedium,
                        color = StateWarningColor)
                }
            }

            if (isRejected) {
                Text("入场评估未通过，请重新输入标的", style = MaterialTheme.typography.bodyMedium,
                    color = StateWarningColor)
            }

            // Subject input + button
            OutlinedTextField(
                value = subjectInput,
                onValueChange = onSubjectChange,
                label = { Text("标的代码/名称，如 600028 石化") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    if (subjectInput.isNotBlank()) viewModel.requestMeeting(subjectInput)
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CommitteeGold,
                    unfocusedBorderColor = BorderColor,
                    focusedLabelColor = CommitteeGold,
                    cursorColor = CommitteeGold,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                ),
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
                Text("召开会议", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AgentRosterCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("委员会成员")
            AgentRole.entries.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { role -> AgentChip(role, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
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
            Text("评级已批准", style = MaterialTheme.typography.titleSmall,
                color = CommitteeGold, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(
                onClick = { viewModel.confirmExecution() },
                colors = ButtonDefaults.buttonColors(containerColor = BuyColor, contentColor = SurfaceDark),
            ) {
                Icon(Icons.Default.Done, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("确认执行", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// Keep for backward compat — other screens may reference
@Composable
private fun LiveLogCard(logs: List<String>, onClickViewAll: () -> Unit) {}
