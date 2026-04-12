package com.committee.investing.ui.screen

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.committee.investing.domain.model.AgentRole
import com.committee.investing.ui.component.MarkdownText
import com.committee.investing.ui.component.SectionHeader
import com.committee.investing.ui.component.color
import com.committee.investing.ui.theme.*
import com.committee.investing.engine.LlmConfig
import com.committee.investing.engine.LlmProvider
import com.committee.investing.ui.viewmodel.AgentChatViewModel
import kotlinx.coroutines.delay

// ── Agents List Screen ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    onAgentClick: (AgentRole) -> Unit,
) {
    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.SmartToy, null, tint = CommitteeGold)
                        Text("委员会成员", color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard),
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(AgentRole.entries.toList()) { role ->
                AgentCard(
                    role = role,
                    onClick = { onAgentClick(role) },
                )
            }
        }
    }
}

@Composable
private fun AgentCard(role: AgentRole, onClick: () -> Unit) {
    val agentColor = role.color
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(agentColor.copy(alpha = 0.15f))
                    .border(1.5.dp, agentColor.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${role.displayName.first()}",
                    color = agentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }

            Spacer(Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    role.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    role.stance,
                    style = MaterialTheme.typography.labelSmall,
                    color = agentColor.copy(alpha = 0.7f),
                )
                Text(
                    role.responsibility,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 2,
                )
            }

            // Arrow (forward)
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ── Agent Config & Chat Screen ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentConfigChatScreen(
    role: AgentRole,
    viewModel: AgentChatViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    var messageInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(role) {
        viewModel.setAgent(role)
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
        containerColor = SurfaceDark,
        topBar = {
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = CommitteeGold)
                    }
                },
                actions = {
                    if (uiState.messages.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearChat() }) {
                            Icon(Icons.Default.DeleteSweep, "清空对话", tint = TextMuted, modifier = Modifier.size(20.dp))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard),
            )
        },
        bottomBar = {
            // Chat input bar
            Surface(
                color = SurfaceCard,
                tonalElevation = 4.dp,
                border = BorderStroke(1.dp, BorderColor),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = { messageInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("发送消息给 ${role.displayName}...", color = TextMuted) },
                        singleLine = true,
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
                    ) {
                        Icon(
                            Icons.Default.Send,
                            "发送",
                            tint = if (messageInput.isNotBlank()) agentColor else TextMuted,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
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
                                SectionHeader("配置文档")
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    uiState.promptSource,
                                    color = if (uiState.promptSource == "本地文件") CommitteeGold else TextMuted,
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
                                        Text("保存", color = SurfaceDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.setEditingPrompt(false) },
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, BorderColor),
                                    ) {
                                        Text("取消", fontSize = 13.sp, color = TextMuted)
                                    }
                                    if (uiState.promptSource == "本地文件") {
                                        OutlinedButton(
                                            onClick = { viewModel.resetPrompt() },
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, StateErrorColor),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StateErrorColor),
                                        ) {
                                            Text("还原", fontSize = 13.sp)
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
                                        Text("编辑", color = CommitteeGold, fontSize = 13.sp)
                                    }
                                    if (uiState.promptSource == "本地文件") {
                                        OutlinedButton(
                                            onClick = { viewModel.resetPrompt() },
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, StateErrorColor),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StateErrorColor),
                                        ) {
                                            Text("还原为默认", fontSize = 13.sp)
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
                                SectionHeader("模型配置")
                                Spacer(Modifier.width(8.dp))
                                if (uiState.isUsingCustomConfig) {
                                    Text("自定义", color = CommitteeGold, style = MaterialTheme.typography.labelSmall)
                                } else {
                                    Text("全局", color = TextMuted, style = MaterialTheme.typography.labelSmall)
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
                            Text("${config.provider.displayName}", color = CommitteeGold, style = MaterialTheme.typography.labelMedium)
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
                                    Text("思考中...", color = TextMuted, style = MaterialTheme.typography.bodySmall)
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
        Text("服务商", color = TextMuted, style = MaterialTheme.typography.labelMedium)
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
        Text("模型", color = TextMuted, style = MaterialTheme.typography.labelMedium)
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
        Text("API Key（留空使用全局配置）", color = TextMuted, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("使用全局 Key", color = TextMuted, fontSize = 12.sp) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CommitteeGold,
                unfocusedBorderColor = BorderColor,
                cursorColor = CommitteeGold,
            ),
        )

        // Base URL（可选覆盖）
        Text("Base URL（留空使用默认）", color = TextMuted, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = baseUrlInput,
            onValueChange = { baseUrlInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(selectedProvider.defaultBaseUrl, color = TextMuted, fontSize = 12.sp) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CommitteeGold,
                unfocusedBorderColor = BorderColor,
                cursorColor = CommitteeGold,
            ),
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
                Text("保存配置", color = SurfaceDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            if (isCustom) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, StateErrorColor),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StateErrorColor),
                ) {
                    Text("重置为全局", fontSize = 13.sp)
                }
            }
        }
    }
}
