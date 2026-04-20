package com.znliang.committee.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.znliang.committee.R
import com.znliang.committee.engine.LlmConfig
import com.znliang.committee.engine.LlmProvider
import com.znliang.committee.ui.component.SectionHeader
import com.znliang.committee.ui.theme.BorderColor
import com.znliang.committee.ui.theme.BuyColor
import com.znliang.committee.ui.theme.CommitteeGold
import com.znliang.committee.ui.theme.StateWarningColor
import com.znliang.committee.ui.theme.SurfaceCard
import com.znliang.committee.ui.theme.SurfaceDark
import com.znliang.committee.ui.theme.TextMuted
import com.znliang.committee.ui.theme.TextPrimary
import com.znliang.committee.ui.theme.TextSecondary
import com.znliang.committee.ui.theme.committeeTextFieldColors
import com.znliang.committee.ui.viewmodel.MeetingViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelConfigScreen(
    viewModel: MeetingViewModel,
    onBack: () -> Unit,
) {
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
                    Text(
                        stringResource(R.string.settings_model_config),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard),
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding(), bottom = 6.dp),
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
                        SectionHeader(stringResource(R.string.settings_model_provider))

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
                                label = { Text(stringResource(R.string.settings_select_model)) },
                                trailingIcon = {
                                    Icon(Icons.Default.ArrowDropDown, null, tint = TextSecondary)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = committeeTextFieldColors(),
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
                            colors = committeeTextFieldColors(),
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
                            text = stringResource(R.string.settings_apikey_desc),
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
                                Text(stringResource(R.string.settings_apikey_configured), color = BuyColor,
                                    style = MaterialTheme.typography.labelLarge)
                            } else {
                                Icon(Icons.Default.Warning, null, tint = StateWarningColor,
                                    modifier = Modifier.size(16.dp))
                                Text(stringResource(R.string.settings_not_configured), color = StateWarningColor,
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
                            colors = committeeTextFieldColors(),
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
                            Text(if (saved) stringResource(R.string.settings_saved) else stringResource(R.string.settings_save_config), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
