package com.znliang.committee.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.znliang.committee.R
import com.znliang.committee.domain.model.AppLanguage
import com.znliang.committee.domain.model.MeetingPresetConfig
import com.znliang.committee.ui.component.SectionHeader
import com.znliang.committee.ui.theme.BorderColor
import com.znliang.committee.ui.theme.CommitteeGold
import com.znliang.committee.ui.theme.SurfaceCard
import com.znliang.committee.ui.theme.SurfaceDark
import com.znliang.committee.ui.theme.TextMuted
import com.znliang.committee.ui.theme.TextPrimary
import com.znliang.committee.ui.theme.TextSecondary
import com.znliang.committee.ui.viewmodel.MeetingViewModel
import com.znliang.committee.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MeetingViewModel,
    presetConfig: MeetingPresetConfig,
    onManageSkills: () -> Unit = {},
    onRestartApp: () -> Unit = {},
    settingsViewModel: SettingsViewModel,
    onNavigateToModelConfig: () -> Unit = {},
    onNavigateToSearchConfig: () -> Unit = {},
    onNavigateToMeetingConfig: () -> Unit = {},
) {
    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Settings, null, tint = CommitteeGold)
                        Text(stringResource(R.string.settings_title), color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard),
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding(), bottom = 6.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── Model Configuration ──────────────────────────────────────
            item {
                SettingsNavCard(
                    icon = { Icon(Icons.Default.SmartToy, null, tint = CommitteeGold, modifier = Modifier.size(20.dp)) },
                    title = stringResource(R.string.settings_model_config),
                    description = stringResource(R.string.settings_model_config_desc),
                    onClick = onNavigateToModelConfig,
                )
            }

            // ── Search Configuration ─────────────────────────────────────
            item {
                SettingsNavCard(
                    icon = { Icon(Icons.Default.Search, null, tint = CommitteeGold, modifier = Modifier.size(20.dp)) },
                    title = stringResource(R.string.settings_search_config),
                    description = stringResource(R.string.settings_search_config_desc),
                    onClick = onNavigateToSearchConfig,
                )
            }

            // ── Meeting Configuration ────────────────────────────────────
            item {
                SettingsNavCard(
                    icon = { Icon(Icons.Default.MeetingRoom, null, tint = CommitteeGold, modifier = Modifier.size(20.dp)) },
                    title = stringResource(R.string.settings_meeting_config),
                    description = stringResource(R.string.settings_meeting_config_desc),
                    onClick = onNavigateToMeetingConfig,
                )
            }

            // ── Skill Management Entry ─────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor),
                ) {
                    TextButton(
                        onClick = onManageSkills,
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    ) {
                        Icon(Icons.Default.Build, null, tint = CommitteeGold, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_skill_mgmt), color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // ── Language ──────────────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionHeader(stringResource(R.string.settings_language))
                        AppLanguage.entries.forEach { language ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        settingsViewModel.saveLanguage(language)
                                        onRestartApp()
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                RadioButton(
                                    selected = settingsViewModel.appConfig.selectedLanguage == language,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = CommitteeGold),
                                )
                                Column {
                                    Text(
                                        stringResource(language.displayNameRes),
                                        color = if (settingsViewModel.appConfig.selectedLanguage == language)
                                            CommitteeGold else TextPrimary,
                                        fontWeight = if (settingsViewModel.appConfig.selectedLanguage == language)
                                            FontWeight.Bold else FontWeight.Normal,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── About ────────────────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionHeader(stringResource(R.string.settings_about))
                        Text(stringResource(R.string.settings_version), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Text(stringResource(R.string.settings_about_desc_1), style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        Text(stringResource(R.string.settings_about_desc_2), style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        Text(stringResource(R.string.settings_about_desc_3), style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsNavCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, BorderColor),
    ) {
        TextButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            icon()
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(description, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Icon(Icons.Default.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}
