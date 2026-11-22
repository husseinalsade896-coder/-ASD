package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.i18n.LocalStrings
import com.example.data.model.CallRecord
import com.example.ui.components.WChatAvatar
import kotlinx.coroutines.delay

@Composable
fun CallOverlayScreen(
    call: CallRecord,
    myUid: String,
    onAnswer: () -> Unit,
    onEndOrDecline: (durationSeconds: Long, declined: Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    val isIncomingRinging = call.status == "RINGING" && call.callerId != myUid
    val isVideo = call.callType.contains("VIDEO")

    var micMuted by remember { mutableStateOf(false) }
    var cameraEnabled by remember { mutableStateOf(isVideo) }
    var frontCamera by remember { mutableStateOf(true) }
    var speakerOn by remember { mutableStateOf(isVideo) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    LaunchedEffect(call.status) {
        if (call.status == "ACTIVE") {
            while (true) {
                delay(1000)
                elapsedSeconds++
            }
        }
    }

    val durationFormatted = remember(elapsedSeconds) {
        "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF07231E), Color(0xFF0B1416))
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top call status badge
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.12f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Rounded.Lock, null, tint = Color(0xFF34D399), modifier = Modifier.size(14.dp))
                        Text(
                            text = "WebRTC Signaling • ${call.callType}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                WChatAvatar(
                    name = call.callerName,
                    online = true,
                    isGroup = call.callType.startsWith("GROUP"),
                    size = 110.dp,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = call.callerName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        isIncomingRinging -> strings.ringingLabel
                        call.status == "ACTIVE" -> "${strings.activeCallLabel} • $durationFormatted"
                        else -> strings.callingLabel
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF5EEAD4),
                )
                if (isVideo && cameraEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (frontCamera) "📷 Front Camera Active" else "📷 Rear Camera Active",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Bottom Call Controls
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!isIncomingRinging) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        CallControlTile(
                            icon = if (micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            label = if (micMuted) strings.unmuteMic else strings.muteMic,
                            active = micMuted,
                            onClick = { micMuted = !micMuted },
                            tag = "call_mute_button",
                        )
                        if (isVideo) {
                            CallControlTile(
                                icon = if (cameraEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                                label = if (cameraEnabled) strings.cameraOn else strings.cameraOff,
                                active = !cameraEnabled,
                                onClick = { cameraEnabled = !cameraEnabled },
                                tag = "call_camera_toggle",
                            )
                            CallControlTile(
                                icon = Icons.Rounded.Cameraswitch,
                                label = strings.switchCamera,
                                active = false,
                                onClick = { frontCamera = !frontCamera },
                                tag = "call_flip_camera",
                            )
                        }
                        CallControlTile(
                            icon = if (speakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeDown,
                            label = strings.speakerOn,
                            active = speakerOn,
                            onClick = { speakerOn = !speakerOn },
                            tag = "call_speaker_toggle",
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    FloatingActionButton(
                        onClick = { onEndOrDecline(elapsedSeconds, false) },
                        containerColor = Color(0xFFDC2626),
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.size(68.dp).testTag("end_call_button"),
                    ) {
                        Icon(Icons.Rounded.CallEnd, contentDescription = strings.endCall, modifier = Modifier.size(30.dp))
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        FloatingActionButton(
                            onClick = { onEndOrDecline(0L, true) },
                            containerColor = Color(0xFFDC2626),
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(68.dp).testTag("decline_call_button"),
                        ) {
                            Icon(Icons.Rounded.CallEnd, contentDescription = strings.declineCall)
                        }
                        FloatingActionButton(
                            onClick = onAnswer,
                            containerColor = Color(0xFF10B981),
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(68.dp).testTag("answer_call_button"),
                        ) {
                            Icon(Icons.Rounded.Call, contentDescription = strings.answerCall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallControlTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (active) Color.White else Color.White.copy(alpha = 0.16f),
                contentColor = if (active) Color(0xFF0B1416) else Color.White,
            ),
            modifier = Modifier.size(56.dp).testTag(tag),
        ) {
            Icon(icon, contentDescription = label)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
