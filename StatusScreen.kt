package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.i18n.LocalStrings
import com.example.core.media.MediaHelper
import com.example.data.model.StatusStory
import com.example.data.model.UiState
import com.example.ui.components.WChatAvatar
import com.example.ui.viewmodel.WChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(viewModel: WChatViewModel) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val statusesState by viewModel.statusesState.collectAsStateWithLifecycle()
    val directoryState by viewModel.directoryState.collectAsStateWithLifecycle()
    val directory = (directoryState as? UiState.Success)?.data ?: emptyList()

    var showCreateDialog by remember { mutableStateOf(false) }
    var viewingStatus by remember { mutableStateOf<StatusStory?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(strings.statusesTab, fontWeight = FontWeight.Bold) },
            actions = {
                FilledTonalButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.padding(end = 12.dp).testTag("add_status_button"),
                ) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text(strings.statusPostBtn)
                }
            },
        )

        when (val state = statusesState) {
            is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
            is UiState.Success -> {
                if (state.data.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.DonutLarge,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(strings.noStatusesYet, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.data, key = { it.id }) { status ->
                            val isMine = status.ownerId == viewModel.currentUserId
                            val bmp = remember(status.mediaPreviewBase64) {
                                MediaHelper.decodeBitmap(status.mediaPreviewBase64)
                            }
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewingStatus = status
                                        viewModel.markStatusViewed(status)
                                    }
                                    .testTag("status_card_${status.id}"),
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            WChatAvatar(status.ownerName, online = true, size = 40.dp)
                                            Column {
                                                Text(status.ownerName, fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    "${strings.statusExpires24h} • ${status.privacy}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        AssistChip(
                                            onClick = { viewingStatus = status },
                                            label = { Text("${status.viewerUids.size} ${strings.statusViewersCount}") },
                                            leadingIcon = { Icon(Icons.Rounded.Visibility, null, modifier = Modifier.size(16.dp)) },
                                        )
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = status.textContent,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(180.dp)
                                                .clip(RoundedCornerShape(14.dp)),
                                            contentScale = ContentScale.Crop,
                                        )
                                        if (status.textContent.isNotBlank()) {
                                            Spacer(Modifier.height(8.dp))
                                            Text(status.textContent)
                                        }
                                    } else {
                                        val bg = runCatching { Color(android.graphics.Color.parseColor(status.backgroundHex)) }
                                            .getOrDefault(MaterialTheme.colorScheme.primary)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(bg)
                                                .padding(20.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = status.textContent,
                                                color = Color.White,
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                        }
                                    }
                                    if (isMine && status.viewerUids.isNotEmpty()) {
                                        Spacer(Modifier.height(6.dp))
                                        val names = status.viewerUids.mapNotNull { uid ->
                                            directory.firstOrNull { it.userId == uid }?.displayName
                                        }
                                        if (names.isNotEmpty()) {
                                            Text(
                                                text = "👁 ${names.joinToString(", ")}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
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

    if (showCreateDialog) {
        var textContent by remember { mutableStateOf("") }
        var selectedPrivacy by remember { mutableStateOf("EVERYONE") }
        var pickedImageB64 by remember { mutableStateOf<String?>(null) }
        val colors = listOf("#0B7A67", "#0F766E", "#1D4ED8", "#7C3AED", "#B45309")
        var bgHex by remember { mutableStateOf(colors.first()) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                pickedImageB64 = MediaHelper.compressImageToBase64(context, uri)?.base64
            }
        }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(strings.statusAddTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = textContent,
                        onValueChange = { textContent = it },
                        placeholder = { Text(strings.statusTextPrompt) },
                        modifier = Modifier.fillMaxWidth().testTag("status_text_input"),
                    )
                    OutlinedButton(
                        onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Image, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (pickedImageB64 != null) "✓ ${strings.statusImageBtn}" else strings.statusImageBtn)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        colors.forEach { hex ->
                            val c = Color(android.graphics.Color.parseColor(hex))
                            Surface(
                                onClick = { bgHex = hex },
                                shape = RoundedCornerShape(50),
                                color = c,
                                border = if (bgHex == hex) ButtonDefaults.outlinedButtonBorder(enabled = true) else null,
                                modifier = Modifier.size(30.dp),
                            ) {}
                        }
                    }
                    Text(strings.statusPrivacyLabel, style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "EVERYONE" to strings.privacyEveryone,
                            "CONTACTS" to strings.privacyContacts,
                            "ONLY_ME" to strings.privacyOnlyMe,
                        ).forEach { (code, label) ->
                            FilterChip(
                                selected = selectedPrivacy == code,
                                onClick = { selectedPrivacy = code },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (textContent.isNotBlank() || pickedImageB64 != null) {
                            viewModel.postStatus(
                                statusType = if (pickedImageB64 != null) "IMAGE" else "TEXT",
                                textContent = textContent,
                                backgroundHex = bgHex,
                                mediaPreviewBase64 = pickedImageB64,
                                privacy = selectedPrivacy,
                            )
                            showCreateDialog = false
                        }
                    },
                    modifier = Modifier.testTag("confirm_post_status"),
                ) { Text(strings.statusPostBtn) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text(strings.cancel) }
            },
        )
    }

    val activeView = viewingStatus
    if (activeView != null) {
        AlertDialog(
            onDismissRequest = { viewingStatus = null },
            title = { Text(activeView.ownerName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val bmp = MediaHelper.decodeBitmap(activeView.mediaPreviewBase64)
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = activeView.textContent,
                            modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    if (activeView.textContent.isNotBlank()) {
                        Text(activeView.textContent, style = MaterialTheme.typography.titleMedium)
                    }
                    Text("${strings.statusViewersCount}: ${activeView.viewerUids.size}")
                }
            },
            confirmButton = { TextButton(onClick = { viewingStatus = null }) { Text(strings.close) } },
        )
    }
}
