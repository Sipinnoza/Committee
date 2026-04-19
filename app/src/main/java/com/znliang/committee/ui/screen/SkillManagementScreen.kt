package com.znliang.committee.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.znliang.committee.R
import com.znliang.committee.data.db.SkillDefinitionEntity
import com.znliang.committee.ui.component.SectionHeader
import com.znliang.committee.ui.theme.*
import com.znliang.committee.ui.viewmodel.SkillManagementViewModel
import androidx.hilt.navigation.compose.hiltViewModel

// ── Skill Management Screen ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillManagementScreen(
    onBack: () -> Unit,
    viewModel: SkillManagementViewModel = hiltViewModel(),
) {
    val skills by viewModel.skills.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingSkill by remember { mutableStateOf<SkillDefinitionEntity?>(null) }
    var deletingSkill by remember { mutableStateOf<SkillDefinitionEntity?>(null) }

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Build, null, tint = CommitteeGold)
                        Text(
                            stringResource(R.string.skill_mgmt_title),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = CommitteeGold,
                contentColor = SurfaceDark,
            ) {
                Icon(Icons.Default.Add, stringResource(R.string.skill_add))
            }
        },
    ) { padding ->
        if (skills.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Extension,
                        null,
                        modifier = Modifier.size(64.dp),
                        tint = TextMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.skill_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.skill_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SectionHeader("${stringResource(R.string.skill_registered_count)} (${skills.size})")
                    Spacer(Modifier.height(8.dp))
                }
                items(items = skills, key = { it.id }) { skill ->
                    SkillCard(
                        skill = skill,
                        onToggleEnabled = { enabled ->
                            viewModel.toggleEnabled(skill.id, enabled)
                        },
                        onEdit = { editingSkill = skill },
                        onDelete = { deletingSkill = skill },
                    )
                }
            }
        }
    }

    // ── Add Dialog ────────────────────────────────────────────────────────
    if (showAddDialog) {
        SkillEditDialog(
            existingSkill = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, desc, params, type, config ->
                viewModel.addSkill(name, desc, params, type, config)
                showAddDialog = false
            },
        )
    }

    // ── Edit Dialog ───────────────────────────────────────────────────────
    if (editingSkill != null) {
        SkillEditDialog(
            existingSkill = editingSkill,
            onDismiss = { editingSkill = null },
            onConfirm = { name, desc, params, type, config ->
                viewModel.updateSkill(editingSkill!!, name, desc, params, type, config)
                editingSkill = null
            },
        )
    }

    // ── Delete Confirmation ───────────────────────────────────────────────
    if (deletingSkill != null) {
        AlertDialog(
            onDismissRequest = { deletingSkill = null },
            title = {
                Text(stringResource(R.string.skill_confirm_delete), color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    stringResource(R.string.skill_delete_msg, deletingSkill!!.name),
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSkill(deletingSkill!!.id)
                        deletingSkill = null
                    },
                ) {
                    Text(stringResource(R.string.skill_delete), color = SellColor, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSkill = null }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(14.dp),
        )
    }
}

// ── Skill Card ───────────────────────────────────────────────────────────────

