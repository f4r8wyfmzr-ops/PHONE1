package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.UserProfile
import com.example.ui.components.formatInr
import com.example.ui.theme.FailureCrimson
import com.example.ui.theme.IndigoRoyal
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.UiState

@Composable
fun SecuritySettingsScreen(
    profileState: UiState<UserProfile>,
    currentLanguage: String,
    currentCustomBackendUrl: String,
    statusBanner: String?,
    onLanguageChange: (String) -> Unit,
    onSaveSettings: (biometricEnabled: Boolean, dailyLimitPaise: Long, perTxnLimitPaise: Long, backendUrl: String) -> Unit,
    onSignOut: () -> Unit,
) {
    val profile = (profileState as? UiState.Success)?.data ?: UserProfile()
    var biometricEnabled by remember(profile.biometricLockEnabled) {
        mutableStateOf(profile.biometricLockEnabled)
    }
    var dailyLimitRupees by remember(profile.dailyLimitPaise) {
        mutableFloatStateOf((profile.dailyLimitPaise / 100L).toFloat().coerceIn(1000f, 100000f))
    }
    var perTxnLimitRupees by remember(profile.perTxnLimitPaise) {
        mutableFloatStateOf((profile.perTxnLimitPaise / 100L).toFloat().coerceIn(500f, 100000f))
    }
    var backendUrl by remember(currentCustomBackendUrl) {
        mutableStateOf(currentCustomBackendUrl)
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.security_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            if (statusBanner != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = statusBanner,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            // Account Summary
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = profile.displayName.ifBlank { "Google Account" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = profile.email,
                        fontFamily = JetBrainsMonoFamily,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Bilingual Language Switcher
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.security_language_label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = currentLanguage == "en",
                            onClick = { onLanguageChange("en") },
                            label = { Text("English") },
                            modifier = Modifier.testTag("settings_lang_en"),
                        )
                        FilterChip(
                            selected = currentLanguage == "hi",
                            onClick = { onLanguageChange("hi") },
                            label = { Text("हिन्दी (Hindi)") },
                            modifier = Modifier.testTag("settings_lang_hi"),
                        )
                    }
                }
            }

            // Biometric + NPCI Limits + PSP Gateway Endpoint
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.security_biometric_label),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(R.string.security_biometric_sub),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = biometricEnabled,
                            onCheckedChange = { biometricEnabled = it },
                            modifier = Modifier.testTag("biometric_toggle"),
                        )
                    }

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.security_daily_limit),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = formatInr(dailyLimitRupees.toLong() * 100L),
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Slider(
                            value = dailyLimitRupees,
                            onValueChange = {
                                dailyLimitRupees = it
                                if (perTxnLimitRupees > it) perTxnLimitRupees = it
                            },
                            valueRange = 5000f..100000f,
                            steps = 18,
                            modifier = Modifier.testTag("daily_limit_slider"),
                        )
                    }

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.security_pertxn_limit),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = formatInr(perTxnLimitRupees.toLong() * 100L),
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Slider(
                            value = perTxnLimitRupees,
                            onValueChange = { perTxnLimitRupees = it.coerceAtMost(dailyLimitRupees) },
                            valueRange = 1000f..dailyLimitRupees.coerceAtLeast(1000f),
                            modifier = Modifier.testTag("pertxn_limit_slider"),
                        )
                    }

                    OutlinedTextField(
                        value = backendUrl,
                        onValueChange = { backendUrl = it },
                        label = { Text(stringResource(R.string.security_psp_endpoint)) },
                        placeholder = { Text("https://psp-gateway.yourdomain.in") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("psp_backend_url_input"),
                    )

                    Button(
                        onClick = {
                            val dailyPaise = (dailyLimitRupees.toLong() * 100L).coerceIn(10000L, 10_000_000L)
                            val perTxnPaise = (perTxnLimitRupees.toLong() * 100L).coerceIn(10000L, dailyPaise)
                            onSaveSettings(biometricEnabled, dailyPaise, perTxnPaise, backendUrl)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoRoyal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("save_security_settings_button"),
                    ) {
                        Text(stringResource(R.string.security_save_cta))
                    }
                }
            }

            // Regulatory Compliance Note
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = IndigoRoyal)
                    Text(
                        text = stringResource(R.string.security_compliance_note),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedButton(
                onClick = onSignOut,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FailureCrimson),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("sign_out_button"),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(modifier = Modifier.height(0.dp))
                Text("  ${stringResource(R.string.security_sign_out)}")
            }
        }
    }
}
