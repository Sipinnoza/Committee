package com.znliang.committee.ui.component

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.znliang.committee.domain.model.MeetingState
import com.znliang.committee.domain.model.PresetRole
import com.znliang.committee.domain.model.Rating
import com.znliang.committee.ui.theme.*
import androidx.compose.ui.res.stringResource
import com.znliang.committee.R

// ── Agent Avatar + Name ───────────────────────────────────────────────────────

/** Resolve color from PresetRole.colorHex with fallback */
fun resolveRoleColor(presetRole: PresetRole?): Color {
    return presetRole?.let {
        runCatching { Color(android.graphics.Color.parseColor(it.colorHex)) }.getOrNull()
    } ?: SupervisorColor
}

// ── Pulsing Status Indicator ──────────────────────────────────────────────────

@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f, label = "alpha",
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        )
    )
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

// ── State Badge ───────────────────────────────────────────────────────────────

@Composable
fun StateBadge(state: MeetingState, modifier: Modifier = Modifier) {
    val (color, isActive) = when (state) {
        MeetingState.IDLE       -> StateIdleColor to false
        MeetingState.REJECTED   -> StateErrorColor to false
        MeetingState.COMPLETED  -> StateActiveColor to false
        else                    -> CommitteeGold to true
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isActive) PulsingDot(color) else Box(
            Modifier.size(8.dp).clip(CircleShape).background(color)
        )
        Text(
            text = stringResource(state.displayNameRes()),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ── Rating Badge ──────────────────────────────────────────────────────────────

@Composable
fun RatingBadge(rating: Rating, modifier: Modifier = Modifier) {
    val color = rating.color
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.5.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(rating.displayNameRes()),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

// ── Section Header ────────────────────────────────────────────────────────────

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(CommitteeGold))
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
    }
}

// ── Step Progress Timeline ────────────────────────────────────────────────────

@Composable
fun MeetingProgressBar(currentState: MeetingState, modifier: Modifier = Modifier) {
    val phases = listOf(
        MeetingState.VALIDATING,
        MeetingState.PREPPING,
        MeetingState.PHASE1_DEBATE,
        MeetingState.PHASE2_ASSESSMENT,
        MeetingState.FINAL_RATING,
        MeetingState.APPROVED,
        MeetingState.COMPLETED,
    )
    val currentIndex = phases.indexOf(currentState)

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        phases.forEachIndexed { i, phase ->
            val isDone    = currentIndex > i
            val isCurrent = currentIndex == i
            val color = when {
                isDone    -> CommitteeGold
                isCurrent -> CommitteeGold
                else      -> BorderColor
            }
            // Dot
            Box(
                modifier = Modifier
                    .size(if (isCurrent) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {}
            // Connector
            if (i < phases.lastIndex) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(if (isDone) CommitteeGold else BorderColor)
                )
            }
        }
    }
}

val Rating.color: Color get() = when (this) {
    Rating.BUY         -> BuyColor
    Rating.OVERWEIGHT  -> OverweightColor
    Rating.HOLD_PLUS   -> HoldPlusColor
    Rating.HOLD        -> HoldColor
    Rating.UNDERWEIGHT -> UnderweightColor
    Rating.SELL        -> SellColor
}
