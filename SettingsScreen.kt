package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocalLanguage
import com.example.core.i18n.LocalStrings
import com.example.core.media.DeviceContact
import com.example.core.media.MediaHelper
import com.example.data.model.UiState
import com.example.ui.components.PermissionRationaleDialog
import com.example.ui.viewmodel.WChatViewModel

private enum class SettingsPermTarget { CONTACTS, NOTIFICATIONS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: WChatViewModel,
    onSignOut: () -> Unit,
) {
    val strings = LocalStrings.current
    val language = LocalLanguage.current
    val context = LocalContext.current
    val darkTheme by viewModel.darkTheme.collectAsStateWithLifecycle()

    val dirState by viewModel.directoryState.collectAsStateWithLifecycle()
    val privState by viewModel.privateAccountState.collectAsStateWithLifecycle()
    val devState by viewModel.devicesState.collectAsStateWithLifecycle()

    val myDir = (dirState as? UiState.Success)?.data?.firstOrNull { it.userId == viewModel.currentUserId }
    val myPriv = (privState as? UiState.Success)?.data
    val devices = (devState as? UiState.Success)?.data ?: emptyList()

    var displayName by remember(myDir?.displayName) { mutableStateOf(myDir?.displayName.orEmpty()) }
    var username by remember(myDir?.username) { mutableStateOf(myDir?.username.orEmpty()) }
    var bio by remember(myDir?.bio) { mutableStateOf(myDir?.bio.orEmpty()) }
    var countryCode by remember(myDir?.countryCode) { mutableStateOf(myDir?.countryCode ?: "+967") }
    var phoneNumber by remember(myPriv?.phoneNumber) { mutableStateOf(myPriv?.phoneNumber.orEmpty()) }

    var lastSeenPrivacy by remember(myDir?.lastSeenPrivacy) { mutableStateOf(myDir?.lastSeenPrivacy ?: "EVERYONE") }
    var avatarPrivacy by remember(myDir?.avatarPrivacy) { mutableStateOf(myDir?.avatarPrivacy ?: "EVERYONE") }
    var readReceipts by remember(myDir?.readReceiptsEnabled) { mutableStateOf(myDir?.readReceiptsEnabled ?: true) }
    var twoFactorEnabled by remember(myDir?.twoFactorEnabled) { mutableStateOf(myDir?.twoFactorEnabled ?: false) }
    var twoStepPin by remember { mutableStateOf("") }

    var notificationsEnabled by remember(myPriv?.notificationsEnabled) { mutableStateOf(myPriv?.notificationsEnabled ?: true) }
    var notificationTone by remember(myPriv?.notificationTone) { mutableStateOf(myPriv?.notificationTone ?: "Emerald Chime") }

    var syncedContacts by remember { mutableStateOf<List<DeviceContact>?>(null) }
    var permRationale by remember { mutableStateOf<SettingsPermTarget?>(null) }
    var permPermanentlyDenied by remember { mutableStateOf(false) }

    val contactsPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            syncedContacts = MediaHelper.readDeviceContacts(context)
        } else {
            permPermanentlyDenied = true
            permRationale = SettingsPermTarget.CONTACTS
        }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        notificationsEnabled = ok
        if (!ok) {
            permPermanentlyDenied = true
            permRationale = SettingsPermTarget.NOTIFICATIONS
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(strings.settingsTab, fontWeight = FontWeight.Bold) })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Profile & Phone
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings.profileSection, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text(strings.displayNameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_display_name"),
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(strings.usernameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_username"),
                    )
                    OutlinedTextField(
                        value = bio,
                        onValueChange = { bio = it },
                        label = { Text(strings.bioLabel) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = countryCode,
                            onValueChange = { countryCode = it },
                            label = { Text(strings.countryCodeLabel) },
                            singleLine = true,
                            modifier = Modifier.width(120.dp),
                        )
                        OutlinedTextField(
                            value = phoneNumber,
                            onValueChange = { phoneNumber = it },
                            label = { Text(strings.phoneNumberLabel) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.updateProfile(
                                displayName = displayName,
                                username = username,
                                bio = bio,
                                countryCode = countryCode,
                                phoneNumber = phoneNumber,
                                phoneVerified = myPriv?.phoneVerified ?: false,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().testTag("save_profile_button"),
                    ) {
                        Icon(Icons.Rounded.Save, null)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.saveProfile)
                    }
                }
            }

            // 2. Privacy & Two-Step Verification
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings.privacySecuritySection, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    val myPub = viewModel.cryptoManager.getPublicKeyBase64(viewModel.currentUserId)
                    Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(strings.e2eeFingerprintTitle, style = MaterialTheme.typography.labelLarge)
                            Text(viewModel.cryptoManager.getFingerprint(myPub), style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(strings.readReceiptsLabel)
                        Switch(checked = readReceipts, onCheckedChange = { readReceipts = it })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(strings.enableTwoStep)
                        Switch(checked = twoFactorEnabled, onCheckedChange = { twoFactorEnabled = it })
                    }

                    if (twoFactorEnabled) {
                        OutlinedTextField(
                            value = twoStepPin,
                            onValueChange = { if (it.length <= 6) twoStepPin = it },
                            label = { Text(strings.twoStepPinLabel) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Text(strings.lastSeenPrivacyLabel, style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("EVERYONE" to strings.privacyEveryone, "CONTACTS" to strings.privacyContacts, "NOBODY" to strings.privacyNobody)
                            .forEach { (code, label) ->
                                FilterChip(
                                    selected = lastSeenPrivacy == code,
                                    onClick = { lastSeenPrivacy = code },
                                    label = { Text(label) },
                                )
                            }
                    }

                    Text(strings.avatarPrivacyLabel, style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("EVERYONE" to strings.privacyEveryone, "CONTACTS" to strings.privacyContacts, "NOBODY" to strings.privacyNobody)
                            .forEach { (code, label) ->
                                FilterChip(
                                    selected = avatarPrivacy == code,
                                    onClick = { avatarPrivacy = code },
                                    label = { Text(label) },
                                )
                            }
                    }

                    // Notifications
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(strings.enableNotificationsLabel)
                        Switch(
                            checked = notificationsEnabled,
                            onCheckedChange = { enable ->
                                if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    permPermanentlyDenied = false
                                    permRationale = SettingsPermTarget.NOTIFICATIONS
                                } else {
                                    notificationsEnabled = enable
                                }
                            },
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Emerald Chime", "Desert Oud", "Soft Pulse").forEach { tone ->
                            FilterChip(
                                selected = notificationTone == tone,
                                onClick = { notificationTone = tone },
                                label = { Text(tone) },
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            viewModel.updatePrivacyAndNotifications(
                                lastSeenPrivacy = lastSeenPrivacy,
                                avatarPrivacy = avatarPrivacy,
                                readReceiptsEnabled = readReceipts,
                                twoFactorEnabled = twoFactorEnabled,
                                twoStepPinRaw = twoStepPin.ifBlank { null },
                                notificationsEnabled = notificationsEnabled,
                                notificationTone = notificationTone,
                            )
                            twoStepPin = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.save)
                    }
                }
            }

            // 3. Linked Devices
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.linkedDevicesLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    devices.forEach { dev ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(dev.deviceName, fontWeight = FontWeight.Medium)
                                Text(dev.platform, style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { viewModel.removeLinkedDevice(dev.id) }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove device")
                            }
                        }
                    }
                }
            }

            // 4. Contacts Sync & Appearance
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings.appearanceSection, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(strings.languageLabel)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = language == AppLanguage.ARABIC,
                                onClick = { viewModel.setLanguage(AppLanguage.ARABIC) },
                                label = { Text("العربية") },
                            )
                            FilterChip(
                                selected = language == AppLanguage.ENGLISH,
                                onClick = { viewModel.setLanguage(AppLanguage.ENGLISH) },
                                label = { Text("English") },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(strings.darkModeLabel)
                        Switch(checked = darkTheme, onCheckedChange = { viewModel.setDarkTheme(it) })
                    }
                    OutlinedButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                                syncedContacts = MediaHelper.readDeviceContacts(context)
                            } else {
                                permPermanentlyDenied = false
                                permRationale = SettingsPermTarget.CONTACTS
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Contacts, null)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.syncContactsBtn)
                    }
                    syncedContacts?.let { list ->
                        Text("${list.size} contacts loaded on device", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // 5. Sign Out
            OutlinedButton(
                onClick = onSignOut,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("sign_out_button"),
            ) {
                Icon(Icons.Rounded.Logout, null)
                Spacer(Modifier.width(8.dp))
                Text(strings.signOut)
            }
        }
    }

    val target = permRationale
    if (target != null) {
        PermissionRationaleDialog(
            rationaleMessage = if (target == SettingsPermTarget.CONTACTS) strings.contactsRationale else strings.notificationsRationale,
            permanentlyDenied = permPermanentlyDenied,
            onRequestPermission = {
                permRationale = null
                if (target == SettingsPermTarget.CONTACTS) {
                    contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onDismiss = { permRationale = null },
        )
    }
}
