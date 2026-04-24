package com.znliang.committee.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.znliang.committee.R
import com.znliang.committee.data.db.MeetingSessionEntity
import com.znliang.committee.domain.model.MeetingState
import com.znliang.committee.ui.component.StateBadge
import com.znliang.committee.ui.theme.*
import com.znliang.committee.ui.viewmodel.MeetingViewModel
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
    val actionState by viewModel.actionState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedRating by remember { mutableStateOf<String?>(null) } // null = All

    // Collect distinct ratings for filter chips
    val allRatings = remember(actionState.sessions) {
        actionState.sessions.mapNotNull { it.rating }.distinct().sorted()
    }

    // Filter sessions
    val filteredSessions = remember(actionState.sessions, searchQuery, selectedRating) {
        actionState.sessions.filter { session ->
            val matchesSearch = searchQuery.isBlank() ||
                session.subject.contains(searchQuery, ignoreCase = true) ||
                (session.rating?.contains(searchQuery, ignoreCase = true) == true)
            val matchesRating = selectedRating == null || session.rating == selectedRating
            matchesSearch && matchesRating
        }
    }

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
                        Text(stringResource(R.string.history_title), color = TextPrimary, fontWeight = FontWeight.Bold)
                        if (actionState.sessions.isNotEmpty()) {
                            Text(
                                "(${actionState.sessions.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMuted,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard),
            )
        }
    ) { padding ->
        if (actionState.sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding(), bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, null, tint = TextMuted, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.history_empty), color = TextMuted, style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding(), bottom = 6.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ── Decision Statistics Dashboard ──────────────────
                item(key = "stats_dashboard") {
                    DecisionStatsDashboard(sessions = actionState.sessions)
                }

                // ── Search & Filter ──────────────────────────────
                item(key = "search_filter") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Search bar
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.history_search_hint)) },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = TextMuted, modifier = Modifier.size(20.dp)) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = committeeTextFieldColors(),
                            shape = RoundedCornerShape(10.dp),
                        )

                        // Rating filter chips
                        if (allRatings.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // "All" chip
                                item {
                                    FilterChip(
                                        selected = selectedRating == null,
                                        onClick = { selectedRating = null },
                                        label = { Text(stringResource(R.string.history_filter_all)) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = CommitteeGold.copy(alpha = 0.15f),
                                            selectedLabelColor = CommitteeGold,
                                            containerColor = SurfaceCard,
                                            labelColor = TextSecondary,
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            borderColor = BorderColor,
                                            selectedBorderColor = CommitteeGold.copy(alpha = 0.4f),
                                            enabled = true,
                                            selected = selectedRating == null,
                                        ),
                                    )
                                }
                                items(allRatings) { rating ->
                                    FilterChip(
                                        selected = selectedRating == rating,
                                        onClick = { selectedRating = if (selectedRating == rating) null else rating },
                                        label = { Text(rating) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = CommitteeGold.copy(alpha = 0.15f),
                                            selectedLabelColor = CommitteeGold,
                                            containerColor = SurfaceCard,
                                            labelColor = TextSecondary,
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            borderColor = BorderColor,
                                            selectedBorderColor = CommitteeGold.copy(alpha = 0.4f),
                                            enabled = true,
                                            selected = selectedRating == rating,
                                        ),
                                    )
                                }
                            }
                        }

                        // Result count
                        if (searchQuery.isNotEmpty() || selectedRating != null) {
                            Text(
                                stringResource(R.string.history_filter_result, filteredSessions.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                        }
                    }
                }

                items(filteredSessions, key = { it.traceId }) { session ->
                    SessionCard(
                        session = session,
                        onRecover = { viewModel.recoverSession(it) },
                        onClick = { onSessionClick(session) },
                    )
                }

                // Empty filter result
                if (filteredSessions.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.FilterList, null, tint = TextMuted, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.history_filter_empty),
                                    color = TextMuted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(session.subject, style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(startTime, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
                StateBadge(state)
            }

            // Meeting metadata chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Rounds chip
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = SurfaceDark,
                ) {
                    Text(
                        stringResource(R.string.history_rounds_label, session.currentRound),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                // Rating chip
                session.rating?.let { rating ->
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = CommitteeGold.copy(alpha = 0.1f),
                    ) {
                        Text(
                            rating,
                            style = MaterialTheme.typography.labelSmall,
                            color = CommitteeGold,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
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
                    Text(stringResource(R.string.history_resume))
                }
            }
        }
    }
}

// ── Decision Statistics Dashboard ─────────────────────────────

@Composable
private fun DecisionStatsDashboard(sessions: List<MeetingSessionEntity>) {
    val stats = remember(sessions) { computeStats(sessions) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, CommitteeGold.copy(alpha = 0.3f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Analytics, null, tint = CommitteeGold, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.stats_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = CommitteeGold,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(14.dp))

            // Key metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatMetric(
                    label = stringResource(R.string.stats_total),
                    value = "${stats.total}",
                    color = TextPrimary,
                )
                StatMetric(
                    label = stringResource(R.string.stats_completed),
                    value = "${stats.completed}",
                    color = BuyColor,
                )
                StatMetric(
                    label = stringResource(R.string.stats_completion_rate),
                    value = "${stats.completionRate}%",
                    color = CommitteeGold,
                )
                StatMetric(
                    label = stringResource(R.string.stats_avg_rounds),
                    value = "%.1f".format(stats.avgRounds),
                    color = TextSecondary,
                )
            }

            // Rating distribution
            if (stats.ratingDistribution.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = BorderColor)
                Spacer(Modifier.height(10.dp))

                Text(
                    stringResource(R.string.stats_rating_distribution),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(8.dp))

                // Horizontal bar chart
                val maxCount = stats.ratingDistribution.values.maxOrNull() ?: 1
                stats.ratingDistribution.forEach { (rating, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            rating,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary,
                            modifier = Modifier.width(80.dp),
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(BorderColor),
                        ) {
                            val fraction = count.toFloat() / maxCount
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(CommitteeGold.copy(alpha = 0.7f)),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "$count",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier.width(24.dp),
                        )
                    }
                }
            }

            // Recent activity indicator
            if (stats.recentWeekCount > 0) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = BorderColor)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(BuyColor, CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.stats_recent_week, stats.recentWeekCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatMetric(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

private data class DecisionStats(
    val total: Int,
    val completed: Int,
    val completionRate: Int,
    val avgRounds: Float,
    val ratingDistribution: Map<String, Int>,
    val recentWeekCount: Int,
)

private fun computeStats(sessions: List<MeetingSessionEntity>): DecisionStats {
    val total = sessions.size
    val completed = sessions.count { it.isCompleted }
    val completionRate = if (total > 0) (completed * 100) / total else 0
    val avgRounds = if (sessions.isNotEmpty())
        sessions.map { it.currentRound }.average().toFloat()
    else 0f

    val ratingDist = sessions
        .mapNotNull { it.rating }
        .groupingBy { it }
        .eachCount()
        .toSortedMap()

    val oneWeekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
    val recentWeek = sessions.count { it.startTime > oneWeekAgo }

    return DecisionStats(
        total = total,
        completed = completed,
        completionRate = completionRate,
        avgRounds = avgRounds,
        ratingDistribution = ratingDist,
        recentWeekCount = recentWeek,
    )
}
