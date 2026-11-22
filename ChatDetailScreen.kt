package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.i18n.LocalStrings
import com.example.core.media.MediaHelper
import com.example.core.media.VoiceRecorderPlayer
import com.example.data.model.ChatMessage
import com.example.data.model.ChatRoom
import com.example.data.model.UiState
import com.example.ui.components.E2eeBadgeChip
import com.example.ui.components.MessageBubble
import com.example.ui.components.PermissionRationaleDialog
import com.example.ui.components.WChatAvatar
import com.example.ui.viewmodel.WChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PendingPermissionFeature {
    MIC_VOICE, CAMERA_PHOTO, LOCATION_ONCE, LOCATION_LIVE, CALL_AUDIO, CALL_VIDEO
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    chatId: String,
    viewModel: WChatViewModel,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val darkTheme by viewModel.darkTheme.collectAsStateWithLifecycle()

    LaunchedEffect(chatId) { viewModel.setActiveChat(chatId) }
    BackHandler { onBack() }

    val chatsState by viewModel.chatsState.collectAsStateWithLifecycle()
    val messagesState by viewModel.activeMessagesState.collectAsStateWithLifecycle()
    val directoryState by viewModel.directoryState.collectAsStateWithLifecycle()
    val privateAcctState by viewModel.privateAccountState.collectAsStateWithLifecycle()

    val directory = (directoryState as? UiState.Success)?.data ?: emptyList()
    val chatRoom = (chatsState as? UiState.Success)?.data?.firstOrNull { it.id == chatId }
    val blockedUids = (privateAcctState as? UiState.Success)?.data?.blockedUids ?: emptyList()

    if (chatRoom == null) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val isGroup = chatRoom.chatType == "GROUP"
    val peerProfile = if (!isGroup) {
        val peerId = chatRoom.memberUids.firstOrNull { it != viewModel.currentUserId }
        directory.firstOrNull { it.userId == peerId }
    } else null
    val roomTitle = peerProfile?.displayName ?: chatRoom.title
    val isPeerBlocked = peerProfile != null && peerProfile.userId in blockedUids

    var inputText by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var replyTarget by remember { mutableStateOf<Pair<ChatMessage, String>?>(null) }
    var editTarget by remember { mutableStateOf<Pair<ChatMessage, String>?>(null) }
    var forwardTarget by remember { mutableStateOf<String?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showE2eeDialog by remember { mutableStateOf(false) }
    var showGroupSettings by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }

    // Voice recorder & player
    val voiceManager = remember(context) { VoiceRecorderPlayer(context) }
    var isRecordingVoice by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var playingMsgId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            voiceManager.stopPlayback()
            if (isRecordingVoice) voiceManager.stopRecordingAndGetBase64()
        }
    }

    LaunchedEffect(isRecordingVoice) {
        if (isRecordingVoice) {
            recordingSeconds = 0
            while (isRecordingVoice) {
                delay(1000)
                recordingSeconds++
            }
        }
    }

    // Typing indicator debounce
    LaunchedEffect(inputText) {
        if (inputText.isNotBlank()) {
            viewModel.setTyping(chatRoom, true)
            delay(2500)
            viewModel.setTyping(chatRoom, false)
        } else {
            viewModel.setTyping(chatRoom, false)
        }
    }

    // Permission state
    var pendingFeature by remember { mutableStateOf<PendingPermissionFeature?>(null) }
    var showRationaleFor by remember { mutableStateOf<PendingPermissionFeature?>(null) }
    var permanentlyDenied by remember { mutableStateOf(false) }

    // Camera capture URI
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        if (ok && uri != null) {
            scope.launch {
                val compressed = MediaHelper.compressImageToBase64(context, uri)
                if (compressed != null) {
                    viewModel.sendEncryptedMessage(
                        chatRoom = chatRoom,
                        plaintext = compressed.fileName,
                        messageType = "IMAGE",
                        mediaName = compressed.fileName,
                        mediaSizeBytes = compressed.sizeBytes,
                        mediaPreviewBase64 = compressed.base64,
                    )
                }
            }
        }
    }

    // Official Zero-Permission Android Photo/Video Multi-Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris ->
        uris.forEach { uri ->
            val mime = context.contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("video/")) {
                val info = MediaHelper.readDocumentPreview(context, uri)
                if (info != null) {
                    viewModel.sendEncryptedMessage(
                        chatRoom = chatRoom,
                        plaintext = info.fileName,
                        messageType = "VIDEO",
                        mediaName = info.fileName,
                        mediaSizeBytes = info.sizeBytes,
                        mediaPreviewBase64 = info.base64.ifBlank { null },
                    )
                }
            } else {
                val compressed = MediaHelper.compressImageToBase64(context, uri)
                if (compressed != null) {
                    viewModel.sendEncryptedMessage(
                        chatRoom = chatRoom,
                        plaintext = compressed.fileName,
                        messageType = "IMAGE",
                        mediaName = compressed.fileName,
                        mediaSizeBytes = compressed.sizeBytes,
                        mediaPreviewBase64 = compressed.base64,
                    )
                }
            }
        }
    }

    // Official SAF Document Picker (PDF/Word/etc.)
    val docPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val doc = MediaHelper.readDocumentPreview(context, uri)
            if (doc != null) {
                viewModel.sendEncryptedMessage(
                    chatRoom = chatRoom,
                    plaintext = doc.fileName,
                    messageType = "FILE",
                    mediaName = doc.fileName,
                    mediaSizeBytes = doc.sizeBytes,
                    mediaPreviewBase64 = doc.base64.ifBlank { null },
                )
            }
        }
    }

    fun executeFeatureAfterGrant(feature: PendingPermissionFeature) {
        when (feature) {
            PendingPermissionFeature.MIC_VOICE -> {
                if (voiceManager.startRecording()) isRecordingVoice = true
            }
            PendingPermissionFeature.CAMERA_PHOTO -> {
                val uri = MediaHelper.createCameraOutputUri(context)
                pendingCameraUri = uri
                cameraLauncher.launch(uri)
            }
            PendingPermissionFeature.LOCATION_ONCE, PendingPermissionFeature.LOCATION_LIVE -> {
                val isLive = feature == PendingPermissionFeature.LOCATION_LIVE
                scope.launch {
                    val coords = MediaHelper.getCurrentCoordinates(context)
                    if (coords != null) {
                        viewModel.sendEncryptedMessage(
                            chatRoom = chatRoom,
                            plaintext = "📍 ${coords.first}, ${coords.second}",
                            messageType = if (isLive) "LIVE_LOCATION" else "LOCATION",
                            latitude = coords.first,
                            longitude = coords.second,
                            liveUntilEpochMs = if (isLive) System.currentTimeMillis() + 15 * 60_000L else null,
                        )
                    } else {
                        Toast.makeText(context, "GPS unavailable", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            PendingPermissionFeature.CALL_AUDIO -> {
                viewModel.startCall(
                    participantUids = chatRoom.memberUids,
                    callType = if (isGroup) "GROUP_VOICE" else "VOICE",
                )
            }
            PendingPermissionFeature.CALL_VIDEO -> {
                viewModel.startCall(
                    participantUids = chatRoom.memberUids,
                    callType = if (isGroup) "GROUP_VIDEO" else "VIDEO",
                )
            }
        }
    }

    val singlePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val f = pendingFeature
        pendingFeature = null
        if (granted && f != null) {
            executeFeatureAfterGrant(f)
        } else if (!granted && f != null) {
            permanentlyDenied = true
            showRationaleFor = f
        }
    }

    val multiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val f = pendingFeature
        pendingFeature = null
        if (results.values.all { it } && f != null) {
            executeFeatureAfterGrant(f)
        } else if (f != null) {
            permanentlyDenied = true
            showRationaleFor = f
        }
    }

    fun requestFeatureWithPermission(feature: PendingPermissionFeature) {
        val needed = when (feature) {
            PendingPermissionFeature.MIC_VOICE, PendingPermissionFeature.CALL_AUDIO ->
                listOf(Manifest.permission.RECORD_AUDIO)
            PendingPermissionFeature.CAMERA_PHOTO ->
                listOf(Manifest.permission.CAMERA)
            PendingPermissionFeature.CALL_VIDEO ->
                listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            PendingPermissionFeature.LOCATION_ONCE, PendingPermissionFeature.LOCATION_LIVE ->
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val allGranted = needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            executeFeatureAfterGrant(feature)
        } else {
            permanentlyDenied = false
            showRationaleFor = feature
        }
    }

    val canSendInRoom = !isPeerBlocked &&
        (!isGroup || !chatRoom.onlyAdminsCanSend || viewModel.currentUserId in chatRoom.adminUids)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_button")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.clickable {
                            if (isGroup) showGroupSettings = true else showE2eeDialog = true
                        },
                    ) {
                        WChatAvatar(
                            name = roomTitle,
                            online = peerProfile?.onlineStatus == "ONLINE",
                            isGroup = isGroup,
                            size = 38.dp,
                        )
                        Column {
                            Text(roomTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val subtitle = when {
                                chatRoom.typingUids.any { it != viewModel.currentUserId } -> strings.typingNow
                                isGroup -> "${chatRoom.memberUids.size} members"
                                peerProfile?.onlineStatus == "ONLINE" -> strings.onlineLabel
                                else -> strings.offlineLabel
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { requestFeatureWithPermission(PendingPermissionFeature.CALL_AUDIO) },
                        modifier = Modifier.testTag("voice_call_button"),
                    ) {
                        Icon(Icons.Rounded.Call, contentDescription = strings.voiceCall)
                    }
                    IconButton(
                        onClick = { requestFeatureWithPermission(PendingPermissionFeature.CALL_VIDEO) },
                        modifier = Modifier.testTag("video_call_button"),
                    ) {
                        Icon(Icons.Rounded.Videocam, contentDescription = strings.videoCall)
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(strings.searchMessagesPlaceholder) },
                                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                onClick = { showOverflowMenu = false; searchOpen = !searchOpen },
                            )
                            DropdownMenuItem(
                                text = { Text(strings.e2eeFingerprintTitle) },
                                leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                                onClick = { showOverflowMenu = false; showE2eeDialog = true },
                            )
                            if (isGroup) {
                                DropdownMenuItem(
                                    text = { Text(strings.groupSettingsTitle) },
                                    leadingIcon = { Icon(Icons.Rounded.AdminPanelSettings, null) },
                                    onClick = { showOverflowMenu = false; showGroupSettings = true },
                                )
                            } else if (peerProfile != null) {
                                DropdownMenuItem(
                                    text = { Text(if (isPeerBlocked) strings.unblockUser else strings.blockUser) },
                                    leadingIcon = { Icon(Icons.Rounded.Block, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.toggleBlockUser(peerProfile.userId)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(strings.reportUser, color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Rounded.Report, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { showOverflowMenu = false; showReportDialog = true },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            AnimatedVisibility(visible = searchOpen) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(strings.searchMessagesPlaceholder) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { searchQuery = ""; searchOpen = false }) {
                            Icon(Icons.Rounded.Close, null)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                E2eeBadgeChip(onClick = { showE2eeDialog = true })
            }

            // Messages List
            val listState = rememberLazyListState()
            when (val mState = messagesState) {
                is UiState.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is UiState.Error -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(mState.message, color = MaterialTheme.colorScheme.error)
                }
                is UiState.Success -> {
                    val decryptedPairs = remember(mState.data, chatRoom, searchQuery) {
                        mState.data.map { msg ->
                            msg to viewModel.decryptMessageText(chatRoom, msg)
                        }.filter { (msg, text) ->
                            searchQuery.isBlank() ||
                                text.contains(searchQuery, ignoreCase = true) ||
                                (msg.mediaName?.contains(searchQuery, true) == true)
                        }
                    }

                    LaunchedEffect(decryptedPairs.size) {
                        if (decryptedPairs.isNotEmpty()) {
                            listState.animateScrollToItem(decryptedPairs.lastIndex)
                            decryptedPairs.forEach { (msg, _) ->
                                if (msg.senderId != viewModel.currentUserId && viewModel.currentUserId !in msg.readByUids) {
                                    viewModel.markMessageRead(chatId, msg)
                                }
                            }
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(decryptedPairs, key = { it.first.id }) { (msg, plain) ->
                            MessageBubble(
                                message = msg,
                                decryptedText = plain,
                                isMine = msg.senderId == viewModel.currentUserId,
                                isDark = darkTheme,
                                isGroup = isGroup,
                                isPlayingVoice = playingMsgId == msg.id,
                                onPlayVoice = { b64 ->
                                    if (playingMsgId == msg.id) {
                                        voiceManager.stopPlayback()
                                        playingMsgId = null
                                    } else {
                                        playingMsgId = msg.id
                                        voiceManager.playBase64(b64) { playingMsgId = null }
                                    }
                                },
                                onOpenAttachment = { name, b64 ->
                                    MediaHelper.saveAndOpenAttachment(context, name, b64)
                                },
                                onOpenLocation = { lat, lng, label ->
                                    MediaHelper.openMapCoordinates(context, lat, lng, label)
                                },
                                onReply = { replyTarget = msg to plain },
                                onForward = { forwardTarget = plain },
                                onCopy = { text ->
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("WChat", text))
                                    Toast.makeText(context, strings.copiedToast, Toast.LENGTH_SHORT).show()
                                },
                                onEdit = {
                                    editTarget = msg to plain
                                    inputText = plain
                                },
                                onDeleteForAll = {
                                    viewModel.deleteMessageForAll(chatId, msg.id)
                                },
                            )
                        }
                    }
                }
            }

            // Reply / Edit banner
            if (replyTarget != null || editTarget != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (editTarget != null) strings.edit else strings.reply,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = editTarget?.second ?: replyTarget?.second.orEmpty(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            replyTarget = null
                            editTarget = null
                        }) {
                            Icon(Icons.Rounded.Close, contentDescription = strings.cancel)
                        }
                    }
                }
            }

            // Composer Bar
            if (!canSendInRoom) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (isPeerBlocked) strings.blockedLabel else strings.onlyAdminsCanSend,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            } else if (isRecordingVoice) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Mic, null, tint = MaterialTheme.colorScheme.error)
                            Text("${strings.recordingVoiceLabel} ${recordingSeconds}s", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                isRecordingVoice = false
                                val voice = voiceManager.stopRecordingAndGetBase64()
                                if (voice != null && voice.base64.isNotBlank()) {
                                    viewModel.sendEncryptedMessage(
                                        chatRoom = chatRoom,
                                        plaintext = "🎤 ${recordingSeconds}s",
                                        messageType = "VOICE",
                                        mediaName = voice.fileName,
                                        mediaSizeBytes = voice.sizeBytes,
                                        mediaPreviewBase64 = voice.base64,
                                    )
                                }
                            },
                            modifier = Modifier.testTag("stop_voice_button"),
                        ) {
                            Text(strings.stopRecording)
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box {
                        IconButton(
                            onClick = { showAttachMenu = true },
                            modifier = Modifier.testTag("attach_button"),
                        ) {
                            Icon(Icons.Rounded.AttachFile, contentDescription = "Attach")
                        }
                        DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(strings.attachPhotoVideo) },
                                leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) },
                                onClick = {
                                    showAttachMenu = false
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(strings.attachCamera) },
                                leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null) },
                                onClick = {
                                    showAttachMenu = false
                                    requestFeatureWithPermission(PendingPermissionFeature.CAMERA_PHOTO)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(strings.attachDocument) },
                                leadingIcon = { Icon(Icons.Rounded.Description, null) },
                                onClick = {
                                    showAttachMenu = false
                                    docPickerLauncher.launch(arrayOf("*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(strings.attachLocation) },
                                leadingIcon = { Icon(Icons.Rounded.LocationOn, null) },
                                onClick = {
                                    showAttachMenu = false
                                    requestFeatureWithPermission(PendingPermissionFeature.LOCATION_ONCE)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(strings.attachLiveLocation) },
                                leadingIcon = { Icon(Icons.Rounded.ShareLocation, null) },
                                onClick = {
                                    showAttachMenu = false
                                    requestFeatureWithPermission(PendingPermissionFeature.LOCATION_LIVE)
                                },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text(strings.typeMessagePlaceholder) },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("message_input"),
                    )

                    if (inputText.isBlank() && editTarget == null) {
                        FilledIconButton(
                            onClick = { requestFeatureWithPermission(PendingPermissionFeature.MIC_VOICE) },
                            modifier = Modifier.size(48.dp).testTag("record_voice_button"),
                        ) {
                            Icon(Icons.Rounded.Mic, contentDescription = strings.recordVoice)
                        }
                    } else {
                        FilledIconButton(
                            onClick = {
                                val text = inputText.trim()
                                if (text.isNotEmpty()) {
                                    val editing = editTarget
                                    if (editing != null) {
                                        viewModel.editMessage(chatRoom, editing.first, text)
                                        editTarget = null
                                    } else {
                                        viewModel.sendEncryptedMessage(
                                            chatRoom = chatRoom,
                                            plaintext = text,
                                            replyToMessage = replyTarget?.first,
                                            replyToPlainPreview = replyTarget?.second,
                                        )
                                        replyTarget = null
                                    }
                                    inputText = ""
                                }
                            },
                            modifier = Modifier.size(48.dp).testTag("send_message_button"),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = strings.send)
                        }
                    }
                }
            }
        }
    }

    // Permission Rationale Dialog
    val rationaleFeature = showRationaleFor
    if (rationaleFeature != null) {
        val rationaleText = when (rationaleFeature) {
            PendingPermissionFeature.MIC_VOICE, PendingPermissionFeature.CALL_AUDIO -> strings.micRationale
            PendingPermissionFeature.CAMERA_PHOTO, PendingPermissionFeature.CALL_VIDEO -> strings.cameraRationale
            PendingPermissionFeature.LOCATION_ONCE, PendingPermissionFeature.LOCATION_LIVE -> strings.locationRationale
        }
        PermissionRationaleDialog(
            rationaleMessage = rationaleText,
            permanentlyDenied = permanentlyDenied,
            onRequestPermission = {
                showRationaleFor = null
                pendingFeature = rationaleFeature
                when (rationaleFeature) {
                    PendingPermissionFeature.MIC_VOICE, PendingPermissionFeature.CALL_AUDIO ->
                        singlePermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    PendingPermissionFeature.CAMERA_PHOTO ->
                        singlePermLauncher.launch(Manifest.permission.CAMERA)
                    PendingPermissionFeature.CALL_VIDEO ->
                        multiPermLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                    PendingPermissionFeature.LOCATION_ONCE, PendingPermissionFeature.LOCATION_LIVE ->
                        singlePermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            },
            onDismiss = { showRationaleFor = null },
        )
    }

    // Forward Message Dialog
    if (forwardTarget != null) {
        val allChats = (chatsState as? UiState.Success)?.data ?: emptyList()
        AlertDialog(
            onDismissRequest = { forwardTarget = null },
            title = { Text(strings.forward) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(allChats, key = { it.id }) { targetRoom ->
                        ListItem(
                            modifier = Modifier.clickable {
                                viewModel.sendEncryptedMessage(
                                    chatRoom = targetRoom,
                                    plaintext = forwardTarget.orEmpty(),
                                    forwarded = true,
                                )
                                forwardTarget = null
                            },
                            headlineContent = { Text(targetRoom.title) },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { forwardTarget = null }) { Text(strings.cancel) } },
        )
    }

    // E2EE Fingerprint Dialog
    if (showE2eeDialog) {
        val myPub = viewModel.cryptoManager.getPublicKeyBase64(viewModel.currentUserId)
        val fp = viewModel.cryptoManager.getFingerprint(myPub)
        AlertDialog(
            onDismissRequest = { showE2eeDialog = false },
            icon = { Icon(Icons.Rounded.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(strings.e2eeFingerprintTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.e2eeDescription)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = fp,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showE2eeDialog = false }) { Text(strings.close) } },
        )
    }

    // Group Admin Settings Dialog
    if (showGroupSettings && isGroup) {
        GroupSettingsDialog(
            chatRoom = chatRoom,
            myUid = viewModel.currentUserId,
            directory = directory,
            onDismiss = { showGroupSettings = false },
            onSave = { title, members, admins, onlyEdit, onlySend ->
                viewModel.updateGroupSettings(chatRoom.id, title, members, admins, onlyEdit, onlySend)
                showGroupSettings = false
            },
        )
    }

    // Report User Dialog
    if (showReportDialog && peerProfile != null) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text(strings.reportUser) },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    placeholder = { Text("Reason...") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (reason.trim().length >= 3) {
                            viewModel.submitAbuseReport(peerProfile.userId, reason)
                            showReportDialog = false
                        }
                    }
                ) { Text(strings.confirm) }
            },
            dismissButton = { TextButton(onClick = { showReportDialog = false }) { Text(strings.cancel) } },
        )
    }
}

