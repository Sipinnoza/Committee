package com.committee.investing.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.committee.investing.data.db.MeetingSessionEntity
import com.committee.investing.domain.model.MeetingState
import com.committee.investing.ui.component.StateBadge
import com.committee.investing.ui.theme.*
import com.committee.investing.ui.viewmodel.MeetingViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: MeetingViewModel,
    onSessionClick: (MeetingSessionEntity) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.History, null, tint = CommitteeGold)
                        Text("历史会议", color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard),
            )
        }
    ) { padding ->
        if (uiState.sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, null, tint = TextMuted, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("暂无历史会议", color = TextMuted, style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(uiState.sessions, key = { it.traceId }) { session ->
                    SessionCard(
                        session = session,
                        onRecover = { viewModel.recoverSession(it) },
                        onClick = { onSessionClick(session) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: MeetingSessionEntity,
    onRecover: (String) -> Unit,
    onClick: () -> Unit,
) {
    val state = runCatching { MeetingState.valueOf(session.currentState) }.getOrDefault(MeetingState.IDLE)
    val startTime = dateFormatter.format(Instant.ofEpochMilli(session.startTime))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(session.subject, style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(session.traceId, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text(startTime, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
                StateBadge(state)
            }

            session.rating?.let { rating ->
                Text("评级：$rating", style = MaterialTheme.typography.bodyMedium,
                    color = CommitteeGold, fontWeight = FontWeight.SemiBold)
            }

            if (!session.isCompleted) {
                OutlinedButton(
                    onClick = { onRecover(session.traceId) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CommitteeGold),
                    border = BorderStroke(1.dp, CommitteeGold.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("恢复会议")
                }
            }
        }
    }
}
