package com.committee.investing.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.committee.investing.engine.LlmConfig
import com.committee.investing.engine.LlmProvider
import com.committee.investing.ui.component.SectionHeader
import com.committee.investing.ui.theme.*
import com.committee.investing.ui.viewmodel.MeetingViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: MeetingViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    var selectedProvider by remember(uiState.llmConfig.provider) {
        mutableStateOf(uiState.llmConfig.provider)
    }
    var selectedModel by remember(uiState.llmConfig.model) {
        mutableStateOf(uiState.llmConfig.model)
    }
    var apiKeyInput by remember(uiState.llmConfig.apiKey) {
        mutableStateOf("")
    }
    var baseUrlInput by remember(uiState.llmConfig.baseUrl) {
        mutableStateOf(uiState.llmConfig.baseUrl)
    }
    var showKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(selectedProvider) {
        selectedModel = selectedProvider.defaultModel
        baseUrlInput = selectedProvider.defaultBaseUrl
    }

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Settings, null, tint = CommitteeGold)
                        Text("设置", color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard),
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── Provider + Model ──────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader("模型提供商")

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LlmProvider.entries.forEach { provider ->
                                FilterChip(
                                    selected = selectedProvider == provider,
                                    onClick = {
                                        selectedProvider = provider
                                        saved = false
                                    },
                                    label = { Text(provider.displayName) },
                                    leadingIcon = {
                                        if (selectedProvider == provider) {
                                            Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                                        }
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CommitteeGold.copy(alpha = 0.15f),
                                        selectedLabelColor = CommitteeGold,
                                    ),
                                    border = BorderStroke(1.dp,
                                        if (selectedProvider == provider) CommitteeGold else BorderColor),
                                )
                            }
                        }

                        // ── Model Dropdown ────────────────────────────────────
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = selectedModel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("选择模型") },
                                trailingIcon = {
                                    Icon(Icons.Default.ArrowDropDown, null, tint = TextSecondary)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CommitteeGold,
                                    unfocusedBorderColor = BorderColor,
                                    focusedLabelColor = CommitteeGold,
                                    cursorColor = CommitteeGold,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                ),
                                interactionSource = remember { MutableInteractionSource() }
                                    .also { source ->
                                        LaunchedEffect(source) {
                                            source.interactions.collect { interaction ->
                                                if (interaction is PressInteraction.Release) {
                                                    modelMenuExpanded = true
                                                }
                                            }
                                        }
                                    },
                            )
                            DropdownMenu(
                                expanded = modelMenuExpanded,
                                onDismissRequest = { modelMenuExpanded = false },
                            ) {
                                selectedProvider.models.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(model, color = if (model == selectedModel) CommitteeGold else TextPrimary,
                                                fontWeight = if (model == selectedModel) FontWeight.Bold else FontWeight.Normal)
                                        },
                                        onClick = {
                                            selectedModel = model
                                            modelMenuExpanded = false
                                            saved = false
                                        },
                                    )
                                }
                            }
                        }

                        // ── Base URL ──────────────────────────────────────────
                        OutlinedTextField(
                            value = baseUrlInput,
                            onValueChange = { baseUrlInput = it; saved = false },
                            label = { Text("API Base URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CommitteeGold,
                                unfocusedBorderColor = BorderColor,
                                focusedLabelColor = CommitteeGold,
                                cursorColor = CommitteeGold,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                            ),
                        )
                    }
                }
            }

            // ── API Key ──────────────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader("API Key")
                        Text(
                            text = "用于驱动六大 AI 委员。Key 仅存储在本设备，不会上传。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (uiState.hasApiKey) {
                                Icon(Icons.Default.CheckCircle, null, tint = BuyColor,
                                    modifier = Modifier.size(16.dp))
                                Text("API Key 已配置", color = BuyColor,
                                    style = MaterialTheme.typography.labelLarge)
                            } else {
                                Icon(Icons.Default.Warning, null, tint = StateWarningColor,
                                    modifier = Modifier.size(16.dp))
                                Text("尚未配置", color = StateWarningColor,
                                    style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it; saved = false },
                            label = { Text(selectedProvider.keyPlaceholder) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (showKey) VisualTransformation.None
                                else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                keyboard?.hide()
                            }),
                            trailingIcon = {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        null, tint = TextSecondary)
                                }
                            },
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
                                viewModel.saveLlmConfig(LlmConfig(
                                    provider = selectedProvider,
                                    apiKey = apiKeyInput.ifBlank { uiState.llmConfig.apiKey },
                                    model = selectedModel,
                                    baseUrl = baseUrlInput,
                                ))
                                saved = true
                            },
                            enabled = apiKeyInput.isNotBlank() || uiState.hasApiKey,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CommitteeGold,
                                contentColor = SurfaceDark,
                            ),
                        ) {
                            Icon(if (saved) Icons.Default.Check else Icons.Default.Save, null,
                                Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (saved) "已保存" else "保存配置", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ── Mandate ──────────────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader("投委会授权约束")
                        MandateRow("目标年化收益", "≥ 10%")
                        MandateRow("最大回撤", "≤ 15%")
                        MandateRow("基准指数", "上证综指")
                        MandateRow("评估周期", "季度")
                        MandateRow("评级体系", "Buy → Overweight → Hold+ → Hold → Underweight → Sell")
                    }
                }
            }

            // ── About ────────────────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionHeader("关于")
                        Text("投委会 App v1.2", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Text("基于 Committee Looper v5.2 规格构建", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        Text("多 Agent 对抗性投资决策系统", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        Text("支持 Anthropic Claude / DeepSeek / Kimi", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun MandateRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary,
            fontWeight = FontWeight.SemiBold)
    }
}
