package com.znliang.committee.ui.screen

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.hilt.navigation.compose.hiltViewModel
import com.znliang.committee.R
import com.znliang.committee.data.db.DecisionActionEntity
import com.znliang.committee.domain.model.MeetingPresetConfig
import com.znliang.committee.domain.model.MeetingState
import com.znliang.committee.domain.model.PresetRole
import com.znliang.committee.engine.runtime.BoardPhase
import com.znliang.committee.engine.runtime.MaterialRef
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
import java.io.ByteArrayOutputStream

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
    val uiState by viewModel.uiState.collectAsState()
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
                            Text(stringResource(uiState.currentState.displayNameRes()),
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
            } else if (isMeetingActive && !uiState.boardFinished) {
                HumanInputBar(
                    viewModel = viewModel,
                    isPaused = uiState.isPaused,
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
                val presetRole = activePreset.findRole(speech.agent)
                ChatBubble(
                    speech = speech,
                    isExpanded = expandedKeys[speech.id] == true,
                    onToggle = {
                        expandedKeys[speech.id] = expandedKeys[speech.id] != true
                    },
                    presetRole = presetRole,
                )
            }

            // ── Meeting Summary Card (when finished) ────────────────────
            if (uiState.boardFinished) {
                item(key = "meeting_summary") {
                    val context = LocalContext.current
                    MeetingSummaryCard(
                        rating = uiState.boardRating,
                        summary = uiState.boardSummary,
                        subject = uiState.subject,
                        ratingScale = activePreset.ratingScale,
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
            if (uiState.pendingActions.isNotEmpty()) {
                item(key = "action_items") {
                    ActionItemsCard(
                        actions = uiState.pendingActions,
                        onStatusChange = { id, status -> viewModel.updateActionStatus(id, status) },
                        onDelete = { id -> viewModel.deleteAction(id) },
                    )
                }
            }

            // ── Add Action Item (when meeting finished) ───────────────
            if (uiState.boardFinished) {
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
    attachedUris: MutableList<Uri>,
    attachedCount: Int,
    onAttachedChanged: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val isRejected = uiState.currentState == MeetingState.REJECTED

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris: List<Uri> ->
        attachedUris.addAll(uris)
        onAttachedChanged()
    }

    /** Convert attached URIs to MaterialRef list with base64 data */
    fun buildMaterialRefs(): List<MaterialRef> {
        return attachedUris.mapIndexed { idx, uri ->
            try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@mapIndexed null
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val fileName = "attachment_${idx + 1}"

                // Read and optionally compress
                val bytes = inputStream.use { it.readBytes() }
                val base64Str = if (mimeType.startsWith("image/") && bytes.size > 1024 * 1024) {
                    // Compress large images
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        val scale = 1024f / maxOf(bitmap.width, bitmap.height)
                        val w = (bitmap.width * scale).toInt()
                        val h = (bitmap.height * scale).toInt()
                        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
                        val baos = ByteArrayOutputStream()
                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                        if (scaled != bitmap) scaled.recycle()
                        bitmap.recycle()
                        Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    } else {
                        Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                } else {
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                }

                MaterialRef(
                    id = idx.toLong(),
                    fileName = fileName,
                    mimeType = mimeType,
                    base64 = base64Str,
                )
            } catch (_: Exception) { null }
        }.filterNotNull()
    }

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
                    if (subjectInput.isNotBlank()) {
                        viewModel.requestMeeting(subjectInput, buildMaterialRefs())
                    }
                }),
                colors = committeeTextFieldColors(),
            )

            // ── Attachment area ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    border = BorderStroke(1.dp, BorderColor),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp), tint = CommitteeGold)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.home_attach_images), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                }
                if (attachedCount > 0) {
                    Text(
                        stringResource(R.string.home_materials_count, attachedCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }

            // Attached file thumbnails
            if (attachedCount > 0) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(attachedUris.size) { idx ->
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceDark)
                                .border(1.dp, BorderColor, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${idx + 1}", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                            // Remove button
                            IconButton(
                                onClick = {
                                    attachedUris.removeAt(idx)
                                    onAttachedChanged()
                                },
                                modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
                            ) {
                                Icon(Icons.Default.Close, null, Modifier.size(12.dp), tint = TextMuted)
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    keyboard?.hide()
                    viewModel.requestMeeting(subjectInput, buildMaterialRefs())
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
 * 会议中底部输入栏 — 暂停/恢复 + 文本输入 + 发言 + 投票
 */
@Composable
private fun HumanInputBar(
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
 * 投票对话框 — AGREE / DISAGREE + 理由
 */
@Composable
private fun VoteDialog(
    onDismiss: () -> Unit,
    onVote: (agree: Boolean, reason: String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.human_vote_title), color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.human_vote_reason_hint)) },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = committeeTextFieldColors(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onVote(true, reason.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = BuyColor, contentColor = SurfaceDark),
            ) {
                Text(stringResource(R.string.human_vote_agree), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Button(
                onClick = { onVote(false, reason.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = SellColor, contentColor = Color.White),
            ) {
                Text(stringResource(R.string.human_vote_disagree), fontWeight = FontWeight.Bold)
            }
        },
        containerColor = SurfaceCard,
    )
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
    ratingScale: List<String> = emptyList(),
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
                    text = "${(if (role.displayNameRes() != 0) stringResource(role.displayNameRes()) else role.displayName).first()}",
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
                        if (role.displayNameRes() != 0) stringResource(role.displayNameRes()) else role.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (role.stanceRes() != 0) stringResource(role.stanceRes()) else role.stance,
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

// ── Action Items Card ─────────────────────────────────────────

@Composable
private fun ActionItemsCard(
    actions: List<DecisionActionEntity>,
    onStatusChange: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.PlaylistAdd,
                    null,
                    tint = CommitteeGold,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.action_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = CommitteeGold,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${actions.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
            Spacer(Modifier.height(8.dp))

            actions.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Status toggle
                    val isDone = action.status == "done"
                    IconButton(
                        onClick = {
                            val next = when (action.status) {
                                "pending" -> "in_progress"
                                "in_progress" -> "done"
                                else -> "pending"
                            }
                            onStatusChange(action.id, next)
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            if (isDone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            null,
                            tint = when (action.status) {
                                "done" -> BuyColor
                                "in_progress" -> CommitteeGold
                                else -> TextMuted
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            action.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDone) TextMuted else TextPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                        if (action.assignee.isNotBlank()) {
                            Text(
                                action.assignee,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                        }
                    }
                    // Status badge
                    val statusLabel = when (action.status) {
                        "pending" -> stringResource(R.string.action_pending)
                        "in_progress" -> stringResource(R.string.action_in_progress)
                        "done" -> stringResource(R.string.action_done)
                        else -> action.status
                    }
                    val statusColor = when (action.status) {
                        "done" -> BuyColor
                        "in_progress" -> CommitteeGold
                        else -> TextMuted
                    }
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier
                            .background(statusColor.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddActionItemDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.action_add),
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.action_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.action_desc_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (title.isNotBlank()) onAdd(title, description) },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(R.string.skill_add_btn), color = CommitteeGold, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = TextMuted)
            }
        },
        containerColor = SurfaceCard,
    )
}
