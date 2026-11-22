package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

@Keep
@IgnoreExtraProperties
data class DirectoryProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val username: String = "",
    val displayName: String = "",
    val bio: String = "",
    val avatarColorHex: String = "#0B7A67",
    val publicKeyBase64: String = "",
    val countryCode: String = "+967",
    val onlineStatus: String = "ONLINE",
    val lastSeenPrivacy: String = "EVERYONE",
    val avatarPrivacy: String = "EVERYONE",
    val readReceiptsEnabled: Boolean = true,
    val twoFactorEnabled: Boolean = false,
    val banned: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class PrivateUserAccount(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val phoneVerified: Boolean = false,
    val twoStepPinHash: String = "",
    val notificationTone: String = "Emerald Chime",
    val notificationsEnabled: Boolean = true,
    val blockedUids: List<String> = emptyList(),
    val languageCode: String = "ar",
    val darkTheme: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class LinkedDevice(
    @DocumentId val id: String = "",
    val userId: String = "",
    val deviceName: String = "",
    val platform: String = "Android",
    val active: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class ChatRoom(
    @DocumentId val id: String = "",
    val chatType: String = "DIRECT",
    val title: String = "",
    val creatorId: String = "",
    val memberUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    val pinnedByUids: List<String> = emptyList(),
    val archivedByUids: List<String> = emptyList(),
    val typingUids: List<String> = emptyList(),
    val lastMessagePreview: String = "",
    val lastMessageSenderId: String = "",
    val onlyAdminsCanEdit: Boolean = false,
    val onlyAdminsCanSend: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val chatId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val memberUids: List<String> = emptyList(),
    val messageType: String = "TEXT",
    val ciphertext: String = "",
    val iv: String = "",
    val encrypted: Boolean = true,
    val mediaName: String? = null,
    val mediaSizeBytes: Long? = null,
    val mediaPreviewBase64: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val liveUntilEpochMs: Long? = null,
    val replyToMessageId: String? = null,
    val replyToPreview: String? = null,
    val forwarded: Boolean = false,
    val edited: Boolean = false,
    val deletedForAll: Boolean = false,
    val readByUids: List<String> = emptyList(),
    val deliveredToUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class StatusStory(
    @DocumentId val id: String = "",
    val ownerId: String = "",
    val ownerName: String = "",
    val statusType: String = "TEXT",
    val textContent: String = "",
    val backgroundHex: String = "#0B7A67",
    val mediaPreviewBase64: String? = null,
    val privacy: String = "EVERYONE",
    val viewerUids: List<String> = emptyList(),
    val expiresAtEpochMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class CallRecord(
    @DocumentId val id: String = "",
    val callerId: String = "",
    val callerName: String = "",
    val participantUids: List<String> = emptyList(),
    val callType: String = "VOICE",
    val status: String = "RINGING",
    val sdpOffer: String? = null,
    val sdpAnswer: String? = null,
    val durationSeconds: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class AbuseReport(
    @DocumentId val id: String = "",
    val reporterId: String = "",
    val reportedUserId: String = "",
    val reason: String = "",
    val status: String = "OPEN",
    val adminNotes: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class SystemConfig(
    @DocumentId val id: String = "",
    val maintenanceMode: Boolean = false,
    val maxUploadMb: Long = 25L,
    val registrationEnabled: Boolean = true,
    val lastBackupEpochMs: Long = 0L,
    val backupStatus: String = "HEALTHY",
    val updatedBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)
