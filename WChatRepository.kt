package com.example.data.firebase

import android.content.Context
import android.os.Build
import com.example.R
import com.example.data.model.*
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class WChatRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")

    private fun isSessionActive(uid: String): Boolean = auth.currentUser?.uid == uid

    private fun <T> Flow<T>.logFirestoreErrors(uid: String, path: String, op: OperationType): Flow<T> =
        catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val toLog = firestoreError ?: (error as? Exception)
            if (toLog != null && isSessionActive(uid)) {
                handleFirestoreError(toLog, op, path)
            }
            throw error
        }

    // --- BOOTSTRAP PROFILE & DEVICE ---
    suspend fun ensureUserBootstrap(
        user: FirebaseUser,
        publicKeyBase64: String,
    ): Result<Unit> {
        val uid = user.uid
        return try {
            requireUserId()
            val dirRef = db.collection("directory").document(uid)
            val dirSnap = dirRef.get().await()
            val safeName = (user.displayName ?: user.email?.substringBefore("@") ?: "W Chat User")
                .take(60).ifBlank { "W Chat User" }
            val safeUsername = (user.email?.substringBefore("@") ?: "user_${uid.take(6)}")
                .lowercase().replace(Regex("[^a-z0-9_.]"), "").take(30).ifBlank { "user_${uid.take(6)}" }

            if (!dirSnap.exists()) {
                val dirPayload = mapOf(
                    "userId" to uid,
                    "username" to safeUsername,
                    "displayName" to safeName,
                    "bio" to "Hey there! I am using W Chat.",
                    "avatarColorHex" to "#0B7A67",
                    "publicKeyBase64" to publicKeyBase64,
                    "countryCode" to "+967",
                    "onlineStatus" to "ONLINE",
                    "lastSeenPrivacy" to "EVERYONE",
                    "avatarPrivacy" to "EVERYONE",
                    "readReceiptsEnabled" to true,
                    "twoFactorEnabled" to false,
                    "banned" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                dirRef.set(dirPayload).await()
            } else {
                dirRef.update(
                    mapOf(
                        "publicKeyBase64" to publicKeyBase64,
                        "onlineStatus" to "ONLINE",
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            }

            val privRef = db.collection("users").document(uid)
            val privSnap = privRef.get().await()
            if (!privSnap.exists()) {
                val privPayload = mapOf(
                    "userId" to uid,
                    "email" to (user.email ?: ""),
                    "phoneNumber" to "",
                    "phoneVerified" to false,
                    "notificationTone" to "Emerald Chime",
                    "notificationsEnabled" to true,
                    "blockedUids" to emptyList<String>(),
                    "languageCode" to "ar",
                    "darkTheme" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                privRef.set(privPayload).await()
            }

            // Register this Android device under users/{uid}/devices/{deviceId}
            val deviceId = "android_${Build.MODEL.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(32)}"
            val devRef = privRef.collection("devices").document(deviceId)
            val devSnap = devRef.get().await()
            if (!devSnap.exists()) {
                devRef.set(
                    mapOf(
                        "userId" to uid,
                        "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}".take(80),
                        "platform" to "Android ${Build.VERSION.RELEASE}",
                        "active" to true,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            } else {
                devRef.update(
                    mapOf(
                        "active" to true,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "directory/$uid")
            Result.failure(e)
        }
    }

    suspend fun setOnlineStatus(uid: String, status: String): Result<Unit> =
        try {
            requireUserId()
            db.collection("directory").document(uid)
                .update(mapOf("onlineStatus" to status, "updatedAt" to FieldValue.serverTimestamp()))
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "directory/$uid")
            Result.failure(e)
        }

    // --- REALTIME STREAMS ---
    fun observeDirectory(uid: String, includeBannedForAdmin: Boolean = false): Flow<List<DirectoryProfile>> = flow {
        requireUserId()
        val query = if (includeBannedForAdmin) {
            db.collection("directory")
        } else {
            db.collection("directory").whereEqualTo("banned", false)
        }
        emitAll(
            query.snapshots()
                .map { it.toObjects(DirectoryProfile::class.java, ServerTimestampBehavior.ESTIMATE) }
                .logFirestoreErrors(uid, "directory", OperationType.LIST)
        )
    }

    fun observePrivateAccount(uid: String): Flow<PrivateUserAccount?> = flow {
        requireUserId()
        emitAll(
            db.collection("users").document(uid).snapshots()
                .map { it.toObject(PrivateUserAccount::class.java, ServerTimestampBehavior.ESTIMATE) }
                .logFirestoreErrors(uid, "users/$uid", OperationType.GET)
        )
    }

    fun observeDevices(uid: String): Flow<List<LinkedDevice>> = flow {
        requireUserId()
        emitAll(
            db.collection("users").document(uid).collection("devices").snapshots()
                .map { it.toObjects(LinkedDevice::class.java, ServerTimestampBehavior.ESTIMATE) }
                .logFirestoreErrors(uid, "users/$uid/devices", OperationType.LIST)
        )
    }

    fun observeChats(uid: String): Flow<List<ChatRoom>> = flow {
        requireUserId()
        emitAll(
            db.collection("chats")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(ChatRoom::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.updatedAt?.toDate()?.time ?: 0L }
                }
                .logFirestoreErrors(uid, "chats", OperationType.LIST)
        )
    }

    fun observeMessages(chatId: String, uid: String): Flow<List<ChatMessage>> = flow {
        requireUserId()
        emitAll(
            db.collection("chats").document(chatId).collection("messages")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(ChatMessage::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.createdAt?.toDate()?.time ?: Long.MAX_VALUE }
                }
                .logFirestoreErrors(uid, "chats/$chatId/messages", OperationType.LIST)
        )
    }

    fun observeStatuses(uid: String): Flow<List<StatusStory>> = flow {
        requireUserId()
        val publicFlow = db.collection("statuses")
            .whereIn("privacy", listOf("EVERYONE", "CONTACTS"))
            .snapshots()
            .map { it.toObjects(StatusStory::class.java, ServerTimestampBehavior.ESTIMATE) }
        val ownFlow = db.collection("statuses")
            .whereEqualTo("ownerId", uid)
            .snapshots()
            .map { it.toObjects(StatusStory::class.java, ServerTimestampBehavior.ESTIMATE) }

        val nowMs = System.currentTimeMillis()
        emitAll(
            combine(publicFlow, ownFlow) { pub, own ->
                (pub + own)
                    .distinctBy { it.id }
                    .filter { it.expiresAtEpochMs > nowMs }
                    .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
            }.logFirestoreErrors(uid, "statuses", OperationType.LIST)
        )
    }

    fun observeCalls(uid: String): Flow<List<CallRecord>> = flow {
        requireUserId()
        emitAll(
            db.collection("calls")
                .whereArrayContains("participantUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(CallRecord::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                }
                .logFirestoreErrors(uid, "calls", OperationType.LIST)
        )
    }

    fun observeReports(uid: String, isAdmin: Boolean): Flow<List<AbuseReport>> = flow {
        requireUserId()
        val query = if (isAdmin) {
            db.collection("reports")
        } else {
            db.collection("reports").whereEqualTo("reporterId", uid)
        }
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(AbuseReport::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                }
                .logFirestoreErrors(uid, "reports", OperationType.LIST)
        )
    }

    fun observeSystemConfig(uid: String): Flow<SystemConfig?> = flow {
        requireUserId()
        emitAll(
            db.collection("system").document("config").snapshots()
                .map { it.toObject(SystemConfig::class.java, ServerTimestampBehavior.ESTIMATE) }
                .logFirestoreErrors(uid, "system/config", OperationType.GET)
        )
    }

    // --- ADMIN METRICS ---
    fun observeAllUsersForAdmin(uid: String): Flow<List<PrivateUserAccount>> = flow {
        requireUserId()
        emitAll(
            db.collection("users").snapshots()
                .map { it.toObjects(PrivateUserAccount::class.java, ServerTimestampBehavior.ESTIMATE) }
                .logFirestoreErrors(uid, "users", OperationType.LIST)
        )
    }

    // --- CHAT & MESSAGE OPERATIONS ---
    suspend fun createOrOpenDirectChat(
        myUid: String,
        peer: DirectoryProfile,
    ): Result<String> = try {
        requireUserId()
        val sortedIds = listOf(myUid, peer.userId).sorted()
        val deterministicId = "dm_${sortedIds[0].take(16)}_${sortedIds[1].take(16)}"
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val ref = db.collection("chats").document(deterministicId)
        val snap = ref.get().await()
        if (!snap.exists()) {
            val payload = mapOf(
                "chatType" to "DIRECT",
                "title" to peer.displayName.take(80).ifBlank { "Chat" },
                "creatorId" to myUid,
                "memberUids" to sortedIds,
                "adminUids" to sortedIds,
                "pinnedByUids" to emptyList<String>(),
                "archivedByUids" to emptyList<String>(),
                "typingUids" to emptyList<String>(),
                "lastMessagePreview" to "🔒 E2EE",
                "lastMessageSenderId" to myUid,
                "onlyAdminsCanEdit" to false,
                "onlyAdminsCanSend" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            ref.set(payload).await()
        }
        Result.success(deterministicId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.CREATE, "chats")
        Result.failure(e)
    }

    suspend fun createGroupChat(
        myUid: String,
        title: String,
        memberUids: List<String>,
    ): Result<String> = try {
        requireUserId()
        val cleanTitle = title.trim().take(80)
        require(cleanTitle.isNotBlank()) { "Group name cannot be empty" }
        val members = (memberUids + myUid).distinct().take(50)
        val chatId = "grp_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val payload = mapOf(
            "chatType" to "GROUP",
            "title" to cleanTitle,
            "creatorId" to myUid,
            "memberUids" to members,
            "adminUids" to listOf(myUid),
            "pinnedByUids" to emptyList<String>(),
            "archivedByUids" to emptyList<String>(),
            "typingUids" to emptyList<String>(),
            "lastMessagePreview" to "🔒 Group created",
            "lastMessageSenderId" to myUid,
            "onlyAdminsCanEdit" to false,
            "onlyAdminsCanSend" to false,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        db.collection("chats").document(chatId).set(payload).await()
        Result.success(chatId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.CREATE, "chats")
        Result.failure(e)
    }

    suspend fun updateGroupSettings(
        chatId: String,
        title: String,
        memberUids: List<String>,
        adminUids: List<String>,
        onlyAdminsCanEdit: Boolean,
        onlyAdminsCanSend: Boolean,
    ): Result<Unit> = try {
        requireUserId()
        db.collection("chats").document(chatId).update(
            mapOf(
                "title" to title.trim().take(80).ifBlank { "Group" },
                "memberUids" to memberUids.distinct().take(50),
                "adminUids" to adminUids.distinct().take(20),
                "onlyAdminsCanEdit" to onlyAdminsCanEdit,
                "onlyAdminsCanSend" to onlyAdminsCanSend,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.UPDATE, "chats/$chatId")
        Result.failure(e)
    }

    suspend fun sendMessage(
        chatRoom: ChatRoom,
        senderId: String,
        senderName: String,
        messageType: String,
        ciphertext: String,
        iv: String,
        previewSummary: String,
        mediaName: String? = null,
        mediaSizeBytes: Long? = null,
        mediaPreviewBase64: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        liveUntilEpochMs: Long? = null,
        replyToMessageId: String? = null,
        replyToPreview: String? = null,
        forwarded: Boolean = false,
    ): Result<Unit> = try {
        requireUserId()
        val msgId = "msg_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val payload = mapOf(
            "chatId" to chatRoom.id,
            "senderId" to senderId,
            "senderName" to senderName.take(60).ifBlank { "User" },
            "memberUids" to chatRoom.memberUids.take(50),
            "messageType" to messageType,
            "ciphertext" to ciphertext.take(12000),
            "iv" to iv.take(128),
            "encrypted" to true,
            "mediaName" to mediaName?.take(200),
            "mediaSizeBytes" to mediaSizeBytes,
            "mediaPreviewBase64" to mediaPreviewBase64?.take(350000),
            "latitude" to latitude,
            "longitude" to longitude,
            "liveUntilEpochMs" to liveUntilEpochMs,
            "replyToMessageId" to replyToMessageId?.take(128),
            "replyToPreview" to replyToPreview?.take(200),
            "forwarded" to forwarded,
            "edited" to false,
            "deletedForAll" to false,
            "readByUids" to listOf(senderId),
            "deliveredToUids" to listOf(senderId),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        ).filterValues { it != null }

        val msgRef = db.collection("chats").document(chatRoom.id).collection("messages").document(msgId)
        msgRef.set(payload).await()

        db.collection("chats").document(chatRoom.id).update(
            mapOf(
                "lastMessagePreview" to previewSummary.take(300),
                "lastMessageSenderId" to senderId,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()

        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.CREATE, "chats/${chatRoom.id}/messages")
        Result.failure(e)
    }

    suspend fun editMessage(
        chatId: String,
        messageId: String,
        newCiphertext: String,
        newIv: String,
    ): Result<Unit> = try {
        requireUserId()
        db.collection("chats").document(chatId).collection("messages").document(messageId).update(
            mapOf(
                "ciphertext" to newCiphertext.take(12000),
                "iv" to newIv.take(128),
                "edited" to true,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.UPDATE, "chats/$chatId/messages/$messageId")
        Result.failure(e)
    }

    suspend fun deleteMessageForAll(
        chatId: String,
        messageId: String,
    ): Result<Unit> = try {
        requireUserId()
        db.collection("chats").document(chatId).collection("messages").document(messageId).update(
            mapOf(
                "ciphertext" to "[deleted]",
                "iv" to "",
                "deletedForAll" to true,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.UPDATE, "chats/$chatId/messages/$messageId")
        Result.failure(e)
    }

    suspend fun markMessageRead(
        chatId: String,
        message: ChatMessage,
        uid: String,
    ): Result<Unit> = try {
        requireUserId()
        if (uid in message.readByUids) return Result.success(Unit)
        db.collection("chats").document(chatId).collection("messages").document(message.id).update(
            mapOf(
                "readByUids" to (message.readByUids + uid).distinct().take(50),
                "deliveredToUids" to (message.deliveredToUids + uid).distinct().take(50),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.UPDATE, "chats/$chatId/messages/${message.id}")
        Result.failure(e)
    }

    suspend fun togglePinChat(chatRoom: ChatRoom, uid: String): Result<Unit> = try {
        requireUserId()
        val updated = if (uid in chatRoom.pinnedByUids) {
            chatRoom.pinnedByUids - uid
        } else {
            (chatRoom.pinnedByUids + uid).distinct().take(50)
        }
        db.collection("chats").document(chatRoom.id).update(
            mapOf("pinnedByUids" to updated, "updatedAt" to FieldValue.serverTimestamp())
        ).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.UPDATE, "chats/${chatRoom.id}")
        Result.failure(e)
    }

    suspend fun toggleArchiveChat(chatRoom: ChatRoom, uid: String): Result<Unit> = try {
        requireUserId()
        val updated = if (uid in chatRoom.archivedByUids) {
            chatRoom.archivedByUids - uid
        } else {
            (chatRoom.archivedByUids + uid).distinct().take(50)
        }
        db.collection("chats").document(chatRoom.id).update(
            mapOf("archivedByUids" to updated, "updatedAt" to FieldValue.serverTimestamp())
        ).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.UPDATE, "chats/${chatRoom.id}")
        Result.failure(e)
    }

    suspend fun setTypingState(chatRoom: ChatRoom, uid: String, typing: Boolean): Result<Unit> = try {
        requireUserId()
        val currentlyTyping = uid in chatRoom.typingUids
        if (typing == currentlyTyping) return Result.success(Unit)
        val updated = if (typing) (chatRoom.typingUids + uid).distinct().take(50) else (chatRoom.typingUids - uid)
        db.collection("chats").document(chatRoom.id).update(
            mapOf("typingUids" to updated, "updatedAt" to FieldValue.serverTimestamp())
        ).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.UPDATE, "chats/${chatRoom.id}")
        Result.failure(e)
    }

    // --- STATUSES (24H) ---
    suspend fun postStatus(
        ownerId: String,
        ownerName: String,
        statusType: String,
        textContent: String,
        backgroundHex: String,
        mediaPreviewBase64: String?,
        privacy: String,
    ): Result<Unit> = try {
        requireUserId()
        val statusId = "st_${UUID.randomUUID().toString().replace("-", "").take(18)}"
        val expiresAt = System.currentTimeMillis() + 24L * 60L * 60L * 1000L
        val payload = mapOf(
            "ownerId" to ownerId,
            "ownerName" to ownerName.take(60).ifBlank { "User" },
            "statusType" to statusType,
            "textContent" to textContent.take(500),
            "backgroundHex" to backgroundHex.take(20),
            "mediaPreviewBase64" to mediaPreviewBase64?.take(350000),
            "privacy" to privacy,
            "viewerUids" to emptyList<String>(),
            "expiresAtEpochMs" to expiresAt,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        ).filterValues { it != null }
        db.collection("statuses").document(statusId).set(payload).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.CREATE, "statuses")
        Result.failure(e)
    }

    suspend fun markStatusViewed(status: StatusStory, viewerUid: String): Result<Unit> = try {
        requireUserId()
        if (status.ownerId == viewerUid || viewerUid in status.viewerUids) return Result.success(Unit)
        db.collection("statuses").document(status.id).update(
            mapOf(
                "viewerUids" to (status.viewerUids + viewerUid).distinct().take(200),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.UPDATE, "statuses/${status.id}")
        Result.failure(e)
    }

    // --- CALLS (WEBRTC SIGNALING) ---
    suspend fun startCall(
        callerId: String,
        callerName: String,
        participantUids: List<String>,
        callType: String,
    ): Result<CallRecord> = try {
        requireUserId()
        val callId = "call_${UUID.randomUUID().toString().replace("-", "").take(18)}"
        val participants = (participantUids + callerId).distinct().take(20)
        val sdpOffer = "v=0\no=wchat ${System.currentTimeMillis()} 1 IN IP4 0.0.0.0\ns=WChat-$callType\nt=0 0\nm=audio 9 RTP/SAVPF 111\na=ice-ufrag:wchat\n"
        val payload = mapOf(
            "callerId" to callerId,
            "callerName" to callerName.take(60).ifBlank { "User" },
            "participantUids" to participants,
            "callType" to callType,
            "status" to "RINGING",
            "sdpOffer" to sdpOffer,
            "durationSeconds" to 0L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        db.collection("calls").document(callId).set(payload).await()
        Result.success(
            CallRecord(
                id = callId,
                callerId = callerId,
                callerName = callerName,
                participantUids = participants,
                callType = callType,
                status = "RINGING",
                sdpOffer = sdpOffer,
                durationSeconds = 0L,
            )
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.CREATE, "calls")
        Result.failure(e)
    }

    suspend fun updateCallStatus(
        callId: String,
        newStatus: String,
        durationSeconds: Long,
        sdpAnswer: String? = null,
    ): Result<Unit> = try {
        requireUserId()
        val map = mutableMapOf<String, Any>(
            "status" to newStatus,
            "durationSeconds" to durationSeconds.coerceAtLeast(0L),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (sdpAnswer != null) {
            map["sdpAnswer"] = sdpAnswer.take(15000)
        }
        db.collection("calls").document(callId).update(map).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.UPDATE, "calls/$callId")
        Result.failure(e)
    }

    // --- PROFILE, PRIVACY, SAFETY ---
    suspend fun updateProfile(
        uid: String,
        displayName: String,
        username: String,
        bio: String,
        countryCode: String,
        phoneNumber: String,
        phoneVerified: Boolean,
    ): Result<Unit> = try {
        requireUserId()
        db.collection("directory").document(uid).update(
            mapOf(
                "displayName" to displayName.trim().take(60).ifBlank { "W Chat User" },
                "username" to username.trim().lowercase().replace(Regex("[^a-z0-9_.]"), "").take(40).ifBlank { "user_${uid.take(6)}" },
                "bio" to bio.trim().take(200),
                "countryCode" to countryCode.trim().take(10),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        db.collection("users").document(uid).update(
            mapOf(
                "phoneNumber" to phoneNumber.trim().take(30),
                "phoneVerified" to phoneVerified,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.UPDATE, "directory/$uid")
        Result.failure(e)
    }

    suspend fun updatePrivacyAndPreferences(
        uid: String,
        lastSeenPrivacy: String,
        avatarPrivacy: String,
        readReceiptsEnabled: Boolean,
        twoFactorEnabled: Boolean,
        twoStepPinHash: String?,
        notificationsEnabled: Boolean,
        notificationTone: String,
        languageCode: String,
        darkTheme: Boolean,
    ): Result<Unit> = try {
        requireUserId()
        db.collection("directory").document(uid).update(
            mapOf(
                "lastSeenPrivacy" to lastSeenPrivacy,
                "avatarPrivacy" to avatarPrivacy,
                "readReceiptsEnabled" to readReceiptsEnabled,
                "twoFactorEnabled" to twoFactorEnabled,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        val privUpdates = mutableMapOf<String, Any>(
            "notificationsEnabled" to notificationsEnabled,
            "notificationTone" to notificationTone.take(60),
            "languageCode" to languageCode,
            "darkTheme" to darkTheme,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (twoStepPinHash != null) {
            privUpdates["twoStepPinHash"] = twoStepPinHash.take(128)
        }
        db.collection("users").document(uid).update(privUpdates).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
        Result.failure(e)
    }

    suspend fun toggleBlockUser(uid: String, currentBlocked: List<String>, targetUid: String): Result<Unit> = try {
        requireUserId()
        val updated = if (targetUid in currentBlocked) {
            currentBlocked - targetUid
        } else {
            (currentBlocked + targetUid).distinct().take(100)
        }
        db.collection("users").document(uid).update(
            mapOf("blockedUids" to updated, "updatedAt" to FieldValue.serverTimestamp())
        ).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
        Result.failure(e)
    }

    suspend fun removeDevice(uid: String, deviceId: String): Result<Unit> = try {
        requireUserId()
        db.collection("users").document(uid).collection("devices").document(deviceId).delete().await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.DELETE, "users/$uid/devices/$deviceId")
        Result.failure(e)
    }

    suspend fun submitReport(reporterId: String, reportedUserId: String, reason: String): Result<Unit> = try {
        requireUserId()
        val reportId = "rep_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val cleanReason = reason.trim().take(500)
        require(cleanReason.length >= 3) { "Report reason too short" }
        db.collection("reports").document(reportId).set(
            mapOf(
                "reporterId" to reporterId,
                "reportedUserId" to reportedUserId,
                "reason" to cleanReason,
                "status" to "OPEN",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.CREATE, "reports")
        Result.failure(e)
    }

    // --- ADMIN ACTIONS ---
    suspend fun adminSetUserBan(targetUid: String, banned: Boolean): Result<Unit> = try {
        requireUserId()
        db.collection("directory").document(targetUid).update(
            mapOf("banned" to banned, "updatedAt" to FieldValue.serverTimestamp())
        ).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.UPDATE, "directory/$targetUid")
        Result.failure(e)
    }

    suspend fun adminResolveReport(reportId: String, status: String, notes: String): Result<Unit> = try {
        requireUserId()
        db.collection("reports").document(reportId).update(
            mapOf(
                "status" to status,
                "adminNotes" to notes.take(500),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.UPDATE, "reports/$reportId")
        Result.failure(e)
    }

    suspend fun adminSaveSystemConfig(
        adminUid: String,
        maintenanceMode: Boolean,
        registrationEnabled: Boolean,
        maxUploadMb: Long,
        lastBackupEpochMs: Long,
        backupStatus: String,
    ): Result<Unit> = try {
        requireUserId()
        val ref = db.collection("system").document("config")
        val snap = ref.get().await()
        if (!snap.exists()) {
            ref.set(
                mapOf(
                    "maintenanceMode" to maintenanceMode,
                    "maxUploadMb" to maxUploadMb.coerceIn(1L, 500L),
                    "registrationEnabled" to registrationEnabled,
                    "lastBackupEpochMs" to lastBackupEpochMs,
                    "backupStatus" to backupStatus,
                    "updatedBy" to adminUid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        } else {
            ref.update(
                mapOf(
                    "maintenanceMode" to maintenanceMode,
                    "maxUploadMb" to maxUploadMb.coerceIn(1L, 500L),
                    "registrationEnabled" to registrationEnabled,
                    "lastBackupEpochMs" to lastBackupEpochMs,
                    "backupStatus" to backupStatus,
                    "updatedBy" to adminUid,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.WRITE, "system/config")
        Result.failure(e)
    }
}