@Composable
private fun SkillCard(
    skill: SkillDefinitionEntity,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── Header Row: name + type badge + toggle ───────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (skill.enabled) TextPrimary else TextMuted,
                        fontWeight = FontWeight.Bold,
                    )
                    // Execution type badge
                    val (badgeColor, badgeText) = when (skill.executionType.lowercase()) {
                        "http" -> CommitteeGold to "HTTP"
                        "llm" -> IntelColor to "LLM"
                        else -> TextMuted to skill.executionType.uppercase()
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.3f)),
                    ) {
                        Text(
                            text = badgeText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                // Enabled toggle
                Switch(
                    checked = skill.enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = CommitteeGold,
                        checkedThumbColor = SurfaceDark,
                        uncheckedTrackColor = BorderColor,
                        uncheckedThumbColor = TextMuted,
                    ),
                    modifier = Modifier.height(24.dp),
                )
            }

            // ── Description ───────────────────────────────────────────
            if (skill.description.isNotBlank()) {
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (skill.enabled) TextSecondary else TextMuted,
                )
            }

            // ── Action Buttons ────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = CommitteeGold,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("编辑", color = CommitteeGold, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = SellColor,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.skill_delete), color = SellColor, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ── Add / Edit Dialog ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillEditDialog(
    existingSkill: SkillDefinitionEntity?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, desc: String, params: String, type: String, config: String) -> Unit,
) {
    var name by remember { mutableStateOf(existingSkill?.name ?: "") }
    var description by remember { mutableStateOf(existingSkill?.description ?: "") }
    var parameters by remember {
        mutableStateOf(
            existingSkill?.parameters
                ?: """{"type":"object","properties":{},"required":[]}"""
        )
    }
    var executionType by remember {
        mutableStateOf(existingSkill?.executionType ?: "http")
    }
    var executionConfig by remember {
        mutableStateOf(existingSkill?.executionConfig ?: "")
    }

    val isEditing = existingSkill != null

    // Default config examples shown as helper text
    val httpExample = """{"url":"https://api.example.com/search","method":"POST","headers":{},"bodyTemplate":"{\"query\":\"{{query}}\"}"}"""
    val llmExample = """{"systemPromptTemplate":"根据{{query}}进行分析"}"""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (isEditing) Icons.Default.Edit else Icons.Default.AddCircle,
                    null,
                    tint = CommitteeGold,
                )
                Text(
                    if (isEditing) stringResource(R.string.skill_edit_skill) else stringResource(R.string.skill_add),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ── Name ───────────────────────────────────────────────
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.skill_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = skillTextFieldColors(),
                )

                // ── Description ────────────────────────────────────────
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.skill_description)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = skillTextFieldColors(),
                )

                // ── Execution Type Chips ───────────────────────────────
                Text(
                    stringResource(R.string.skill_exec_type),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("http", "llm").forEach { type ->
                        FilterChip(
                            selected = executionType == type,
                            onClick = { executionType = type },
                            label = {
                                Text(
                                    type.uppercase(),
                                    fontWeight = if (executionType == type) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            leadingIcon = {
                                if (executionType == type) {
                                    Icon(
                                        Icons.Default.Check,
                                        null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CommitteeGold.copy(alpha = 0.15f),
                                selectedLabelColor = CommitteeGold,
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (executionType == type) CommitteeGold else BorderColor,
                            ),
                        )
                    }
                }

                // ── Parameters (JSON Schema) ──────────────────────────
                OutlinedTextField(
                    value = parameters,
                    onValueChange = { parameters = it },
                    label = { Text(stringResource(R.string.skill_param_template)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    supportingText = {
                        Text(
                            """例: {"type":"object","properties":{"query":{"type":"string"}}}""",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    },
                    maxLines = 5,
                    colors = skillTextFieldColors(),
                )

                // ── Execution Config ──────────────────────────────────
                OutlinedTextField(
                    value = executionConfig,
                    onValueChange = { executionConfig = it },
                    label = { Text(stringResource(R.string.skill_exec_config)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    supportingText = {
                        Text(
                            if (executionType == "http")
                                "例: $httpExample"
                            else
                                "例: $llmExample",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    },
                    maxLines = 5,
                    colors = skillTextFieldColors(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(name, description, parameters, executionType, executionConfig)
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CommitteeGold,
                    contentColor = SurfaceDark,
                ),
            ) {
                Icon(
                    if (isEditing) Icons.Default.Check else Icons.Default.Add,
                    null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (isEditing) stringResource(R.string.skill_save_btn) else stringResource(R.string.skill_add_btn), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        },
        containerColor = SurfaceCard,
        shape = RoundedCornerShape(14.dp),
    )
}

// ── Reusable TextField Colors ────────────────────────────────────────────────

@Composable
private fun skillTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = CommitteeGold,
    unfocusedBorderColor = BorderColor,
    focusedLabelColor = CommitteeGold,
    unfocusedLabelColor = TextMuted,
    cursorColor = CommitteeGold,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedSupportingTextColor = CommitteeGold,
    unfocusedSupportingTextColor = TextMuted,
    focusedContainerColor = SurfaceDark,
    unfocusedContainerColor = SurfaceDark,
)
