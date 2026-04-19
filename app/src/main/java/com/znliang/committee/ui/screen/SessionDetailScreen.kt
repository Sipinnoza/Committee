package com.znliang.committee.ui.screen

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.znliang.committee.R
import com.znliang.committee.data.db.MeetingSessionEntity
import com.znliang.committee.domain.model.MeetingState
import com.znliang.committee.domain.model.SpeechRecord
import com.znliang.committee.ui.component.ChatBubble
import com.znliang.committee.ui.component.EventBubble
import com.znliang.committee.ui.component.SectionHeader
import com.znliang.committee.ui.component.StateBadge
import com.znliang.committee.ui.theme.*
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = CommitteeGold)
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
                modifier = Modifier.fillMaxSize().padding(padding),
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
                        }
                    }
                }

                // Event bubble for meeting start
                item {
                    EventBubble(stringResource(R.string.session_meeting_start, timeFormatter.format(Instant.ofEpochMilli(session.startTime))))
                }

                // All speeches
                items(speeches, key = { it.id }) { speech ->
                    ChatBubble(
                        speech = speech,
                        isExpanded = expandedKeys[speech.id] == true,
                        onToggle = {
                            expandedKeys[speech.id] = expandedKeys[speech.id] != true
                        },
                    )
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
