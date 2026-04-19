package com.znliang.committee.ui.theme

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable

// ── Reusable TextField Colors ────────────────────────────────────────────────

/**
 * 统一的 TextField 配色方案，消除各 Screen 中重复的 OutlinedTextFieldDefaults.colors() 调用。
 *
 * 用法:  colors = committeeTextFieldColors()
 */
@Composable
fun committeeTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = CommitteeGold,
    unfocusedBorderColor = BorderColor,
    focusedLabelColor = CommitteeGold,
    cursorColor = CommitteeGold,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
)