@Composable
private fun GroupSettingsDialog(
    chatRoom: ChatRoom,
    myUid: String,
    directory: List<com.example.data.model.DirectoryProfile>,
    onDismiss: () -> Unit,
    onSave: (String, List<String>, List<String>, Boolean, Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    val amAdmin = myUid in chatRoom.adminUids
    var title by remember { mutableStateOf(chatRoom.title) }
    var onlySend by remember { mutableStateOf(chatRoom.onlyAdminsCanSend) }
    var onlyEdit by remember { mutableStateOf(chatRoom.onlyAdminsCanEdit) }
    val members = remember { mutableStateListOf<String>().apply { addAll(chatRoom.memberUids) } }
    val admins = remember { mutableStateListOf<String>().apply { addAll(chatRoom.adminUids) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.groupSettingsTitle) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    enabled = amAdmin || !chatRoom.onlyAdminsCanEdit,
                    label = { Text(strings.groupName) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (amAdmin) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = onlySend, onCheckedChange = { onlySend = it })
                        Text(strings.onlyAdminsCanSend)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = onlyEdit, onCheckedChange = { onlyEdit = it })
                        Text(strings.onlyAdminsCanEdit)
                    }
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    items(members.toList()) { uid ->
                        val name = directory.firstOrNull { it.userId == uid }?.displayName ?: uid.take(10)
                        val isAdm = uid in admins
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(if (isAdm) "$name ★" else name)
                            if (amAdmin && uid != myUid) {
                                Row {
                                    TextButton(onClick = {
                                        if (isAdm) admins.remove(uid) else admins.add(uid)
                                    }) { Text(strings.makeAdmin) }
                                    TextButton(onClick = {
                                        members.remove(uid)
                                        admins.remove(uid)
                                    }) { Text(strings.removeMember, color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (amAdmin || !chatRoom.onlyAdminsCanEdit) {
                Button(onClick = { onSave(title, members.toList(), admins.toList(), onlyEdit, onlySend) }) {
                    Text(strings.save)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.close) } },
    )
}
