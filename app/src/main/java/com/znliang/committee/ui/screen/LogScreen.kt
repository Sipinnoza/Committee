package com.znliang.committee.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.znliang.committee.R
import androidx.compose.ui.unit.sp
import com.znliang.committee.ui.theme.*
import com.znliang.committee.ui.viewmodel.MeetingViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(viewModel: MeetingViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var userScrolled by remember { mutableStateOf(false) }

    // Detect user manual scroll (not at bottom)
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            val total = listState.layoutInfo.totalItemsCount
            if (lastVisible != null && total > 0 && lastVisible < total - 1) {
                userScrolled = true
            }
        }
    }

    // Reset userScrolled when log list is replaced/emptied
    LaunchedEffect(uiState.looperLogs.isEmpty()) {
        if (uiState.looperLogs.isEmpty()) {
            userScrolled = false
        }
    }

    // Auto-scroll only if user hasn't manually scrolled up
    LaunchedEffect(uiState.looperLogs.size) {
        if (uiState.looperLogs.isNotEmpty() && !userScrolled) {
            scope.launch { listState.animateScrollToItem(uiState.looperLogs.lastIndex) }
        }
    }

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Terminal, null, tint = IntelColor)
                        Text(stringResource(R.string.log_title), color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard),
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceCard),
        ) {
            if (uiState.looperLogs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.log_waiting), color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(items = uiState.looperLogs, key = { idx, _ -> "log_$idx" }) { _, line ->
                        val color = when {
                            line.contains(stringResource(R.string.log_tag_transition)) -> CommitteeGold
                            line.contains(stringResource(R.string.log_tag_event))    -> AnalystColor
                            line.contains(stringResource(R.string.log_tag_mic))    -> StrategistColor
                            line.contains(stringResource(R.string.log_tag_timeout))    -> SellColor
                            line.contains(stringResource(R.string.log_tag_recovery))    -> IntelColor
                            line.contains(stringResource(R.string.log_tag_skip))-> TextMuted
                            else                       -> TextSecondary
                        }
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            ),
                            color = color,
                        )
                    }
                }
            }
        }
    }
}
