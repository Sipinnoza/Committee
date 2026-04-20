package com.znliang.committee.ui.screen

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.znliang.committee.R
import com.znliang.committee.ui.component.SectionHeader
import com.znliang.committee.ui.theme.BorderColor
import com.znliang.committee.ui.theme.CommitteeGold
import com.znliang.committee.ui.theme.SurfaceCard
import com.znliang.committee.ui.theme.SurfaceDark
import com.znliang.committee.ui.theme.TextMuted
import com.znliang.committee.ui.theme.TextPrimary
import com.znliang.committee.ui.theme.committeeTextFieldColors
import com.znliang.committee.ui.viewmodel.MeetingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchConfigScreen(
    viewModel: MeetingViewModel,
    onBack: () -> Unit,
) {
    var tavilyKeyInput by remember { mutableStateOf("") }
    var showTavilyKey by remember { mutableStateOf(false) }
    var tavilySaved by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_search_config),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = TextPrimary)
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
            // ── Tavily Search API Key ────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(stringResource(R.string.settings_tavily_title))
                        Text(
                            stringResource(R.string.settings_tavily_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                        OutlinedTextField(
                            value = tavilyKeyInput,
                            onValueChange = { tavilyKeyInput = it; tavilySaved = false },
                            label = { Text("Tavily API Key") },
                            placeholder = { Text("tvly-x...xxxx") },
                            singleLine = true,
                            visualTransformation = if (showTavilyKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showTavilyKey = !showTavilyKey }) {
                                    Icon(
                                        if (showTavilyKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        null, tint = TextMuted
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = committeeTextFieldColors(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(
                                onClick = {
                                    viewModel.saveTavilyKey(tavilyKeyInput)
                                    tavilySaved = true
                                },
                                enabled = tavilyKeyInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CommitteeGold,
                                    contentColor = SurfaceDark,
                                ),
                            ) {
                                Icon(if (tavilySaved) Icons.Default.Check else Icons.Default.Save, null,
                                    Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (tavilySaved) stringResource(R.string.settings_saved) else stringResource(R.string.settings_tavily_save), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
