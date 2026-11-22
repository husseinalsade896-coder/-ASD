package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.i18n.LocalStrings
import com.example.data.model.UiState
import com.example.ui.viewmodel.WChatViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(viewModel: WChatViewModel) {
    val strings = LocalStrings.current
    val dirState by viewModel.directoryState.collectAsStateWithLifecycle()
    val allUsersState by viewModel.adminAllUsersState.collectAsStateWithLifecycle()
    val chatsState by viewModel.chatsState.collectAsStateWithLifecycle()
    val callsState by viewModel.callsState.collectAsStateWithLifecycle()
    val reportsState by viewModel.reportsState.collectAsStateWithLifecycle()
    val configState by viewModel.systemConfigState.collectAsStateWithLifecycle()

    val directory = (dirState as? UiState.Success)?.data ?: emptyList()
    val allUsers = (allUsersState as? UiState.Success)?.data ?: emptyList()
    val chats = (chatsState as? UiState.Success)?.data ?: emptyList()
    val calls = (callsState as? UiState.Success)?.data ?: emptyList()
    val reports = (reportsState as? UiState.Success)?.data ?: emptyList()
    val sysConfig = (configState as? UiState.Success)?.data

    var maintenanceMode by remember(sysConfig?.maintenanceMode) { mutableStateOf(sysConfig?.maintenanceMode ?: false) }
    var registrationEnabled by remember(sysConfig?.registrationEnabled) { mutableStateOf(sysConfig?.registrationEnabled ?: true) }
    var maxUploadMb by remember(sysConfig?.maxUploadMb) { mutableStateOf((sysConfig?.maxUploadMb ?: 25L).toString()) }

    val onlineCount = directory.count { it.onlineStatus == "ONLINE" }
    val registeredCount = maxOf(directory.size, allUsers.size)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.AdminPanelSettings, null, tint = MaterialTheme.colorScheme.primary)
                    Text(strings.adminDashboardTitle, fontWeight = FontWeight.Bold)
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Metrics Grid
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(strings.registeredUsersMetric, "$registeredCount", Icons.Rounded.People, Modifier.weight(1f))
                MetricCard(strings.onlineUsersMetric, "$onlineCount", Icons.Rounded.Wifi, Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(strings.chatsTab, "${chats.size}", Icons.Rounded.Forum, Modifier.weight(1f))
                MetricCard(strings.totalCallsMetric, "${calls.size}", Icons.Rounded.Call, Modifier.weight(1f))
            }

            // Cloud Backup & System Settings
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings.backupHealthLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val lastBackupStr = remember(sysConfig?.lastBackupEpochMs) {
                        val ms = sysConfig?.lastBackupEpochMs ?: 0L
                        if (ms == 0L) "Never" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
                    }
                    Text("Status: ${sysConfig?.backupStatus ?: "HEALTHY"} • Last Snapshot: $lastBackupStr")
                    Button(
                        onClick = { viewModel.adminTriggerBackup() },
                        modifier = Modifier.fillMaxWidth().testTag("admin_backup_button"),
                    ) {
                        Icon(Icons.Rounded.Backup, null)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.triggerBackupBtn)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(strings.maintenanceModeLabel)
                        Switch(checked = maintenanceMode, onCheckedChange = { maintenanceMode = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(strings.registrationEnabledLabel)
                        Switch(checked = registrationEnabled, onCheckedChange = { registrationEnabled = it })
                    }
                    OutlinedTextField(
                        value = maxUploadMb,
                        onValueChange = { maxUploadMb = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Max Attachment (MB)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilledTonalButton(
                        onClick = {
                            viewModel.adminSaveSystemConfig(
                                maintenanceMode = maintenanceMode,
                                registrationEnabled = registrationEnabled,
                                maxUploadMb = maxUploadMb.toLongOrNull() ?: 25L,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.save)
                    }
                }
            }

            // Account Moderation
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Account Moderation (${directory.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    directory.filter { it.userId != viewModel.currentUserId }.forEach { user ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(user.displayName, fontWeight = FontWeight.SemiBold)
                                Text("@${user.username} • ${user.countryCode} • ${if (user.banned) "BANNED" else user.onlineStatus}", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { viewModel.adminToggleBan(user.userId, !user.banned) },
                            ) {
                                Text(if (user.banned) strings.unbanUserBtn else strings.banUserBtn)
                            }
                        }
                    }
                }
            }

            // Abuse Reports Queue
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${strings.reportsQueueTitle} (${reports.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (reports.isEmpty()) {
                        Text("No open reports", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        reports.forEach { report ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text("Target: ${report.reportedUserId.take(12)} • ${report.status}", fontWeight = FontWeight.Bold)
                                    Text(report.reason, style = MaterialTheme.typography.bodyMedium)
                                    if (report.status != "RESOLVED") {
                                        Spacer(Modifier.height(6.dp))
                                        Button(
                                            onClick = { viewModel.adminResolveReport(report.id, "Reviewed by admin") },
                                        ) {
                                            Text(strings.resolveReportBtn)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    Card(shape = RoundedCornerShape(18.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
