package com.znliang.committee.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.znliang.committee.R
import com.znliang.committee.domain.model.AgentRole
import com.znliang.committee.engine.LlmConfig
import com.znliang.committee.engine.LlmProvider
import com.znliang.committee.ui.component.MarkdownText
import com.znliang.committee.ui.component.SectionHeader
import com.znliang.committee.ui.component.color
import com.znliang.committee.ui.theme.BorderColor
import com.znliang.committee.ui.theme.CommitteeGold
import com.znliang.committee.ui.theme.SellColor
import com.znliang.committee.ui.theme.StateErrorColor
import com.znliang.committee.ui.theme.SurfaceCard
import com.znliang.committee.ui.theme.SurfaceDark
import com.znliang.committee.ui.theme.TextMuted
import com.znliang.committee.ui.theme.TextPrimary
import com.znliang.committee.ui.theme.TextSecondary
import com.znliang.committee.ui.theme.committeeTextFieldColors
import com.znliang.committee.ui.viewmodel.AgentChatViewModel
import com.znliang.committee.ui.viewmodel.AgentMemoryDetail
import com.znliang.committee.ui.viewmodel.AgentMemoryViewModel
import kotlinx.coroutines.delay

// ── Agent Config & Chat Screen ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentConfigChatScreen(
    role: AgentRole,
    viewModel: AgentChatViewModel,
    onBack: () -> Unit,
    memoryViewModel: AgentMemoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val memoryDetail by memoryViewModel.detail.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    var messageInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.agents_tab_chat),
        stringResource(R.string.agents_tab_experience),
        stringResource(R.string.agents_tab_skills),
        stringResource(R.string.agents_tab_evolution),
    )
    val localFileLabel = stringResource(R.string.agents_local_file)

    LaunchedEffect(role) {
        viewModel.setAgent(role)
        memoryViewModel.loadDetail(role)
    }

    // Auto-scroll on new messages
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.content?.length) {
        val itemCount = listState.layoutInfo.totalItemsCount
        if (itemCount > 0) {
            delay(50)
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    val agentColor = role.color

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = SurfaceDark,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(agentColor.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${role.displayName.first()}", color = agentColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(role.displayName, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text("·", color = TextMuted)
                            Text(role.stance, style = MaterialTheme.typography.labelSmall, color = agentColor.copy(alpha = 0.7f))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = CommitteeGold)
                        }
                    },
                    actions = {
                        if (selectedTab == 0 && uiState.messages.isNotEmpty()) {
                            IconButton(onClick = { viewModel.clearChat() }) {
                                Icon(Icons.Default.DeleteSweep, stringResource(R.string.agents_clear_chat), tint = TextMuted, modifier = Modifier.size(20.dp))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard),
                    windowInsets = TopAppBarDefaults.windowInsets,
                )
                // Tab row
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = SurfaceCard,
                    contentColor = CommitteeGold,
                    edgePadding = 12.dp,
                    divider = {},
                    modifier = Modifier.background(SurfaceCard),
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(title, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal)
                            },
                            selectedContentColor = CommitteeGold,
                            unselectedContentColor = TextMuted,
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (selectedTab == 0) {
            // Chat input bar — 添加 navigationBars padding 避免被系统导航栏遮挡
            Surface(
                color = SurfaceCard,
                tonalElevation = 4.dp,
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = { messageInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 42.dp, max = 120.dp),
                        placeholder = { Text(stringResource(R.string.agents_send_message_to, role.displayName), color = TextMuted) },
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            keyboard?.hide()
                            if (messageInput.isNotBlank()) {
                                viewModel.sendMessage(messageInput)
                                messageInput = ""
                            }
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = agentColor,
                            unfocusedBorderColor = BorderColor,
                            cursorColor = agentColor,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                        ),
                    )
                    IconButton(
                        onClick = {
                            keyboard?.hide()
                            if (messageInput.isNotBlank()) {
                                viewModel.sendMessage(messageInput)
                                messageInput = ""
                            }
                        },
                        enabled = messageInput.isNotBlank() && !uiState.isLoading,
                        modifier = Modifier.padding(bottom = 2.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            stringResource(R.string.agents_send),
                            tint = if (messageInput.isNotBlank()) agentColor else TextMuted,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                if (targetState > initialState) {
                    slideInHorizontally { it / 3 } togetherWith slideOutHorizontally { -it / 3 }
                } else {
                    slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it / 3 }
                }
            },
            label = "tabTransition",
        ) { tab ->
            when (tab) {
            0 -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
            // ── Prompt 优化建议卡片 ────────────────────────────────
            if (uiState.promptSuggestion.isNotBlank()) {
                item {
                    PromptSuggestionCard(
                        suggestion = uiState.promptSuggestion,
                        agentColor = agentColor,
                        onApply = { viewModel.applyPromptSuggestion() },
                        onDismiss = { viewModel.dismissSuggestion() },
                    )
                }
            }

            // System prompt card (editable)
            item {
                var expanded by remember { mutableStateOf(false) }
                var editingText by remember(uiState.systemPrompt) { mutableStateOf(uiState.systemPrompt) }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, agentColor.copy(alpha = 0.2f)),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = !expanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionHeader(stringResource(R.string.agents_config_doc))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    uiState.promptSource,
                                    color = if (uiState.promptSource == localFileLabel) CommitteeGold else TextMuted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Icon(
                                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null, tint = TextMuted, modifier = Modifier.size(18.dp),
                            )
                        }
                        if (expanded) {
                            Spacer(Modifier.height(8.dp))
                            if (uiState.isEditingPrompt) {
                                // Edit mode
                                OutlinedTextField(
                                    value = editingText,
                                    onValueChange = { editingText = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 200.dp),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = TextPrimary,
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = agentColor,
                                        unfocusedBorderColor = BorderColor,
                                        cursorColor = agentColor,
                                    ),
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = { viewModel.savePrompt(editingText) },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = CommitteeGold),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Text(stringResource(R.string.save), color = SurfaceDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.setEditingPrompt(false) },
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, BorderColor),
                                    ) {
                                        Text(stringResource(R.string.cancel), fontSize = 13.sp, color = TextMuted)
                                    }
                                    if (uiState.promptSource == localFileLabel) {
                                        OutlinedButton(
                                            onClick = { viewModel.resetPrompt() },
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, StateErrorColor),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StateErrorColor),
                                        ) {
                                            Text(stringResource(R.string.agents_restore), fontSize = 13.sp)
                                        }
                                    }
                                }
                            } else {
                                // View mode
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    color = SurfaceDark,
                                ) {
                                    SelectionContainer {
                                        Text(
                                            uiState.systemPrompt,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                lineHeight = 18.sp,
                                            ),
                                            color = TextSecondary,
                                            modifier = Modifier.padding(10.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            editingText = uiState.systemPrompt
                                            viewModel.setEditingPrompt(true)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CommitteeGold.copy(alpha = 0.15f)),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp), tint = CommitteeGold)
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.edit), color = CommitteeGold, fontSize = 13.sp)
                                    }
                                    if (uiState.promptSource == localFileLabel) {
                                        OutlinedButton(
                                            onClick = { viewModel.resetPrompt() },
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, StateErrorColor),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StateErrorColor),
                                        ) {
                                            Text(stringResource(R.string.agents_reset_default), fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Model config card (collapsible)
            item {
                var configExpanded by remember { mutableStateOf(false) }
                val config = uiState.agentConfig

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { configExpanded = !configExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionHeader(stringResource(R.string.agents_model_config))
                                Spacer(Modifier.width(8.dp))
                                if (uiState.isUsingCustomConfig) {
                                    Text(stringResource(R.string.agents_custom), color = CommitteeGold, style = MaterialTheme.typography.labelSmall)
                                } else {
                                    Text(stringResource(R.string.agents_global), color = TextMuted, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Icon(
                                if (configExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null, tint = TextMuted, modifier = Modifier.size(18.dp),
                            )
                        }
                        // 一行摘要（始终可见）
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(config.provider.displayName, color = CommitteeGold, style = MaterialTheme.typography.labelMedium)
                            Text(config.model, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                        }

                        if (configExpanded) {
                            Spacer(Modifier.height(10.dp))
                            ModelConfigEditor(
                                currentConfig = config,
                                isCustom = uiState.isUsingCustomConfig,
                                onSave = { viewModel.saveAgentConfig(it) },
                                onReset = { viewModel.resetAgentConfig() },
                            )
                        }
                    }
                }
            }

            // Chat messages
            items(uiState.messages, key = { it.id }) { msg ->
                if (msg.role == "user") {
                    // User bubble - right aligned
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .clip(RoundedCornerShape(12.dp, 2.dp, 12.dp, 12.dp))
                                .background(CommitteeGold.copy(alpha = 0.1f))
                                .border(1.dp, CommitteeGold.copy(alpha = 0.2f), RoundedCornerShape(12.dp, 2.dp, 12.dp, 12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                msg.content,
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                } else {
                    // Agent bubble - left aligned with avatar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .animateContentSize(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.Top,
                    ) {
                        // Avatar
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(agentColor.copy(alpha = 0.15f))
                                .border(1.dp, agentColor.copy(alpha = 0.4f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${role.displayName.first()}", color = agentColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        Spacer(Modifier.width(8.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                role.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                color = agentColor,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(2.dp, 12.dp, 12.dp, 12.dp))
                                    .background(SurfaceCard)
                                    .border(1.dp, agentColor.copy(alpha = 0.1f), RoundedCornerShape(2.dp, 12.dp, 12.dp, 12.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                if (msg.isStreaming && msg.content.isEmpty()) {
                                    // Thinking indicator
                                    Text(stringResource(R.string.agents_thinking), color = TextMuted, style = MaterialTheme.typography.bodySmall)
                                } else {
                                    MarkdownText(text = msg.content)
                                }
                            }
                        }
                    }
                }
            }

            // Error display
            uiState.error?.let { error ->
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(error, color = StateErrorColor, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
                }
            }
            1 -> { // 经验
                ExperienceTab(padding, memoryDetail, agentColor)
            }
            2 -> { // 技能
                SkillsTab(padding, memoryDetail, agentColor)
            }
            3 -> { // 进化
                EvolutionTab(padding, memoryDetail, agentColor)
            }
            }
        }
    }
}

// ── Model Config Editor ──────────────────────────────────────────────────

@Composable
private fun ModelConfigEditor(
    currentConfig: LlmConfig,
    isCustom: Boolean,
    onSave: (LlmConfig) -> Unit,
    onReset: () -> Unit,
) {
    var selectedProvider by remember(currentConfig) { mutableStateOf(currentConfig.provider) }
    var selectedModel by remember(currentConfig) { mutableStateOf(currentConfig.model) }
    var apiKeyInput by remember(currentConfig) { mutableStateOf(currentConfig.apiKey) }
    var baseUrlInput by remember(currentConfig) { mutableStateOf(currentConfig.baseUrl) }

    // 当切换 provider 时，自动切到默认模型
    LaunchedEffect(selectedProvider) {
        if (selectedModel !in selectedProvider.models) {
            selectedModel = selectedProvider.defaultModel
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Provider 选择
        Text(stringResource(R.string.agents_provider_label), color = TextMuted, style = MaterialTheme.typography.labelMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            LlmProvider.entries.forEach { provider ->
                val isSelected = provider == selectedProvider
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedProvider = provider },
                    label = { Text(provider.displayName, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CommitteeGold.copy(alpha = 0.2f),
                        selectedLabelColor = CommitteeGold,
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) CommitteeGold else BorderColor,
                    ),
                )
            }
        }

        // Model 选择
        Text(stringResource(R.string.agents_model_label), color = TextMuted, style = MaterialTheme.typography.labelMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            selectedProvider.models.forEach { model ->
                val isSelected = model == selectedModel
                val shortName = model.removePrefix(selectedProvider.id + "/")
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedModel = model },
                    label = { Text(shortName, fontSize = 11.sp, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CommitteeGold.copy(alpha = 0.15f),
                        selectedLabelColor = CommitteeGold,
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) CommitteeGold.copy(alpha = 0.5f) else BorderColor,
                    ),
                )
            }
        }

        // API Key（可选覆盖）
        Text(stringResource(R.string.agents_apikey_hint), color = TextMuted, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.agents_use_global_key), color = TextMuted, fontSize = 12.sp) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
            colors = committeeTextFieldColors(),
        )

        // Base URL（可选覆盖）
        Text(stringResource(R.string.agents_baseurl_hint), color = TextMuted, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = baseUrlInput,
            onValueChange = { baseUrlInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(selectedProvider.defaultBaseUrl, color = TextMuted, fontSize = 12.sp) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
            colors = committeeTextFieldColors(),
        )

        // 按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    onSave(LlmConfig(
                        provider = selectedProvider,
                        apiKey = apiKeyInput,
                        model = selectedModel,
                        baseUrl = baseUrlInput.ifBlank { selectedProvider.defaultBaseUrl },
                    ))
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = CommitteeGold),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(stringResource(R.string.agents_save_config), color = SurfaceDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            if (isCustom) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, StateErrorColor),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StateErrorColor),
                ) {
                    Text(stringResource(R.string.agents_reset_global), fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * Prompt 自优化建议卡片
 */
@Composable
private fun PromptSuggestionCard(
    suggestion: String,
    agentColor: Color,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 解析建议
    var reflection by remember { mutableStateOf("") }
    var suggestionText by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("MEDIUM") }

    LaunchedEffect(suggestion) {
        suggestion.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("REFLECTION:", ignoreCase = true) ->
                    reflection = trimmed.substringAfter(":").trim()
                trimmed.startsWith("SUGGESTION:", ignoreCase = true) ->
                    suggestionText = trimmed.substringAfter(":").trim()
                trimmed.startsWith("PRIORITY:", ignoreCase = true) ->
                    priority = trimmed.substringAfter(":").trim().uppercase()
            }
        }
    }

    val priorityColor = when (priority) {
        "HIGH" -> SellColor
        "LOW" -> TextMuted
        else -> CommitteeGold
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, agentColor.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            // 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = agentColor,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.agents_self_opt),
                    style = MaterialTheme.typography.titleSmall,
                    color = agentColor,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    priority,
                    style = MaterialTheme.typography.labelSmall,
                    color = priorityColor,
                    modifier = Modifier
                        .background(priorityColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            // 反思
            if (reflection.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    reflection,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    lineHeight = 18.sp,
                )
            }

            // 建议
            if (suggestionText.isNotBlank() && !suggestionText.contains(stringResource(R.string.agents_no_change))) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = BorderColor)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.agents_suggest_improve),
                    style = MaterialTheme.typography.labelMedium,
                    color = CommitteeGold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    suggestionText,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    lineHeight = 18.sp,
                )
            }

            // 操作按钮
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (suggestionText.isNotBlank() && !suggestionText.contains(stringResource(R.string.agents_no_change))) {
                    Button(
                        onClick = onApply,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = agentColor),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Default.Check, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.agents_accept), color = SurfaceDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, TextMuted),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted),
                ) {
                    Text(if (suggestionText.contains(stringResource(R.string.agents_no_change))) stringResource(R.string.agents_got_it) else stringResource(R.string.agents_ignore), fontSize = 12.sp)
                }
            }
        }
    }
}

