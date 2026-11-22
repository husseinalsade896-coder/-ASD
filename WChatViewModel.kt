package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.crypto.E2eeCryptoManager
import com.example.core.i18n.AppLanguage
import com.example.data.firebase.WChatRepository
import com.example.data.model.*
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.crypto.SecretKey

class WChatViewModel(
    private val repository: WChatRepository,
    val cryptoManager: E2eeCryptoManager,
    val currentUserId: String,
    val currentUserEmail: String,
    val isEmailVerified: Boolean,
) : ViewModel() {

    val isAdmin: Boolean =
        isEmailVerified && currentUserEmail.equals("husseinalsade896@gmail.com", ignoreCase = true)

    private val _selectedLanguage = MutableStateFlow(AppLanguage.ARABIC)
    val selectedLanguage: StateFlow<AppLanguage> = _selectedLanguage.asStateFlow()

    private val _darkTheme = MutableStateFlow(false)
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    private val _activeChatId = MutableStateFlow<String?>(null)
    val activeChatId: StateFlow<String?> = _activeChatId.asStateFlow()

    private val _activeCall = MutableStateFlow<CallRecord?>(null)
    val activeCall: StateFlow<CallRecord?> = _activeCall.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    init {
        bootstrapUser()
    }

    private fun bootstrapUser() {
        val user = Firebase.auth.currentUser ?: return
        val publicKey = cryptoManager.getPublicKeyBase64(currentUserId)
        viewModelScope.launch {
            repository.ensureUserBootstrap(user, publicKey)
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    private fun <T> Flow<T>.toUiState(): StateFlow<UiState<T>> =
        map<T, UiState<T>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Stream error", rootError)
                emit(UiState.Error(rootError.localizedMessage ?: "Firestore stream error"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading,
            )

    val directoryState: StateFlow<UiState<List<DirectoryProfile>>> =
        repository.observeDirectory(currentUserId, includeBannedForAdmin = isAdmin).toUiState()

    val privateAccountState: StateFlow<UiState<PrivateUserAccount?>> =
        repository.observePrivateAccount(currentUserId)
            .onEach { acct ->
                if (acct != null) {
                    _selectedLanguage.value = AppLanguage.fromCode(acct.languageCode)
                    _darkTheme.value = acct.darkTheme
                }
            }
            .toUiState()

    val devicesState: StateFlow<UiState<List<LinkedDevice>>> =
        repository.observeDevices(currentUserId).toUiState()

    val chatsState: StateFlow<UiState<List<ChatRoom>>> =
        repository.observeChats(currentUserId).toUiState()

    val statusesState: StateFlow<UiState<List<StatusStory>>> =
        repository.observeStatuses(currentUserId).toUiState()

    val callsState: StateFlow<UiState<List<CallRecord>>> =
        repository.observeCalls(currentUserId)
            .onEach { list ->
                val incomingRinging = list.firstOrNull {
                    it.status == "RINGING" && it.callerId != currentUserId
                }
                if (incomingRinging != null && _activeCall.value == null) {
                    _activeCall.value = incomingRinging
                }
                val current = _activeCall.value
                if (current != null) {
                    val refreshed = list.firstOrNull { it.id == current.id }
                    if (refreshed != null) {
                        if (refreshed.status in listOf("ENDED", "DECLINED", "MISSED")) {
                            _activeCall.value = null
                        } else {
                            _activeCall.value = refreshed
                        }
                    }
                }
            }
            .toUiState()

    val reportsState: StateFlow<UiState<List<AbuseReport>>> =
        repository.observeReports(currentUserId, isAdmin).toUiState()

    val systemConfigState: StateFlow<UiState<SystemConfig?>> =
        repository.observeSystemConfig(currentUserId).toUiState()

    val adminAllUsersState: StateFlow<UiState<List<PrivateUserAccount>>> =
        if (isAdmin) repository.observeAllUsersForAdmin(currentUserId).toUiState()
        else MutableStateFlow(UiState.Success(emptyList()))

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeMessagesState: StateFlow<UiState<List<ChatMessage>>> =
        _activeChatId.flatMapLatest { chatId ->
            if (chatId.isNullOrBlank()) flowOf(emptyList())
            else repository.observeMessages(chatId, currentUserId)
        }.toUiState()

    fun setLanguage(language: AppLanguage) {
        _selectedLanguage.value = language
        val acct = (privateAccountState.value as? UiState.Success)?.data
        val myDir = currentDirectoryProfile()
        if (acct != null && myDir != null) {
            viewModelScope.launch {
                repository.updatePrivacyAndPreferences(
                    uid = currentUserId,
                    lastSeenPrivacy = myDir.lastSeenPrivacy,
                    avatarPrivacy = myDir.avatarPrivacy,
                    readReceiptsEnabled = myDir.readReceiptsEnabled,
                    twoFactorEnabled = myDir.twoFactorEnabled,
                    twoStepPinHash = null,
                    notificationsEnabled = acct.notificationsEnabled,
                    notificationTone = acct.notificationTone,
                    languageCode = language.code,
                    darkTheme = _darkTheme.value,
                )
            }
        }
    }

    fun setDarkTheme(enabled: Boolean) {
        _darkTheme.value = enabled
        val acct = (privateAccountState.value as? UiState.Success)?.data
        val myDir = currentDirectoryProfile()
        if (acct != null && myDir != null) {
            viewModelScope.launch {
                repository.updatePrivacyAndPreferences(
                    uid = currentUserId,
                    lastSeenPrivacy = myDir.lastSeenPrivacy,
                    avatarPrivacy = myDir.avatarPrivacy,
                    readReceiptsEnabled = myDir.readReceiptsEnabled,
                    twoFactorEnabled = myDir.twoFactorEnabled,
                    twoStepPinHash = null,
                    notificationsEnabled = acct.notificationsEnabled,
                    notificationTone = acct.notificationTone,
                    languageCode = _selectedLanguage.value.code,
                    darkTheme = enabled,
                )
            }
        }
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun setActiveChat(chatId: String?) {
        _activeChatId.value = chatId
    }

    fun currentDirectoryProfile(): DirectoryProfile? =
        (directoryState.value as? UiState.Success)?.data?.firstOrNull { it.userId == currentUserId }

    fun directoryList(): List<DirectoryProfile> =
        (directoryState.value as? UiState.Success)?.data ?: emptyList()

    fun resolveChatKey(chatRoom: ChatRoom): SecretKey {
        val dir = directoryList()
        return if (chatRoom.chatType == "DIRECT") {
            val peerId = chatRoom.memberUids.firstOrNull { it != currentUserId } ?: currentUserId
            val peerPub = dir.firstOrNull { it.userId == peerId }?.publicKeyBase64.orEmpty()
            if (peerPub.isNotBlank()) {
                cryptoManager.deriveDirectKey(currentUserId, peerPub, chatRoom.id)
            } else {
                cryptoManager.deriveGroupKey(chatRoom.id, listOf(currentUserId))
            }
        } else {
            val memberKeys = chatRoom.memberUids.mapNotNull { uid ->
                dir.firstOrNull { it.userId == uid }?.publicKeyBase64?.takeIf { it.isNotBlank() }
            }
            cryptoManager.deriveGroupKey(chatRoom.id, memberKeys.ifEmpty { listOf(chatRoom.id) })
        }
    }

    fun decryptMessageText(chatRoom: ChatRoom, msg: ChatMessage): String {
        if (msg.deletedForAll) return "🗑️"
        val key = resolveChatKey(chatRoom)
        return cryptoManager.decrypt(msg.ciphertext, msg.iv, key)
    }

    fun openDirectChat(peer: DirectoryProfile, onReady: (String) -> Unit) {
        viewModelScope.launch {
            repository.createOrOpenDirectChat(currentUserId, peer)
                .onSuccess { chatId ->
                    _activeChatId.value = chatId
                    onReady(chatId)
                }
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun createGroup(title: String, memberUids: List<String>, onReady: (String) -> Unit) {
        viewModelScope.launch {
            repository.createGroupChat(currentUserId, title, memberUids)
                .onSuccess { chatId ->
                    _activeChatId.value = chatId
                    onReady(chatId)
                }
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun sendEncryptedMessage(
        chatRoom: ChatRoom,
        plaintext: String,
        messageType: String = "TEXT",
        mediaName: String? = null,
        mediaSizeBytes: Long? = null,
        mediaPreviewBase64: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        liveUntilEpochMs: Long? = null,
        replyToMessage: ChatMessage? = null,
        replyToPlainPreview: String? = null,
        forwarded: Boolean = false,
    ) {
        val myName = currentDirectoryProfile()?.displayName
            ?: Firebase.auth.currentUser?.displayName
            ?: "W Chat User"
        val key = resolveChatKey(chatRoom)
        val encrypted = cryptoManager.encrypt(plaintext, key)
        val summary = when (messageType) {
            "IMAGE" -> "📷 ${mediaName ?: "Photo"}"
            "VIDEO" -> "🎥 ${mediaName ?: "Video"}"
            "FILE" -> "📎 ${mediaName ?: "Document"}"
            "VOICE" -> "🎤 Voice message"
            "LOCATION" -> "📍 Location"
            "LIVE_LOCATION" -> "🛰️ Live Location (15m)"
            else -> "🔒 ${plaintext.take(40)}"
        }
        viewModelScope.launch {
            repository.sendMessage(
                chatRoom = chatRoom,
                senderId = currentUserId,
                senderName = myName,
                messageType = messageType,
                ciphertext = encrypted.ciphertextBase64,
                iv = encrypted.ivBase64,
                previewSummary = summary,
                mediaName = mediaName,
                mediaSizeBytes = mediaSizeBytes,
                mediaPreviewBase64 = mediaPreviewBase64,
                latitude = latitude,
                longitude = longitude,
                liveUntilEpochMs = liveUntilEpochMs,
                replyToMessageId = replyToMessage?.id,
                replyToPreview = replyToPlainPreview?.take(120),
                forwarded = forwarded,
            ).onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun editMessage(chatRoom: ChatRoom, message: ChatMessage, newPlaintext: String) {
        val key = resolveChatKey(chatRoom)
        val encrypted = cryptoManager.encrypt(newPlaintext, key)
        viewModelScope.launch {
            repository.editMessage(chatRoom.id, message.id, encrypted.ciphertextBase64, encrypted.ivBase64)
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun deleteMessageForAll(chatId: String, messageId: String) {
        viewModelScope.launch {
            repository.deleteMessageForAll(chatId, messageId)
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun markMessageRead(chatId: String, message: ChatMessage) {
        val receiptsOn = currentDirectoryProfile()?.readReceiptsEnabled ?: true
        if (!receiptsOn) return
        viewModelScope.launch {
            repository.markMessageRead(chatId, message, currentUserId)
        }
    }

    fun togglePin(chatRoom: ChatRoom) {
        viewModelScope.launch {
            repository.togglePinChat(chatRoom, currentUserId)
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun toggleArchive(chatRoom: ChatRoom) {
        viewModelScope.launch {
            repository.toggleArchiveChat(chatRoom, currentUserId)
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun setTyping(chatRoom: ChatRoom, typing: Boolean) {
        viewModelScope.launch {
            repository.setTypingState(chatRoom, currentUserId, typing)
        }
    }

    fun updateGroupSettings(
        chatId: String,
        title: String,
        memberUids: List<String>,
        adminUids: List<String>,
        onlyAdminsCanEdit: Boolean,
        onlyAdminsCanSend: Boolean,
    ) {
        viewModelScope.launch {
            repository.updateGroupSettings(chatId, title, memberUids, adminUids, onlyAdminsCanEdit, onlyAdminsCanSend)
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun postStatus(
        statusType: String,
        textContent: String,
        backgroundHex: String,
        mediaPreviewBase64: String?,
        privacy: String,
    ) {
        val myName = currentDirectoryProfile()?.displayName ?: "W Chat User"
        viewModelScope.launch {
            repository.postStatus(currentUserId, myName, statusType, textContent, backgroundHex, mediaPreviewBase64, privacy)
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun markStatusViewed(status: StatusStory) {
        viewModelScope.launch {
            repository.markStatusViewed(status, currentUserId)
        }
    }

    fun startCall(participantUids: List<String>, callType: String) {
        val myName = currentDirectoryProfile()?.displayName ?: "W Chat User"
        viewModelScope.launch {
            repository.startCall(currentUserId, myName, participantUids, callType)
                .onSuccess { _activeCall.value = it }
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun answerCall(call: CallRecord) {
        val answer = "v=0\no=wchat-ans ${System.currentTimeMillis()} 1 IN IP4 0.0.0.0\ns=Answer\nt=0 0\n"
        viewModelScope.launch {
            repository.updateCallStatus(call.id, "ACTIVE", 0L, answer)
                .onSuccess { _activeCall.value = call.copy(status = "ACTIVE", sdpAnswer = answer) }
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun endOrDeclineCall(call: CallRecord, durationSeconds: Long, declined: Boolean = false) {
        val finalStatus = if (declined) "DECLINED" else "ENDED"
        _activeCall.value = null
        viewModelScope.launch {
            repository.updateCallStatus(call.id, finalStatus, durationSeconds)
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun updateProfile(
        displayName: String,
        username: String,
        bio: String,
        countryCode: String,
        phoneNumber: String,
        phoneVerified: Boolean,
    ) {
        viewModelScope.launch {
            repository.updateProfile(currentUserId, displayName, username, bio, countryCode, phoneNumber, phoneVerified)
                .onSuccess { _statusBanner.value = "✓ Saved" }
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun updatePrivacyAndNotifications(
        lastSeenPrivacy: String,
        avatarPrivacy: String,
        readReceiptsEnabled: Boolean,
        twoFactorEnabled: Boolean,
        twoStepPinRaw: String?,
        notificationsEnabled: Boolean,
        notificationTone: String,
    ) {
        val pinHash = twoStepPinRaw?.takeIf { it.isNotBlank() }?.let {
            cryptoManager.hashTwoStepPin(it, currentUserId)
        }
        viewModelScope.launch {
            repository.updatePrivacyAndPreferences(
                uid = currentUserId,
                lastSeenPrivacy = lastSeenPrivacy,
                avatarPrivacy = avatarPrivacy,
                readReceiptsEnabled = readReceiptsEnabled,
                twoFactorEnabled = twoFactorEnabled,
                twoStepPinHash = pinHash,
                notificationsEnabled = notificationsEnabled,
                notificationTone = notificationTone,
                languageCode = _selectedLanguage.value.code,
                darkTheme = _darkTheme.value,
            ).onSuccess { _statusBanner.value = "✓ Saved" }
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun toggleBlockUser(targetUid: String) {
        val currentBlocked = (privateAccountState.value as? UiState.Success)?.data?.blockedUids ?: emptyList()
        viewModelScope.launch {
            repository.toggleBlockUser(currentUserId, currentBlocked, targetUid)
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun removeLinkedDevice(deviceId: String) {
        viewModelScope.launch {
            repository.removeDevice(currentUserId, deviceId)
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun submitAbuseReport(reportedUserId: String, reason: String) {
        viewModelScope.launch {
            repository.submitReport(currentUserId, reportedUserId, reason)
                .onSuccess { _statusBanner.value = "✓ Report submitted" }
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun adminToggleBan(targetUid: String, banned: Boolean) {
        viewModelScope.launch {
            repository.adminSetUserBan(targetUid, banned)
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun adminResolveReport(reportId: String, notes: String) {
        viewModelScope.launch {
            repository.adminResolveReport(reportId, "RESOLVED", notes)
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun adminSaveSystemConfig(maintenanceMode: Boolean, registrationEnabled: Boolean, maxUploadMb: Long) {
        val existing = (systemConfigState.value as? UiState.Success)?.data
        viewModelScope.launch {
            repository.adminSaveSystemConfig(
                adminUid = currentUserId,
                maintenanceMode = maintenanceMode,
                registrationEnabled = registrationEnabled,
                maxUploadMb = maxUploadMb,
                lastBackupEpochMs = existing?.lastBackupEpochMs ?: System.currentTimeMillis(),
                backupStatus = existing?.backupStatus ?: "HEALTHY",
            ).onSuccess { _statusBanner.value = "✓ Config updated" }
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    fun adminTriggerBackup() {
        val existing = (systemConfigState.value as? UiState.Success)?.data
        viewModelScope.launch {
            repository.adminSaveSystemConfig(
                adminUid = currentUserId,
                maintenanceMode = existing?.maintenanceMode ?: false,
                registrationEnabled = existing?.registrationEnabled ?: true,
                maxUploadMb = existing?.maxUploadMb ?: 25L,
                lastBackupEpochMs = System.currentTimeMillis(),
                backupStatus = "HEALTHY",
            ).onSuccess { _statusBanner.value = "✓ Cloud snapshot backed up" }
                .onFailure { _statusBanner.value = it.localizedMessage }
        }
    }

    suspend fun setOfflineBeforeSignOut() {
        repository.setOnlineStatus(currentUserId, "OFFLINE")
    }

    private companion object {
        const val TAG = "WChatViewModel"
    }
}
