package com.committee.investing.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.committee.investing.domain.model.AgentRole
import com.committee.investing.domain.model.SpeechRecord
import com.committee.investing.ui.theme.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())

/**
 * 群聊对话气泡 — 左侧头像 + 名称 + 时间，右侧气泡内容
 */
@Composable
fun ChatBubble(
    speech: SpeechRecord,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val agentColor = speech.agent.color
    val avatarLetter = speech.agent.displayName.first()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .animateContentSize(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // ── Avatar ─────────────────────────────────────────
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(agentColor.copy(alpha = 0.15f))
                .border(1.5.dp, agentColor.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$avatarLetter",
                color = agentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }

        Spacer(Modifier.width(8.dp))

        // ── Bubble Content ─────────────────────────────────
        Column(modifier = Modifier.weight(1f)) {
            // Name + Time + Round
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = speech.agent.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = agentColor,
                    fontWeight = FontWeight.Bold,
                )
                // Stance tag
                Text(
                    text = speech.agent.stance,
                    style = MaterialTheme.typography.labelSmall,
                    color = agentColor.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                )
                Text(
                    text = "R${speech.round}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontSize = 10.sp,
                )
                Text(
                    text = timeFormatter.format(speech.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontSize = 10.sp,
                )
            }

            Spacer(Modifier.height(4.dp))

            // Message bubble
            Column(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 2.dp,
                            topEnd = 12.dp,
                            bottomStart = 12.dp,
                            bottomEnd = 12.dp,
                        )
                    )
                    .background(SurfaceCard)
                    .border(1.dp, agentColor.copy(alpha = 0.12f),
                        RoundedCornerShape(
                            topStart = 2.dp,
                            topEnd = 12.dp,
                            bottomStart = 12.dp,
                            bottomEnd = 12.dp,
                        )
                    )
                    .clickable(enabled = !speech.isStreaming) { onToggle() }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .animateContentSize()
            ) {
                val displayText = if (speech.isStreaming || isExpanded) {
                    speech.content
                } else {
                    speech.content.take(120) + if (speech.content.length > 120) "…" else ""
                }
                MarkdownText(
                    text = displayText,
                )

                // Streaming cursor: blinking block
                if (speech.isStreaming) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "cursor")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 0.2f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 600),
                                repeatMode = RepeatMode.Reverse,
                            ),
                            label = "cursorAlpha",
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(agentColor.copy(alpha = alpha), RoundedCornerShape(2.dp))
                        )
                    }
                } else if (speech.content.length > 120) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = agentColor.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 系统消息气泡 — 居中灰色小字，用于状态变化、调度通知等
 */
@Composable
fun SystemBubble(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextMuted,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontSize = 11.sp,
        )
    }
}

/**
 * 事件型系统气泡 — 居中带底色胶囊，用于阶段转换等关键节点
 */
@Composable
fun EventBubble(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = CommitteeGold,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.1f))
                .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
            )
        }
    }
}
