package com.znliang.committee.ui.screen

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.znliang.committee.R
import com.znliang.committee.data.db.MeetingSessionEntity
import com.znliang.committee.domain.model.MeetingState
import com.znliang.committee.domain.model.PresetRole
import com.znliang.committee.domain.model.SpeechRecord
import com.znliang.committee.ui.component.AgendaCard
import com.znliang.committee.ui.component.ChatBubble
import com.znliang.committee.ui.component.EventBubble
import com.znliang.committee.ui.component.SectionHeader
import com.znliang.committee.ui.component.StateBadge
import com.znliang.committee.ui.model.VoteInfo
import com.znliang.committee.ui.screen.home.VoteResultsBar
import com.znliang.committee.ui.theme.*
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    .withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    session: MeetingSessionEntity,
    speeches: List<SpeechRecord>,
    onBack: () -> Unit,
) {
    val expandedKeys = remember { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()
    val state = runCatching { MeetingState.valueOf(session.currentState) }.getOrDefault(MeetingState.IDLE)
    val startTime = dateFormatter.format(Instant.ofEpochMilli(session.startTime))

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            session.subject,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "$startTime · ${session.traceId}",
                            color = TextMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = CommitteeGold)
                    }
                },
                actions = {
                    StateBadge(state, Modifier.padding(end = 12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard),
            )
        }
    ) { padding ->
        if (speeches.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding(), bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.History, null,
                        tint = TextMuted,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.session_no_speeches), color = TextMuted, style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                // ── Error message (if session failed) ──
                if (session.errorMessage != null) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = SellColor.copy(alpha = 0.08f)),
                            border = BorderStroke(1.dp, SellColor.copy(alpha = 0.3f)),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                SectionHeader(stringResource(R.string.session_error_title))
                                Spacer(Modifier.height(4.dp))
                                Text(session.errorMessage, color = SellColor, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // Session info card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        border = BorderStroke(1.dp, BorderColor),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SectionHeader(stringResource(R.string.session_summary))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(stringResource(R.string.session_start_time), color = TextMuted, style = MaterialTheme.typography.bodySmall)
                                Text(startTime, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(stringResource(R.string.session_speech_count), color = TextMuted, style = MaterialTheme.typography.bodySmall)
                                Text("${speeches.size}", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                            }
                            session.rating?.let { rating ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(stringResource(R.string.session_rating_label), color = TextMuted, style = MaterialTheme.typography.bodySmall)
                                    Text(rating, color = CommitteeGold, fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            session.userOverrideRating?.let { overrideRating ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(stringResource(R.string.session_user_override, overrideRating), color = CommitteeGold, fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                if (session.userOverrideReason.isNotBlank()) {
                                    Text(stringResource(R.string.session_override_reason, session.userOverrideReason), color = TextSecondary,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                // Event bubble for meeting start
                item {
                    EventBubble(stringResource(R.string.session_meeting_start, timeFormatter.format(Instant.ofEpochMilli(session.startTime))))
                }

                // ── 讨论摘要 ──
                if (session.summary.isNotBlank()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                            border = BorderStroke(1.dp, BorderColor),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                SectionHeader(stringResource(R.string.session_discussion_summary))
                                Spacer(Modifier.height(4.dp))
                                Text(session.summary, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // ── 执行计划 ──
                if (!session.executionPlan.isNullOrBlank()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                            border = BorderStroke(1.dp, BorderColor),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                SectionHeader(stringResource(R.string.session_execution_plan))
                                Spacer(Modifier.height(4.dp))
                                Text(session.executionPlan, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // ── 投票结果 ──
                if (session.votesJson.isNotBlank()) {
                    item {
                        val votes = remember(session.votesJson) { deserializeVotesForDetail(session.votesJson) }
                        if (votes.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                                border = BorderStroke(1.dp, BorderColor),
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    SectionHeader(stringResource(R.string.session_votes))
                                    Spacer(Modifier.height(4.dp))
                                    VoteResultsBar(votes = votes)
                                }
                            }
                        }
                    }
                }

                // ── 置信度 ──
                if (session.decisionConfidence > 0) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                            border = BorderStroke(1.dp, BorderColor),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                SectionHeader(stringResource(R.string.confidence_label))
                                Spacer(Modifier.height(4.dp))
                                val confColor = when {
                                    session.decisionConfidence >= 75 -> BuyColor
                                    session.decisionConfidence >= 50 -> CommitteeGold
                                    else -> SellColor
                                }
                                val animConf by animateFloatAsState(
                                    targetValue = session.decisionConfidence / 100f,
                                    animationSpec = tween(800, easing = FastOutSlowInEasing),
                                    label = "conf",
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(
                                        Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(BorderColor)
                                    ) {
                                        Box(Modifier.fillMaxWidth(animConf).fillMaxHeight().background(confColor, RoundedCornerShape(4.dp)))
                                    }
                                    Text("${session.decisionConfidence}%", color = confColor, fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium)
                                }
                                if (session.confidenceBreakdown.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(session.confidenceBreakdown, color = TextMuted, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                // ── 贡献度排行 ──
                if (session.contributionsJson.isNotBlank()) {
                    item {
                        val contributions = remember(session.contributionsJson) { deserializeContributionsForDetail(session.contributionsJson) }
                        if (contributions.isEmpty()) return@item
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                            border = BorderStroke(1.dp, BorderColor),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                SectionHeader(stringResource(R.string.contrib_scores_title))
                                Spacer(Modifier.height(4.dp))
                                contributions.values.sortedByDescending { it.overall }.forEach { score ->
                                    val displayNameResId = PresetRole.displayNameResForId(score.roleId)
                                    val displayName = if (displayNameResId != 0) stringResource(displayNameResId) else score.roleId
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(displayName, color = TextPrimary, style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f))
                                        Text("%.1f".format(score.overall), color = CommitteeGold, fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }

                // All speeches
                items(speeches, key = { it.id }) { speech ->
                    if (speech.agent == "system") {
                        val eventColor = when {
                            speech.isConsensusEvent -> BuyColor
                            speech.isPhaseTransition -> CommitteeGold
                            speech.isAgendaEvent -> IntelColor
                            else -> StateWarningColor
                        }
                        if (speech.isAgendaEvent) {
                            AgendaCard(text = speech.content)
                        } else {
                            EventBubble(text = speech.content, color = eventColor)
                        }
                    } else {
                        ChatBubble(
                            speech = speech,
                            isExpanded = expandedKeys[speech.id] == true,
                            onToggle = {
                                expandedKeys[speech.id] = expandedKeys[speech.id] != true
                            },
                        )
                    }
                }

                // Meeting end
                item {
                    EventBubble(stringResource(R.string.session_meeting_end, state))
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

private fun deserializeVotesForDetail(json: String): Map<String, VoteInfo> {
    if (json.isBlank()) return emptyMap()
    return try {
        val arr = JSONArray(json)
        buildMap {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val role = obj.getString("role")
                put(role, VoteInfo(
                    role = role,
                    agree = obj.getBoolean("agree"),
                    reason = obj.optString("reason", ""),
                    round = obj.getInt("round"),
                    numericScore = if (obj.has("numericScore")) obj.getInt("numericScore") else null,
                    stanceLabel = if (obj.has("stanceLabel")) obj.getString("stanceLabel") else null,
                ))
            }
        }
    } catch (_: Exception) { emptyMap() }
}

private data class DetailContribution(val roleId: String, val overall: Float)

private fun deserializeContributionsForDetail(json: String): Map<String, DetailContribution> {
    if (json.isBlank()) return emptyMap()
    return try {
        val arr = JSONArray(json)
        buildMap {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val roleId = obj.getString("roleId")
                val ig = obj.optInt("informationGain", 0)
                val lq = obj.optInt("logicQuality", 0)
                val iq = obj.optInt("interactionQuality", 0)
                put(roleId, DetailContribution(roleId = roleId, overall = (ig + lq + iq) / 3f))
            }
        }
    } catch (_: Exception) { emptyMap() }
}