// ── Agent Detail Tabs: 经验 / 技能 / 进化 ─────────────────────────────

@Composable
private fun ExperienceTab(
    padding: PaddingValues,
    detail: AgentMemoryDetail?,
    agentColor: Color,
) {
    val experiences = detail?.experiences ?: emptyList()
    if (experiences.isEmpty()) {
        EmptyTabHint(padding, stringResource(R.string.agents_no_experience), stringResource(R.string.agents_no_experience_hint))
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = experiences, key = { it.id }) { exp ->
                val categoryIcon = when (exp.category) {
                    "STRATEGY" -> "🎯"
                    "MISTAKE" -> "⚠️"
                    "INSIGHT" -> "💡"
                    "PROMPT_FIX" -> "🔧"
                    else -> "📝"
                }
                val outcomeColor = when (exp.outcome) {
                    "POSITIVE" -> CommitteeGold
                    "NEGATIVE" -> StateErrorColor
                    else -> TextMuted
                }
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, agentColor.copy(alpha = 0.12f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$categoryIcon ${exp.category}", color = agentColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Spacer(Modifier.weight(1f))
                            Text(exp.outcome, color = outcomeColor, fontSize = 11.sp,
                                modifier = Modifier.background(outcomeColor.copy(alpha = 0.1f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(exp.content, color = TextPrimary, fontSize = 13.sp, maxLines = 4)
                        Spacer(Modifier.height(4.dp))
                        Text(formatTimestamp(exp.createdAt), color = TextMuted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillsTab(
    padding: PaddingValues,
    detail: AgentMemoryDetail?,
    agentColor: Color,
) {
    val skills = detail?.skills ?: emptyList()
    if (skills.isEmpty()) {
        EmptyTabHint(padding, stringResource(R.string.agents_no_skills), stringResource(R.string.agents_no_skills_hint))
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = skills, key = { it.id }) { skill ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, agentColor.copy(alpha = 0.12f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, null, tint = CommitteeGold, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(skill.skillName, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            // confidence bar
                            val conf = skill.confidence.coerceIn(0f, 1f)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LinearProgressIndicator(
                                    progress = { conf },
                                    modifier = Modifier.width(40.dp).height(4.dp).clip(CircleShape),
                                    color = CommitteeGold,
                                    trackColor = BorderColor,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("${(conf * 100).toInt()}%", color = TextMuted, fontSize = 10.sp)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(skill.strategyContent, color = TextSecondary, fontSize = 12.sp, maxLines = 3)
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Text(stringResource(R.string.agents_used_times, skill.usageCount), color = TextMuted, fontSize = 10.sp)
                            if (skill.lastUsed > 0) {
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(R.string.agents_last_used, formatTimestamp(skill.lastUsed)), color = TextMuted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EvolutionTab(
    padding: PaddingValues,
    detail: AgentMemoryDetail?,
    agentColor: Color,
) {
    val changelogs = detail?.changelogs ?: emptyList()
    val outcomes = detail?.outcomes ?: emptyList()

    if (changelogs.isEmpty() && outcomes.isEmpty()) {
        EmptyTabHint(padding, stringResource(R.string.agents_no_evolution), stringResource(R.string.agents_no_evolution_hint))
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Prompt 变更历史
            if (changelogs.isNotEmpty()) {
                item {
                    SectionHeader(stringResource(R.string.agents_prompt_changes))
                    Spacer(Modifier.height(4.dp))
                }
                items(items = changelogs, key = { it.id }) { cl ->
                    val typeIcon = when (cl.changeType) {
                        "AUTO_EVOLVE" -> "🧬"
                        "MANUAL" -> "✏️"
                        "SUGGESTION_APPLIED" -> "✅"
                        "ROLLBACK" -> "⏪"
                        else -> "📝"
                    }
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        border = BorderStroke(1.dp, if (cl.isRolledBack) StateErrorColor.copy(alpha = 0.3f) else agentColor.copy(alpha = 0.12f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("$typeIcon ${cl.changeType}", color = agentColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                if (cl.isRolledBack) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.agents_rolled_back), color = StateErrorColor, fontSize = 10.sp,
                                        modifier = Modifier.background(StateErrorColor.copy(alpha = 0.1f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                                }
                                Spacer(Modifier.weight(1f))
                                Text(formatTimestamp(cl.createdAt), color = TextMuted, fontSize = 10.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(cl.reason, color = TextSecondary, fontSize = 12.sp, maxLines = 3)
                        }
                    }
                }
            }
            // 会议结果
            if (outcomes.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader(stringResource(R.string.agents_meeting_outcomes))
                    Spacer(Modifier.height(4.dp))
                }
                items(items = outcomes, key = { it.id }) { out ->
                    val voteColor = when (out.voteCorrect) {
                        true -> CommitteeGold
                        false -> StateErrorColor
                        else -> TextMuted
                    }
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        border = BorderStroke(1.dp, agentColor.copy(alpha = 0.12f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(out.subject, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2)
                            Spacer(Modifier.height(4.dp))
                            Row {
                                if (out.agentVote != null) {
                                    Text(stringResource(R.string.agents_vote_format, out.agentVote), color = voteColor, fontSize = 11.sp)
                                    Spacer(Modifier.width(12.dp))
                                }
                                if (out.finalRating != null) {
                                    Text(stringResource(R.string.agents_rating_format, out.finalRating), color = TextMuted, fontSize = 11.sp)
                                    Spacer(Modifier.width(12.dp))
                                }
                                if (out.voteCorrect != null) {
                                    Text(if (out.voteCorrect) stringResource(R.string.agents_vote_correct) else stringResource(R.string.agents_vote_deviation), color = voteColor, fontSize = 11.sp)
                                }
                            }
                            if (out.lessonsLearned.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(out.lessonsLearned, color = TextSecondary, fontSize = 12.sp, maxLines = 3)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(formatTimestamp(out.createdAt), color = TextMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTabHint(padding: PaddingValues, title: String, subtitle: String) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = TextMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = TextMuted.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

private fun formatTimestamp(ts: Long): String {
    if (ts <= 0) return ""
    return java.time.Instant.ofEpochMilli(ts)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm", java.util.Locale.getDefault()))
}
