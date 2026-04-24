package com.znliang.committee.ui.screen.home

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.znliang.committee.R
import com.znliang.committee.domain.model.MeetingPresetConfig
import com.znliang.committee.domain.model.MeetingState
import com.znliang.committee.domain.model.PresetRecommender
import com.znliang.committee.domain.model.PresetRole
import com.znliang.committee.ui.model.MaterialItem
import com.znliang.committee.ui.component.resolveRoleColor
import com.znliang.committee.ui.theme.BorderColor
import com.znliang.committee.ui.theme.CommitteeGold
import com.znliang.committee.ui.theme.StateWarningColor
import com.znliang.committee.ui.theme.SurfaceCard
import com.znliang.committee.ui.theme.SurfaceDark
import com.znliang.committee.ui.theme.TextMuted
import com.znliang.committee.ui.theme.TextPrimary
import com.znliang.committee.ui.theme.TextSecondary
import com.znliang.committee.ui.theme.committeeTextFieldColors
import com.znliang.committee.ui.viewmodel.AgentMemoryStats
import com.znliang.committee.ui.viewmodel.ConfigState
import com.znliang.committee.ui.viewmodel.MeetingViewModel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * 待机时的发起会议卡片
 */
@Composable
fun MeetingInitCard(
    currentState: MeetingState,
    configState: ConfigState,
    viewModel: MeetingViewModel,
    presetConfig: MeetingPresetConfig,
    activePresetId: String,
    subjectInput: String,
    onSubjectChange: (String) -> Unit,
    attachedUris: MutableList<Uri>,
    attachedCount: Int,
    onAttachedChanged: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val isRejected = currentState == MeetingState.REJECTED
    val ioScope = rememberCoroutineScope()

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris: List<Uri> ->
        attachedUris.addAll(uris)
        onAttachedChanged()
    }

    /** Convert attached URIs to MaterialItem list with base64 data (runs on IO thread) */
    suspend fun buildMaterialItems(): List<MaterialItem> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        attachedUris.toList().mapIndexed { idx, uri ->
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

                MaterialItem(
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
            if (configState.hasApiKey) {
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
                        text = configState.llmConfig.displayTag,
                        style = MaterialTheme.typography.labelMedium,
                        color = CommitteeGold,
                    )
                }
            }

            // API Key warning
            if (!configState.hasApiKey) {
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
            val inputHintRes = when (activePresetId) {
                "investment_committee" -> R.string.home_input_topic_investment_committee
                "general_meeting" -> R.string.home_input_topic_general_meeting
                "product_review" -> R.string.home_input_topic_product_review
                "tech_review" -> R.string.home_input_topic_tech_review
                "debate" -> R.string.home_input_topic_debate
                "paper_review" -> R.string.home_input_topic_paper_review
                "startup_pitch" -> R.string.home_input_topic_startup_pitch
                "legal_review" -> R.string.home_input_topic_legal_review
                "incident_postmortem" -> R.string.home_input_topic_incident_postmortem
                "brainstorm" -> R.string.home_input_topic_brainstorm
                else -> R.string.home_input_topic
            }
            OutlinedTextField(
                value = subjectInput,
                onValueChange = onSubjectChange,
                label = { Text(stringResource(inputHintRes)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    if (subjectInput.isNotBlank()) {
                        ioScope.launch {
                            val materials = buildMaterialItems()
                            viewModel.requestMeeting(subjectInput, materials)
                        }
                        attachedUris.clear()
                        onAttachedChanged()
                    }
                }),
                colors = committeeTextFieldColors(),
            )

            // ── Smart Preset Recommendation ──
            val recommendedId = remember(subjectInput) { PresetRecommender.recommend(subjectInput) }
            val scope = rememberCoroutineScope()
            if (recommendedId != null && recommendedId != activePresetId) {
                val recPreset = remember(recommendedId) { presetConfig.findPreset(recommendedId) }
                if (recPreset != null) {
                    val presetName = if (recPreset.nameRes() != 0) stringResource(recPreset.nameRes()) else recPreset.name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CommitteeGold.copy(alpha = 0.08f))
                            .border(1.dp, CommitteeGold.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .clickable {
                                scope.launch { presetConfig.setActivePreset(recommendedId) }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Assignment, null,
                            tint = CommitteeGold,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.recommend_preset_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                            Text(
                                presetName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = CommitteeGold,
                            )
                        }
                        Text(
                            stringResource(R.string.recommend_switch),
                            style = MaterialTheme.typography.labelSmall,
                            color = CommitteeGold,
                            fontWeight = FontWeight.Bold,
                        )
                        Icon(
                            Icons.Default.ChevronRight, null,
                            tint = CommitteeGold,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

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

            // ── Quick Decision Mode Toggle ──
            var quickMode by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Speed,
                    null,
                    tint = if (quickMode) CommitteeGold else TextMuted,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.quick_decision_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (quickMode) CommitteeGold else TextSecondary,
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = quickMode,
                    onCheckedChange = { quickMode = it },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = CommitteeGold,
                        checkedThumbColor = SurfaceDark,
                    ),
                )
            }
            if (quickMode) {
                Text(
                    stringResource(R.string.quick_decision_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }

            // ── Urgency & Duration Estimate ──
            var urgencyLevel by remember { mutableStateOf(0) } // 0=normal, 1=urgent, 2=critical
            val urgencyLabels = listOf(
                stringResource(R.string.urgency_normal),
                stringResource(R.string.urgency_urgent),
                stringResource(R.string.urgency_critical),
            )
            val urgencyColors = listOf(TextMuted, StateWarningColor, com.znliang.committee.ui.theme.SellColor)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.urgency_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                urgencyLabels.forEachIndexed { idx, label ->
                    val isSelected = urgencyLevel == idx
                    Surface(
                        modifier = Modifier.clickable { urgencyLevel = idx },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) urgencyColors[idx].copy(alpha = 0.15f) else Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) urgencyColors[idx].copy(alpha = 0.5f) else BorderColor,
                        ),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) urgencyColors[idx] else TextMuted,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // Estimated duration
            val estimatedMinutes = remember(quickMode, urgencyLevel) {
                val baseMinutes = if (quickMode) 2 else 5
                val urgencyMultiplier = when (urgencyLevel) {
                    2 -> 0.5f  // critical: fastest
                    1 -> 0.7f  // urgent: faster
                    else -> 1.0f
                }
                (baseMinutes * urgencyMultiplier).toInt().coerceAtLeast(1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Speed, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.estimated_duration, estimatedMinutes),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }

            Button(
                onClick = {
                    keyboard?.hide()
                    ioScope.launch {
                        val materials = buildMaterialItems()
                        if (quickMode) {
                            viewModel.requestQuickDecision(subjectInput, materials)
                        } else {
                            viewModel.requestMeeting(subjectInput, materials)
                        }
                    }
                    attachedUris.clear()
                    onAttachedChanged()
                },
                enabled = subjectInput.isNotBlank() && (currentState == MeetingState.IDLE || isRejected),
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

// ── Compact Agent Card for home screen ──────────────────────────────────────

@Composable
fun CompactAgentCard(role: PresetRole, stats: AgentMemoryStats? = null, onClick: () -> Unit) {
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
                            AgentBadge("\uD83E\uDDE0 ${stats.experienceCount}", CommitteeGold)
                        }
                        if (stats.skillCount > 0) {
                            AgentBadge("\uD83C\uDF93 ${stats.skillCount}", agentColor)
                        }
                        if (stats.changelogCount > 0) {
                            AgentBadge("\u2728 ${stats.changelogCount}", TextSecondary)
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

/**
 * 参与者头像条 — 显示会议参与的各Agent角色，以彩色首字母圆形徽章呈现
 */
@Composable
fun ParticipantAvatarRow(roles: List<PresetRole>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy((-4).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        roles.forEach { role ->
            val roleColor = resolveRoleColor(role)
            val displayChar = if (role.displayNameRes() != 0) {
                role.displayName.first().uppercaseChar()
            } else {
                role.displayName.firstOrNull()?.uppercaseChar() ?: '?'
            }
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(roleColor.copy(alpha = 0.2f), CircleShape)
                    .border(1.5f.dp, roleColor.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$displayChar",
                    style = MaterialTheme.typography.labelSmall,
                    color = roleColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // Role names summary
        val names = roles.take(4).joinToString(" \u00B7 ") { it.displayName }
        val suffix = if (roles.size > 4) " +${roles.size - 4}" else ""
        Text(
            names + suffix,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1,
        )
    }
}
