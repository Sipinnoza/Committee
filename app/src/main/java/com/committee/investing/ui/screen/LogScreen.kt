package com.committee.investing.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.committee.investing.ui.theme.*
import com.committee.investing.ui.viewmodel.MeetingViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(viewModel: MeetingViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.looperLogs.size) {
        if (uiState.looperLogs.isNotEmpty()) {
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
                        Text("Looper 日志", color = TextPrimary, fontWeight = FontWeight.Bold)
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
                    Text("Looper 已启动，等待事件...", color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(uiState.looperLogs) { line ->
                        val color = when {
                            line.contains("[状态转换]") -> CommitteeGold
                            line.contains("[事件]")    -> AnalystColor
                            line.contains("[话筒]")    -> StrategistColor
                            line.contains("[超时]")    -> SellColor
                            line.contains("[恢复]")    -> IntelColor
                            line.contains("[幂等跳过]")-> TextMuted
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
