package com.znliang.committee.ui.screen.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.znliang.committee.R
import com.znliang.committee.ui.theme.BorderColor
import com.znliang.committee.ui.theme.BuyColor
import com.znliang.committee.ui.theme.CommitteeGold
import com.znliang.committee.ui.theme.SellColor
import com.znliang.committee.ui.theme.SurfaceCard
import com.znliang.committee.ui.theme.SurfaceDark
import com.znliang.committee.ui.theme.TextMuted
import com.znliang.committee.ui.theme.TextPrimary
import com.znliang.committee.ui.theme.TextSecondary
import com.znliang.committee.ui.theme.committeeTextFieldColors

/**
 * 投票对话框 — AGREE / DISAGREE + 理由
 */
@Composable
fun VoteDialog(
    onDismiss: () -> Unit,
    onVote: (agree: Boolean, reason: String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.human_vote_title), color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.human_vote_reason_hint)) },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = committeeTextFieldColors(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onVote(true, reason.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = BuyColor, contentColor = SurfaceDark),
            ) {
                Text(stringResource(R.string.human_vote_agree), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Button(
                onClick = { onVote(false, reason.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = SellColor, contentColor = Color.White),
            ) {
                Text(stringResource(R.string.human_vote_disagree), fontWeight = FontWeight.Bold)
            }
        },
        containerColor = SurfaceCard,
    )
}

/**
 * 投票结果可视化 — 水平堆叠条 + 各Agent投票详情
 */
@Composable
fun VoteResultsBar(votes: Map<String, com.znliang.committee.ui.model.VoteInfo>) {
    val agreeCount = votes.values.count { it.agree }
    val disagreeCount = votes.size - agreeCount
    val agreeRatio = if (votes.isNotEmpty()) agreeCount.toFloat() / votes.size else 0f
    val animatedAgreeRatio by animateFloatAsState(
        targetValue = agreeRatio,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "voteAgree",
    )

    Column {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.HowToVote, null, tint = CommitteeGold, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.vote_results_title),
                style = MaterialTheme.typography.labelMedium,
                color = CommitteeGold,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.vote_results_count, agreeCount, disagreeCount),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
        Spacer(Modifier.height(6.dp))

        // Stacked horizontal bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            if (agreeRatio > 0f) {
                Box(
                    modifier = Modifier
                        .weight(animatedAgreeRatio.coerceAtLeast(0.05f))
                        .fillMaxHeight()
                        .background(BuyColor.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (agreeCount > 0) {
                        Text(
                            "$agreeCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
            if (disagreeCount > 0) {
                Box(
                    modifier = Modifier
                        .weight((1f - animatedAgreeRatio).coerceAtLeast(0.05f))
                        .fillMaxHeight()
                        .background(SellColor.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$disagreeCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        // Legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.vote_agree_label), style = MaterialTheme.typography.labelSmall, color = BuyColor)
            Text(stringResource(R.string.vote_disagree_label), style = MaterialTheme.typography.labelSmall, color = SellColor)
        }

        // Per-agent vote breakdown
        Spacer(Modifier.height(6.dp))
        votes.forEach { (role, vote) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (vote.agree) BuyColor.copy(alpha = 0.7f) else SellColor.copy(alpha = 0.7f),
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    role,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary,
                    modifier = Modifier.width(80.dp),
                    maxLines = 1,
                )
                Text(
                    if (vote.agree) stringResource(R.string.vote_agree_label) else stringResource(R.string.vote_disagree_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (vote.agree) BuyColor else SellColor,
                    fontWeight = FontWeight.Bold,
                )
                if (vote.reason.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        vote.reason.take(40),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 覆写决策对话框 — 让用户能推翻 Agent 结论
 */
@Composable
fun OverrideDecisionDialog(
    currentRating: String,
    ratingScale: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var selectedRating by remember { mutableStateOf(currentRating) }
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Override Decision", color = CommitteeGold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Agent suggests: $currentRating", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                Text("Your decision:", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                // Rating chips
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ratingScale) { rating ->
                        val isSelected = rating == selectedRating
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) CommitteeGold.copy(alpha = 0.2f) else SurfaceCard,
                            border = BorderStroke(
                                if (isSelected) 1.5.dp else 1.dp,
                                if (isSelected) CommitteeGold else BorderColor,
                            ),
                            modifier = Modifier.clickable { selectedRating = rating },
                        ) {
                            Text(
                                rating,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = if (isSelected) CommitteeGold else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    placeholder = { Text("Why do you disagree?", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (reason.isNotBlank()) onConfirm(selectedRating, reason) },
                enabled = reason.isNotBlank() && selectedRating != currentRating,
            ) {
                Text("Confirm Override", color = if (reason.isNotBlank() && selectedRating != currentRating) CommitteeGold else TextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Keep Agent Decision", color = TextMuted)
            }
        },
    )
}
