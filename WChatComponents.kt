package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.core.i18n.LocalStrings
import com.example.core.media.MediaHelper
import com.example.data.model.ChatMessage
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun WChatAvatar(
    name: String,
    online: Boolean = false,
    isGroup: Boolean = false,
    size: Dp = 48.dp,
    accentHex: String = "#0B7A67",
) {
    val bgColor = remember(accentHex, name) {
        runCatching { Color(android.graphics.Color.parseColor(accentHex)) }
            .getOrDefault(EmeraldPrimary)
    }
    Box(modifier = Modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(bgColor.copy(alpha = 0.16f))
                .border(1.5.dp, bgColor.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isGroup) {
                Icon(
                    imageVector = Icons.Rounded.Groups,
                    contentDescription = name,
                    tint = bgColor,
                    modifier = Modifier.size(size * 0.52f),
                )
            } else {
                Text(
                    text = name.trim().take(2).uppercase().ifBlank { "W" },
                    style = MaterialTheme.typography.titleMedium,
                    color = bgColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (online && !isGroup) {
            Box(
                modifier = Modifier
                    .size(13.dp)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(StatusOnlineGreen)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}

@Composable
fun E2eeBadgeChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
        modifier = modifier.testTag("e2ee_badge_chip"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = strings.e2eeBadge,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = strings.e2eeBadge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    decryptedText: String,
    isMine: Boolean,
    isDark: Boolean,
    isGroup: Boolean,
    isPlayingVoice: Boolean,
    onPlayVoice: (String) -> Unit,
    onOpenAttachment: (String, String) -> Unit,
    onOpenLocation: (Double, Double, String) -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onCopy: (String) -> Unit,
    onEdit: () -> Unit,
    onDeleteForAll: () -> Unit,
) {
    val strings = LocalStrings.current
    var showMenu by remember { mutableStateOf(false) }

    val bubbleColor = when {
        isMine && isDark -> OutgoingBubbleDark
        isMine -> OutgoingBubbleLight
        isDark -> IncomingBubbleDark
        else -> IncomingBubbleLight
    }

    val formattedTime = remember(message.createdAt) {
        val date = message.createdAt?.toDate() ?: Date()
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isMine) 18.dp else 4.dp,
                    bottomEnd = if (isMine) 4.dp else 18.dp,
                ),
                color = bubbleColor,
                tonalElevation = 1.dp,
                shadowElevation = 1.dp,
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .clickable { showMenu = true }
                    .testTag("message_bubble_${message.id}"),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (isGroup && !isMine) {
                        Text(
                            text = message.senderName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    if (message.forwarded && !message.deletedForAll) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Forward,
                                contentDescription = strings.forwardedLabel,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = strings.forwardedLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (!message.replyToPreview.isNullOrBlank() && !message.deletedForAll) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                        ) {
                            Text(
                                text = message.replyToPreview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }

                    if (message.deletedForAll) {
                        Text(
                            text = "🚫 ${strings.deletedMessageLabel}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        when (message.messageType) {
                            "IMAGE" -> {
                                val bmp = remember(message.mediaPreviewBase64) {
                                    MediaHelper.decodeBitmap(message.mediaPreviewBase64)
                                }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = message.mediaName ?: "Image",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 220.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                if (decryptedText.isNotBlank()) {
                                    Text(text = decryptedText, style = MaterialTheme.typography.bodyMedium)
                                }
                            }

                            "VOICE" -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(vertical = 2.dp),
                                ) {
                                    IconButton(
                                        onClick = {
                                            val b64 = message.mediaPreviewBase64.orEmpty()
                                            if (b64.isNotBlank()) onPlayVoice(b64)
                                        },
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    ) {
                                        Icon(
                                            imageVector = if (isPlayingVoice) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                            contentDescription = "Voice",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = decryptedText.ifBlank { "🎤 Voice Message" },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            text = "${((message.mediaSizeBytes ?: 0L) / 1024L).coerceAtLeast(1L)} KB",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }

                            "FILE", "VIDEO" -> {
                                Surface(
                                    onClick = {
                                        val b64 = message.mediaPreviewBase64.orEmpty()
                                        if (b64.isNotBlank()) {
                                            onOpenAttachment(message.mediaName ?: "file", b64)
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (message.messageType == "VIDEO") Icons.Rounded.Videocam else Icons.AutoMirrored.Rounded.InsertDriveFile,
                                            contentDescription = message.mediaName,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Column(modifier = Modifier.weight(1f, fill = false)) {
                                            Text(
                                                text = message.mediaName ?: decryptedText,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "${((message.mediaSizeBytes ?: 0L) / 1024L).coerceAtLeast(1L)} KB",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = "Download",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }

                            "LOCATION", "LIVE_LOCATION" -> {
                                val lat = message.latitude ?: 0.0
                                val lng = message.longitude ?: 0.0
                                Surface(
                                    onClick = { onOpenLocation(lat, lng, message.senderName) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.LocationOn,
                                            contentDescription = "Location",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Column {
                                            Text(
                                                text = if (message.messageType == "LIVE_LOCATION") strings.attachLiveLocation else strings.attachLocation,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Text(
                                                text = "%.5f, %.5f".format(lat, lng),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                }
                            }

                            else -> {
                                Text(
                                    text = decryptedText,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (message.edited && !message.deletedForAll) {
                            Text(
                                text = strings.editedLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isMine && !message.deletedForAll) {
                            val isRead = message.readByUids.size > 1
                            val isDelivered = message.deliveredToUids.size > 1 || message.readByUids.isNotEmpty()
                            Icon(
                                imageVector = if (isDelivered) Icons.Rounded.DoneAll else Icons.Rounded.Done,
                                contentDescription = if (isRead) "Read" else "Sent",
                                tint = if (isRead) ReadTickBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(strings.reply) },
                    leadingIcon = { Icon(Icons.Rounded.Reply, null) },
                    onClick = { showMenu = false; onReply() },
                )
                DropdownMenuItem(
                    text = { Text(strings.forward) },
                    leadingIcon = { Icon(Icons.Rounded.Forward, null) },
                    onClick = { showMenu = false; onForward() },
                )
                DropdownMenuItem(
                    text = { Text(strings.copy) },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                    onClick = { showMenu = false; onCopy(decryptedText) },
                )
                if (isMine && !message.deletedForAll && message.messageType == "TEXT") {
                    DropdownMenuItem(
                        text = { Text(strings.edit) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { showMenu = false; onEdit() },
                    )
                }
                if (isMine && !message.deletedForAll) {
                    DropdownMenuItem(
                        text = { Text(strings.deleteForAll, color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDeleteForAll() },
                    )
                }
            }
        }
    }
}
