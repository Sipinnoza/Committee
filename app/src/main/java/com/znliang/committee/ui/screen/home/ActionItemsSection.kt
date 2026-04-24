package com.znliang.committee.ui.screen.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.znliang.committee.R
import com.znliang.committee.data.db.DecisionActionEntity
import com.znliang.committee.ui.theme.BorderColor
import com.znliang.committee.ui.theme.BuyColor
import com.znliang.committee.ui.theme.CommitteeGold
import com.znliang.committee.ui.theme.SurfaceCard
import com.znliang.committee.ui.theme.TextMuted
import com.znliang.committee.ui.theme.TextPrimary

@Composable
fun ActionItemsCard(
    actions: List<DecisionActionEntity>,
    onStatusChange: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.PlaylistAdd,
                    null,
                    tint = CommitteeGold,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.action_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = CommitteeGold,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${actions.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
            Spacer(Modifier.height(8.dp))

            actions.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Status toggle
                    val isDone = action.status == "done"
                    IconButton(
                        onClick = {
                            val next = when (action.status) {
                                "pending" -> "in_progress"
                                "in_progress" -> "done"
                                else -> "pending"
                            }
                            onStatusChange(action.id, next)
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            if (isDone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            null,
                            tint = when (action.status) {
                                "done" -> BuyColor
                                "in_progress" -> CommitteeGold
                                else -> TextMuted
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            action.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDone) TextMuted else TextPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                        if (action.assignee.isNotBlank()) {
                            Text(
                                action.assignee,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                        }
                    }
                    // Status badge
                    val statusLabel = when (action.status) {
                        "pending" -> stringResource(R.string.action_pending)
                        "in_progress" -> stringResource(R.string.action_in_progress)
                        "done" -> stringResource(R.string.action_done)
                        else -> action.status
                    }
                    val statusColor = when (action.status) {
                        "done" -> BuyColor
                        "in_progress" -> CommitteeGold
                        else -> TextMuted
                    }
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier
                            .background(statusColor.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun AddActionItemDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.action_add),
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.action_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.action_desc_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (title.isNotBlank()) onAdd(title, description) },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(R.string.skill_add_btn), color = CommitteeGold, fontWeight = FontWeight.Bold)
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
