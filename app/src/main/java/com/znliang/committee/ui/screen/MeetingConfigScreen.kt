package com.znliang.committee.ui.screen

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.znliang.committee.R
import com.znliang.committee.domain.model.ALL_PRESETS
import com.znliang.committee.domain.model.MeetingPresetConfig
import com.znliang.committee.domain.model.PresetRole
import com.znliang.committee.ui.component.SectionHeader
import com.znliang.committee.ui.theme.BorderColor
import com.znliang.committee.ui.theme.CommitteeGold
import com.znliang.committee.ui.theme.SurfaceCard
import com.znliang.committee.ui.theme.SurfaceDark
import com.znliang.committee.ui.theme.TextMuted
import com.znliang.committee.ui.theme.TextPrimary
import com.znliang.committee.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingConfigScreen(
    presetConfig: MeetingPresetConfig,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var activePreset by remember { mutableStateOf(presetConfig.getActivePreset()) }

    LaunchedEffect(Unit) {
        presetConfig.activePresetFlow().collect { activePreset = it }
    }

    // Agent management state
    var showAgentDialog by remember { mutableStateOf(false) }
    var editingRole by remember { mutableStateOf<PresetRole?>(null) }
    var customRoles by remember { mutableStateOf(presetConfig.getActivePreset().roles) }

    // Refresh custom roles when preset changes
    LaunchedEffect(activePreset) {
        customRoles = activePreset.roles
    }

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_meeting_config),
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
            // ── Meeting Mode ─────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeader(stringResource(R.string.settings_meeting_mode))
                        Text(
                            stringResource(R.string.settings_meeting_mode_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )

                        ALL_PRESETS.forEach { preset ->
                            val isSelected = activePreset.id == preset.id
                            Card(
                                onClick = {
                                    if (!isSelected) {
                                        scope.launch {
                                            presetConfig.setActivePreset(preset.id)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) CommitteeGold.copy(alpha = 0.12f)
                                        else SurfaceDark,
                                ),
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) CommitteeGold else BorderColor,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = when (preset.iconName) {
                                            "account_balance" -> Icons.Default.AccountBalance
                                            "groups" -> Icons.Default.Groups
                                            else -> Icons.Default.MeetingRoom
                                        },
                                        contentDescription = null,
                                        tint = if (isSelected) CommitteeGold else TextSecondary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            stringResource(preset.nameRes()),
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) CommitteeGold else TextPrimary,
                                        )
                                        Text(
                                            stringResource(
                                                R.string.settings_roles_format,
                                                preset.roles.size,
                                                preset.roles.joinToString(", ") { role ->
                                                    role.displayName
                                                }
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            null,
                                            tint = CommitteeGold,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
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
                        SectionHeader(stringResource(R.string.settings_mandate_title))
                        Text(
                            "${stringResource(activePreset.committeeLabelRes())} · ${stringResource(activePreset.nameRes())}",
                            style = MaterialTheme.typography.labelLarge,
                            color = CommitteeGold,
                        )
                        // Show mandates from active preset
                        activePreset.mandates.forEach { (key, value) ->
                            val label = when (key) {
                                "debate_rounds" -> stringResource(R.string.mandate_debate_rounds)
                                "consensus_required" -> stringResource(R.string.mandate_consensus_required)
                                "supervisor_final_call" -> stringResource(R.string.mandate_supervisor_final)
                                "phase1_label" -> stringResource(R.string.mandate_phase1)
                                "phase2_label" -> stringResource(R.string.mandate_phase2)
                                else -> key
                            }
                            val displayValue = when (value) {
                                "true" -> stringResource(R.string.mandate_yes)
                                "false" -> stringResource(R.string.mandate_no)
                                else -> value
                            }
                            MandateRow(label, displayValue)
                        }
                        MandateRow(
                            stringResource(R.string.settings_rating_system),
                            activePreset.ratingScale.joinToString(" → "),
                        )
                    }
                }
            }

            // ── Agent Management ───────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionHeader(stringResource(R.string.settings_role_management))
                            Spacer(Modifier.weight(1f))
                            if (presetConfig.hasCustomRoles(activePreset.id)) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            presetConfig.resetToDefaultRoles(activePreset.id)
                                            customRoles = presetConfig.getActivePreset().roles
                                        }
                                    },
                                ) {
                                    Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(16.dp), tint = TextMuted)
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.settings_reset_default), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.settings_current_mode, stringResource(activePreset.nameRes()), customRoles.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                        // Role list
                        customRoles.forEachIndexed { index, role ->
                            val roleColor = runCatching {
                                androidx.compose.ui.graphics.Color(role.colorHex.toColorInt())
                            }.getOrDefault(TextSecondary)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = SurfaceCard.copy(alpha = 0.5f)),
                                border = BorderStroke(1.dp, roleColor.copy(alpha = 0.3f)),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Color dot
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(roleColor, RoundedCornerShape(5.dp)),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(role.let { val res = it.displayNameRes(); if (res != 0) stringResource(res) else it.displayName }, fontWeight = FontWeight.SemiBold, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                        Text(role.let { val res = it.stanceRes(); if (res != 0) stringResource(res) else it.stance }, style = MaterialTheme.typography.labelSmall, color = roleColor)
                                    }
                                    // Edit button
                                    IconButton(
                                        onClick = {
                                            editingRole = role
                                            showAgentDialog = true
                                        },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(Icons.Default.Edit, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                                    }
                                    // Delete button
                                    IconButton(
                                        onClick = {
                                            val updated = customRoles.toMutableList().apply { removeAt(index) }
                                            scope.launch {
                                                presetConfig.saveCustomRoles(activePreset.id, updated)
                                                customRoles = updated
                                            }
                                        },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(Icons.Default.Delete, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        // Add role button
                        OutlinedButton(
                            onClick = {
                                editingRole = null
                                showAgentDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, CommitteeGold.copy(alpha = 0.5f)),
                        ) {
                            Icon(Icons.Default.Add, null, tint = CommitteeGold, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_add_role), color = CommitteeGold, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Agent edit/add dialog
        if (showAgentDialog) {
            AgentEditDialog(
                existingRole = editingRole,
                onDismiss = { showAgentDialog = false },
                onSave = { role ->
                    val updated = if (editingRole != null) {
                        customRoles.map { if (it.id == editingRole!!.id) role else it }
                    } else {
                        customRoles + role
                    }
                    scope.launch {
                        presetConfig.saveCustomRoles(activePreset.id, updated)
                        customRoles = updated
                    }
                    showAgentDialog = false
                },
            )
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

@Composable
private fun AgentEditDialog(
    existingRole: PresetRole?,
    onDismiss: () -> Unit,
    onSave: (PresetRole) -> Unit,
) {
    var name by remember { mutableStateOf(existingRole?.displayName ?: "") }
    var stance by remember { mutableStateOf(existingRole?.stance ?: "") }
    var responsibility by remember { mutableStateOf(existingRole?.responsibility ?: "") }
    var colorHex by remember { mutableStateOf(existingRole?.colorHex ?: "#4CAF50") }

    val predefinedColors = listOf("#4CAF50", "#F44336", "#2196F3", "#FF9800", "#9C27B0", "#607D8B", "#E91E63", "#00BCD4")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (existingRole != null) stringResource(R.string.settings_edit_role) else stringResource(R.string.settings_add_role),
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_role_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Stance
                OutlinedTextField(
                    value = stance,
                    onValueChange = { stance = it },
                    label = { Text(stringResource(R.string.settings_stance)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Responsibility
                OutlinedTextField(
                    value = responsibility,
                    onValueChange = { responsibility = it },
                    label = { Text(stringResource(R.string.settings_responsibility)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3,
                )
                // Color picker
                Text(stringResource(R.string.settings_role_color), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    predefinedColors.forEach { hex ->
                        val color = androidx.compose.ui.graphics.Color(hex.toColorInt())
                        val selected = colorHex == hex
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    color,
                                    RoundedCornerShape(if (selected) 8.dp else 16.dp),
                                )
                                .then(
                                    if (selected) Modifier.border(2.dp, TextPrimary, RoundedCornerShape(8.dp))
                                    else Modifier,
                                )
                                .clickable { colorHex = hex },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) return@TextButton
                    val id = existingRole?.id ?: "custom_${System.currentTimeMillis()}"
                    onSave(
                        PresetRole(
                            id = id,
                            displayName = name,
                            stance = stance,
                            responsibility = responsibility,
                            systemPromptKey = existingRole?.systemPromptKey ?: "role_custom",
                            colorHex = colorHex,
                        ),
                    )
                },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.save), color = CommitteeGold, fontWeight = FontWeight.Bold)
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
