package com.example.twopchat.group.runtime

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.bridge.P2PBridgeProvider
import com.example.twopchat.media.*
import com.example.twopchat.group.attachments.GroupAttachmentManifest
import com.example.twopchat.group.attachments.GroupAttachmentStore
import com.example.twopchat.group.attachments.MAX_ATTACHMENT_BYTES
import com.example.twopchat.group.crypto.EpochAeadGroupCrypto
import com.example.twopchat.group.crypto.GroupIdentitySignatures
import com.example.twopchat.group.model.ConversationId
import com.example.twopchat.group.model.DeviceId
import com.example.twopchat.group.model.GroupAction
import com.example.twopchat.group.model.GroupContentPolicy
import com.example.twopchat.group.model.GroupMember
import com.example.twopchat.group.model.GroupPermission
import com.example.twopchat.group.model.GroupPermissionSet
import com.example.twopchat.group.model.GroupRole
import com.example.twopchat.group.model.GroupRolePolicy
import com.example.twopchat.group.model.HybridLogicalClock
import com.example.twopchat.group.model.ReplicaCandidate
import com.example.twopchat.group.model.ReplicaAck
import com.example.twopchat.group.model.ReplicaAckState
import com.example.twopchat.group.model.ReplicaPlanner
import com.example.twopchat.group.model.ReplicationStatus
import com.example.twopchat.group.model.RetryPolicy
import com.example.twopchat.group.model.PolicyDecision
import com.example.twopchat.group.model.PolicyDenialReason
import com.example.twopchat.group.model.UserId
import com.example.twopchat.group.protocol.GroupControlFrames
import com.example.twopchat.group.protocol.GroupAttachmentBlockFrame
import com.example.twopchat.group.protocol.GroupAttachmentFrames
import com.example.twopchat.group.protocol.GroupAttachmentRequest
import com.example.twopchat.group.protocol.GroupEpochKeyPackage
import com.example.twopchat.group.protocol.GroupEventFactory
import com.example.twopchat.group.ui.GroupReadReceipt
import com.example.twopchat.group.protocol.GroupEventKind
import com.example.twopchat.group.protocol.GroupInvite
import com.example.twopchat.group.protocol.GroupInviteMember
import com.example.twopchat.group.protocol.GroupInviteResponse
import com.example.twopchat.group.protocol.GroupOwnerLineage
import com.example.twopchat.group.protocol.GroupOwnerTransitionCertificate
import com.example.twopchat.group.protocol.GroupRosterMemberStatus
import com.example.twopchat.group.protocol.GroupRosterSnapshot
import com.example.twopchat.group.protocol.GroupRosterSnapshotMember
import com.example.twopchat.group.protocol.GroupStoreAck
import com.example.twopchat.group.protocol.GroupSyncBatch
import com.example.twopchat.group.protocol.GroupSyncRequest
import com.example.twopchat.group.protocol.GroupWireEvent
import com.example.twopchat.group.protocol.GroupWireProtocol
import com.example.twopchat.group.storage.GroupDatabaseHelper
import com.example.twopchat.group.storage.GroupTimelineCursor
import com.example.twopchat.group.storage.StoredGroup
import com.example.twopchat.group.storage.StoredGroupEpochKey
import com.example.twopchat.group.storage.StoredGroupEvent
import com.example.twopchat.group.storage.StoredGroupEventKind
import com.example.twopchat.group.storage.StoredGroupMember
import com.example.twopchat.group.storage.StoredGroupMessage
import com.example.twopchat.group.storage.StoredOwnerLineageCertificate
import com.example.twopchat.group.storage.StoredOutboxTask
import com.example.twopchat.group.storage.StoredOutboxState
import com.example.twopchat.group.storage.StoredPendingInvite
import com.example.twopchat.group.storage.StoredReceipt
import com.example.twopchat.group.storage.StoredRosterSnapshotPage
import com.example.twopchat.group.storage.StoredSyncCursor
import com.example.twopchat.group.ui.CreateGroupUiState
import com.example.twopchat.group.ui.GroupAdminLogEntry
import com.example.twopchat.group.ui.GroupAttachmentUi
import com.example.twopchat.group.ui.GroupChatUiState
import com.example.twopchat.group.ui.GroupContactSummary
import com.example.twopchat.group.ui.GroupDeliveryStatus
import com.example.twopchat.group.ui.GroupInfoUiState
import com.example.twopchat.group.ui.GroupManagementPermissions
import com.example.twopchat.group.ui.GroupMemberPermissions
import com.example.twopchat.group.ui.GroupMetadata
import com.example.twopchat.group.ui.GroupReaction
import com.example.twopchat.group.ui.GroupReplyPreview
import com.example.twopchat.group.ui.GroupSummary
import com.example.twopchat.group.ui.GroupSyncStatus
import com.example.twopchat.group.ui.GroupTimelineMessage
import com.example.twopchat.group.ui.PendingGroupInvite
import com.example.twopchat.group.ui.PendingGroupInvitesUiState
import java.security.MessageDigest
import java.security.SecureRandom
import java.io.File
import com.example.twopchat.security.ImageSanitizer
import com.example.twopchat.security.TemporaryCacheSanitizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable group runtime layered over the existing authenticated pairwise
 * sessions. Network delivery is at-least-once; the append-only SQLCipher event
 * log makes local application idempotent and anti-entropy repairs gaps.
 */
object GroupChatCoordinator {
    private const val TAG = "GroupChatCoordinator"
    private const val INVITE_LIFETIME_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val MAX_CLOCK_SKEW_MS = 5L * 60L * 1_000L
    private const val SMALL_GROUP_FANOUT = 128
    private const val LARGE_GROUP_REPLICAS = 16
    private const val TIMELINE_PAGE_SIZE = 200
    private const val MAX_TIMELINE_WINDOW = 100_000
    private const val SYNC_CURSOR_CHUNK_SIZE = 256
    private const val ACK_RETRY_MS = 30_000L
    private const val MAX_AUTHOR_SEQUENCE_GAP = 4_096L
    private const val INVITE_RESPONSE_PREFIX = "invite-response:"
    private const val MAX_ATTACHMENT_MANIFEST_BYTES = 220 * 1024
    private const val ATTACHMENT_SERVE_WINDOW_MS = 60_000L
    private const val ATTACHMENT_SERVE_BYTES_PER_WINDOW = 32L * 1024L * 1024L
    private const val PENDING_DEPARTURE_PREFIX = "pending_group_departure_"

    @Volatile
    var activeChatsSubTab: Int = 0

    private fun newRuntimeScope(): CoroutineScope {
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception in group runtime scope", throwable)
        }
        val dispatcher = Dispatchers.IO.limitedParallelism(4)
        return CoroutineScope(SupervisorJob() + dispatcher + handler)
    }

    @Volatile
    private var scope = newRuntimeScope()
    private val emitMutex = Mutex()
    private val controlMutex = Mutex()
    private val outboxFlushMutex = Mutex()
    private val retryPolicy = RetryPolicy(maxAttempts = Int.MAX_VALUE)
    private val eventFactory = GroupEventFactory(EpochAeadGroupCrypto)
    private val chatFlows = ConcurrentHashMap<String, MutableStateFlow<GroupChatUiState>>()
    private val infoFlows = ConcurrentHashMap<String, MutableStateFlow<GroupInfoUiState>>()
    private val timelineLimits = ConcurrentHashMap<String, Int>()
    private val lastReadReceiptTargets = ConcurrentHashMap<String, String>()
    private val activeGroupChats = ConcurrentHashMap.newKeySet<String>()
    private val syncRequestScopes = ConcurrentHashMap<String, List<String>>()
    private val syncFailureCounts = ConcurrentHashMap<String, Int>()
    private val startupScheduled = AtomicBoolean(false)
    private val recoveryNeeded = AtomicBoolean(true)
    private val attachmentManifests = ConcurrentHashMap<String, GroupAttachmentManifest>()
    private val attachmentRequests =
        ConcurrentHashMap<String, PendingAttachmentRequest>()
    private val attachmentBlockStores = ConcurrentHashMap<String, GroupAttachmentStore>()
    private val attachmentServeBudgets = ConcurrentHashMap<String, AttachmentServeBudget>()
    private val controlAncestorCache = ConcurrentHashMap<String, ControlAncestorCache>()
    private val typingMembersByGroup = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    private val _summaries = MutableStateFlow<List<GroupSummary>>(emptyList())
    val summaries: StateFlow<List<GroupSummary>> = _summaries.asStateFlow()
    private val _createState = MutableStateFlow(CreateGroupUiState())
    val createState: StateFlow<CreateGroupUiState> = _createState.asStateFlow()
    private val _pendingInvites = MutableStateFlow(PendingGroupInvitesUiState())
    val pendingInvites: StateFlow<PendingGroupInvitesUiState> = _pendingInvites.asStateFlow()
    private val _targetScrollMessageId = MutableStateFlow<Pair<String, String>?>(null)
    val targetScrollMessageId: StateFlow<Pair<String, String>?> = _targetScrollMessageId.asStateFlow()

    fun setTargetScrollMessage(groupId: String, messageId: String) {
        _targetScrollMessageId.value = groupId to messageId
    }

    fun clearTargetScrollMessage() {
        _targetScrollMessageId.value = null
    }

    @Volatile private var applicationContext: Context? = null
    @Volatile private var database: GroupDatabaseHelper? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val activeScope = synchronized(this) {
            if (database == null) {
                applicationContext = appContext
                database = GroupDatabaseHelper(appContext)
            }
            if (!startupScheduled.compareAndSet(false, true)) return
            scope
        }
        activeScope.launch {
            try {
                runCatching {
                    db().listGroups().forEach { drainStoredControlChain(it.groupId) }
                    reconcileDurableState()
                }
                    .onSuccess { recoveryNeeded.set(false) }
                    .onFailure { Log.w(TAG, "Group recovery failed: ${it.message}") }
                refreshCreateState()
                refreshPendingInvites()
                refreshAllGroups()
                flushDueOutbox()
                flushDeclinedInviteResponses()
            } catch (e: Exception) {
                Log.e(TAG, "Failed during group coordinator startup initialization", e)
            }
        }
    }

    fun shutdown() {
        synchronized(this) {
            val runtimeJob = scope.coroutineContext[Job]
            runtimeJob?.cancel()
            runBlocking { runtimeJob?.join() }
            database?.close()
            database = null
            applicationContext = null
            startupScheduled.set(false)
            recoveryNeeded.set(true)
            chatFlows.clear()
            infoFlows.clear()
            _summaries.value = emptyList()
            _pendingInvites.value = PendingGroupInvitesUiState()
            attachmentManifests.clear()
            attachmentRequests.clear()
            attachmentBlockStores.clear()
            attachmentServeBudgets.clear()
            controlAncestorCache.clear()
            activeGroupChats.clear()
            lastReadReceiptTargets.clear()
            scope = newRuntimeScope()
        }
    }

    fun deleteAll(context: Context): Boolean {
        shutdown()
        val appContext = context.applicationContext
        val databaseDeleted = GroupDatabaseHelper.deleteDatabase(appContext)
        File(appContext.filesDir, "group_attachment_blocks").deleteRecursively()
        File(appContext.filesDir, "group_downloads").deleteRecursively()
        appContext.cacheDir.listFiles()
            ?.filter { it.name.startsWith("group-upload-") }
            ?.forEach(File::deleteRecursively)
        return databaseDeleted
    }

    fun chatState(groupId: String): StateFlow<GroupChatUiState> {
        val flow = chatFlows.computeIfAbsent(groupId) {
            MutableStateFlow(
                GroupChatUiState(
                    groupId = groupId,
                    title = "Group",
                    memberCount = 0,
                    syncStatus = GroupSyncStatus.SYNCING,
                    composerEnabled = false,
                    textComposerEnabled = false,
                    mediaComposerEnabled = false,
                ),
            )
        }
        scope.launch {
            markReadAndSendReceipt(groupId)
            refreshGroup(groupId)
        }
        return flow.asStateFlow()
    }

    fun setGroupChatActive(groupId: String, active: Boolean) {
        if (active) {
            activeGroupChats += groupId
            scope.launch {
                markReadAndSendReceipt(groupId)
                refreshGroup(groupId)
            }
        } else {
            activeGroupChats -= groupId
            typingMembersByGroup[groupId]?.clear()
        }
    }

    fun markRead(groupId: String) {
        scope.launch {
            markReadAndSendReceipt(groupId)
            refreshGroup(groupId)
            refreshAllSummariesWithoutRecursion()
        }
    }

    fun sendTyping(groupId: String, isTyping: Boolean) {
        val context = applicationContext ?: return
        scope.launch {
            val group = db().getGroup(groupId) ?: return@launch
            val localMember = db().getMember(groupId, group.localDeviceId) ?: return@launch
            if (!localMember.isParticipating()) return@launch
            val members = db().listMembers(groupId).filter {
                it.isParticipating() && it.deviceId != group.localDeviceId
            }
            val typingJson = JSONObject().apply {
                put("type", GroupWireProtocol.TYPE_TYPING)
                put("version", GroupWireProtocol.VERSION)
                put("group_id", groupId)
                put("device_id", group.localDeviceId)
                put("display_name", localMember.displayName)
                put("is_typing", isTyping)
                put("timestamp", System.currentTimeMillis())
            }

            for (member in members) {
                if (P2PMessageRelay.peerSessionStates[member.peerName] == true) {
                    P2PMessageRelay.sendGroupFrame(context, member.peerName, typingJson)
                }
            }
        }
    }

    fun infoState(groupId: String): StateFlow<GroupInfoUiState> {
        val flow = infoFlows.computeIfAbsent(groupId) {
            MutableStateFlow(emptyInfoState(groupId))
        }
        scope.launch { refreshGroup(groupId) }
        return flow.asStateFlow()
    }

    fun refreshContacts() {
        scope.launch { refreshCreateState() }
    }

    fun createGroup(
        title: String,
        description: String,
        contactIds: Set<String>,
        onCreated: (String) -> Unit = {},
    ) {
        scope.launch {
            val normalizedTitle = title.trim()
            val normalizedDescription = description.trim()
            if (normalizedTitle.isBlank() || normalizedTitle.length > 160 ||
                normalizedDescription.length > 2_000
            ) {
                _createState.value = _createState.value.copy(
                    errorMessage = "Enter a valid group name and description",
                )
                return@launch
            }
            _createState.value = _createState.value.copy(isCreating = true, errorMessage = null)
            runCatching {
                createGroupInternal(normalizedTitle, normalizedDescription, contactIds)
            }.onFailure { error ->
                _createState.value = _createState.value.copy(
                    isCreating = false,
                    errorMessage = error.message ?: "Unable to create group",
                )
            }.onSuccess { groupId ->
                _createState.value = _createState.value.copy(isCreating = false)
                onCreated(groupId)
            }
        }
    }

    fun loadOlderMessages(groupId: String) {
        timelineLimits.compute(groupId) { _, current ->
            ((current ?: TIMELINE_PAGE_SIZE) + TIMELINE_PAGE_SIZE)
                .coerceAtMost(MAX_TIMELINE_WINDOW)
        }
        scope.launch { refreshGroup(groupId) }
    }

    fun updateGroupInfo(groupId: String, title: String, description: String) {
        updateGroupInfo(groupId, title, description, null)
    }

    fun updateGroupInfo(groupId: String, title: String, description: String, avatarUri: String? = null) {
        val normalizedTitle = title.trim()
        val normalizedDescription = description.trim()
        if (normalizedTitle.isBlank() || normalizedTitle.length > 160 ||
            normalizedDescription.length > 2_000
        ) {
            return
        }
        scope.launch {
            val context = applicationContext
            val (persistedPath, avatarData) = if (avatarUri != null && context != null) {
                runCatching {
                    val bmp = if (avatarUri.startsWith("content://")) {
                        context.contentResolver.openInputStream(Uri.parse(avatarUri))?.use { stream ->
                            android.graphics.BitmapFactory.decodeStream(stream)
                        }
                    } else {
                        val f = File(avatarUri)
                        if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath) else null
                    }
                    if (bmp != null) {
                        val maxDim = 512
                        val scaledBmp = if (bmp.width > maxDim || bmp.height > maxDim) {
                            val scale = maxDim.toFloat() / Math.max(bmp.width, bmp.height)
                            android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                        } else {
                            bmp
                        }
                        val avatarsDir = File(context.filesDir, "group_avatars").also { it.mkdirs() }
                        val destFile = File(avatarsDir, "${groupId}.jpg")
                        val out = java.io.ByteArrayOutputStream()
                        scaledBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                        val compressed = out.toByteArray()
                        destFile.writeBytes(compressed)
                        val b64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
                        destFile.absolutePath to b64
                    } else null to null
                }.getOrElse { null to null }
            } else null to null

            requestSerializedControl(
                groupId,
                "update_info",
                JSONObject().apply {
                    put("title", normalizedTitle)
                    put("description", normalizedDescription)
                    // Always send avatar_uri field (even if null) so receiver knows it changed
                    if (avatarUri != null) {
                        put("avatar_uri", persistedPath ?: avatarUri)
                        avatarData?.let { put("avatar_data", it) }
                    }
                },
            )
        }
    }

    fun updateGroupWallpaper(groupId: String, wallpaperUri: String?, dimming: Int = 45, isBlur: Boolean = false) {
        scope.launch {
            val context = applicationContext ?: return@launch
            val group = db().getGroup(groupId) ?: return@launch
            val local = localIdentity()
            val member = db().getMember(groupId, local.deviceId)
            val role = parseRole(member?.role ?: "")
            if (role != GroupRole.OWNER && role != GroupRole.ADMINISTRATOR) {
                Log.w(TAG, "Refusing to update wallpaper: local user is not OWNER or ADMIN")
                return@launch
            }
            val (persistedPath, wallpaperData) = if (wallpaperUri != null) {
                runCatching {
                    val bmp = if (wallpaperUri.startsWith("content://")) {
                        context.contentResolver.openInputStream(Uri.parse(wallpaperUri))?.use { stream ->
                            android.graphics.BitmapFactory.decodeStream(stream)
                        }
                    } else {
                        val f = File(wallpaperUri)
                        if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath) else null
                    }
                    if (bmp != null) {
                        val maxDim = 1280
                        val scaledBmp = if (bmp.width > maxDim || bmp.height > maxDim) {
                            val scale = maxDim.toFloat() / Math.max(bmp.width, bmp.height)
                            android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                        } else {
                            bmp
                        }
                        val dir = File(context.filesDir, "group_wallpapers").also { it.mkdirs() }
                        val destFile = File(dir, "${groupId}.jpg")
                        val out = java.io.ByteArrayOutputStream()
                        scaledBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                        val compressed = out.toByteArray()
                        destFile.writeBytes(compressed)
                        val b64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
                        destFile.absolutePath to b64
                    } else null to null
                }.getOrElse { null to null }
            } else null to null

            if (persistedPath != null) {
                P2PPreferences.prefs(context).edit().apply {
                    putString("group_wallpaper_$groupId", persistedPath)
                    putInt("group_wallpaper_dimming_$groupId", dimming)
                    putBoolean("group_wallpaper_blur_$groupId", isBlur)
                    apply()
                }
            } else if (wallpaperUri == null) {
                P2PPreferences.prefs(context).edit().apply {
                    remove("group_wallpaper_$groupId")
                    remove("group_wallpaper_dimming_$groupId")
                    remove("group_wallpaper_blur_$groupId")
                    apply()
                }
            }

            val g = db().getGroup(groupId) ?: return@launch
            requestSerializedControl(
                groupId,
                "update_info",
                JSONObject().apply {
                    put("title", g.title)
                    put("description", g.description)
                    if (wallpaperUri != null) {
                        put("wallpaper_uri", persistedPath ?: wallpaperUri)
                        if (!wallpaperData.isNullOrBlank()) {
                            put("wallpaper_data", wallpaperData)
                        }
                        put("wallpaper_dimming", dimming)
                        put("wallpaper_blur", isBlur)
                    } else {
                        put("wallpaper_uri", "")
                    }
                }
            )
        }
    }

    fun inviteMembers(groupId: String, contactIds: Set<String>) {
        if (contactIds.isEmpty()) return
        scope.launch {
            val context = requireNotNull(applicationContext)
            val prefs = P2PPreferences.prefs(context)
            contactIds.forEach { peerName ->
                val fingerprint = prefs.getString(P2PPreferences.peerFingerprint(peerName), null)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                requestSerializedControl(
                    groupId,
                    "invite",
                    JSONObject().apply {
                        put("member_device_id", stableDeviceId(fingerprint))
                        put("fingerprint", fingerprint)
                        put("peer_name", peerName.take(160))
                    },
                )
                val peerEndpoint = prefs.getString(P2PPreferences.lastEndpoint(peerName), null)
                    ?: P2PMessageRelay.peerEndpoints[peerName]
                if (!peerEndpoint.isNullOrBlank()) {
                    P2PMessageRelay.processOfflineQueue(context, peerName, peerEndpoint)
                }
            }
            enqueuePendingMemberInvites(groupId)
            flushDueOutbox()
            refreshGroup(groupId)
        }
    }

    fun sendMessage(groupId: String, text: String, replyToMessageId: String? = null) {
        val normalized = text.trim()
        if (
            normalized.isEmpty() ||
            normalized.length > 64_000 ||
            chatFlows[groupId]?.value?.textComposerEnabled != true
        ) {
            return
        }
        cancelReply(groupId)
        scope.launch {
            emitEvent(
                groupId = groupId,
                kind = if (replyToMessageId == null) {
                    GroupEventKind.MESSAGE
                } else {
                    GroupEventKind.REPLY
                },
                payload = JSONObject().apply {
                    put("text", normalized)
                    replyToMessageId?.let { put("reply_to", it) }
                },
                targetEventId = replyToMessageId,
            )
        }
    }

    fun sendAttachment(groupId: String, uri: String, mimeType: String?, caption: String? = null) {
        if (chatFlows[groupId]?.value?.mediaComposerEnabled != true) return
        scope.launch {
            val group = db().getGroup(groupId) ?: return@launch
            val local = db().getMember(groupId, group.localDeviceId) ?: return@launch
            if (
                !local.isParticipating() ||
                !GroupRolePolicy.canPerform(
                    local.toPolicyMember(),
                    GroupAction.POST_MEDIA,
                ).allowed
            ) {
                return@launch
            }
            val staged = stageAttachment(Uri.parse(uri)) ?: return@launch
            try {
                val finalMime = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                    ?: staged.mimeType
                    ?: when {
                        staged.file.name.endsWith(".jpg", true) || staged.file.name.endsWith(".jpeg", true) -> "image/jpeg"
                        staged.file.name.endsWith(".png", true) -> "image/png"
                        staged.file.name.endsWith(".webp", true) -> "image/webp"
                        staged.file.name.endsWith(".gif", true) -> "image/gif"
                        staged.file.name.endsWith(".mp4", true) -> "video/mp4"
                        else -> "application/octet-stream"
                    }
                val manifest = attachmentStore(groupId).encrypt(
                    staged.file,
                    finalMime,
                )
                val manifestJson = manifest.toJson()
                if (
                    manifestJson.toString().toByteArray(Charsets.UTF_8).size >
                    MAX_ATTACHMENT_MANIFEST_BYTES
                ) {
                    attachmentStore(groupId).discard(manifest)
                    throw IllegalArgumentException(
                        "attachment manifest is too large for a group event",
                    )
                }
                val eventText = if (!caption.isNullOrBlank()) caption.trim() else manifest.fileName
                val event = emitEvent(
                    groupId,
                    GroupEventKind.MEDIA,
                    JSONObject().apply {
                        put("text", eventText)
                        put("attachment", manifestJson)
                    },
                )
                if (event != null) {
                    attachmentManifests[attachmentManifestKey(groupId, event.eventId)] = manifest
                    assembleAttachmentIfComplete(groupId, event.eventId, manifest)
                    refreshGroup(groupId)
                } else {
                    attachmentStore(groupId).discard(manifest)
                }
            } finally {
                staged.directory.deleteRecursively()
            }
        }
    }

    fun sendMediaAlbum(groupId: String, uris: List<String>, mimeTypes: List<String>, caption: String? = null) {
        if (uris.isEmpty()) return
        if (uris.size == 1) {
            sendAttachment(groupId, uris.first(), mimeTypes.firstOrNull(), caption)
            return
        }
        if (chatFlows[groupId]?.value?.mediaComposerEnabled != true) return
        scope.launch {
            val group = db().getGroup(groupId) ?: return@launch
            val local = db().getMember(groupId, group.localDeviceId) ?: return@launch
            if (
                !local.isParticipating() ||
                !GroupRolePolicy.canPerform(
                    local.toPolicyMember(),
                    GroupAction.POST_MEDIA,
                ).allowed
            ) {
                return@launch
            }
            val stagedList = mutableListOf<StagedAttachment>()
            val manifests = mutableListOf<GroupAttachmentManifest>()
            try {
                for ((idx, uriStr) in uris.withIndex()) {
                    val staged = stageAttachment(Uri.parse(uriStr)) ?: continue
                    stagedList.add(staged)
                    val paramMime = mimeTypes.getOrNull(idx)
                    val finalMime = paramMime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                        ?: staged.mimeType
                        ?: when {
                            staged.file.name.endsWith(".jpg", true) || staged.file.name.endsWith(".jpeg", true) -> "image/jpeg"
                            staged.file.name.endsWith(".png", true) -> "image/png"
                            staged.file.name.endsWith(".webp", true) -> "image/webp"
                            staged.file.name.endsWith(".gif", true) -> "image/gif"
                            staged.file.name.endsWith(".mp4", true) -> "video/mp4"
                            else -> "application/octet-stream"
                        }
                    val manifest = attachmentStore(groupId).encrypt(staged.file, finalMime)
                    manifests.add(manifest)
                }
                if (manifests.isEmpty()) return@launch

                val manifestsJsonArray = JSONArray()
                manifests.forEach { manifestsJsonArray.put(it.toJson()) }

                val eventText = if (!caption.isNullOrBlank()) caption.trim() else "Альбом (${manifests.size})"
                val event = emitEvent(
                    groupId,
                    GroupEventKind.MEDIA,
                    JSONObject().apply {
                        put("text", eventText)
                        put("attachments", manifestsJsonArray)
                        put("attachment", manifests.first().toJson())
                    },
                )
                if (event != null) {
                    for (manifest in manifests) {
                        attachmentManifests[attachmentManifestKey(groupId, event.eventId)] = manifest
                        assembleAttachmentIfComplete(groupId, event.eventId, manifest)
                    }
                    refreshGroup(groupId)
                } else {
                    for (manifest in manifests) {
                        attachmentStore(groupId).discard(manifest)
                    }
                }
            } finally {
                for (staged in stagedList) {
                    staged.directory.deleteRecursively()
                }
            }
        }
    }

    fun downloadAttachment(groupId: String, messageId: String) {
        scope.launch {
            val manifests = loadAttachmentManifests(groupId, messageId)
            if (manifests.isEmpty()) return@launch
            for (manifest in manifests) {
                if (!assembleAttachmentIfComplete(groupId, messageId, manifest)) {
                    requestMissingAttachmentBlocks(groupId, messageId, manifest)
                }
            }
            refreshGroup(groupId)
        }
    }

    fun editMessage(groupId: String, messageId: String, newText: String) {
        val normalized = newText.trim()
        if (normalized.isEmpty() || normalized.length > 64_000) return
        scope.launch {
            emitEvent(
                groupId,
                GroupEventKind.EDIT,
                JSONObject().put("text", normalized),
                messageId,
            )
        }
    }

    fun deleteMessage(groupId: String, messageId: String) {
        scope.launch {
            emitEvent(groupId, GroupEventKind.DELETE, JSONObject(), messageId)
        }
    }

    fun clearHistory(groupId: String) {
        scope.launch {
            try {
                db().clearHistory(groupId)
                refreshAllSummariesWithoutRecursion()
                refreshGroup(groupId)
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e(TAG, "Failed to clear group history for $groupId", error)
            }
        }
    }

    fun toggleReaction(groupId: String, messageId: String, emoji: String) {
        val normalized = emoji.trim().take(16)
        if (normalized.isBlank()) return
        scope.launch {
            val local = localIdentity()
            val active = reactionState(
                groupId,
                setOf(messageId),
            )["$messageId\u0000$normalized\u0000${local.deviceId}"] == true
            emitEvent(
                groupId,
                if (active) GroupEventKind.REACTION_REMOVE else GroupEventKind.REACTION_ADD,
                JSONObject().put("emoji", normalized),
                messageId,
            )
        }
    }

    fun pinMessage(groupId: String, messageId: String) {
        scope.launch { emitEvent(groupId, GroupEventKind.PIN, JSONObject(), messageId) }
    }

    fun unpinMessage(groupId: String, messageId: String) {
        scope.launch { emitEvent(groupId, GroupEventKind.UNPIN, JSONObject(), messageId) }
    }

    fun createPoll(groupId: String, question: String, options: List<String>, isAnonymous: Boolean) {
        val normalizedQuestion = question.trim()
        val normalizedOptions = options.map(String::trim).filter(String::isNotEmpty)
        if (
            normalizedQuestion.isEmpty() ||
            normalizedQuestion.length > 500 ||
            normalizedOptions.size !in 2..10 ||
            normalizedOptions.any { it.length > 200 } ||
            normalizedOptions.distinct().size != normalizedOptions.size
        ) {
            return
        }
        scope.launch {
            val jsonOpts = org.json.JSONArray()
            normalizedOptions.forEach(jsonOpts::put)
            val payload = JSONObject()
                .put("type", "poll")
                .put("question", normalizedQuestion)
                .put("options", jsonOpts)
                .put("anonymous", isAnonymous)
            emitEvent(groupId, GroupEventKind.POLL, payload)
        }
    }

    fun setAdminOnlyPosting(groupId: String, enabled: Boolean) {
        scope.launch {
            val group = db().getGroup(groupId) ?: return@launch
            if (group.adminOnlyPosting == enabled) return@launch
            requestSerializedControl(
                groupId,
                "update_info",
                JSONObject().apply {
                    put("title", group.title)
                    put("description", group.description)
                    put("admin_only_posting", enabled)
                },
            )
        }
    }

    fun requestJoinFromInvite(groupId: String, inviteToken: String, ownerPeerName: String) {
        val normalizedGroupId = groupId.trim().take(128)
        val normalizedToken = inviteToken.trim().take(128)
        if (normalizedGroupId.isEmpty() || normalizedToken.isEmpty() || ownerPeerName.isBlank()) return
        val context = applicationContext ?: return
        P2PMessageRelay.sendGroupFrame(
            context,
            ownerPeerName,
            JSONObject()
                .put("type", GroupWireProtocol.TYPE_JOIN_REQUEST)
                .put("version", GroupWireProtocol.VERSION)
                .put("group_id", normalizedGroupId)
                .put("invite_token", normalizedToken),
        )
    }

    fun acceptPendingInviteForGroup(groupId: String): Boolean {
        val pending = database?.listPendingInvites()?.firstOrNull { it.groupId == groupId }
        if (pending != null) {
            acceptInvite(pending.inviteId)
            return true
        }
        return false
    }

    fun votePoll(groupId: String, pollId: String, optionId: Int) {
        if (optionId < 0) return
        scope.launch {
            val payload = JSONObject()
                .put("option_id", optionId)
            emitEvent(groupId, GroupEventKind.POLL_VOTE, payload, pollId)
        }
    }

    fun retryMessage(groupId: String, messageId: String) {
        scope.launch {
            db().requeueOutboxForEvent(groupId, messageId)
            flushDueOutbox()
            refreshGroup(groupId)
        }
    }

    fun setMemberRole(groupId: String, memberId: String, role: GroupRole) {
        if (role !in setOf(GroupRole.ADMINISTRATOR, GroupRole.MODERATOR, GroupRole.MEMBER)) return
        scope.launch {
            requestSerializedControl(
                groupId,
                "set_role",
                JSONObject().apply {
                    put("member_device_id", memberId)
                    put("role", role.name)
                    put("permissions", GroupRolePolicy.defaultPermissions(role).bits)
                },
            )
        }
    }

    fun setMemberRestrictions(
        groupId: String,
        memberId: String,
        permissions: GroupMemberPermissions,
    ) {
        val bits = buildPermissionSet(permissions).bits
        scope.launch {
            requestSerializedControl(
                groupId,
                "restrict",
                JSONObject().apply {
                    put("member_device_id", memberId)
                    put("permissions", bits)
                },
            )
        }
    }

    fun removeMember(groupId: String, memberId: String, banned: Boolean = false) {
        scope.launch {
            requestSerializedControl(
                groupId,
                "remove",
                JSONObject().apply {
                    put("member_device_id", memberId)
                    put("status", if (banned) "BANNED" else "LEFT")
                },
            )
        }
    }

    fun transferOwnership(groupId: String, memberId: String) {
        scope.launch {
            controlMutex.withLock {
                val group = db().getGroup(groupId) ?: return@withLock
                val oldOwner = db().getMember(groupId, group.ownerDeviceId)
                    ?: return@withLock
                val newOwner = db().getMember(groupId, memberId)
                    ?.takeIf { it.isParticipating() && it.signingKeyBase64.isNotBlank() }
                    ?: return@withLock
                val lineage = currentOwnerLineage(group)
                if (lineage.certificates.size >= GroupOwnerLineage.MAX_TRANSITIONS) {
                    Log.w(TAG, "Owner lineage limit reached for $groupId")
                    return@withLock
                }
                val unsigned = GroupOwnerTransitionCertificate(
                    groupId = groupId,
                    previousOwnerAnchor = lineage.anchor,
                    lineageSequence = lineage.nextSequence,
                    previousControlHead = group.controlHead,
                    oldOwnerFingerprint = oldOwner.transportFingerprint,
                    oldOwnerDeviceId = oldOwner.deviceId,
                    oldOwnerSigningKey = oldOwner.signingKeyBase64,
                    newOwnerFingerprint = newOwner.transportFingerprint,
                    newOwnerDeviceId = newOwner.deviceId,
                    newOwnerSigningKey = newOwner.signingKeyBase64,
                    createdAtMs = System.currentTimeMillis(),
                    nonce = UUID.randomUUID().toString(),
                    signatureBase64 = "",
                )
                val certificate = unsigned.copy(
                    signatureBase64 = GroupIdentitySignatures.sign(
                        unsigned.canonicalForSignature(),
                    ),
                )
                emitEvent(
                    groupId,
                    GroupEventKind.OWNERSHIP_TRANSFERRED,
                    JSONObject().apply {
                        put("member_device_id", memberId)
                        put(
                            "owner_transition",
                            GroupOwnerLineage.toJson(certificate),
                        )
                    },
                    certificate.transitionId(),
                )
            }
        }
    }

    fun canLeaveGroup(groupId: String): Boolean {
        val storage = database ?: return false
        val group = storage.getGroup(groupId) ?: return false
        if (group.ownerDeviceId != group.localDeviceId) return true
        return storage.listMembers(groupId).none {
            it.deviceId != group.localDeviceId && it.isParticipating()
        }
    }

    fun deleteGroup(groupId: String) {
        scope.launch {
            purgeLocalGroup(groupId)
            refreshAllGroups()
        }
    }

    fun leaveGroup(groupId: String) {
        scope.launch {
            val group = db().getGroup(groupId) ?: return@launch
            if (!canLeaveGroup(groupId)) return@launch

            if (group.ownerDeviceId == group.localDeviceId) {
                purgeLocalGroup(groupId)
                refreshAllGroups()
                return@launch
            }

            // Keep the group and its durable outbox until the owner confirms the
            // serialized removal. The pending flag hides it from the local UI.
            val proposal = emitEvent(
                groupId,
                GroupEventKind.SYSTEM,
                JSONObject().apply {
                    put("control_proposal", "leave")
                    put("member_device_id", group.localDeviceId)
                },
                group.localDeviceId,
            ) ?: return@launch
            applicationContext?.let { context ->
                P2PPreferences.prefs(context).edit()
                    .putBoolean("$PENDING_DEPARTURE_PREFIX$groupId", true)
                    .apply()
            }
            activeGroupChats.remove(groupId)
            timelineLimits.remove(groupId)
            lastReadReceiptTargets.remove(groupId)
            Log.i(TAG, "Queued durable leave proposal ${proposal.eventId} for $groupId")
            refreshAllGroups()
        }
    }

    fun startReply(groupId: String, messageId: String) {
        scope.launch {
            val message = db().getEvent(groupId, messageId) ?: return@launch
            val author = db().getMember(groupId, message.authorDeviceId)
            updateChatFlow(groupId) {
                it.copy(
                    currentReply = GroupReplyPreview(
                        messageId,
                        author?.displayName ?: "Member",
                        message.body.orEmpty(),
                    ),
                )
            }
        }
    }

    fun cancelReply(groupId: String) {
        updateChatFlow(groupId) { it.copy(currentReply = null) }
    }

    private suspend fun markReadAndSendReceipt(groupId: String) {
        val group = db().getGroup(groupId) ?: return
        db().markRead(groupId)
        val latestRemote = db().loadTimeline(groupId, 50).firstOrNull {
            it.authorDeviceId != group.localDeviceId
        } ?: return
        if (lastReadReceiptTargets.put(groupId, latestRemote.messageId) == latestRemote.messageId) {
            return
        }
        emitEvent(
            groupId,
            GroupEventKind.READ_RECEIPT,
            JSONObject().put("event_id", latestRemote.messageId),
            latestRemote.messageId,
        )
    }

    fun acceptInvite(inviteId: String) {
        scope.launch { acceptInviteInternal(inviteId) }
    }

    fun declineInvite(inviteId: String) {
        scope.launch { declineInviteInternal(inviteId) }
    }

    /**
     * Called before the legacy 1:1 JSON router. Returns true for every group
     * frame, including malformed ones, so group controls can never fall through
     * and be rendered as direct-chat text.
     */
    fun handleIncoming(context: Context, senderPeerName: String, json: JSONObject): Boolean {
        if (!GroupWireProtocol.isGroupFrame(json)) return false
        initialize(context)
        val wire = json.toString()
        if (wire.toByteArray(Charsets.UTF_8).size > GroupWireProtocol.MAX_WIRE_BYTES) return true
        scope.launch {
            runCatching { processIncoming(senderPeerName, json) }
                .onFailure { error ->
                    Log.w(TAG, "Rejected group frame from $senderPeerName: ${error.message}")
                }
        }
        return true
    }

    fun onPeerConnected(context: Context, peerName: String) {
        initialize(context)
        scope.launch {
            val fingerprint = runCatching { transportFingerprint(peerName) }.getOrNull()
            val groups = db().listGroups()
            val memberships = groups.mapNotNull { group ->
                val transportDeviceId = fingerprint?.let(::stableDeviceId)
                val member = db().listMembers(group.groupId)
                    .firstOrNull {
                        fingerprint != null && transportDeviceId != null &&
                            GroupMembershipTransitions.isReconnectCandidate(
                                it.status,
                                it.deviceId,
                                it.transportFingerprint,
                                fingerprint,
                                transportDeviceId,
                            )
                    }
                    ?: return@mapNotNull null
                if (member.peerName != peerName) {
                    db().upsertMember(member.copy(peerName = peerName))
                }
                group to member
            }
            val now = System.currentTimeMillis()
            memberships.forEach { (group, member) ->
                db().requeueOutboxForRecipient(group.groupId, member.deviceId, now)
            }
            // Key packages and roster controls must be replayed before sync can
            // successfully ingest ciphertext from epochs created while offline.
            flushDueOutbox()
            // Re-enqueue current epoch key for the connecting peer if we are owner and
            // they are missing the key (e.g. first connect after being added).
            memberships.forEach { (group, member) ->
                if (!member.isParticipating()) return@forEach
                val localIsOwner = group.localDeviceId == group.ownerDeviceId
                if (localIsOwner) {
                    val controlHead = group.controlHead ?: return@forEach
                    db().getEpochKey(group.groupId, group.currentEpoch)?.let { epochKey ->
                        enqueueEpochKeyPackages(
                            group.groupId,
                            controlHead,
                            group.currentEpoch,
                            epochKey.keyMaterial,
                        )
                    }
                }
            }
            flushDueOutbox()
            memberships.forEach { (group, member) ->
                if (member.isParticipating()) sendSyncRequests(group, member)
            }
        }
    }

    @Volatile
    private var cachedActiveMemberPeerNames: Set<String>? = null
    @Volatile
    private var lastActiveMemberCacheTimeMs: Long = 0L
    private const val ACTIVE_MEMBER_CACHE_TTL_MS = 15_000L

    fun invalidateActiveMemberCache() {
        cachedActiveMemberPeerNames = null
        lastActiveMemberCacheTimeMs = 0L
    }

    fun listActiveGroupMemberPeerNames(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        val cached = cachedActiveMemberPeerNames
        if (cached != null && (now - lastActiveMemberCacheTimeMs) < ACTIVE_MEMBER_CACHE_TTL_MS) {
            return cached
        }
        return runCatching {
            initialize(context)
            val groups = db().listGroups()
            val result = groups.flatMap { group ->
                db().listMembers(group.groupId)
                    .filter { it.isParticipating() && it.deviceId != group.localDeviceId }
                    .map { it.peerName }
            }.filter { it.isNotBlank() }.toSet()
            cachedActiveMemberPeerNames = result
            lastActiveMemberCacheTimeMs = now
            result
        }.getOrDefault(cached ?: emptySet())
    }

    suspend fun runAntiEntropy(): Int {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        // Repair memberships whose transport identity changed while the app was
        // running.  Waiting for a fresh connection event left already-connected
        // participants stuck until they re-entered the group or restarted both
        // peers.
        val context = applicationContext ?: return 0
        listActiveGroupMemberPeerNames(context)
            .filter { P2PMessageRelay.peerSessionStates[it] == true }
            .forEach { onPeerConnected(context, it) }
        if (recoveryNeeded.get()) {
            runCatching { reconcileDurableState() }
                .onSuccess { recoveryNeeded.set(false) }
                .onFailure { Log.w(TAG, "Deferred group recovery failed: ${it.message}") }
        }
        flushDeclinedInviteResponses()
        var flushed = flushDueOutbox()
        db().listGroups().forEach { initialGroup ->
            expireStaleInvites(initialGroup.groupId)
            val group = db().getGroup(initialGroup.groupId) ?: return@forEach
            enqueuePendingMemberInvites(group.groupId)
            repairReplicaAttachments(group)
            val connected = db().listMembers(group.groupId)
                .filter {
                    it.isParticipating() &&
                        it.deviceId != group.localDeviceId &&
                        P2PMessageRelay.peerSessionStates[it.peerName] == true
                }
            val syncPeers = if (connected.size <= 3) {
                connected
            } else {
                val selected = ReplicaPlanner.selectReplicas(
                    "${group.groupId}\u0000${group.localDeviceId}\u0000anti-entropy",
                    connected.map { ReplicaCandidate(DeviceId(it.deviceId)) },
                    3,
                ).mapTo(hashSetOf()) { it.value }
                connected.filter { it.deviceId in selected }
            }
            syncPeers.forEach { sendSyncRequests(group, it) }
        }
        flushed += flushDueOutbox()
        return flushed
    }

    /**
     * An invite contains the current epoch secret. Merely rejecting an old
     * signature does not revoke that already-disclosed key, so the owner must
     * remove an unaccepted recipient and rotate the epoch when its fixed
     * invitation lifetime ends.
     */
    private suspend fun expireStaleInvites(
        groupId: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val candidateIds = db().listMembers(groupId)
            .filter {
                GroupMembershipTransitions.shouldExpireInvite(
                    status = it.status,
                    invitedAtMs = it.updatedAtMs,
                    nowMs = nowMs,
                    lifetimeMs = INVITE_LIFETIME_MS,
                )
            }
            .map { it.deviceId }

        for (deviceId in candidateIds) {
            controlMutex.withLock {
                val group = db().getGroup(groupId) ?: return@withLock
                if (group.localDeviceId != group.ownerDeviceId) return@withLock
                val recipient = db().getMember(groupId, deviceId) ?: return@withLock
                if (
                    !GroupMembershipTransitions.shouldExpireInvite(
                        status = recipient.status,
                        invitedAtMs = recipient.updatedAtMs,
                        nowMs = nowMs,
                        lifetimeMs = INVITE_LIFETIME_MS,
                    )
                ) {
                    return@withLock
                }
                executeSerializedControlLocked(
                    groupId = groupId,
                    action = "remove",
                    payload = JSONObject().apply {
                        put("member_device_id", deviceId)
                        put("status", "LEFT")
                        put("reason", "invite_expired")
                    },
                    proposalEventId = null,
                )
            }
        }
    }

    /**
     * Repairs the only cross-transaction artifacts left around serialized
     * epoch transitions. Ordinary local events and their replication tasks are
     * committed atomically; the latest-event replay also upgrades databases
     * produced by earlier builds.
     */
    private suspend fun reconcileDurableState() {
        val local = localIdentity()
        db().listGroups().forEach { initial ->
            drainStoredControlChain(initial.groupId)
            val group = db().getGroup(initial.groupId) ?: return@forEach
            if (group.localDeviceId != local.deviceId) return@forEach

            db().latestAuthorEvent(group.groupId, local.deviceId)
                ?.payload
                ?.let { wireBytes ->
                    runCatching {
                        val json = JSONObject(wireBytes.toString(Charsets.UTF_8))
                        val event = GroupWireProtocol.parseEvent(json)
                        enqueueEventForRecipients(group, event, json)
                    }.onFailure {
                        Log.w(TAG, "Could not recover local group event: ${it.message}")
                    }
                }

            canonicalControlEvents(group).forEach controlLoop@{ control ->
                if (control.authorDeviceId != local.deviceId) return@controlLoop
                val payload = runCatching { JSONObject(control.body.orEmpty()) }.getOrNull()
                val wire = control.payload?.let {
                    runCatching {
                        GroupWireProtocol.parseEvent(JSONObject(it.toString(Charsets.UTF_8)))
                    }.getOrNull()
                }
                if (payload != null && wire != null) {
                    val nextEpoch = payload.optLong("next_epoch", 0L)
                    if (
                        wire.kind in setOf(
                            GroupEventKind.MEMBER_ADDED,
                            GroupEventKind.MEMBER_REMOVED,
                        ) &&
                        nextEpoch in 1..group.currentEpoch
                    ) {
                        db().getEpochKey(group.groupId, nextEpoch)?.let { epochKey ->
                            enqueueEpochKeyPackages(
                                group.groupId,
                                wire.eventId,
                                nextEpoch,
                                epochKey.keyMaterial,
                            )
                        }
                    }
                    if (
                        wire.kind == GroupEventKind.MEMBER_ADDED &&
                        payload.optString("status") == "ACTIVE" &&
                        group.controlHead == wire.eventId
                    ) {
                        payload.optString("member_device_id")
                            .takeIf(String::isNotBlank)
                            ?.let { recipient ->
                                enqueueRosterSnapshot(group.groupId, recipient)
                            }
                    }
                }
            }
            enqueuePendingMemberInvites(group.groupId)
        }
    }

    private fun flushDeclinedInviteResponses(): Int {
        val context = applicationContext ?: return 0
        var attempted = 0
        db().listDeclinedInvites().forEach { pending ->
            runCatching {
                val invite = GroupWireProtocol.parseInvite(
                    JSONObject(pending.token.toString(Charsets.UTF_8)),
                )
                require(invite.verifySignature())
                val owner = invite.members.single {
                    it.deviceId == pending.inviterDeviceId &&
                        it.fingerprint == invite.ownerFingerprint
                }
                val response = signedInviteResponse(invite, accepted = false)
                attempted++
                P2PMessageRelay.sendGroupFrame(
                    context,
                    owner.peerName,
                    GroupControlFrames.inviteResponseToJson(response),
                )
            }.onFailure {
                Log.w(TAG, "Could not retry declined group invite: ${it.message}")
            }
        }
        return attempted
    }

    suspend fun flushDueOutbox(limit: Int = 200): Int = outboxFlushMutex.withLock {
        val context = applicationContext ?: return@withLock 0
        val storage = database ?: return@withLock 0
        val tasks = storage.loadDueOutbox(System.currentTimeMillis(), limit)
        tasks.forEach { task ->
            val member = storage.getMember(task.groupId, task.recipientDeviceId)
            if (
                member?.status in setOf("LEFT", "BANNED") &&
                !taskCarriesMemberRemoval(task)
            ) {
                storage.failOutbox(task.taskId, "recipient is no longer a group member")
                return@forEach
            }
            if (member == null || member.peerName.isBlank()) {
                storage.retryOutbox(
                    task.taskId,
                    retryPolicy.nextAttemptAt(
                        System.currentTimeMillis(),
                        task.attempts,
                        task.taskId,
                    ),
                    "recipient unavailable",
                )
                return@forEach
            }
            if (!ensurePinnedGroupRoute(member)) {
                storage.retryOutbox(
                    task.taskId,
                    retryPolicy.nextAttemptAt(
                        System.currentTimeMillis(),
                        task.attempts,
                        task.taskId,
                    ),
                    "peer transport identity conflicts with the signed group roster",
                )
                return@forEach
            }
            val payload = runCatching {
                JSONObject(task.payload.toString(Charsets.UTF_8))
            }.getOrNull()
            if (payload == null) {
                storage.retryOutbox(
                    task.taskId,
                    Long.MAX_VALUE,
                    "invalid persisted group frame",
                )
                return@forEach
            }
            P2PMessageRelay.sendGroupFrame(context, member.peerName, payload) { sent ->
                scope.launch {
                    val now = System.currentTimeMillis()
                    storage.retryOutbox(
                        task.taskId,
                        if (sent) {
                            now + ACK_RETRY_MS
                        } else {
                            retryPolicy.nextAttemptAt(now, task.attempts, task.taskId)
                        },
                        if (sent) "awaiting durable store acknowledgement" else "peer offline",
                        now,
                    )
                }
            }
        }
        tasks.size
    }

    private suspend fun createGroupInternal(
        title: String,
        description: String,
        contactIds: Set<String>,
    ): String {
        val context = requireNotNull(applicationContext)
        val prefs = P2PPreferences.prefs(context)
        val local = localIdentity()
        val contacts = contactIds.map { peerName ->
            val fingerprint = prefs.getString(P2PPreferences.peerFingerprint(peerName), null)
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("$peerName has no verified identity")
            StoredGroupMember(
                groupId = "",
                deviceId = stableDeviceId(fingerprint),
                accountId = fingerprint,
                displayName = peerName.take(160),
                transportFingerprint = fingerprint,
                peerName = peerName.take(160),
                signingKeyBase64 = "",
                role = GroupRole.MEMBER.name,
                permissions = GroupRolePolicy.defaultPermissions(GroupRole.MEMBER).bits,
                status = "INVITED",
                joinedEpoch = 1,
            )
        }.distinctBy { it.deviceId }
        require(contacts.size <= GroupWireProtocol.MAX_GROUP_MEMBERS_IN_INVITE - 1)
        val groupId = UUID.randomUUID().toString()
        val owner = StoredGroupMember(
            groupId = groupId,
            deviceId = local.deviceId,
            accountId = local.fingerprint,
            displayName = local.displayName,
            transportFingerprint = local.fingerprint,
            peerName = local.displayName,
            signingKeyBase64 = local.signingKey,
            role = GroupRole.OWNER.name,
            permissions = GroupRolePolicy.defaultPermissions(GroupRole.OWNER).bits,
            status = "ACTIVE",
            joinedEpoch = 1,
        )
        val members = listOf(owner) + contacts.map { it.copy(groupId = groupId) }
        val secret = EpochAeadGroupCrypto.generateEpochSecret()
        db().createGroup(
            StoredGroup(
                groupId = groupId,
                title = title,
                description = description,
                localDeviceId = local.deviceId,
                ownerDeviceId = local.deviceId,
                currentEpoch = 1,
            ),
            members,
            StoredGroupEpochKey(groupId, 1, secret),
        )

        enqueuePendingMemberInvites(groupId)
        refreshGroup(groupId)
        refreshAllGroups()
        flushDueOutbox()
        return groupId
    }

    private suspend fun processIncoming(senderPeerName: String, json: JSONObject) {
        when (json.optString("type")) {
            GroupWireProtocol.TYPE_INVITE -> receiveInvite(senderPeerName, json)
            GroupWireProtocol.TYPE_INVITE_RESPONSE -> receiveInviteResponse(senderPeerName, json)
            GroupWireProtocol.TYPE_EVENT -> receiveEvent(senderPeerName, json, acknowledge = true)
            GroupWireProtocol.TYPE_STORE_ACK -> receiveStoreAck(senderPeerName, json)
            GroupWireProtocol.TYPE_SYNC_REQUEST -> receiveSyncRequest(senderPeerName, json)
            GroupWireProtocol.TYPE_SYNC_BATCH -> receiveSyncBatch(senderPeerName, json)
            GroupWireProtocol.TYPE_KEY_PACKAGE -> receiveKeyPackage(senderPeerName, json)
            GroupWireProtocol.TYPE_ROSTER_SNAPSHOT -> receiveRosterSnapshot(senderPeerName, json)
            GroupWireProtocol.TYPE_ATTACHMENT_REQUEST ->
                receiveAttachmentRequest(senderPeerName, json)
            GroupWireProtocol.TYPE_ATTACHMENT_BLOCK ->
                receiveAttachmentBlock(senderPeerName, json)
            GroupWireProtocol.TYPE_JOIN_REQUEST ->
                receiveJoinRequest(senderPeerName, json)
            GroupWireProtocol.TYPE_TYPING ->
                receiveGroupTyping(senderPeerName, json)
        }
    }

    private suspend fun receiveGroupTyping(senderPeerName: String, json: JSONObject) {
        val groupId = json.optString("group_id").take(128)
        val deviceId = json.optString("device_id").take(128)
        val displayName = json.optString("display_name").take(160)
        val isTyping = json.optBoolean("is_typing", false)
        val group = db().getGroup(groupId) ?: return
        requireTransportMember(groupId, senderPeerName)
        val member = db().getMember(groupId, deviceId) ?: return
        if (!member.isParticipating() || deviceId == group.localDeviceId) return

        val memberName = member.displayName.ifBlank { displayName.ifBlank { senderPeerName } }
        val groupTyping = typingMembersByGroup.computeIfAbsent(groupId) { ConcurrentHashMap() }
        if (isTyping) {
            groupTyping[memberName] = System.currentTimeMillis()
        } else {
            groupTyping.remove(memberName)
        }
        refreshGroup(groupId)
    }

    private suspend fun receiveJoinRequest(senderPeerName: String, json: JSONObject) {
        require(json.optInt("version", -1) == GroupWireProtocol.VERSION)
        val groupId = json.optString("group_id").take(128)
        val suppliedToken = json.optString("invite_token").take(128)
        val group = db().getGroup(groupId) ?: return
        val localMember = db().getMember(groupId, group.localDeviceId) ?: return
        if (
            !GroupRolePolicy.canPerform(
                localMember.toPolicyMember(),
                GroupAction.INVITE_MEMBER,
            ).allowed
        ) {
            return
        }
        val expectedToken = inviteCapability(group)
        if (!MessageDigest.isEqual(
                suppliedToken.toByteArray(Charsets.UTF_8),
                expectedToken.toByteArray(Charsets.UTF_8),
            )
        ) {
            throw SecurityException("invalid group invite capability")
        }
        val prefs = P2PPreferences.prefs(requireNotNull(applicationContext))
        val fingerprint = prefs.getString(P2PPreferences.peerFingerprint(senderPeerName), null)
            ?.takeIf(String::isNotBlank)
            ?: return
        requestSerializedControl(
            groupId,
            "invite",
            JSONObject()
                .put("member_device_id", stableDeviceId(fingerprint))
                .put("fingerprint", fingerprint)
                .put("peer_name", senderPeerName.take(160)),
        )
    }

    private suspend fun receiveInvite(senderPeerName: String, json: JSONObject) {
        val invite = GroupWireProtocol.parseInvite(json)
        val now = System.currentTimeMillis()
        require(invite.cryptoSuite == EpochAeadGroupCrypto.suiteId) {
            "group invite uses an unsupported crypto suite"
        }
        require(invite.createdAtMs <= now + MAX_CLOCK_SKEW_MS) {
            "group invite was created too far in the future"
        }
        require(now - invite.createdAtMs <= INVITE_LIFETIME_MS) {
            "group invite has expired"
        }
        require(transportFingerprint(senderPeerName) == invite.senderFingerprint) {
            "group invite transport identity does not match its signed sender"
        }
        require(invite.verifySignature()) { "group invite signature is invalid" }
        require(invite.members.map { it.deviceId }.toSet().size == invite.members.size) {
            "group invite contains duplicate device identities"
        }
        require(invite.members.map { it.fingerprint }.toSet().size == invite.members.size) {
            "group invite contains duplicate account identities"
        }
        require(invite.members.all { it.deviceId == stableDeviceId(it.fingerprint) }) {
            "group invite contains an invalid device identity"
        }
        require(
            invite.members.all {
                runCatching { parseRole(it.role) }.getOrNull() in setOf(
                    GroupRole.OWNER,
                    GroupRole.MEMBER,
                ) && it.status in setOf("ACTIVE", "INVITED")
            },
        )
        val invitingOwner = invite.members.singleOrNull {
            it.fingerprint == invite.ownerFingerprint &&
                it.fingerprint == invite.senderFingerprint &&
                it.deviceId == stableDeviceId(invite.senderFingerprint) &&
                it.signingKey == invite.senderSigningKey &&
                parseRole(it.role) == GroupRole.OWNER &&
                it.status == "ACTIVE"
        } ?: throw SecurityException("group invite has no unique authenticated owner")
        require(invite.coordinatorFingerprint == invitingOwner.fingerprint) {
            "group invite coordinator is not its authenticated owner"
        }
        val local = localIdentity()
        val localEntry = invite.members.firstOrNull {
            it.fingerprint == local.fingerprint && it.deviceId == local.deviceId
        } ?: throw SecurityException("group invite is not addressed to this device")
        require(localEntry.role != GroupRole.OWNER.name) {
            "group invite cannot assign ownership to its recipient"
        }
        val existingGroup = db().getGroup(invite.groupId)
        if (existingGroup != null) {
            val existingLocal = db().getMember(invite.groupId, local.deviceId)
                ?: throw SecurityException("local group has no local membership record")
            val existingOwner = db().getMember(invite.groupId, existingGroup.ownerDeviceId)
                ?: throw SecurityException("provisional group has no authenticated owner")
            val ownerMatches =
                invitingOwner.deviceId == existingOwner.deviceId &&
                invitingOwner.fingerprint == existingOwner.transportFingerprint &&
                invitingOwner.signingKey == existingOwner.signingKeyBase64
            val ownerContinuity = ownerMatches ||
                (
                    existingLocal.status in setOf("LEFT", "BANNED") &&
                        verifyInviteOwnerContinuity(
                            existingGroup,
                            existingOwner,
                            invitingOwner,
                            invite,
                        )
                    )
            require(ownerContinuity) {
                "group invite has no valid continuity proof from the established owner"
            }
            val rejoinAllowed = GroupMembershipTransitions.canAcceptRejoinInvite(
                localStatus = existingLocal.status,
                ownerMatches = ownerContinuity,
                addressedAsInvited = localEntry.status == "INVITED",
                hasControlHead = invite.controlHead != null,
                existingEpoch = existingGroup.currentEpoch,
                removedEpoch = existingLocal.removedEpoch,
                incomingEpoch = invite.epoch,
                incomingCreatedAtMs = invite.createdAtMs,
                localUpdatedAtMs = existingLocal.updatedAtMs,
                maxClockSkewMs = MAX_CLOCK_SKEW_MS,
            )
            require(
                existingLocal.isParticipating() ||
                    existingLocal.status == "JOINING" ||
                    rejoinAllowed,
            ) {
                "invite collides with an unrelated or stale local group"
            }
            if (rejoinAllowed) {
                db().savePendingInvite(
                    StoredPendingInvite(
                        inviteId = invite.inviteId,
                        groupId = invite.groupId,
                        inviterDeviceId = invitingOwner.deviceId,
                        token = json.toString().toByteArray(Charsets.UTF_8),
                        expiresAtMs = invite.createdAtMs + INVITE_LIFETIME_MS,
                    ),
                )
                sendAck(
                    senderPeerName,
                    GroupStoreAck(invite.groupId, invite.inviteId, local.deviceId, now),
                )
                refreshPendingInvites()
                return
            }
            require(
                existingLocal.isParticipating() ||
                    GroupMembershipTransitions.canRefreshJoiningInvite(
                        localStatus = existingLocal.status,
                        ownerMatches = ownerMatches,
                        existingEpoch = existingGroup.currentEpoch,
                        incomingEpoch = invite.epoch,
                        sameControlHead = invite.controlHead == existingGroup.controlHead,
                        incomingCreatedAtMs = invite.createdAtMs,
                        existingUpdatedAtMs = existingGroup.updatedAtMs,
                        maxClockSkewMs = MAX_CLOCK_SKEW_MS,
                    ),
            ) { "refreshed invite changes or rolls back provisional group trust" }
            if (existingLocal.status == "JOINING") {
                db().savePendingInvite(
                    StoredPendingInvite(
                        inviteId = invite.inviteId,
                        groupId = invite.groupId,
                        inviterDeviceId = invitingOwner.deviceId,
                        token = json.toString().toByteArray(Charsets.UTF_8),
                        expiresAtMs = invite.createdAtMs + INVITE_LIFETIME_MS,
                    ),
                )
                sendAck(
                    senderPeerName,
                    GroupStoreAck(invite.groupId, invite.inviteId, local.deviceId, now),
                )
                acceptInviteInternal(invite.inviteId)
                return
            }
            sendAck(
                senderPeerName,
                GroupStoreAck(invite.groupId, invite.inviteId, local.deviceId, now),
            )
            return
        }
        db().savePendingInvite(
            StoredPendingInvite(
                inviteId = invite.inviteId,
                groupId = invite.groupId,
                inviterDeviceId = invitingOwner.deviceId,
                token = json.toString().toByteArray(Charsets.UTF_8),
                expiresAtMs = invite.createdAtMs + INVITE_LIFETIME_MS,
            ),
        )
        sendAck(
            senderPeerName,
            GroupStoreAck(invite.groupId, invite.inviteId, local.deviceId, now),
        )
        refreshPendingInvites()
    }

    private suspend fun acceptInviteInternal(inviteId: String) {
        val pending = db().listPendingInvites().firstOrNull { it.inviteId == inviteId } ?: return
        val invite = GroupWireProtocol.parseInvite(
            JSONObject(pending.token.toString(Charsets.UTF_8)),
        )
        require(invite.verifySignature())
        val local = localIdentity()
        val key = invite.epochSecretBase64.decodeBase64()
        require(key.size == 32)
        val members = invite.members.map { member ->
            StoredGroupMember(
                groupId = invite.groupId,
                deviceId = member.deviceId,
                accountId = member.fingerprint,
                displayName = member.peerName,
                transportFingerprint = member.fingerprint,
                peerName = member.peerName,
                signingKeyBase64 = if (member.fingerprint == local.fingerprint) {
                    local.signingKey
                } else {
                    member.signingKey
                },
                role = member.role,
                permissions = GroupRolePolicy.defaultPermissions(parseRole(member.role)).bits,
                status = if (member.fingerprint == local.fingerprint) "JOINING" else member.status,
                joinedEpoch = invite.epoch,
            )
        }
        val owner = members.singleOrNull { it.transportFingerprint == invite.ownerFingerprint }
            ?: throw SecurityException("group invite has no unique owner")
        val ownerLineage = validatedInviteOwnerLineage(invite, owner)
        val response = signedInviteResponse(invite, accepted = true)
        val responseTask = buildOutboxTask(
            invite.groupId,
            inviteResponseEventId(invite.inviteId),
            owner.deviceId,
            GroupControlFrames.inviteResponseToJson(response),
        )
        val savedAvatarPath = persistGroupInviteAvatar(invite)
        persistGroupInviteWallpaper(invite)
        val existingGroup = db().getGroup(invite.groupId)
        if (existingGroup == null) {
            db().createGroup(
                StoredGroup(
                    groupId = invite.groupId,
                    title = invite.title,
                    description = invite.description,
                    avatarUri = savedAvatarPath,
                    adminOnlyPosting = invite.adminOnlyPosting,
                    localDeviceId = local.deviceId,
                    ownerDeviceId = owner.deviceId,
                    currentEpoch = invite.epoch,
                    controlHead = invite.controlHead,
                    createdAtMs = invite.createdAtMs,
                ),
                members,
                StoredGroupEpochKey(invite.groupId, invite.epoch, key),
                ownerLineage,
                listOf(responseTask),
            )
        } else {
            val existingLocal = db().getMember(invite.groupId, local.deviceId)
                ?: throw SecurityException("local group has no local membership record")
            val existingOwner = db().getMember(invite.groupId, existingGroup.ownerDeviceId)
                ?: throw SecurityException("local group has no authenticated owner")
            val ownerMatches =
                owner.deviceId == existingOwner.deviceId &&
                    owner.transportFingerprint == existingOwner.transportFingerprint &&
                    owner.signingKeyBase64 == existingOwner.signingKeyBase64
            val localInviteEntry = invite.members.singleOrNull {
                it.deviceId == local.deviceId && it.fingerprint == local.fingerprint
            } ?: throw SecurityException("accepted invite is not addressed to this device")
            val ownerContinuity = ownerMatches ||
                (
                    existingLocal.status in setOf("LEFT", "BANNED") &&
                        verifyInviteOwnerContinuity(
                            existingGroup,
                            existingOwner,
                            GroupInviteMember(
                                fingerprint = owner.transportFingerprint,
                                peerName = owner.peerName,
                                deviceId = owner.deviceId,
                                signingKey = owner.signingKeyBase64,
                                role = owner.role,
                                status = owner.status,
                            ),
                            invite,
                        )
                    )
            require(ownerContinuity) {
                "accepted invite has no valid continuity proof from the established owner"
            }
            val rejoining = GroupMembershipTransitions.canAcceptRejoinInvite(
                localStatus = existingLocal.status,
                ownerMatches = ownerContinuity,
                addressedAsInvited = localInviteEntry.status == "INVITED",
                hasControlHead = invite.controlHead != null,
                existingEpoch = existingGroup.currentEpoch,
                removedEpoch = existingLocal.removedEpoch,
                incomingEpoch = invite.epoch,
                incomingCreatedAtMs = invite.createdAtMs,
                localUpdatedAtMs = existingLocal.updatedAtMs,
                maxClockSkewMs = MAX_CLOCK_SKEW_MS,
            )
            val refreshingJoin = GroupMembershipTransitions.canRefreshJoiningInvite(
                localStatus = existingLocal.status,
                ownerMatches = ownerMatches,
                existingEpoch = existingGroup.currentEpoch,
                incomingEpoch = invite.epoch,
                sameControlHead = invite.controlHead == existingGroup.controlHead,
                incomingCreatedAtMs = invite.createdAtMs,
                existingUpdatedAtMs = existingGroup.updatedAtMs,
                maxClockSkewMs = MAX_CLOCK_SKEW_MS,
            )
            require(rejoining || refreshingJoin) {
                "accepted invite is stale for the local membership state"
            }
            val updatedGroup = existingGroup.copy(
                title = invite.title,
                description = invite.description,
                ownerDeviceId = owner.deviceId,
                currentEpoch = invite.epoch,
                controlHead = invite.controlHead,
                updatedAtMs = System.currentTimeMillis(),
            )
            val quarantinedRoster = if (rejoining) {
                db().listMembers(invite.groupId)
                    .filter {
                        it.deviceId != owner.deviceId &&
                            it.deviceId != local.deviceId
                    }
                    .map {
                        it.copy(
                            role = GroupRole.LEFT.name,
                            permissions = 0L,
                            status = "LEFT",
                            removedEpoch = maxOf(it.removedEpoch ?: 0L, invite.epoch),
                        )
                    }
            } else {
                emptyList()
            }
            require(
                db().applyAcceptedInviteState(
                    expectedGroup = existingGroup,
                    updatedGroup = updatedGroup,
                    epochKey = StoredGroupEpochKey(invite.groupId, invite.epoch, key),
                    members = quarantinedRoster + members,
                    ownerLineage = ownerLineage,
                    syncCursors = invite.historyCursors.map { (deviceId, sequence) ->
                        StoredSyncCursor(
                            groupId = invite.groupId,
                            deviceId = deviceId,
                            lastAuthorSeq = sequence,
                            lastEventId = invite.controlHead,
                        )
                    },
                    outboxTasks = listOf(responseTask),
                ),
            ) { "group changed while accepting its invite" }
        }
        db().deletePendingInvite(inviteId)
        refreshPendingInvites()
        refreshGroup(invite.groupId)
        refreshAllGroups()
        flushDueOutbox()
    }

    private suspend fun declineInviteInternal(inviteId: String) {
        val pending = db().listPendingInvites().firstOrNull { it.inviteId == inviteId } ?: return
        val invite = GroupWireProtocol.parseInvite(
            JSONObject(pending.token.toString(Charsets.UTF_8)),
        )
        require(invite.verifySignature())
        require(db().markInviteDeclined(inviteId))
        refreshPendingInvites()
        flushDeclinedInviteResponses()
    }

    private suspend fun receiveInviteResponse(senderPeerName: String, json: JSONObject) =
        controlMutex.withLock {
            receiveInviteResponseLocked(senderPeerName, json)
        }

    private suspend fun receiveInviteResponseLocked(
        senderPeerName: String,
        json: JSONObject,
    ) {
        val response = GroupControlFrames.parseInviteResponse(json)
        require(response.verify())
        require(transportFingerprint(senderPeerName) == response.memberFingerprint)
        require(response.memberDeviceId == stableDeviceId(response.memberFingerprint))
        val group = db().getGroup(response.groupId) ?: return
        val inviteTask = db().getOutboxTask(
            outboxTaskId(response.groupId, response.inviteId, response.memberDeviceId),
        ) ?: throw SecurityException("invite response has no matching issued invite")
        val issuedInvite = GroupWireProtocol.parseInvite(
            JSONObject(inviteTask.payload.toString(Charsets.UTF_8)),
        )
        require(issuedInvite.verifySignature())
        require(issuedInvite.groupId == group.groupId)
        val currentOwner = db().getMember(group.groupId, group.ownerDeviceId)
            ?: throw SecurityException("group has no current owner")
        require(group.localDeviceId == currentOwner.deviceId)
        require(
            issuedInvite.senderFingerprint == currentOwner.transportFingerprint &&
                issuedInvite.ownerFingerprint == currentOwner.transportFingerprint &&
                issuedInvite.senderSigningKey == currentOwner.signingKeyBase64,
        ) { "invite was not issued by the current owner" }
        val issuedRecipient = issuedInvite.members.singleOrNull {
            it.deviceId == response.memberDeviceId &&
                it.fingerprint == response.memberFingerprint
        } ?: throw SecurityException("invite response recipient does not match issued invite")
        require(response.createdAtMs >= issuedInvite.createdAtMs)
        require(response.createdAtMs <= System.currentTimeMillis() + MAX_CLOCK_SKEW_MS)
        val member = db().getMember(response.groupId, response.memberDeviceId)
            ?: throw SecurityException("unknown group invite recipient")
        require(member.transportFingerprint == response.memberFingerprint)
        if (!response.accepted) {
            if (member.status == "INVITED") {
                executeSerializedControlLocked(
                    response.groupId,
                    "remove",
                    JSONObject().apply {
                        put("member_device_id", response.memberDeviceId)
                        put("status", "LEFT")
                    },
                    proposalEventId = null,
                )
                require(
                    db().getMember(response.groupId, response.memberDeviceId)
                        ?.status in setOf("LEFT", "BANNED"),
                ) { "declined invite removal was not committed" }
            } else {
                require(member.status in setOf("LEFT", "BANNED", "ACTIVE")) {
                    "invite recipient is no longer eligible"
                }
            }
            sendAck(
                senderPeerName,
                GroupStoreAck(
                    response.groupId,
                    inviteResponseEventId(response.inviteId),
                    group.localDeviceId,
                    System.currentTimeMillis(),
                ),
            )
            refreshGroup(response.groupId)
            return
        }
        if (
            issuedInvite.epoch != group.currentEpoch ||
            issuedInvite.controlHead != group.controlHead
        ) {
            enqueuePendingMemberInvites(group.groupId, response.memberDeviceId)
            flushDueOutbox()
            sendAck(
                senderPeerName,
                GroupStoreAck(
                    response.groupId,
                    inviteResponseEventId(response.inviteId),
                    group.localDeviceId,
                    System.currentTimeMillis(),
                ),
            )
            throw SecurityException("invite response refers to stale group state")
        }
        if (member.status == "ACTIVE") {
            require(response.accepted && member.signingKeyBase64 == response.memberSigningKey)
            enqueueRosterSnapshot(response.groupId, response.memberDeviceId)
            flushDueOutbox()
            sendAck(
                senderPeerName,
                GroupStoreAck(
                    response.groupId,
                    inviteResponseEventId(response.inviteId),
                    group.localDeviceId,
                    System.currentTimeMillis(),
                ),
            )
            return
        }
        require(member.status == "INVITED") { "invite recipient is no longer eligible" }
        val local = localIdentity()
        if (group.ownerDeviceId == local.deviceId) {
            val nextEpoch = group.currentEpoch + 1L
            val nextEpochSecret = db().getEpochKey(response.groupId, nextEpoch)?.keyMaterial
                ?: EpochAeadGroupCrypto.generateEpochSecret().also {
                    check(
                        db().storeEpochKey(
                            StoredGroupEpochKey(response.groupId, nextEpoch, it),
                        ),
                    )
                }
            val acceptance = emitEvent(
                response.groupId,
                GroupEventKind.MEMBER_ADDED,
                JSONObject().apply {
                    put("member_device_id", response.memberDeviceId)
                    put("fingerprint", response.memberFingerprint)
                    put("peer_name", senderPeerName)
                    put("signing_key", response.memberSigningKey)
                    put("role", GroupRole.MEMBER.name)
                    put("permissions", GroupRolePolicy.defaultPermissions(GroupRole.MEMBER).bits)
                    put("status", "ACTIVE")
                    put("joined_epoch", nextEpoch)
                    put("next_epoch", nextEpoch)
                },
                response.memberDeviceId,
            )
            if (acceptance != null) {
                enqueueEpochKeyPackages(
                    response.groupId,
                    acceptance.eventId,
                    nextEpoch,
                    nextEpochSecret,
                )
                enqueueRosterSnapshot(response.groupId, response.memberDeviceId)
                flushDueOutbox()
            } else {
                val committed = db().getMember(response.groupId, response.memberDeviceId)
                require(
                    committed?.status == "ACTIVE" &&
                        committed.signingKeyBase64 == response.memberSigningKey,
                ) { "accepted membership was not committed" }
            }
        }
        sendAck(
            senderPeerName,
            GroupStoreAck(
                response.groupId,
                inviteResponseEventId(response.inviteId),
                group.localDeviceId,
                System.currentTimeMillis(),
            ),
        )
        refreshGroup(response.groupId)
    }

    private suspend fun receiveStoreAck(senderPeerName: String, json: JSONObject) {
        val ack = GroupControlFrames.parseStoreAck(json)
        val group = db().getGroup(ack.groupId)
        if (group == null) {
            val inviteId = ack.eventId
                .takeIf { it.startsWith(INVITE_RESPONSE_PREFIX) }
                ?.removePrefix(INVITE_RESPONSE_PREFIX)
                ?: return
            val pending = db().getStoredInvite(inviteId)
                ?.takeIf { it.state == "DECLINED" && it.groupId == ack.groupId }
                ?: return
            val invite = GroupWireProtocol.parseInvite(
                JSONObject(pending.token.toString(Charsets.UTF_8)),
            )
            val owner = invite.members.singleOrNull {
                it.deviceId == pending.inviterDeviceId &&
                    it.fingerprint == invite.ownerFingerprint
            } ?: return
            require(ack.recipientDeviceId == owner.deviceId)
            require(transportFingerprint(senderPeerName) == owner.fingerprint)
            db().deletePendingInvite(inviteId)
            return
        }
        val exactTask = db().getOutboxTask(
            outboxTaskId(ack.groupId, ack.eventId, ack.recipientDeviceId),
        )
        if (exactTask != null) {
            val issuedInvite = runCatching {
                GroupWireProtocol.parseInvite(
                    JSONObject(exactTask.payload.toString(Charsets.UTF_8)),
                )
            }.getOrNull()
            if (issuedInvite != null) {
                require(issuedInvite.verifySignature())
                require(issuedInvite.groupId == group.groupId)
                val recipient = issuedInvite.members.singleOrNull {
                    it.deviceId == ack.recipientDeviceId
                } ?: throw SecurityException("invite ACK has no matching signed recipient")
                require(transportFingerprint(senderPeerName) == recipient.fingerprint)
                require(
                    db().getMember(group.groupId, recipient.deviceId)
                        ?.transportFingerprint == recipient.fingerprint,
                )
                db().acknowledgeOutbox(exactTask.taskId)
                refreshGroup(ack.groupId)
                return
            }
            val removalEvent = runCatching {
                GroupWireProtocol.parseEvent(
                    JSONObject(exactTask.payload.toString(Charsets.UTF_8)),
                )
            }.getOrNull()?.takeIf { it.kind == GroupEventKind.MEMBER_REMOVED }
            if (removalEvent != null) {
                val recipient = db().getMember(group.groupId, ack.recipientDeviceId)
                    ?: throw SecurityException("removal ACK has no roster recipient")
                val author = db().getMember(group.groupId, removalEvent.authorDeviceId)
                    ?: throw SecurityException("removal ACK refers to an unknown author")
                require(transportFingerprint(senderPeerName) == recipient.transportFingerprint)
                require(removalEvent.verifySignature(author.signingKeyBase64))
                require(isCanonicalControlEvent(group, removalEvent.eventId))
                require(memberWasActiveAt(recipient, removalEvent.epoch))
                val epochKey = db().getEpochKey(group.groupId, removalEvent.epoch)
                    ?: throw SecurityException("removal ACK refers to a forgotten epoch")
                val removalPayload = eventFactory.decrypt(removalEvent, epochKey.keyMaterial)
                require(
                    removalPayload.optString("member_device_id") == recipient.deviceId,
                ) { "removal ACK sender is not the removed member" }
                db().acknowledgeOutbox(exactTask.taskId)
                refreshGroup(ack.groupId)
                return
            }
        }
        val member = requireTransportMember(group.groupId, senderPeerName)
        require(ack.recipientDeviceId == member.deviceId)
        db().acknowledgeOutbox(outboxTaskId(ack.groupId, ack.eventId, ack.recipientDeviceId))
        refreshGroup(ack.groupId)
    }

    private suspend fun receiveEvent(
        senderPeerName: String,
        json: JSONObject,
        acknowledge: Boolean,
    ) = controlMutex.withLock {
        emitMutex.withLock {
            receiveEventLocked(senderPeerName, json, acknowledge)
        }
    }

    private suspend fun receiveEventLocked(
        senderPeerName: String,
        json: JSONObject,
        acknowledge: Boolean,
    ) {
        val event = GroupWireProtocol.parseEvent(json)
        val group = db().getGroup(event.groupId) ?: return
        requireTransportMember(group.groupId, senderPeerName)
        val author = db().getMember(group.groupId, event.authorDeviceId)
            ?: throw SecurityException("group event author is absent from the accepted roster")
        require(author.deviceId == stableDeviceId(author.transportFingerprint)) {
            "group event author has an invalid roster identity"
        }
        require(event.authorFingerprint == author.transportFingerprint) {
            "group event fingerprint does not match the accepted roster"
        }
        require(author.signingKeyBase64.isNotBlank()) {
            "group event author has no accepted signing key"
        }
        require(event.verifySignature(author.signingKeyBase64)) {
            "group event signature is invalid"
        }
        val sequenceOccupant = db().getEventByAuthorSequence(
            group.groupId,
            event.authorDeviceId,
            event.authorSequence,
            minimumEpoch = author.joinedEpoch,
        )
        require(sequenceOccupant == null || sequenceOccupant.eventId == event.eventId) {
            "group author equivocation at sequence ${event.authorSequence}"
        }
        val acceptedBaseline = db().getSyncCursor(group.groupId, event.authorDeviceId)
            ?.lastAuthorSeq
            ?: 0L
        val highestStored = db().nextAuthorSequence(
            group.groupId,
            event.authorDeviceId,
        ) - 1L
        val sequenceFloor = maxOf(acceptedBaseline, highestStored)
        val sequenceCeiling = if (
            sequenceFloor > Long.MAX_VALUE - MAX_AUTHOR_SEQUENCE_GAP
        ) {
            Long.MAX_VALUE
        } else {
            sequenceFloor + MAX_AUTHOR_SEQUENCE_GAP
        }
        if (event.authorSequence == 1L) {
            require(event.previousAuthorEvent == null)
        } else {
            require(!event.previousAuthorEvent.isNullOrBlank())
            db().getEventByAuthorSequence(
                group.groupId,
                event.authorDeviceId,
                event.authorSequence - 1L,
                minimumEpoch = author.joinedEpoch,
            )?.let { predecessor ->
                require(event.previousAuthorEvent == predecessor.eventId) {
                    "group author hash chain does not reference its predecessor"
                }
            }
        }
        if (event.authorSequence < Long.MAX_VALUE) {
            db().getEventByAuthorSequence(
                group.groupId,
                event.authorDeviceId,
                event.authorSequence + 1L,
                minimumEpoch = author.joinedEpoch,
            )?.let { successor ->
                val successorWire = successor.payload?.let {
                    GroupWireProtocol.parseEvent(JSONObject(it.toString(Charsets.UTF_8)))
                } ?: throw SecurityException("stored group successor has no wire payload")
                require(successorWire.previousAuthorEvent == event.eventId) {
                    "group author hash chain conflicts with its stored successor"
                }
            }
        }
        val now = System.currentTimeMillis()
        require(event.hlcPhysicalMs <= now + MAX_CLOCK_SKEW_MS)
        require(event.expiresAtMs == 0L || event.expiresAtMs >= now)
        val epochKey = db().getEpochKey(group.groupId, event.epoch)
            ?: throw SecurityException("missing group epoch key")
        val payload = eventFactory.decrypt(event, epochKey.keyMaterial)
        val localMembership = db().getMember(group.groupId, group.localDeviceId)
            ?: throw SecurityException("local device has no group membership")
        var isJoiningBootstrapControl = false
        if (localMembership.status == "JOINING") {
            val isLocalActivation =
                event.kind == GroupEventKind.MEMBER_ADDED &&
                    payload.optString("status") == "ACTIVE"
            val isLocalRemoval =
                event.kind == GroupEventKind.MEMBER_REMOVED &&
                    payload.optString("status") in setOf("LEFT", "BANNED")
            require(
                (isLocalActivation || isLocalRemoval) &&
                    payload.optString("member_device_id") == group.localDeviceId &&
                    event.authorDeviceId == group.ownerDeviceId &&
                    (
                        event.controlHead == group.controlHead ||
                            event.eventId == group.controlHead
                        ),
            ) {
                "group roster bootstrap must complete before later events are accepted"
            }
            isJoiningBootstrapControl = isLocalActivation || isLocalRemoval
        }
        require(
            isJoiningBootstrapControl || event.authorSequence <= sequenceCeiling,
        ) { "group author sequence gap exceeds the accepted window" }
        val existing = db().getEvent(group.groupId, event.eventId)
        if (existing != null) {
            if (isSerializedControl(event.kind)) {
                applySerializedControl(group, event, payload)
                drainStoredControlChain(group.groupId)
            }
            maybeProcessControlProposal(group, event, payload, author)
            if (acknowledge) {
                sendAck(
                    senderPeerName,
                    GroupStoreAck(group.groupId, event.eventId, group.localDeviceId, now),
                )
            }
            return
        }
        require(validatePolicy(group, author, event, payload))

        val stored = event.toStored(payload, json)
        val inserted = db().ingestEvent(
            stored,
            countAsUnread = event.authorDeviceId != group.localDeviceId &&
                event.kind in setOf(
                    GroupEventKind.MESSAGE,
                    GroupEventKind.POLL,
                    GroupEventKind.MEDIA,
                    GroupEventKind.REPLY,
                ),
        )
        if (!inserted) {
            // Another receiver coroutine may have committed the exact duplicate
            // after the check above. Only that exact event is acknowledged.
            require(db().getEvent(group.groupId, event.eventId) != null) {
                "group event conflicted with an existing author sequence"
            }
            if (acknowledge) {
                sendAck(
                    senderPeerName,
                    GroupStoreAck(group.groupId, event.eventId, group.localDeviceId, now),
                )
            }
            return
        }
        if (isSerializedControl(event.kind)) {
            applySerializedControl(group, event, payload)
            drainStoredControlChain(group.groupId)
        } else {
            applyApplicationProjection(event)
        }
        maybeProcessControlProposal(group, event, payload, author)
        if (acknowledge) {
            sendAck(
                senderPeerName,
                GroupStoreAck(group.groupId, event.eventId, group.localDeviceId, now),
            )
        }
        if (inserted && event.authorDeviceId != group.localDeviceId &&
            event.kind in setOf(
                GroupEventKind.MESSAGE,
                GroupEventKind.POLL,
                GroupEventKind.MEDIA,
                GroupEventKind.REPLY,
            )
        ) {
            applicationContext?.let { context ->
                val prefs = com.example.twopchat.config.P2PPreferences.prefs(context)
                val text = if (event.kind == GroupEventKind.POLL) {
                    val appLang = prefs.getString("app_language", "en") ?: "en"
                    val pollPrefix = com.example.twopchat.data.Localizations.tr(appLang, "poll_notification_prefix", "Poll: ")
                    "$pollPrefix${payload.optString("question")}"
                } else {
                    payload.optString("text")
                }
                val myDisplayName = prefs.getString("username_profile", "") ?: ""
                val isMentioned = GroupNotificationService.isGroupMention(text, myDisplayName)
                val isChatActive = group.groupId in activeGroupChats

                if (!isChatActive || isMentioned) {
                    GroupNotificationService.show(
                        context,
                        group.groupId,
                        group.title,
                        author.displayName,
                        text,
                    )
                } else {
                    GroupNotificationService.cancelNotificationForGroup(context, group.groupId)
                }
            }
        }
        if (inserted) replicateStoredEvent(group, event, json, senderPeerName)
        if (inserted && event.kind == GroupEventKind.MEDIA) {
            val manifestsToSeed = mutableListOf<GroupAttachmentManifest>()
            payload.optJSONArray("attachments")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { json ->
                        runCatching { GroupAttachmentManifest.fromJson(json) }.getOrNull()?.let { manifestsToSeed.add(it) }
                    }
                }
            }
            if (manifestsToSeed.isEmpty()) {
                payload.optJSONObject("attachment")?.let { manifestJson ->
                    runCatching { GroupAttachmentManifest.fromJson(manifestJson) }.getOrNull()?.let { manifestsToSeed.add(it) }
                }
            }
            val isSmallMediaOrSticker = manifestsToSeed.any {
                it.mimeType.contains("sticker") ||
                it.mimeType.startsWith("audio/") ||
                StickerSupport.isStickerFileName(it.fileName) ||
                it.plaintextSize <= 512 * 1024L
            }
            if (isSmallMediaOrSticker || shouldSeedAttachment(group, stored)) {
                for (manifest in manifestsToSeed) {
                    attachmentManifests[
                        attachmentManifestKey(group.groupId, event.eventId)
                    ] = manifest
                    requestMissingAttachmentBlocks(
                        group.groupId,
                        event.eventId,
                        manifest,
                        preferredPeerName = senderPeerName,
                    )
                }
            }
        }
        refreshGroup(group.groupId)
        if (
            inserted &&
            event.authorDeviceId != group.localDeviceId &&
            group.groupId in activeGroupChats &&
            event.kind in setOf(
                GroupEventKind.MESSAGE,
                GroupEventKind.POLL,
                GroupEventKind.MEDIA,
                GroupEventKind.REPLY,
            )
        ) {
            markReadAndSendReceipt(group.groupId)
        }
    }

    private suspend fun receiveSyncRequest(senderPeerName: String, json: JSONObject) {
        val request = GroupControlFrames.parseSyncRequest(json)
        val group = db().getGroup(request.groupId) ?: return
        val requester = requireTransportMember(group.groupId, senderPeerName)
        require(request.requesterDeviceId == requester.deviceId)
        val events = mutableListOf<JSONObject>()
        var directOversizedEvent: JSONObject? = null
        var hasMore = false
        cursorLoop@ for ((authorDeviceId, after) in request.cursors.toSortedMap()) {
            if (events.size >= GroupWireProtocol.MAX_SYNC_EVENTS) {
                hasMore = true
                break
            }
            val author = db().getMember(group.groupId, authorDeviceId)
                ?: throw SecurityException("sync request contains a non-member author")
            val remaining = GroupWireProtocol.MAX_SYNC_EVENTS - events.size
            val stored = db().listEventsSince(
                group.groupId,
                author.deviceId,
                after,
                remaining,
                minimumEpoch = requester.joinedEpoch,
            )
            for (event in stored) {
                val wire = event.payload?.let {
                    JSONObject(it.toString(Charsets.UTF_8))
                } ?: continue
                events += wire
                val probe = GroupControlFrames.syncBatchToJson(
                    GroupSyncBatch(request.requestId, group.groupId, events, hasMore = true),
                )
                if (
                    probe.toString().toByteArray(Charsets.UTF_8).size >
                    GroupWireProtocol.MAX_WIRE_BYTES
                ) {
                    events.removeAt(events.lastIndex)
                    if (events.isEmpty()) directOversizedEvent = wire
                    hasMore = true
                    break@cursorLoop
                }
            }
            if (stored.size == remaining) hasMore = true
        }
        val batch = GroupSyncBatch(request.requestId, group.groupId, events, hasMore)
        val batchJson = GroupControlFrames.syncBatchToJson(batch)
        GroupWireProtocol.requireBoundedWire(batchJson.toString())
        val context = applicationContext ?: return
        directOversizedEvent?.let { oversized ->
            GroupWireProtocol.requireBoundedWire(oversized.toString())
            P2PMessageRelay.sendGroupFrame(
                context,
                senderPeerName,
                oversized,
            )
        }
        P2PMessageRelay.sendGroupFrame(
            context,
            senderPeerName,
            batchJson,
        )
    }

    private suspend fun receiveSyncBatch(senderPeerName: String, json: JSONObject) {
        val batch = GroupControlFrames.parseSyncBatch(json)
        val group = db().getGroup(batch.groupId) ?: return
        requireTransportMember(group.groupId, senderPeerName)
        var failedEvents = 0
        batch.events.forEach { eventJson ->
            if (runCatching {
                    receiveEvent(senderPeerName, eventJson, acknowledge = false)
                }.isFailure
            ) {
                failedEvents++
            }
        }
        val retryKey = "${group.groupId}\u0000$senderPeerName"
        if (batch.hasMore || failedEvents > 0) {
            val member = db().listMembers(group.groupId).firstOrNull { it.peerName == senderPeerName }
            val authorIds = syncRequestScopes.remove(batch.requestId)
            if (member != null && authorIds != null) {
                val failures = if (failedEvents > 0) {
                    syncFailureCounts.merge(retryKey, 1, Int::plus) ?: 1
                } else {
                    0
                }
                if (failures <= 5) {
                    if (failedEvents > 0) {
                        delay(retryPolicy.delayForAttempt(failures - 1, retryKey))
                    }
                    sendSyncRequestChunk(group, member, authorIds)
                }
            }
        } else {
            syncRequestScopes.remove(batch.requestId)
            syncFailureCounts.remove(retryKey)
        }
    }

    private suspend fun receiveKeyPackage(senderPeerName: String, json: JSONObject) {
        val keyPackage = GroupControlFrames.parseKeyPackage(json)
        val group = db().getGroup(keyPackage.groupId) ?: return
        require(keyPackage.recipientDeviceId == group.localDeviceId)
        val sender = requireTransportMember(group.groupId, senderPeerName)
        require(sender.deviceId == keyPackage.senderDeviceId)
        require(sender.transportFingerprint == keyPackage.senderFingerprint)
        require(sender.signingKeyBase64 == keyPackage.senderSigningKey)
        require(keyPackage.verify())
        val control = db().getEvent(group.groupId, keyPackage.controlHead)
            ?: throw SecurityException("epoch key package has no accepted control event")
        require(isCanonicalControlEvent(group, control.eventId)) {
            "epoch key package refers to a losing control fork"
        }
        require(control.authorDeviceId == sender.deviceId)
        require(control.kind in setOf(
            GroupEventKind.MEMBER_ADDED.name,
            GroupEventKind.MEMBER_REMOVED.name,
        ))
        val controlPayload = JSONObject(control.body.orEmpty())
        require(controlPayload.optLong("next_epoch", -1L) == keyPackage.epoch)
        require(keyPackage.epoch <= group.currentEpoch + 1L)
        val localMember = db().getMember(group.groupId, group.localDeviceId)
            ?: throw SecurityException("local device is not in the group roster")
        require(memberWasActiveAt(localMember, keyPackage.epoch))
        val secret = keyPackage.epochSecretBase64.decodeBase64()
        require(secret.size == 32)
        require(
            db().storeEpochKey(StoredGroupEpochKey(group.groupId, keyPackage.epoch, secret)),
        ) { "conflicting secret for an existing group epoch" }
        sendAck(
            senderPeerName,
            GroupStoreAck(
                group.groupId,
                keyPackageEventId(keyPackage.epoch, keyPackage.controlHead),
                group.localDeviceId,
                System.currentTimeMillis(),
            ),
        )
        refreshGroup(group.groupId)
    }

    private suspend fun receiveRosterSnapshot(senderPeerName: String, json: JSONObject) {
        val snapshot = GroupControlFrames.parseRosterSnapshot(json)
        val group = db().getGroup(snapshot.groupId) ?: return
        require(snapshot.recipientDeviceId == group.localDeviceId)
        require(snapshot.controlHead == group.controlHead) {
            "roster snapshot is not for the current control head"
        }
        require(snapshot.epoch == group.currentEpoch) {
            "roster snapshot is not for the current group epoch"
        }
        require(snapshot.createdAtMs <= System.currentTimeMillis() + MAX_CLOCK_SKEW_MS)
        val owner = db().getMember(group.groupId, group.ownerDeviceId)
            ?: throw SecurityException("group has no owner")
        requireTransportMember(group.groupId, senderPeerName)
        require(
            snapshot.ownerDeviceId == owner.deviceId &&
                snapshot.ownerFingerprint == owner.transportFingerprint &&
                snapshot.ownerSigningKey == owner.signingKeyBase64 &&
                transportFingerprint(senderPeerName) == owner.transportFingerprint,
        ) { "roster snapshot sender is not the current owner" }
        require(snapshot.verify()) { "invalid roster snapshot signature" }
        require(
            snapshot.members.none {
                it.role == GroupRole.OWNER && it.deviceId != group.ownerDeviceId
            },
        ) { "roster snapshot contains a second owner" }

        require(
            db().storeRosterSnapshotPage(
                StoredRosterSnapshotPage(
                    groupId = group.groupId,
                    controlHead = snapshot.controlHead,
                    epoch = snapshot.epoch,
                    pageIndex = snapshot.pageIndex,
                    totalPages = snapshot.totalPages,
                    snapshotCreatedAtMs = snapshot.createdAtMs,
                    payload = json.toString().toByteArray(Charsets.UTF_8),
                ),
            ),
        ) { "roster snapshot page conflicts with a stored page" }
        val storedPages = db().listRosterSnapshotPages(group.groupId, snapshot.controlHead)
        if (storedPages.size == snapshot.totalPages) {
            require(storedPages.map { it.pageIndex } == (0 until snapshot.totalPages).toList())
            val complete = storedPages.map { page ->
                GroupControlFrames.parseRosterSnapshot(
                    JSONObject(page.payload.toString(Charsets.UTF_8)),
                )
            }
            require(
                complete.all {
                    it.groupId == snapshot.groupId &&
                        it.recipientDeviceId == snapshot.recipientDeviceId &&
                        it.controlHead == snapshot.controlHead &&
                        it.epoch == snapshot.epoch &&
                        it.totalPages == snapshot.totalPages &&
                        it.createdAtMs == snapshot.createdAtMs &&
                        it.ownerDeviceId == snapshot.ownerDeviceId &&
                        it.ownerFingerprint == snapshot.ownerFingerprint &&
                        it.ownerSigningKey == snapshot.ownerSigningKey &&
                        it.verify()
                },
            ) { "roster snapshot pages do not form one signed generation" }
            val completeMembers = complete.flatMap { it.members }
            require(
                completeMembers.size in 1..GroupWireProtocol.MAX_GROUP_MEMBERS_IN_INVITE &&
                    completeMembers.map { it.deviceId }.toSet().size == completeMembers.size &&
                    completeMembers.map { it.fingerprint }.toSet().size == completeMembers.size,
            ) { "complete roster snapshot contains duplicate or excessive members" }
            val snapshotOwner = completeMembers.singleOrNull {
                it.deviceId == group.ownerDeviceId &&
                    it.role == GroupRole.OWNER &&
                    it.status == GroupRosterMemberStatus.ACTIVE
            } ?: throw SecurityException("complete roster snapshot has no unique owner")
            require(
                snapshotOwner.fingerprint == owner.transportFingerprint &&
                    snapshotOwner.signingKey == owner.signingKeyBase64,
            ) { "complete roster owner credential does not match the group" }
            val local = localIdentity()
            val snapshotLocal = completeMembers.singleOrNull {
                it.deviceId == group.localDeviceId
            } ?: throw SecurityException("complete roster snapshot omits this device")
            require(
                snapshotLocal.fingerprint == local.fingerprint &&
                    snapshotLocal.signingKey == local.signingKey &&
                    snapshotLocal.status == GroupRosterMemberStatus.ACTIVE,
            ) { "complete roster snapshot does not activate the local identity" }
            val updates = completeMembers.map { member ->
                val existing = db().getMember(group.groupId, member.deviceId)
                if (existing != null) {
                    require(
                        existing.accountId == member.fingerprint &&
                            existing.transportFingerprint == member.fingerprint &&
                            (
                                existing.signingKeyBase64.isBlank() ||
                                    existing.signingKeyBase64 == member.signingKey
                                ),
                    ) { "roster snapshot changes an established member identity" }
                }
                StoredGroupMember(
                    groupId = group.groupId,
                    deviceId = member.deviceId,
                    accountId = member.fingerprint,
                    displayName = member.displayName,
                    transportFingerprint = member.fingerprint,
                    peerName = member.peerName,
                    signingKeyBase64 = member.signingKey,
                    role = member.role.name,
                    permissions = member.permissions and GroupPermission.knownBits,
                    status = member.status.name,
                    joinedEpoch = member.joinedEpoch,
                    removedEpoch = member.removedEpoch,
                    createdAtMs = member.createdAtMs,
                    updatedAtMs = member.updatedAtMs,
                )
            }
            require(
                db().applyCompleteRosterSnapshot(
                    group.groupId,
                    snapshot.controlHead,
                    snapshot.epoch,
                    updates,
                    completeMembers.associate {
                        it.deviceId to it.lastAuthorSequence
                    },
                ),
            ) { "group advanced while its roster snapshot was assembling" }
        }
        sendAck(
            senderPeerName,
            GroupStoreAck(
                group.groupId,
                rosterSnapshotEventId(
                    snapshot.controlHead,
                    snapshot.pageIndex,
                ),
                group.localDeviceId,
                System.currentTimeMillis(),
            ),
        )
        refreshGroup(group.groupId)
    }

    private fun receiveAttachmentRequest(senderPeerName: String, json: JSONObject) {
        val request = GroupAttachmentFrames.parseRequest(json)
        val group = db().getGroup(request.groupId) ?: return
        val requester = requireTransportMember(group.groupId, senderPeerName)
        require(request.requesterDeviceId == requester.deviceId)
        val event = db().getEvent(group.groupId, request.eventId)
            ?: throw SecurityException("attachment request references an unknown event")
        require(event.kind == GroupEventKind.MEDIA.name)
        val manifests = loadAttachmentManifests(group.groupId, event.eventId)
        if (manifests.isEmpty()) {
            throw SecurityException("attachment request has no valid group manifest")
        }
        val allowedCids = manifests.flatMapTo(hashSetOf()) { manifest ->
            manifest.blocks.map { it.ciphertextCid }
        }
        require(request.ciphertextCids.all { it in allowedCids }) {
            "attachment request crosses its signed group manifest"
        }
        val context = applicationContext ?: return
        val blocks = request.ciphertextCids.mapNotNull { cid ->
            attachmentStore(group.groupId).readBlock(cid)?.let { cid to it }
        }
        val responseBytes = blocks.sumOf { it.second.size.toLong() }
        require(
            reserveAttachmentServeBudget(
                "${group.groupId}\u0000${requester.deviceId}",
                responseBytes,
            ),
        ) { "attachment response rate limit exceeded" }
        blocks.forEach { (cid, block) ->
            val frame = GroupAttachmentBlockFrame(
                requestId = request.requestId,
                groupId = group.groupId,
                senderDeviceId = group.localDeviceId,
                ciphertextCid = cid,
                ciphertextBase64 = Base64.encodeToString(block, Base64.NO_WRAP),
            )
            P2PMessageRelay.sendGroupFrame(
                context,
                senderPeerName,
                GroupAttachmentFrames.blockToJson(frame),
            )
        }
    }

    private suspend fun receiveAttachmentBlock(senderPeerName: String, json: JSONObject) {
        val block = GroupAttachmentFrames.parseBlock(json)
        val group = db().getGroup(block.groupId) ?: return
        val sender = requireTransportMember(group.groupId, senderPeerName)
        require(block.senderDeviceId == sender.deviceId)
        val pending = attachmentRequests[block.requestId]
            ?: throw SecurityException("unsolicited group attachment block")
        require(
            pending.groupId == group.groupId &&
                block.ciphertextCid in pending.requestedCids,
        )
        val ciphertext = try {
            Base64.decode(block.ciphertextBase64, Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw SecurityException("invalid attachment block Base64", error)
        }
        attachmentStore(group.groupId).putVerifiedBlock(block.ciphertextCid, ciphertext)
        if (
            attachmentStore(group.groupId).missingBlocks(pending.manifest)
                .none { it in pending.requestedCids }
        ) {
            attachmentRequests.remove(block.requestId)
        }
        assembleAttachmentIfComplete(
            pending.groupId,
            pending.eventId,
            pending.manifest,
        )
        refreshGroup(group.groupId)
    }

    private fun requestMissingAttachmentBlocks(
        groupId: String,
        eventId: String,
        manifest: GroupAttachmentManifest,
        preferredPeerName: String? = null,
    ) {
        val group = db().getGroup(groupId) ?: return
        val missing = attachmentStore(groupId).missingBlocks(manifest)
        if (missing.isEmpty()) {
            assembleAttachmentIfComplete(groupId, eventId, manifest)
            return
        }
        val connected = db().listMembers(groupId)
            .filter {
                it.isParticipating() &&
                it.deviceId != group.localDeviceId &&
                    (P2PMessageRelay.peerSessionStates[it.peerName] == true || it.peerName == preferredPeerName)
            }
        val sourceEvent = db().getEvent(groupId, eventId)
        val plannedReplicaIds = sourceEvent?.let { event ->
            val candidates = db().listMembers(groupId)
                .filter {
                    it.isParticipating() &&
                        it.deviceId != event.authorDeviceId
                }
                .map { ReplicaCandidate(DeviceId(it.deviceId)) }
            ReplicaPlanner.selectReplicas(
                event.authorDeviceId,
                candidates,
                LARGE_GROUP_REPLICAS,
            ).mapTo(hashSetOf()) { it.value }
        }.orEmpty()
        val peers = buildList {
            preferredPeerName?.let { preferred ->
                connected.firstOrNull { it.peerName == preferred }?.let(::add)
            }
            sourceEvent?.let { event ->
                connected.firstOrNull { it.deviceId == event.authorDeviceId }?.let(::add)
            }
            addAll(connected.filter { it.deviceId in plannedReplicaIds })
            addAll(connected.filter { it.deviceId !in plannedReplicaIds })
        }.distinctBy { it.deviceId }.take(3)
        if (peers.isEmpty()) return
        val context = applicationContext ?: return
        missing.chunked(GroupAttachmentFrames.MAX_CIDS_PER_REQUEST).forEach { cids ->
            peers.forEach { peer ->
                if (!ensurePinnedGroupRoute(peer)) return@forEach
                val requestId = UUID.randomUUID().toString()
                attachmentRequests[requestId] = PendingAttachmentRequest(
                    groupId = groupId,
                    eventId = eventId,
                    manifest = manifest,
                    requestedCids = cids.toSet(),
                )
                if (attachmentRequests.size > 2_048) {
                    attachmentRequests.keys
                        .take(attachmentRequests.size - 2_048)
                        .forEach(attachmentRequests::remove)
                }
                P2PMessageRelay.sendGroupFrame(
                    context,
                    peer.peerName,
                    GroupAttachmentFrames.requestToJson(
                        GroupAttachmentRequest(
                            requestId = requestId,
                            groupId = groupId,
                            eventId = eventId,
                            requesterDeviceId = group.localDeviceId,
                            ciphertextCids = cids,
                        ),
                    ),
                )
            }
        }
    }

    private fun repairReplicaAttachments(group: StoredGroup) {
        db().listRecentEvents(group.groupId, 1_000)
            .filter { it.kind == GroupEventKind.MEDIA.name }
            .forEach { event ->
                if (!shouldSeedAttachment(group, event)) return@forEach
                loadAttachmentManifests(group.groupId, event.eventId).forEach { manifest ->
                    if (attachmentStore(group.groupId).missingBlocks(manifest).isNotEmpty()) {
                        requestMissingAttachmentBlocks(group.groupId, event.eventId, manifest)
                    }
                }
            }
    }

    private fun shouldSeedAttachment(group: StoredGroup, event: StoredGroupEvent): Boolean {
        if (event.authorDeviceId == group.localDeviceId) return true
        val candidates = db().listMembers(group.groupId)
            .filter { it.isParticipating() && it.deviceId != event.authorDeviceId }
            .map { ReplicaCandidate(DeviceId(it.deviceId)) }
        return group.localDeviceId in ReplicaPlanner.selectReplicas(
            event.authorDeviceId,
            candidates,
            LARGE_GROUP_REPLICAS,
        ).map { it.value }
    }

    private suspend fun emitEvent(
        groupId: String,
        kind: GroupEventKind,
        payload: JSONObject,
        targetEventId: String? = null,
    ): GroupWireEvent? = emitMutex.withLock {
        val storage = db()
        val group = storage.getGroup(groupId) ?: return@withLock null
        val local = localIdentity()
        val author = storage.getMember(groupId, local.deviceId) ?: return@withLock null
        if (!validatePolicy(group, author, kind, targetEventId, payload)) return@withLock null
        val key = storage.getEpochKey(groupId, group.currentEpoch) ?: return@withLock null
        val sequence = storage.nextAuthorSequence(groupId, local.deviceId)
        val latestAuthor = storage.latestAuthorEvent(groupId, local.deviceId)
        val observed = listOfNotNull(
            latestAuthor,
            storage.listRecentEvents(groupId, 1).lastOrNull(),
            group.controlHead?.let { storage.getEvent(groupId, it) },
            targetEventId?.let { storage.getEvent(groupId, it) },
        )
        val wallClockMs = System.currentTimeMillis()
        val observedClock = observed.maxWithOrNull(
            compareBy<StoredGroupEvent>(
                StoredGroupEvent::hlcPhysicalMs,
                StoredGroupEvent::hlcLogical,
            ),
        )
        val nextClock = if (observedClock == null) {
            HybridLogicalClock(wallClockMs, 0, local.deviceId)
        } else {
            HybridLogicalClock(
                observedClock.hlcPhysicalMs,
                observedClock.hlcLogical.coerceAtMost(
                    HybridLogicalClock.MAX_LOGICAL_COUNTER,
                ),
                local.deviceId,
            ).tick(wallClockMs)
        }
        val event = eventFactory.create(
            groupId = groupId,
            epoch = group.currentEpoch,
            epochSecret = key.keyMaterial,
            kind = kind,
            authorFingerprint = local.fingerprint,
            authorDeviceId = local.deviceId,
            authorSequence = sequence,
            previousAuthorEvent = latestAuthor?.eventId,
            controlHead = group.controlHead,
            hlcPhysicalMs = nextClock.physicalTimeMs,
            hlcLogical = nextClock.logicalCounter,
            plaintextPayload = payload,
            targetEventId = targetEventId,
        )
        val json = GroupWireProtocol.eventToJson(event)
        val outboxTasks = buildEventOutboxTasks(group, event, payload, json)
        check(
            storage.ingestEventWithOutbox(
                event.toStored(payload, json),
                countAsUnread = false,
                tasks = outboxTasks,
            ),
        )
        if (isSerializedControl(kind)) {
            applySerializedControl(group, event, payload)
            drainStoredControlChain(group.groupId)
        } else {
            applyApplicationProjection(event)
        }
        refreshGroup(groupId)
        flushDueOutbox()
        event
    }

    private fun validatePolicy(
        group: StoredGroup,
        author: StoredGroupMember,
        event: GroupWireEvent,
        payload: JSONObject,
    ): Boolean {
        if (!isCanonicalControlContext(group, event.controlHead)) return false
        if (
            requiresCurrentControlHead(group, author, event, payload) &&
            event.controlHead != group.controlHead
        ) {
            return false
        }
        return validatePolicy(group, author, event.kind, event.targetEventId, payload)
    }

    private fun validatePolicy(
        group: StoredGroup,
        author: StoredGroupMember,
        kind: GroupEventKind,
        targetEventId: String?,
        payload: JSONObject,
    ): Boolean {
        if (!author.isParticipating()) return false
        val actor = author.toPolicyMember()
        if (
            kind in setOf(
                GroupEventKind.MESSAGE,
                GroupEventKind.REPLY,
                GroupEventKind.POLL,
                GroupEventKind.MEDIA,
            ) &&
            !GroupRolePolicy.canPostUnderGroupPolicy(group.adminOnlyPosting, actor.role)
        ) {
            return false
        }
        val decision = when (kind) {
            GroupEventKind.MESSAGE,
            GroupEventKind.REPLY,
            -> requireLinkPermission(
                actor,
                payload.optString("text"),
                GroupRolePolicy.canPerform(actor, GroupAction.POST_MESSAGE),
            )
            GroupEventKind.POLL -> {
                val question = payload.optString("question").trim()
                val options = payload.optJSONArray("options")
                val normalized = buildList {
                    if (options != null) {
                        for (index in 0 until options.length()) {
                            add(options.optString(index).trim())
                        }
                    }
                }
                if (
                    question.isEmpty() ||
                    question.length > 500 ||
                    normalized.size !in 2..10 ||
                    normalized.any { it.isEmpty() || it.length > 200 } ||
                    normalized.distinct().size != normalized.size
                ) {
                    PolicyDecision.deny(PolicyDenialReason.PERMISSION_MISSING)
                } else {
                    GroupRolePolicy.canPerform(actor, GroupAction.POST_MESSAGE)
                }
            }
            GroupEventKind.POLL_VOTE -> {
                val target = targetEventId?.let { db().getEvent(group.groupId, it) }
                    ?: return false
                val optionCount = runCatching {
                    require(target.kind == GroupEventKind.POLL.name)
                    JSONObject(target.body.orEmpty()).getJSONArray("options").length()
                }.getOrNull() ?: return false
                if (payload.optInt("option_id", -1) !in 0 until optionCount) {
                    PolicyDecision.deny(PolicyDenialReason.PERMISSION_MISSING)
                } else {
                    GroupRolePolicy.canPerform(actor, GroupAction.POST_MESSAGE)
                }
            }
            GroupEventKind.SYSTEM ->
                GroupRolePolicy.canPerform(actor, GroupAction.POST_MESSAGE)
            GroupEventKind.MEDIA -> requireLinkPermission(
                actor,
                payload.optString("caption"),
                GroupRolePolicy.canPerform(actor, GroupAction.POST_MEDIA),
            )
            GroupEventKind.EDIT -> {
                val target = targetEventId?.let { db().getEvent(group.groupId, it) } ?: return false
                val targetAuthor = db().getMember(group.groupId, target.authorDeviceId) ?: return false
                requireLinkPermission(
                    actor,
                    payload.optString("text"),
                    GroupRolePolicy.canEditMessage(actor, UserId(targetAuthor.accountId)),
                )
            }
            GroupEventKind.DELETE -> {
                val target = targetEventId?.let { db().getEvent(group.groupId, it) } ?: return false
                val targetAuthor = db().getMember(group.groupId, target.authorDeviceId) ?: return false
                GroupRolePolicy.canDeleteMessage(actor, UserId(targetAuthor.accountId))
            }
            GroupEventKind.REACTION_ADD,
            GroupEventKind.REACTION_REMOVE,
            -> GroupRolePolicy.canPerform(actor, GroupAction.ADD_REACTION)
            GroupEventKind.PIN,
            GroupEventKind.UNPIN,
            -> GroupRolePolicy.canPinMessage(actor)
            GroupEventKind.GROUP_UPDATED ->
                GroupRolePolicy.canPerform(actor, GroupAction.MANAGE_GROUP_INFO)
            GroupEventKind.MEMBER_ADDED -> {
                val targetId = payload.optString("member_device_id")
                val target = db().getMember(group.groupId, targetId)
                val requestedStatus = payload.optString("status")
                val requestedRole = runCatching {
                    parseRole(payload.optString("role"))
                }.getOrNull()
                val memberPermissions =
                    GroupRolePolicy.defaultPermissions(GroupRole.MEMBER).bits
                val validTransition = GroupMembershipTransitions.canApplyMemberAdded(
                    target?.status,
                    requestedStatus,
                )
                if (
                    !validTransition ||
                    requestedRole != GroupRole.MEMBER ||
                    payload.optLong("permissions", -1L) != memberPermissions ||
                    payload.optLong("next_epoch", -1L) != group.currentEpoch + 1L ||
                    (
                        requestedStatus == "ACTIVE" &&
                            payload.optLong("joined_epoch", -1L) !=
                            payload.optLong("next_epoch", -2L)
                        )
                ) {
                    return false
                }
                GroupRolePolicy.canPerform(actor, GroupAction.INVITE_MEMBER)
            }
            GroupEventKind.MEMBER_REMOVED -> {
                if (
                    payload.optLong("next_epoch", -1L) != group.currentEpoch + 1L
                ) {
                    return false
                }
                val targetId = payload.optString("member_device_id")
                if (targetId == author.deviceId && payload.optBoolean("voluntary")) {
                    com.example.twopchat.group.model.PolicyDecision.ALLOW
                } else {
                    val target = db().getMember(group.groupId, targetId)?.toPolicyMember()
                        ?: return false
                    if (payload.optString("status") == "BANNED") {
                        GroupRolePolicy.canBanMember(actor, target)
                    } else {
                        GroupRolePolicy.canRemoveMember(actor, target)
                    }
                }
            }
            GroupEventKind.ROLE_CHANGED -> {
                val target = db().getMember(
                    group.groupId,
                    payload.optString("member_device_id"),
                ) ?: return false
                if (target.status !in setOf("ACTIVE", "RESTRICTED")) return false
                val desired = runCatching { parseRole(payload.optString("role")) }.getOrNull()
                    ?: return false
                if (
                    payload.optLong("permissions", -1L) !=
                    GroupRolePolicy.defaultPermissions(desired).bits
                ) {
                    return false
                }
                GroupRolePolicy.canAssignRole(actor, target.toPolicyMember(), desired)
            }
            GroupEventKind.MEMBER_RESTRICTED -> {
                val storedTarget = db().getMember(
                    group.groupId,
                    payload.optString("member_device_id"),
                ) ?: return false
                if (storedTarget.status !in setOf("ACTIVE", "RESTRICTED")) return false
                val requested = payload.optLong("permissions", -1L)
                val memberPermissions =
                    GroupRolePolicy.defaultPermissions(GroupRole.MEMBER).bits
                if (requested < 0L || requested and memberPermissions.inv() != 0L) return false
                GroupRolePolicy.canRestrictMember(actor, storedTarget.toPolicyMember())
            }
            GroupEventKind.OWNERSHIP_TRANSFERRED -> {
                val target = db().getMember(
                    group.groupId,
                    payload.optString("member_device_id"),
                ) ?: return false
                if (target.status != "ACTIVE" || target.deviceId == author.deviceId) return false
                val certificate = runCatching {
                    GroupOwnerLineage.parse(
                        payload.getJSONObject("owner_transition"),
                    )
                }.getOrNull() ?: return false
                val lineage = currentOwnerLineage(group)
                if (
                    lineage.certificates.size >= GroupOwnerLineage.MAX_TRANSITIONS ||
                    certificate.groupId != group.groupId ||
                    certificate.previousOwnerAnchor != lineage.anchor ||
                    certificate.lineageSequence != lineage.nextSequence ||
                    certificate.previousControlHead != group.controlHead ||
                    certificate.transitionId() != targetEventId ||
                    !certificate.verify() ||
                    certificate.createdAtMs >
                    System.currentTimeMillis() + MAX_CLOCK_SKEW_MS ||
                    !sameCredential(
                        certificate.oldCredential(),
                        author.toOwnerCredential(),
                    ) ||
                    !sameCredential(
                        certificate.newCredential(),
                        target.toOwnerCredential(),
                    )
                ) {
                    return false
                }
                // A provisional JOINING client only knows the owner credential
                // from its signed invite. Finish or revoke every outstanding
                // invite before changing that trust anchor.
                if (
                    !GroupMembershipTransitions.canTransferOwnership(
                        hasPendingInvite = db().listMembers(group.groupId)
                            .any { it.status == "INVITED" },
                        hasPendingRosterDelivery =
                            db().hasPendingOutboxEventPrefix(group.groupId, "roster:"),
                    )
                ) {
                    return false
                }
                val roster = runCatching {
                    db().listMembers(group.groupId).map { it.toPolicyMember() }
                }.getOrNull() ?: return false
                GroupRolePolicy.transferOwnership(
                    roster,
                    actor.userId,
                    UserId(target.accountId),
                ).decision
            }
            GroupEventKind.READ_RECEIPT ->
                com.example.twopchat.group.model.PolicyDecision.ALLOW
            GroupEventKind.TYPING ->
                GroupRolePolicy.canPerform(actor, GroupAction.POST_MESSAGE)
        }
        if (!decision.allowed) return false
        // Membership and role mutations are serialized by the current owner.
        // Moderators/admins still operate content controls without a leader.
        if (isSerializedControl(kind) && author.deviceId != group.ownerDeviceId) {
            return false
        }
        return true
    }

    private fun requireLinkPermission(
        actor: GroupMember,
        text: String,
        baseDecision: PolicyDecision,
    ): PolicyDecision =
        if (baseDecision.allowed && GroupContentPolicy.containsExternalLink(text)) {
            GroupRolePolicy.canPerform(actor, GroupAction.POST_LINK)
        } else {
            baseDecision
        }

    private suspend fun requestSerializedControl(
        groupId: String,
        action: String,
        payload: JSONObject,
    ) {
        val group = db().getGroup(groupId) ?: return
        val local = db().getMember(groupId, group.localDeviceId) ?: return
        if (!validateProposalPermission(group, local, action, payload)) return
        if (group.ownerDeviceId == group.localDeviceId) {
            executeSerializedControl(groupId, action, payload, proposalEventId = null)
            return
        }
        emitEvent(
            groupId,
            GroupEventKind.SYSTEM,
            JSONObject(payload.toString()).apply { put("control_proposal", action) },
            payload.optString("member_device_id").takeIf { it.isNotBlank() },
        )
    }

    private fun maybeProcessControlProposal(
        group: StoredGroup,
        event: GroupWireEvent,
        payload: JSONObject,
        author: StoredGroupMember,
    ) {
        if (event.kind != GroupEventKind.SYSTEM || group.localDeviceId != group.ownerDeviceId) return
        val current = db().getGroup(group.groupId) ?: return
        if (event.controlHead != current.controlHead) return
        val action = payload.optString("control_proposal")
        if (action !in setOf(
                "set_role",
                "restrict",
                "remove",
                "leave",
                "update_info",
                "invite",
            )
        ) {
            return
        }
        if (db().hasSerializedControlTarget(group.groupId, event.eventId)) return
        if (!validateProposalPermission(group, author, action, payload)) return
        scope.launch {
            executeSerializedControl(group.groupId, action, payload, event.eventId)
        }
    }

    private fun validateProposalPermission(
        group: StoredGroup,
        author: StoredGroupMember,
        action: String,
        payload: JSONObject,
    ): Boolean {
        val actor = author.toPolicyMember()
        val targetId = payload.optString("member_device_id")
        val storedTarget = db().getMember(group.groupId, targetId)
        val target = storedTarget?.toPolicyMember()
        return when (action) {
            "set_role" -> {
                val desired = runCatching { parseRole(payload.optString("role")) }.getOrNull()
                    ?: return false
                storedTarget?.status in setOf("ACTIVE", "RESTRICTED") &&
                    payload.optLong("permissions", -1L) ==
                    GroupRolePolicy.defaultPermissions(desired).bits &&
                    target != null &&
                    GroupRolePolicy.canAssignRole(actor, target, desired).allowed
            }
            "restrict" -> {
                val requested = payload.optLong("permissions", -1L)
                val memberPermissions =
                    GroupRolePolicy.defaultPermissions(GroupRole.MEMBER).bits
                storedTarget?.status in setOf("ACTIVE", "RESTRICTED") &&
                    target != null &&
                    requested >= 0L &&
                    requested and memberPermissions.inv() == 0L &&
                    GroupRolePolicy.canRestrictMember(actor, target).allowed
            }
            "remove" -> target != null && if (payload.optString("status") == "BANNED") {
                GroupRolePolicy.canBanMember(actor, target).allowed
            } else {
                GroupRolePolicy.canRemoveMember(actor, target).allowed
            }
            "leave" -> targetId == author.deviceId && author.deviceId != group.ownerDeviceId
            "update_info" -> GroupRolePolicy.canPerform(
                actor,
                GroupAction.MANAGE_GROUP_INFO,
            ).allowed
            "invite" -> {
                val fingerprint = payload.optString("fingerprint")
                val peerName = payload.optString("peer_name")
                val occupied = db().listMembers(group.groupId).count {
                    it.status !in setOf("LEFT", "BANNED")
                }
                fingerprint.isNotBlank() && peerName.isNotBlank() &&
                    (
                        storedTarget == null ||
                            storedTarget.status in setOf("LEFT", "BANNED")
                        ) &&
                    occupied < GroupWireProtocol.MAX_GROUP_MEMBERS_IN_INVITE &&
                    GroupRolePolicy.canPerform(actor, GroupAction.INVITE_MEMBER).allowed
            }
            else -> false
        }
    }

    private suspend fun executeSerializedControl(
        groupId: String,
        action: String,
        payload: JSONObject,
        proposalEventId: String?,
    ) = controlMutex.withLock {
        executeSerializedControlLocked(groupId, action, payload, proposalEventId)
    }

    private suspend fun executeSerializedControlLocked(
        groupId: String,
        action: String,
        payload: JSONObject,
        proposalEventId: String?,
    ) {
        if (
            proposalEventId != null &&
            db().hasSerializedControlTarget(groupId, proposalEventId)
        ) {
            return
        }
        val controlPayload = if (proposalEventId != null) {
            val currentGroup = db().getGroup(groupId) ?: return
            val proposal = db().getEvent(groupId, proposalEventId) ?: return
            if (proposal.kind != GroupEventKind.SYSTEM.name) return
            val proposalWire = proposal.payload?.let {
                runCatching {
                    GroupWireProtocol.parseEvent(JSONObject(it.toString(Charsets.UTF_8)))
                }.getOrNull()
            } ?: return
            if (
                proposalWire.controlHead != currentGroup.controlHead ||
                proposalWire.authorDeviceId != proposal.authorDeviceId
            ) {
                return
            }
            val proposer = db().getMember(groupId, proposal.authorDeviceId) ?: return
            val storedPayload = runCatching { JSONObject(proposal.body.orEmpty()) }.getOrNull()
                ?: return
            if (
                storedPayload.optString("control_proposal") != action ||
                !validateProposalPermission(currentGroup, proposer, action, storedPayload)
            ) {
                return
            }
            storedPayload
        } else {
            payload
        }
        val targetId = controlPayload.optString("member_device_id")
        when (action) {
            "set_role" -> emitEvent(
                groupId,
                GroupEventKind.ROLE_CHANGED,
                JSONObject(controlPayload.toString()).apply {
                    proposalEventId?.let { put("proposal_event_id", it) }
                },
                proposalEventId ?: targetId,
            )
            "restrict" -> emitEvent(
                groupId,
                GroupEventKind.MEMBER_RESTRICTED,
                JSONObject(controlPayload.toString()).apply {
                    proposalEventId?.let { put("proposal_event_id", it) }
                },
                proposalEventId ?: targetId,
            )
            "remove",
            "leave",
            -> {
                val group = db().getGroup(groupId) ?: return
                val nextEpoch = group.currentEpoch + 1
                val newSecret = db().getEpochKey(groupId, nextEpoch)?.keyMaterial
                    ?: EpochAeadGroupCrypto.generateEpochSecret().also {
                        check(db().storeEpochKey(StoredGroupEpochKey(groupId, nextEpoch, it)))
                    }
                val event = emitEvent(
                    groupId,
                    GroupEventKind.MEMBER_REMOVED,
                    JSONObject(controlPayload.toString()).apply {
                        put("status", optString("status", "LEFT"))
                        put("next_epoch", nextEpoch)
                        proposalEventId?.let { put("proposal_event_id", it) }
                    },
                    proposalEventId ?: targetId,
                ) ?: return
                enqueueEpochKeyPackages(groupId, event.eventId, nextEpoch, newSecret)
                flushDueOutbox()
                refreshGroup(groupId)
            }
            "update_info" -> emitEvent(
                groupId,
                GroupEventKind.GROUP_UPDATED,
                JSONObject(controlPayload.toString()).apply {
                    proposalEventId?.let { put("proposal_event_id", it) }
                },
                proposalEventId,
            )
            "invite" -> inviteOneMember(groupId, controlPayload, proposalEventId)
        }
    }

    private suspend fun inviteOneMember(
        groupId: String,
        payload: JSONObject,
        proposalEventId: String?,
    ) {
        val group = db().getGroup(groupId) ?: return
        val local = localIdentity()
        val localMember = db().getMember(groupId, local.deviceId) ?: return
        if (!GroupRolePolicy.canPerform(localMember.toPolicyMember(), GroupAction.INVITE_MEMBER).allowed) return
        val deviceId = payload.optString("member_device_id")
        val fingerprint = payload.optString("fingerprint")
        val peerName = payload.optString("peer_name").take(160)
        if (deviceId != stableDeviceId(fingerprint) || peerName.isBlank()) return
        val existing = db().getMember(groupId, deviceId)
        if (existing != null && existing.status !in setOf("LEFT", "BANNED")) return
        val occupied = db().listMembers(groupId).count {
            it.status !in setOf("LEFT", "BANNED")
        }
        if (occupied >= GroupWireProtocol.MAX_GROUP_MEMBERS_IN_INVITE) return

        val nextEpoch = group.currentEpoch + 1
        val newSecret = db().getEpochKey(groupId, nextEpoch)?.keyMaterial
            ?: EpochAeadGroupCrypto.generateEpochSecret().also {
                check(db().storeEpochKey(StoredGroupEpochKey(groupId, nextEpoch, it)))
            }
        val event = emitEvent(
            groupId,
            GroupEventKind.MEMBER_ADDED,
            JSONObject().apply {
                put("member_device_id", deviceId)
                put("fingerprint", fingerprint)
                put("peer_name", peerName)
                put("signing_key", "")
                put("role", GroupRole.MEMBER.name)
                put("permissions", GroupRolePolicy.defaultPermissions(GroupRole.MEMBER).bits)
                put("status", "INVITED")
                put("next_epoch", nextEpoch)
                proposalEventId?.let { put("proposal_event_id", it) }
            },
            proposalEventId ?: deviceId,
        ) ?: return
        enqueueEpochKeyPackages(groupId, event.eventId, nextEpoch, newSecret)
        enqueuePendingMemberInvites(groupId, deviceId)
        flushDueOutbox()
        refreshGroup(groupId)
    }

    private fun applySerializedControl(
        originalGroup: StoredGroup,
        event: GroupWireEvent,
        payload: JSONObject,
    ) {
        val current = db().getGroup(originalGroup.groupId) ?: return
        if (current.controlHead == event.eventId) return
        if (current.controlHead != event.controlHead) return
        val nextEpoch = payload.optLong("next_epoch", 0L).takeIf { it > 0L }
        if (
            (
                event.kind == GroupEventKind.MEMBER_REMOVED ||
                    event.kind == GroupEventKind.MEMBER_ADDED
                ) &&
            nextEpoch != event.epoch + 1L
        ) {
            return
        }
        var nextOwner: String? = null
        var nextTitle: String? = null
        var nextDescription: String? = null
        var nextAvatarUri: String? = null
        var nextAdminOnlyPosting: Boolean? = null
        val memberUpdates = mutableListOf<StoredGroupMember>()
        when (event.kind) {
            GroupEventKind.GROUP_UPDATED -> {
                nextTitle = payload.optString("title", current.title).take(160)
                nextDescription = payload.optString(
                    "description",
                    current.description,
                ).take(2_000)
                if (payload.has("avatar_uri")) {
                    // If the peer sent inline avatar_data, decode and save locally
                    val avatarDataB64 = payload.optString("avatar_data", "")
                    val ctx = applicationContext
                    if (avatarDataB64.isNotBlank() && ctx != null) {
                        runCatching {
                            val avatarBytes = Base64.decode(avatarDataB64, Base64.NO_WRAP)
                            val avatarsDir = File(ctx.filesDir, "group_avatars").also { it.mkdirs() }
                            val destFile = File(avatarsDir, "${current.groupId}.jpg")
                            destFile.writeBytes(avatarBytes)
                            nextAvatarUri = destFile.absolutePath
                        }.onFailure {
                            nextAvatarUri = payload.optString("avatar_uri", "").ifBlank { null }
                        }
                    } else {
                        nextAvatarUri = payload.optString("avatar_uri", "").ifBlank { null }
                    }
                }
                if (payload.has("wallpaper_uri") || payload.has("wallpaper_data")) {
                    val wallpaperDataB64 = payload.optString("wallpaper_data", "")
                    val wallpaperUriInPayload = payload.optString("wallpaper_uri", "")
                    val wallpaperDimming = payload.optInt("wallpaper_dimming", 45)
                    val wallpaperBlur = payload.optBoolean("wallpaper_blur", false)
                    val ctx = applicationContext
                    if (wallpaperDataB64.isNotBlank() && ctx != null) {
                        // Inline base64 blob — save to local file and register the path.
                        runCatching {
                            val bytes = Base64.decode(wallpaperDataB64, Base64.NO_WRAP)
                            val dir = File(ctx.filesDir, "group_wallpapers").also { it.mkdirs() }
                            val destFile = File(dir, "${current.groupId}.jpg")
                            destFile.writeBytes(bytes)
                            P2PPreferences.prefs(ctx).edit().apply {
                                putString("group_wallpaper_${current.groupId}", destFile.absolutePath)
                                putInt("group_wallpaper_dimming_${current.groupId}", wallpaperDimming)
                                putBoolean("group_wallpaper_blur_${current.groupId}", wallpaperBlur)
                                apply()
                            }
                        }
                    } else if (wallpaperUriInPayload.isBlank() && ctx != null) {
                        // Explicit clear — remove wallpaper.
                        P2PPreferences.prefs(ctx).edit().apply {
                            remove("group_wallpaper_${current.groupId}")
                            remove("group_wallpaper_dimming_${current.groupId}")
                            remove("group_wallpaper_blur_${current.groupId}")
                            apply()
                        }
                    } else if (wallpaperUriInPayload.isNotBlank() && ctx != null) {
                        val fallbackFile = File(ctx.filesDir, "group_wallpapers/${current.groupId}.jpg")
                        val localPath = when {
                            File(wallpaperUriInPayload).exists() -> wallpaperUriInPayload
                            fallbackFile.exists() -> fallbackFile.absolutePath
                            else -> null
                        }
                        if (localPath != null) {
                            P2PPreferences.prefs(ctx).edit().apply {
                                putString("group_wallpaper_${current.groupId}", localPath)
                                putInt("group_wallpaper_dimming_${current.groupId}", wallpaperDimming)
                                putBoolean("group_wallpaper_blur_${current.groupId}", wallpaperBlur)
                                apply()
                            }
                        }
                    }
                }
                if (payload.has("admin_only_posting")) {
                    nextAdminOnlyPosting = payload.optBoolean("admin_only_posting")
                }
                if (nextTitle.isNullOrBlank()) return
            }
            GroupEventKind.MEMBER_ADDED -> {
                val deviceId = payload.optString("member_device_id")
                val existing = db().getMember(originalGroup.groupId, deviceId)
                val requestedStatus = payload.optString("status")
                val occupied = db().listMembers(originalGroup.groupId).count {
                    it.status !in setOf("LEFT", "BANNED")
                }
                if (
                    requestedStatus == "INVITED" &&
                    existing?.status !in setOf(null, "LEFT", "BANNED")
                ) {
                    return
                }
                if (
                    requestedStatus == "INVITED" &&
                    occupied >= GroupWireProtocol.MAX_GROUP_MEMBERS_IN_INVITE
                ) {
                    return
                }
                val peerName = payload.optString("peer_name").take(160)
                val fingerprint = payload.optString("fingerprint").take(256)
                val signingKey = payload.optString("signing_key").take(256)
                if (stableDeviceId(fingerprint) != deviceId) return
                if (
                    existing != null &&
                    (
                        existing.accountId != fingerprint ||
                            existing.transportFingerprint != fingerprint
                        )
                ) {
                    return
                }
                val role = GroupRole.MEMBER
                val status = requestedStatus.ifBlank { "ACTIVE" }.let {
                    if (it == "INVITED") "INVITED" else "ACTIVE"
                }
                val projectedStatus = if (
                    existing?.deviceId == originalGroup.localDeviceId &&
                    existing.status == "JOINING" &&
                    status == "ACTIVE"
                ) {
                    "JOINING"
                } else {
                    status
                }
                val updated = existing?.copy(
                    displayName = peerName.ifBlank { existing.displayName },
                    peerName = peerName.ifBlank { existing.peerName },
                    signingKeyBase64 = signingKey.ifBlank { existing.signingKeyBase64 },
                    role = role.name,
                    permissions = GroupRolePolicy.defaultPermissions(role).bits,
                    status = projectedStatus,
                    joinedEpoch = maxOf(
                        existing.joinedEpoch,
                        payload.optLong("joined_epoch", existing.joinedEpoch),
                    ),
                    removedEpoch = null,
                    updatedAtMs = event.hlcPhysicalMs,
                ) ?: StoredGroupMember(
                    groupId = originalGroup.groupId,
                    deviceId = deviceId,
                    accountId = fingerprint,
                    displayName = peerName,
                    transportFingerprint = fingerprint,
                    peerName = peerName,
                    signingKeyBase64 = signingKey,
                    role = role.name,
                    permissions = GroupRolePolicy.defaultPermissions(role).bits,
                    status = projectedStatus,
                    joinedEpoch = payload.optLong(
                        "joined_epoch",
                        payload.optLong("next_epoch", event.epoch),
                    ),
                    createdAtMs = event.hlcPhysicalMs,
                    updatedAtMs = event.hlcPhysicalMs,
                )
                if (updated.deviceId.isNotBlank() && updated.accountId.isNotBlank() &&
                    updated.displayName.isNotBlank()
                ) {
                    memberUpdates += updated
                } else return
            }
            GroupEventKind.ROLE_CHANGED -> {
                val deviceId = payload.optString("member_device_id")
                val existing = db().getMember(originalGroup.groupId, deviceId) ?: return
                val role = parseRole(payload.optString("role"))
                memberUpdates += existing.copy(
                    role = role.name,
                    permissions = GroupRolePolicy.defaultPermissions(role).bits,
                    status = "ACTIVE",
                    updatedAtMs = event.hlcPhysicalMs,
                )
            }
            GroupEventKind.MEMBER_RESTRICTED -> {
                val deviceId = payload.optString("member_device_id")
                val existing = db().getMember(originalGroup.groupId, deviceId) ?: return
                val memberPermissions =
                    GroupRolePolicy.defaultPermissions(GroupRole.MEMBER).bits
                val requestedPermissions =
                    payload.optLong("permissions", 0L) and memberPermissions
                memberUpdates += existing.copy(
                    role = GroupRole.MEMBER.name,
                    permissions = requestedPermissions,
                    status = if (requestedPermissions == memberPermissions) {
                        "ACTIVE"
                    } else {
                        "RESTRICTED"
                    },
                    updatedAtMs = event.hlcPhysicalMs,
                )
            }
            GroupEventKind.MEMBER_REMOVED -> {
                val deviceId = payload.optString("member_device_id")
                val existing = db().getMember(originalGroup.groupId, deviceId) ?: return
                val banned = payload.optString("status") == "BANNED"
                memberUpdates += existing.copy(
                    role = if (banned) GroupRole.BANNED.name else GroupRole.LEFT.name,
                    permissions = 0,
                    status = if (banned) "BANNED" else "LEFT",
                    removedEpoch = nextEpoch ?: event.epoch,
                    updatedAtMs = event.hlcPhysicalMs,
                )
            }
            GroupEventKind.OWNERSHIP_TRANSFERRED -> {
                val targetId = payload.optString("member_device_id")
                val oldOwner = db().getMember(originalGroup.groupId, current.ownerDeviceId)
                    ?: return
                val newOwner = db().getMember(originalGroup.groupId, targetId) ?: return
                if (newOwner.status != "ACTIVE" || newOwner.deviceId == oldOwner.deviceId) return
                val roster = runCatching {
                    db().listMembers(originalGroup.groupId).map { it.toPolicyMember() }
                }.getOrNull() ?: return
                if (
                    !GroupRolePolicy.transferOwnership(
                        roster,
                        UserId(oldOwner.accountId),
                        UserId(newOwner.accountId),
                    ).decision.allowed
                ) {
                    return
                }
                nextOwner = targetId
                memberUpdates += oldOwner.copy(
                    role = GroupRole.ADMINISTRATOR.name,
                    permissions = GroupRolePolicy.defaultPermissions(
                        GroupRole.ADMINISTRATOR,
                    ).bits,
                    updatedAtMs = event.hlcPhysicalMs,
                )
                memberUpdates += newOwner.copy(
                    role = GroupRole.OWNER.name,
                    permissions = GroupRolePolicy.defaultPermissions(GroupRole.OWNER).bits,
                    status = "ACTIVE",
                    updatedAtMs = event.hlcPhysicalMs,
                )
            }
            else -> return
        }
        val applied = db().applyControlMutation(
            groupId = current.groupId,
            expectedHead = event.controlHead,
            newHead = event.eventId,
            ownerDeviceId = nextOwner,
            currentEpoch = nextEpoch,
            title = nextTitle,
            description = nextDescription,
            avatarUri = nextAvatarUri,
            adminOnlyPosting = nextAdminOnlyPosting,
            members = memberUpdates,
            ownerLineageCertificate = if (
                event.kind == GroupEventKind.OWNERSHIP_TRANSFERRED
            ) {
                runCatching {
                    val transitionObj = payload.optJSONObject("owner_transition") ?: return@runCatching null
                    GroupOwnerLineage.parse(transitionObj)
                }.getOrNull()?.let { certificate ->
                    StoredOwnerLineageCertificate(
                        groupId = current.groupId,
                        sequence = certificate.lineageSequence,
                        transitionId = certificate.transitionId(),
                        payload = GroupOwnerLineage.toJson(certificate)
                            .toString()
                            .toByteArray(Charsets.UTF_8),
                    )
                }
            } else {
                null
            },
            admissionRecipientDeviceId = if (
                originalGroup.localDeviceId == originalGroup.ownerDeviceId &&
                event.kind == GroupEventKind.MEMBER_ADDED &&
                payload.optString("status") == "ACTIVE"
            ) {
                payload.optString("member_device_id").takeIf(String::isNotBlank)
            } else {
                null
            },
            updatedAtMs = event.hlcPhysicalMs,
        )
        if (!applied) {
            Log.w(TAG, "Control head race rejected ${event.eventId}")
        }
    }

    /**
     * A later control event may arrive before its parent. Once the missing
     * parent advances the head, replay already-validated children from durable
     * storage so an out-of-order delivery cannot strand the control chain.
     */
    private fun drainStoredControlChain(groupId: String) {
        repeat(100) {
            val current = db().getGroup(groupId) ?: return
            var advanced = false
            for (child in db().listControlChildren(groupId, current.controlHead, limit = 100)) {
                val childKind = runCatching {
                    GroupEventKind.valueOf(child.kind)
                }.getOrNull()
                if (childKind == null || !isSerializedControl(childKind)) continue
                val wireBytes = child.payload ?: continue
                val wire = runCatching {
                    GroupWireProtocol.parseEvent(
                        JSONObject(wireBytes.toString(Charsets.UTF_8)),
                    )
                }.getOrNull() ?: continue
                val key = db().getEpochKey(groupId, wire.epoch) ?: continue
                val payload = runCatching { eventFactory.decrypt(wire, key.keyMaterial) }
                    .getOrNull() ?: continue
                val author = db().getMember(groupId, wire.authorDeviceId) ?: continue
                if (!wire.verifySignature(author.signingKeyBase64) ||
                    !memberWasActiveAt(author, wire.epoch) ||
                    !validatePolicy(current, author, wire, payload)
                ) {
                    Log.w(TAG, "Quarantined unauthorized control child ${wire.eventId}")
                    continue
                }
                applySerializedControl(current, wire, payload)
                advanced = db().getGroup(groupId)?.controlHead != current.controlHead
                if (advanced) break
            }
            if (!advanced) return
        }
    }

    private fun applyApplicationProjection(@Suppress("UNUSED_PARAMETER") event: GroupWireEvent) {
        // Message mutations, pin state and read receipts are materialized in
        // the same SQL transaction as the append-only event.
    }

    private fun enqueueEventForRecipients(
        group: StoredGroup,
        event: GroupWireEvent,
        json: JSONObject,
    ) {
        val recipients = selectEventRecipients(group, event)
        recipients.forEach { recipient ->
            enqueueFrame(group.groupId, event.eventId, recipient.deviceId, json)
        }
    }

    private fun buildEventOutboxTasks(
        group: StoredGroup,
        event: GroupWireEvent,
        payload: JSONObject,
        json: JSONObject,
    ): List<StoredOutboxTask> {
        val recipients = selectEventRecipients(group, event)
            .associateByTo(linkedMapOf()) { it.deviceId }
        if (
            event.kind in setOf(
                GroupEventKind.MEMBER_ADDED,
                GroupEventKind.MEMBER_REMOVED,
                GroupEventKind.ROLE_CHANGED,
                GroupEventKind.MEMBER_RESTRICTED,
                GroupEventKind.OWNERSHIP_TRANSFERRED,
            )
        ) {
            payload.optString("member_device_id")
                .takeIf { it.isNotBlank() && it != group.localDeviceId }
                ?.let { db().getMember(group.groupId, it) }
                ?.takeIf {
                    it.isParticipating() ||
                        (
                            event.kind == GroupEventKind.MEMBER_ADDED &&
                                payload.optString("status") == "ACTIVE" &&
                                it.status == "INVITED"
                            ) ||
                        (
                            event.kind == GroupEventKind.MEMBER_REMOVED &&
                                it.status in setOf("ACTIVE", "RESTRICTED", "LEFT", "BANNED")
                            )
                }
                ?.let { recipients[it.deviceId] = it }
        }
        if (payload.has("control_proposal") && group.ownerDeviceId != group.localDeviceId) {
            db().getMember(group.groupId, group.ownerDeviceId)
                ?.takeIf { it.isParticipating() }
                ?.let { recipients[it.deviceId] = it }
        }
        return recipients.values
            .asSequence()
            .map {
                buildOutboxTask(
                    group.groupId,
                    event.eventId,
                    it.deviceId,
                    json,
                )
            }
            .toList()
    }

    private fun selectEventRecipients(
        group: StoredGroup,
        event: GroupWireEvent,
    ): List<StoredGroupMember> {
        if (!isSerializedControl(event.kind)) {
            return selectRecipients(
                group,
                event.authorDeviceId,
                event.authorDeviceId,
                event.epoch,
            )
        }
        val recipients = db().listMembers(group.groupId)
            .asSequence()
            .filter {
                it.deviceId != event.authorDeviceId &&
                    it.deviceId != group.localDeviceId &&
                    it.isParticipating() &&
                    memberWasActiveAt(it, event.epoch)
            }
            .associateByTo(linkedMapOf()) { it.deviceId }
        if (event.kind in setOf(GroupEventKind.MEMBER_ADDED, GroupEventKind.MEMBER_REMOVED)) {
            event.targetEventId
                ?.let { db().getMember(group.groupId, it) }
                ?.takeIf {
                    it.deviceId != event.authorDeviceId &&
                        it.deviceId != group.localDeviceId
                }
                ?.let { recipients[it.deviceId] = it }
        }
        return recipients.values.toList()
    }

    private fun taskCarriesMemberRemoval(task: StoredOutboxTask): Boolean =
        runCatching {
            GroupWireProtocol.parseEvent(
                JSONObject(task.payload.toString(Charsets.UTF_8)),
            ).kind == GroupEventKind.MEMBER_REMOVED
        }.getOrDefault(false)

    private fun replicateStoredEvent(
        group: StoredGroup,
        event: GroupWireEvent,
        json: JSONObject,
        senderPeerName: String,
    ) {
        val candidates = db().listMembers(group.groupId)
            .filter {
                it.isParticipating() &&
                    it.deviceId != event.authorDeviceId &&
                    memberWasActiveAt(it, event.epoch)
            }
        if (candidates.size <= SMALL_GROUP_FANOUT) return
        val primaryReplica = ReplicaPlanner.selectReplicas(
            event.authorDeviceId,
            candidates.map { ReplicaCandidate(DeviceId(it.deviceId)) },
            LARGE_GROUP_REPLICAS,
        ).firstOrNull()?.value
        if (primaryReplica != group.localDeviceId) return
        selectRecipients(
            group,
            event.authorDeviceId,
            event.authorDeviceId,
            event.epoch,
        )
            .filter { it.peerName != senderPeerName }
            .forEach { enqueueFrame(group.groupId, event.eventId, it.deviceId, json) }
        scope.launch { flushDueOutbox() }
    }

    private fun selectRecipients(
        group: StoredGroup,
        objectKey: String,
        authorDeviceId: String,
        eventEpoch: Long? = null,
    ): List<StoredGroupMember> {
        val members = db().listMembers(group.groupId)
            .filter {
                it.isParticipating() &&
                    it.deviceId != authorDeviceId &&
                    (eventEpoch == null || memberWasActiveAt(it, eventEpoch))
            }
        if (members.size <= SMALL_GROUP_FANOUT) {
            return members.filter { it.deviceId != group.localDeviceId }
        }
        val selectedIds = ReplicaPlanner.selectReplicas(
            objectKey,
            members.map { ReplicaCandidate(DeviceId(it.deviceId)) },
            LARGE_GROUP_REPLICAS,
        ).mapTo(hashSetOf()) { it.value }
        // Connected peers get real-time delivery in addition to the durable HRW
        // replica set; disconnected peers repair through anti-entropy.
        return buildList {
            addAll(members.filter { it.deviceId in selectedIds })
            addAll(
                members.filter {
                    it.deviceId !in selectedIds &&
                        P2PMessageRelay.peerSessionStates[it.peerName] == true
                },
            )
        }.filter { it.deviceId != group.localDeviceId }
            .take(SMALL_GROUP_FANOUT)
    }

    private fun enqueueEpochKeyPackages(
        groupId: String,
        controlHead: String,
        epoch: Long,
        secret: ByteArray,
    ) {
        val group = db().getGroup(groupId) ?: return
        val local = localIdentity()
        db().listMembers(groupId)
            .filter {
                it.isParticipating() &&
                    it.deviceId != group.localDeviceId &&
                    memberWasActiveAt(it, epoch)
            }
            .forEach { recipient ->
                val unsigned = GroupEpochKeyPackage(
                    groupId = groupId,
                    epoch = epoch,
                    epochSecretBase64 = secret.base64(),
                    recipientDeviceId = recipient.deviceId,
                    controlHead = controlHead,
                    senderFingerprint = local.fingerprint,
                    senderDeviceId = local.deviceId,
                    senderSigningKey = local.signingKey,
                    createdAtMs = System.currentTimeMillis(),
                    signatureBase64 = "",
                )
                val signed = unsigned.copy(
                    signatureBase64 = GroupIdentitySignatures.sign(
                        unsigned.canonicalForSignature(),
                    ),
                )
                enqueueFrame(
                    groupId,
                    keyPackageEventId(epoch, controlHead),
                    recipient.deviceId,
                    GroupControlFrames.keyPackageToJson(signed),
                )
            }
    }

    private fun enqueuePendingMemberInvites(
        groupId: String,
        onlyRecipientDeviceId: String? = null,
    ) {
        val group = db().getGroup(groupId) ?: return
        val localMember = db().getMember(groupId, group.localDeviceId) ?: return
        if (!localMember.isParticipating()) return
        val owner = db().getMember(groupId, group.ownerDeviceId) ?: return
        val epochKey = db().getEpochKey(groupId, group.currentEpoch) ?: return
        val ownerLineage = currentOwnerLineage(group)
        val now = System.currentTimeMillis()
        val validityWindow = now / INVITE_LIFETIME_MS
        db().listMembers(groupId)
            .asSequence()
            .filter {
                it.status == "INVITED" &&
                    !GroupMembershipTransitions.shouldExpireInvite(
                        status = it.status,
                        invitedAtMs = it.updatedAtMs,
                        nowMs = now,
                        lifetimeMs = INVITE_LIFETIME_MS,
                    ) &&
                    (onlyRecipientDeviceId == null || it.deviceId == onlyRecipientDeviceId)
            }
            .forEach { recipient ->
                val inviteId = pendingInviteId(group, recipient.deviceId, validityWindow)
                val historyCursors = linkedMapOf(
                    owner.deviceId to (db().nextAuthorSequence(groupId, owner.deviceId) - 1L),
                    recipient.deviceId to (
                        db().nextAuthorSequence(groupId, recipient.deviceId) - 1L
                        ),
                )
                val groupAvatarB64 = applicationContext?.let { ctx ->
                    val avatarFile = File(ctx.filesDir, "group_avatars/${groupId}.jpg").takeIf { it.exists() }
                        ?: group.avatarUri?.let { File(it) }?.takeIf { it.exists() }
                    avatarFile?.let { file ->
                        runCatching {
                            val bytes = file.readBytes()
                            if (bytes.size <= GroupWireProtocol.MAX_GROUP_AVATAR_BYTES) {
                                Base64.encodeToString(bytes, Base64.NO_WRAP)
                            } else {
                                null
                            }
                        }.getOrNull()
                    }
                }
                val groupWallpaperB64 = applicationContext?.let { ctx ->
                    File(ctx.filesDir, "group_wallpapers/${groupId}.jpg")
                        .takeIf { it.exists() && it.length() <= GroupWireProtocol.MAX_GROUP_WALLPAPER_BYTES }
                        ?.let { file ->
                            runCatching {
                                Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                            }.getOrNull()
                        }
                }
                val unsigned = GroupInvite(
                    inviteId = inviteId,
                    groupId = groupId,
                    title = group.title,
                    description = group.description,
                    groupAvatarDataB64 = groupAvatarB64,
                    groupAvatarSigned = groupAvatarB64 != null,
                    groupWallpaperDataB64 = groupWallpaperB64,
                    groupWallpaperSigned = groupWallpaperB64 != null,
                    adminOnlyPosting = group.adminOnlyPosting,
                    epoch = group.currentEpoch,
                    epochSecretBase64 = epochKey.keyMaterial.base64(),
                    ownerFingerprint = owner.transportFingerprint,
                    senderFingerprint = owner.transportFingerprint,
                    senderSigningKey = owner.signingKeyBase64,
                    coordinatorFingerprint = owner.transportFingerprint,
                    controlHead = group.controlHead,
                    historyCursors = historyCursors,
                    ownerTransitions = ownerLineage.certificates,
                    createdAtMs = now,
                    rosterSize = db().listMembers(groupId).count {
                        it.isParticipating() || it.deviceId == recipient.deviceId
                    },
                    members = listOf(owner, recipient).map { member ->
                        GroupInviteMember(
                            fingerprint = member.transportFingerprint,
                            peerName = member.displayName,
                            deviceId = member.deviceId,
                            signingKey = member.signingKeyBase64,
                            role = member.role,
                            status = member.status,
                        )
                    },
                    cryptoSuite = EpochAeadGroupCrypto.suiteId,
                    signatureBase64 = "",
                )
                val signed = unsigned.copy(
                    signatureBase64 = GroupIdentitySignatures.sign(
                        unsigned.canonicalForSignature(),
                    ),
                )
                enqueueFrame(
                    groupId,
                    inviteId,
                    recipient.deviceId,
                    GroupWireProtocol.inviteToJson(signed),
                )
            }
    }

    private fun enqueueRosterSnapshot(groupId: String, recipientDeviceId: String) {
        val group = db().getGroup(groupId) ?: return
        if (group.localDeviceId != group.ownerDeviceId) return
        val controlHead = group.controlHead ?: return
        val owner = db().getMember(groupId, group.ownerDeviceId) ?: return
        val recipient = db().getMember(groupId, recipientDeviceId) ?: return
        if (!recipient.isParticipating()) return
        val roster = db().listMembers(groupId)
            .filter { it.isParticipating() }
            .sortedBy { it.deviceId }
        val admissionCursors = db().listAdmissionCursors(
            groupId,
            recipientDeviceId,
            controlHead,
        ).associate { it.authorDeviceId to it.lastAuthorSequence }
        if (
            roster.isEmpty() ||
            admissionCursors.keys != roster.mapTo(hashSetOf()) { it.deviceId } ||
            roster.any {
                it.transportFingerprint.isBlank() ||
                    it.signingKeyBase64.isBlank() ||
                    it.displayName.isBlank() ||
                    it.peerName.isBlank()
            }
        ) {
            Log.w(TAG, "Cannot build a complete roster snapshot for $groupId")
            return
        }
        val pages = roster.chunked(GroupControlFrames.MAX_ROSTER_MEMBERS_PER_PAGE)
        val createdAtMs = System.currentTimeMillis()
        pages.forEachIndexed { pageIndex, page ->
            val unsigned = GroupRosterSnapshot(
                recipientDeviceId = recipientDeviceId,
                groupId = groupId,
                controlHead = controlHead,
                epoch = group.currentEpoch,
                pageIndex = pageIndex,
                totalPages = pages.size,
                ownerFingerprint = owner.transportFingerprint,
                ownerDeviceId = owner.deviceId,
                ownerSigningKey = owner.signingKeyBase64,
                createdAtMs = createdAtMs,
                members = page.map { member ->
                    GroupRosterSnapshotMember(
                        deviceId = member.deviceId,
                        fingerprint = member.transportFingerprint,
                        displayName = member.displayName,
                        peerName = member.peerName,
                        signingKey = member.signingKeyBase64,
                        role = parseRole(member.role),
                        permissions = member.permissions and GroupPermission.knownBits,
                        status = runCatching { GroupRosterMemberStatus.valueOf(member.status) }
                            .getOrDefault(GroupRosterMemberStatus.ACTIVE),
                        joinedEpoch = member.joinedEpoch,
                        removedEpoch = member.removedEpoch,
                        lastAuthorSequence = admissionCursors.getValue(member.deviceId),
                        createdAtMs = member.createdAtMs,
                        updatedAtMs = member.updatedAtMs,
                    )
                },
                signatureBase64 = "",
            )
            val signed = unsigned.copy(
                signatureBase64 = GroupIdentitySignatures.sign(
                    unsigned.canonicalForSignature(),
                ),
            )
            enqueueFrame(
                groupId,
                rosterSnapshotEventId(controlHead, pageIndex),
                recipientDeviceId,
                GroupControlFrames.rosterSnapshotToJson(signed),
            )
        }
    }

    private fun enqueueFrame(
        groupId: String,
        eventId: String,
        recipientDeviceId: String,
        json: JSONObject,
    ) {
        db().enqueueOutbox(buildOutboxTask(groupId, eventId, recipientDeviceId, json))
    }

    private fun buildOutboxTask(
        groupId: String,
        eventId: String,
        recipientDeviceId: String,
        json: JSONObject,
    ): StoredOutboxTask {
        val wire = json.toString()
        GroupWireProtocol.requireBoundedWire(wire)
        return StoredOutboxTask(
            taskId = outboxTaskId(groupId, eventId, recipientDeviceId),
            groupId = groupId,
            eventId = eventId,
            recipientDeviceId = recipientDeviceId,
            payload = wire.toByteArray(Charsets.UTF_8),
            nextAttemptMs = System.currentTimeMillis(),
        )
    }

    private fun sendAck(peerName: String, ack: GroupStoreAck) {
        val context = applicationContext ?: return
        if (db().getGroup(ack.groupId) != null) {
            val fingerprint = runCatching { transportFingerprint(peerName) }.getOrNull() ?: return
            val member = db().listMembers(ack.groupId).singleOrNull {
                it.transportFingerprint == fingerprint
            } ?: return
            if (!ensurePinnedGroupRoute(member)) return
        }
        P2PMessageRelay.sendGroupFrame(
            context,
            peerName,
            GroupControlFrames.storeAckToJson(ack),
        )
    }

    private fun sendSyncRequests(group: StoredGroup, peer: StoredGroupMember) {
        db().listMembers(group.groupId)
            .map { it.deviceId }
            .sorted()
            .chunked(SYNC_CURSOR_CHUNK_SIZE)
            .forEach { authorIds -> sendSyncRequestChunk(group, peer, authorIds) }
    }

    private fun sendSyncRequestChunk(
        group: StoredGroup,
        peer: StoredGroupMember,
        authorIds: List<String>,
    ) {
        val cursors = authorIds.associateWith { authorDeviceId ->
            val baseline = db().getSyncCursor(group.groupId, authorDeviceId)
                ?.lastAuthorSeq
                ?: 0L
            db().contiguousAuthorSequence(
                group.groupId,
                authorDeviceId,
                baseline,
            )
        }
        val requestId = UUID.randomUUID().toString()
        syncRequestScopes[requestId] = authorIds
        if (syncRequestScopes.size > 1_024) {
            syncRequestScopes.keys.take(syncRequestScopes.size - 1_024).forEach {
                syncRequestScopes.remove(it)
            }
        }
        val request = GroupSyncRequest(
            requestId = requestId,
            groupId = group.groupId,
            requesterDeviceId = group.localDeviceId,
            cursors = cursors,
        )
        if (!ensurePinnedGroupRoute(peer)) return
        P2PMessageRelay.sendGroupFrame(
            requireNotNull(applicationContext),
            peer.peerName,
            GroupControlFrames.syncRequestToJson(request),
        )
    }

    private suspend fun refreshAllGroups() {
        invalidateActiveMemberCache()
        finalizeConfirmedDepartures()
        val groups = visibleGroups()
        groups.forEach { refreshGroup(it.groupId) }
        _summaries.value = groups.map { group ->
            val last = db().loadTimeline(group.groupId, 1).firstOrNull()
            GroupSummary(
                groupId = group.groupId,
                title = group.title,
                description = group.description,
                memberCount = db().listMembers(group.groupId).count { it.isParticipating() },
                unreadCount = group.unreadCount,
                lastMessagePreview = formatGroupLastMessagePreview(last),
                lastActivityLabel = last?.createdAtMs?.let(::formatTime).orEmpty(),
                isVerified = true,
                avatarUri = group.avatarUri,
            )
        }
    }

    private fun loadTimelineWindow(
        groupId: String,
        limit: Int,
    ): Pair<List<StoredGroupMessage>, Boolean> {
        val requested = limit.coerceIn(1, MAX_TIMELINE_WINDOW)
        val rows = ArrayList<StoredGroupMessage>(minOf(requested + 1, 4_000))
        var before: GroupTimelineCursor? = null
        while (rows.size <= requested) {
            val pageSize = minOf(1_000, requested + 1 - rows.size)
            if (pageSize <= 0) break
            val page = db().loadTimeline(groupId, pageSize, before)
            if (page.isEmpty()) break
            rows += page
            if (page.size < pageSize) break
            before = GroupTimelineCursor.from(page.last())
        }
        val hasMore = rows.size > requested
        return rows.take(requested) to hasMore
    }

    private suspend fun refreshGroup(groupId: String) {
        val group = db().getGroup(groupId) ?: return
        val members = db().listMembers(groupId)
        val memberByDevice = members.associateBy { it.deviceId }
        val localMember = memberByDevice[group.localDeviceId]
        if (isDeparturePending(groupId) && localMember?.status == "LEFT") {
            purgeLocalGroup(groupId)
            refreshAllSummariesWithoutRecursion()
            return
        }
        val localParticipates = localMember?.isParticipating() == true
        val events = db().listRecentEvents(groupId)
        val timelineLimit = timelineLimits[groupId] ?: TIMELINE_PAGE_SIZE
        val (messagesNewest, hasMoreBefore) = loadTimelineWindow(groupId, timelineLimit)
        val reactionState = reactionState(
            groupId,
            messagesNewest.mapTo(hashSetOf()) { it.messageId },
        )
        val messageById = messagesNewest.associateBy { it.messageId }
        val readReceiptHorizonByMember = db().listAllReceipts(groupId)
            .asSequence()
            .filter { it.type == "READ" && it.recipientDeviceId != group.localDeviceId }
            .mapNotNull { receipt ->
                val target = messageById[receipt.eventId] ?: db().loadMessage(groupId, receipt.eventId)
                target?.let { receipt.recipientDeviceId to (it to receipt) }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, targets) ->
                targets.maxWith { left, right -> groupTimelineOrder.compare(left.first, right.first) }
            }
        val readHorizonByMember = readReceiptHorizonByMember.mapValues { it.value.first }
        val latestPollVotes = linkedMapOf<Pair<String, String>, Int>()
        events.asSequence()
            .filter { it.kind == GroupEventKind.POLL_VOTE.name && it.targetEventId != null }
            .forEach { vote ->
                val optionId = runCatching {
                    JSONObject(vote.body.orEmpty()).optInt("option_id", -1)
                }.getOrDefault(-1)
                if (optionId >= 0) {
                    latestPollVotes[requireNotNull(vote.targetEventId) to vote.authorDeviceId] = optionId
                }
            }
        val eventMap = events.associateBy { it.eventId }
        val timeline = messagesNewest.asReversed().map { message ->
            val event = eventMap[message.messageId] ?: db().getEvent(groupId, message.messageId)
            val author = memberByDevice[message.authorDeviceId]
            val authorPolicy = author?.toPolicyMember()
            val localPolicy = localMember?.takeIf { it.isParticipating() }?.toPolicyMember()
            val reactions = reactionState
                .filterKeys { it.startsWith("${message.messageId}\u0000") }
                .filterValues { it }
                .keys
                .groupBy { it.split('\u0000')[1] }
                .map { (emoji, keys) ->
                    GroupReaction(
                        emoji = emoji,
                        count = keys.size,
                        reactedByMe = keys.any { it.endsWith("\u0000${group.localDeviceId}") },
                    )
                }
            val reply = event?.targetEventId?.let { targetId ->
                val target = messageById[targetId] ?: db().loadMessage(groupId, targetId)
                val targetAuthor = target?.let { memberByDevice[it.authorDeviceId] }
                target?.let {
                    GroupReplyPreview(
                        targetId,
                        targetAuthor?.displayName ?: "Member",
                        if (it.deleted) "Message deleted" else it.body,
                    )
                }
            }
            val attachmentsUi = if (!message.deleted && event?.kind == GroupEventKind.MEDIA.name) {
                loadAttachmentManifests(groupId, message.messageId).map { manifest ->
                    val missing = attachmentStore(groupId).missingBlocks(manifest)
                    val destination = attachmentDestination(groupId, manifest)
                    if (!destination.exists() && missing.isEmpty()) {
                        assembleAttachmentIfComplete(groupId, message.messageId, manifest)
                    }
                    val verified = isVerifiedAttachmentDestination(
                        destination,
                        manifest,
                    )
                    GroupAttachmentUi(
                        attachmentId = manifest.attachmentId,
                        fileName = manifest.fileName,
                        mimeType = manifest.mimeType,
                        sizeLabel = formatByteCount(manifest.plaintextSize),
                        availableBlocks = manifest.blocks.size - missing.size,
                        totalBlocks = manifest.blocks.size,
                        isDownloaded = verified,
                        localPath = destination.absolutePath.takeIf { verified || destination.exists() }
                    )
                }
            } else {
                emptyList()
            }
            val attachment = if (attachmentsUi.size == 1) attachmentsUi.first() else null
            val poll = if (!message.deleted && event?.kind == GroupEventKind.POLL.name) {
                runCatching {
                    val payload = JSONObject(message.body)
                    val optionsJson = payload.getJSONArray("options")
                    val votesByMember = latestPollVotes
                        .filterKeys { (pollId, _) -> pollId == message.messageId }
                        .mapKeys { (key, _) -> key.second }
                    projectGroupPoll(
                        pollId = message.messageId,
                        question = payload.getString("question"),
                        options = List(optionsJson.length(), optionsJson::getString),
                        isAnonymous = payload.optBoolean("anonymous", false),
                        latestVotesByMember = votesByMember,
                        localDeviceId = group.localDeviceId,
                    )
                }.getOrNull()
            } else {
                null
            }
            val readReceiptsList = membersWhoReadMessage(message, readHorizonByMember)
                .asSequence()
                .mapNotNull { memberId ->
                    val memberName = memberByDevice[memberId]?.displayName ?: return@mapNotNull null
                    val member = memberByDevice[memberId] ?: return@mapNotNull null
                    val receipt = readReceiptHorizonByMember[memberId]?.second
                    val epochMs = receipt?.receivedAtMs ?: message.createdAtMs
                    GroupReadReceipt(
                        memberId = memberId,
                        displayName = memberName,
                        avatarPeerName = member.peerName,
                        readTimeLabel = formatTime(epochMs),
                        readEpochMs = epochMs
                    )
                }
                .distinctBy { it.memberId }
                .toList()
            val readByMembers = readReceiptsList.map { it.displayName }
            GroupTimelineMessage(
                messageId = message.messageId,
                authorId = message.authorDeviceId,
                authorName = author?.displayName ?: "Member",
                authorRole = author?.role.toUiRole(),
                text = when {
                    message.deleted -> "Message deleted"
                    poll != null -> ""
                    else -> message.body
                },
                timestampLabel = formatTime(message.createdAtMs),
                timestampEpochMs = message.createdAtMs,
                isMine = message.authorDeviceId == group.localDeviceId,
                isEdited = message.edited,
                isPinned = group.pinnedEventId == message.messageId,
                attachment = attachment,
                attachments = attachmentsUi,
                replyTo = reply,
                reactions = reactions,
                deliveryStatus = deliveryStatus(group, message),
                canEdit = localPolicy != null && authorPolicy != null &&
                    GroupRolePolicy.canEditMessage(localPolicy, authorPolicy.userId).allowed &&
                    !message.deleted,
                canDelete = localPolicy != null && authorPolicy != null &&
                    GroupRolePolicy.canDeleteMessage(localPolicy, authorPolicy.userId).allowed &&
                    !message.deleted,
                canReact = localPolicy?.let {
                    GroupRolePolicy.canPerform(it, GroupAction.ADD_REACTION).allowed
                } == true,
                canPin = localPolicy?.let(GroupRolePolicy::canPinMessage)?.allowed == true,
                poll = poll,
                readByMembers = readByMembers,
                readReceipts = readReceiptsList,
            )
        }
        val activeMembers = members.count { it.isParticipating() }
        val online = members.count {
            it.isParticipating() &&
                (
                    it.deviceId == group.localDeviceId ||
                        P2PMessageRelay.peerSessionStates[it.peerName] == true
                    )
        }
        val existingReply = chatFlows[groupId]?.value?.currentReply
        val hasCurrentEpochKey = db().getEpochKey(groupId, group.currentEpoch) != null
        val groupPostingAllowed = localMember?.let {
            runCatching {
                GroupRolePolicy.canPostUnderGroupPolicy(
                    group.adminOnlyPosting,
                    parseRole(it.role),
                )
            }.getOrDefault(false)
        } == true
        val canPost = localParticipates && hasCurrentEpochKey && groupPostingAllowed && localMember.toPolicyMember().let {
            GroupRolePolicy.canPerform(it, GroupAction.POST_MESSAGE).allowed
        } == true
        val canPostMedia =
            localParticipates && hasCurrentEpochKey && groupPostingAllowed && localMember.toPolicyMember().let {
            GroupRolePolicy.canPerform(it, GroupAction.POST_MEDIA).allowed
        } == true
        val uiMembers = members.filter { it.status != "LEFT" }.map { member ->
            com.example.twopchat.group.ui.GroupMember(
                memberId = member.deviceId,
                displayName = member.displayName,
                role = member.role.toUiRole(),
                statusLabel = if (member.deviceId == group.localDeviceId) {
                    "В сети (Это устройство)"
                } else if (P2PMessageRelay.peerSessionStates[member.peerName] == true) {
                    "В сети"
                } else if (!member.isParticipating()) {
                    when (member.status.uppercase(Locale.ROOT)) {
                        "INVITED" -> "Приглашение отправлено"
                        "RESTRICTED" -> "Права ограничены"
                        "BANNED" -> "Заблокирован"
                        else -> "Не активен"
                    }
                } else {
                    "Оффлайн"
                },
                isCurrentUser = member.deviceId == group.localDeviceId
            )
        }
        val groupTypingMap = typingMembersByGroup[groupId]
        val now = System.currentTimeMillis()
        val activeTypers = if (groupTypingMap != null) {
            groupTypingMap.entries.removeIf { now - it.value > 4_500L }
            groupTypingMap.keys.toList()
        } else {
            emptyList()
        }
        val typingStatusText = when (activeTypers.size) {
            0 -> ""
            1 -> "${activeTypers[0]} печатает..."
            2 -> "${activeTypers[0]} и ${activeTypers[1]} печатают..."
            else -> "Несколько участников печатают..."
        }

        chatFlows.computeIfAbsent(groupId) {
            MutableStateFlow(
                GroupChatUiState(groupId, group.title, activeMembers, members = uiMembers, typingStatus = typingStatusText),
            )
        }.value = GroupChatUiState(
            groupId = groupId,
            title = group.title,
            memberCount = activeMembers,
            members = uiMembers,
            typingStatus = typingStatusText,
            syncStatus = when {
                localMember?.status == "JOINING" -> GroupSyncStatus.SYNCING
                localParticipates && !hasCurrentEpochKey -> GroupSyncStatus.SYNCING
                activeMembers <= 1 || online == activeMembers -> GroupSyncStatus.LIVE
                online <= 1 -> GroupSyncStatus.OFFLINE
                online < minOf(activeMembers, LARGE_GROUP_REPLICAS + 1) -> GroupSyncStatus.DEGRADED
                else -> GroupSyncStatus.SYNCING
            },
            messages = timeline,
            hasMoreBefore = hasMoreBefore,
            currentReply = existingReply,
            pinnedMessage = group.pinnedEventId?.let { pinnedId ->
                val pinned = messageById[pinnedId] ?: db().loadMessage(groupId, pinnedId)
                pinned?.let {
                    GroupReplyPreview(
                        pinnedId,
                        memberByDevice[it.authorDeviceId]?.displayName ?: "Member",
                        if (it.deleted) "Message deleted" else it.body,
                    )
                }
            },
            composerEnabled = canPost || canPostMedia,
            textComposerEnabled = canPost,
            mediaComposerEnabled = canPostMedia,
            readOnlyReason = if (canPost || canPostMedia) {
                ""
            } else if (localMember?.status == "JOINING") {
                "Ожидание подтверждения членства владельцем группы"
            } else if (localParticipates && !hasCurrentEpochKey) {
                "Ожидание получения текущего ключа шифрования группы"
            } else if (!groupPostingAllowed && group.adminOnlyPosting) {
                "Только администраторы могут отправлять сообщения"
            } else {
                "Отправка сообщений ограничена администратором"
            },
            wallpaperUri = applicationContext?.let { ctx ->
                P2PPreferences.prefs(ctx).getString("group_wallpaper_${group.groupId}", null)
            },
        )
        infoFlows.computeIfAbsent(groupId) {
            MutableStateFlow(emptyInfoState(groupId))
        }.value = buildInfoState(group, members, events, timeline)
        refreshAllSummariesWithoutRecursion()
    }

    private fun refreshAllSummariesWithoutRecursion() {
        _summaries.value = visibleGroups().map { group ->
            val last = db().loadTimeline(group.groupId, 1).firstOrNull()
            GroupSummary(
                groupId = group.groupId,
                title = group.title,
                description = group.description,
                memberCount = db().listMembers(group.groupId).count { it.isParticipating() },
                unreadCount = group.unreadCount,
                lastMessagePreview = formatGroupLastMessagePreview(last),
                lastActivityLabel = last?.createdAtMs?.let(::formatTime).orEmpty(),
                isVerified = true,
                avatarUri = group.avatarUri,
            )
        }
    }

    private fun buildInfoState(
        group: StoredGroup,
        members: List<StoredGroupMember>,
        events: List<StoredGroupEvent>,
        timelineMessages: List<GroupTimelineMessage> = emptyList(),
    ): GroupInfoUiState {
        val local = members.firstOrNull { it.deviceId == group.localDeviceId }
        val localPolicy = local?.takeIf { it.isParticipating() }?.toPolicyMember()
        val hasPendingInvites = members.any { it.status == "INVITED" }

        applicationContext?.let { ctx ->
            members.forEach { member ->
                if (member.deviceId != group.localDeviceId && member.peerName.isNotBlank()) {
                    if (P2PMessageRelay.peerAvatars[member.displayName] == null) {
                        P2PMessageRelay.shareAvatar(ctx, member.peerName)
                    }
                }
            }
        }
        val uiMembers = members.filter { it.status != "LEFT" }.map { member ->
            val targetPolicy = member.toPolicyMember()
            com.example.twopchat.group.ui.GroupMember(
                memberId = member.deviceId,
                displayName = member.displayName,
                role = member.role.toUiRole(),
                statusLabel = if (member.deviceId == group.localDeviceId) {
                    "В сети (Это устройство)"
                } else if (P2PMessageRelay.peerSessionStates[member.peerName] == true) {
                    "В сети"
                } else if (!member.isParticipating()) {
                    when (member.status.uppercase(Locale.ROOT)) {
                        "INVITED" -> "Приглашение отправлено"
                        "RESTRICTED" -> "Права ограничены"
                        "BANNED" -> "Заблокирован"
                        "LEFT" -> "Вышел из группы"
                        else -> "Не в сети"
                    }
                } else {
                    "Не в сети"
                },
                isCurrentUser = member.deviceId == group.localDeviceId,
                permissions = member.permissions.toUiPermissions(),
                canChangeRole = localPolicy?.let { actor ->
                    setOf(
                        GroupRole.ADMINISTRATOR,
                        GroupRole.MODERATOR,
                        GroupRole.MEMBER,
                    ).any { desired ->
                        GroupRolePolicy.canAssignRole(actor, targetPolicy, desired).allowed
                    }
                } == true,
                canRestrict = localPolicy?.let {
                    GroupRolePolicy.canRestrictMember(it, targetPolicy).allowed
                } == true,
                canRemove = localPolicy?.let {
                    GroupRolePolicy.canRemoveMember(it, targetPolicy).allowed
                } == true,
                canBan = localPolicy?.let {
                    GroupRolePolicy.canBanMember(it, targetPolicy).allowed
                } == true,
                canTransferOwnership = local?.role == GroupRole.OWNER.name &&
                    member.deviceId != group.localDeviceId &&
                    member.status == "ACTIVE" &&
                    !hasPendingInvites,
            )
        }
        val canManageInfo = localPolicy?.let {
            GroupRolePolicy.canPerform(it, GroupAction.MANAGE_GROUP_INFO).allowed
        } == true
        return GroupInfoUiState(
            metadata = GroupMetadata(
                groupId = group.groupId,
                title = group.title,
                description = group.description,
                avatarUri = group.avatarUri,
                memberCount = members.count { it.isParticipating() },
                createdByLabel = members.firstOrNull {
                    it.deviceId == group.ownerDeviceId
                }?.displayName.orEmpty(),
                createdAtLabel = formatDate(group.createdAtMs),
                replicationLabel = if (members.size <= SMALL_GROUP_FANOUT) {
                    "Full member fan-out"
                } else {
                    "$LARGE_GROUP_REPLICAS deterministic replicas + anti-entropy"
                },
                inviteToken = if (
                    localPolicy?.let { member ->
                        GroupRolePolicy.canPerform(member, GroupAction.INVITE_MEMBER).allowed
                    } == true
                ) {
                    inviteCapability(group)
                } else {
                    ""
                },
                adminOnlyPosting = group.adminOnlyPosting,
                wallpaperUri = applicationContext?.let { ctx ->
                    P2PPreferences.prefs(ctx).getString("group_wallpaper_${group.groupId}", null)
                }
            ),
            currentUserRole = local?.role.toUiRole(),
            members = uiMembers,
            inviteCandidates = getKnownContacts().filter { candidate ->
                members.none { member ->
                    (member.peerName.equals(candidate.contactId, ignoreCase = true) ||
                     member.displayName.equals(candidate.displayName, ignoreCase = true)) &&
                     (member.isParticipating() || member.status.uppercase(Locale.ROOT) in setOf("ACTIVE", "INVITED", "JOINING", "RESTRICTED"))
                }
            },
            management = GroupManagementPermissions(
                canEditMetadata = canManageInfo,
                canInviteMembers = localPolicy?.let {
                    GroupRolePolicy.canPerform(it, GroupAction.INVITE_MEMBER).allowed
                } == true,
                canViewAdminLog = localPolicy?.let {
                    GroupRolePolicy.canPerform(it, GroupAction.VIEW_ADMIN_LOG).allowed
                } == true,
                canManageRoles = localPolicy?.let {
                    GroupRolePolicy.canPerform(it, GroupAction.ASSIGN_ROLE).allowed
                } == true,
                canRestrictMembers = localPolicy?.let {
                    GroupRolePolicy.canPerform(it, GroupAction.RESTRICT_MEMBER).allowed
                } == true,
                canRemoveMembers = localPolicy?.let {
                    GroupRolePolicy.canPerform(it, GroupAction.REMOVE_MEMBER).allowed
                } == true,
                canBanMembers = localPolicy?.let {
                    GroupRolePolicy.canPerform(it, GroupAction.BAN_MEMBER).allowed
                } == true,
                canTransferOwnership = local?.role == GroupRole.OWNER.name &&
                    local.isParticipating() &&
                    !hasPendingInvites,
                canLeave = local?.role != GroupRole.OWNER.name ||
                    members.none {
                        it.deviceId != group.localDeviceId && it.isParticipating()
                    },
            ),
            adminLog = events.filter { isAdminLogKind(it.kind) }.takeLast(100).asReversed().map { evt ->
                val actorName = members.firstOrNull { member ->
                    member.deviceId == evt.authorDeviceId
                }?.displayName ?: "Участник"

                val payloadJson = evt.payload?.let { pBytes ->
                    runCatching { JSONObject(String(pBytes, Charsets.UTF_8)) }.getOrNull()
                }

                val targetMemberName = payloadJson?.optString("member_device_id")?.let { targetId ->
                    members.firstOrNull { it.deviceId == targetId }?.displayName ?: targetId.take(8)
                }

                val actionDescription = when (evt.kind) {
                    "GROUP_UPDATED" -> {
                        when {
                            payloadJson?.has("wallpaper_uri") == true || payloadJson?.has("wallpaper_data") == true ->
                                "изменил обои чата"
                            payloadJson?.has("avatar_uri") == true || payloadJson?.has("avatar_data") == true ->
                                "изменил фото профиля беседы"
                            payloadJson?.has("title") == true ->
                                "изменил название беседы на «${payloadJson.optString("title")}»"
                            payloadJson?.has("description") == true ->
                                "изменил описание беседы"
                            else -> "обновил параметры беседы"
                        }
                    }
                    "MEMBER_ADDED" -> {
                        if (targetMemberName != null) "добавил пользователя $targetMemberName"
                        else "добавил участника"
                    }
                    "MEMBER_REMOVED" -> {
                        if (targetMemberName != null) "исключил пользователя $targetMemberName"
                        else "исключил участника"
                    }
                    "ROLE_CHANGED" -> {
                        val roleStr = when (payloadJson?.optString("role")?.uppercase()) {
                            "ADMIN" -> "Администратор"
                            "MODERATOR" -> "Модератор"
                            "MEMBER" -> "Участник"
                            else -> payloadJson?.optString("role") ?: ""
                        }
                        if (targetMemberName != null) "изменил роль $targetMemberName на $roleStr"
                        else "изменил роль участника"
                    }
                    "PERMISSIONS_CHANGED", "MEMBER_MUTED" -> {
                        if (targetMemberName != null) "изменил права $targetMemberName"
                        else "изменил права участника"
                    }
                    "PIN" -> "закрепил сообщение"
                    "UNPIN" -> "открепил сообщение"
                    else -> evt.kind.lowercase().replace('_', ' ')
                }

                GroupAdminLogEntry(
                    entryId = evt.eventId,
                    actorName = actorName,
                    action = actionDescription,
                    timestampLabel = formatDate(evt.createdAtMs),
                )
            },
            timelineMessages = timelineMessages
        )
    }

    private fun getKnownContacts(): List<GroupContactSummary> {
        val context = applicationContext ?: return emptyList()
        val prefs = P2PPreferences.prefs(context)
        val allPeers = P2PPreferences.getAllKnownPeers(context)

        return allPeers
            .asSequence()
            .mapNotNull { peerName ->
                val fingerprint = P2PPreferences.getPeerFingerprint(context, peerName)
                    ?: prefs.getString(P2PPreferences.peerFingerprint(peerName), null).orEmpty()
                val avatar = P2PMessageRelay.peerAvatars[peerName]
                GroupContactSummary(
                    contactId = peerName,
                    displayName = peerName,
                    secondaryText = if (fingerprint.isNotBlank()) fingerprint.take(16) else peerName,
                    isOnline = P2PMessageRelay.peerSessionStates[peerName] == true,
                )
            }
            .sortedBy { it.displayName.lowercase(Locale.ROOT) }
            .toList()
    }

    private fun refreshCreateState() {
        _createState.value = _createState.value.copy(knownContacts = getKnownContacts())
    }

    private fun refreshPendingInvites() {
        val invites = db().listPendingInvites().mapNotNull { stored ->
            runCatching {
                val invite = GroupWireProtocol.parseInvite(
                    JSONObject(stored.token.toString(Charsets.UTF_8)),
                )
                val inviter = invite.members.firstOrNull {
                    it.fingerprint == invite.senderFingerprint
                }
                val avatarPath = persistGroupInviteAvatar(invite)
                PendingGroupInvite(
                    inviteId = invite.inviteId,
                    groupId = invite.groupId,
                    groupTitle = invite.title,
                    groupDescription = invite.description,
                    groupAvatarUri = avatarPath,
                    inviterName = inviter?.peerName ?: "Contact",
                    memberCount = invite.rosterSize,
                    receivedAtLabel = formatDate(stored.createdAtMs),
                )
            }.getOrNull()
        }
        _pendingInvites.value = PendingGroupInvitesUiState(invites)
    }

    internal fun visibleGroups(): List<StoredGroup> {
        if (applicationContext == null) return emptyList()
        return db().listGroups().filter { group ->
            !isDeparturePending(group.groupId) &&
                db().getMember(group.groupId, group.localDeviceId)
                    ?.status !in setOf(null, "LEFT", "BANNED")
        }
    }

    private fun persistGroupInviteAvatar(invite: GroupInvite): String? {
        val context = applicationContext ?: return null
        val avatarFile = File(context.filesDir, "group_avatars/${invite.groupId}.jpg")
        val encoded = invite.groupAvatarDataB64?.takeIf { invite.groupAvatarSigned } ?: return avatarFile
            .takeIf(File::exists)
            ?.absolutePath
        return runCatching {
            require(encoded.length <= GroupWireProtocol.MAX_GROUP_AVATAR_BASE64_CHARS)
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size <= GroupWireProtocol.MAX_GROUP_AVATAR_BYTES)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outMimeType?.startsWith("image/") == true)
            require(bounds.outWidth in 1..4_096 && bounds.outHeight in 1..4_096)
            require(bounds.outWidth.toLong() * bounds.outHeight.toLong() <= 16_000_000L)
            avatarFile.parentFile?.mkdirs()
            avatarFile.writeBytes(bytes)
            avatarFile.absolutePath
        }.getOrElse { error ->
            Log.w(TAG, "Rejected invalid group avatar for ${invite.groupId}: ${error.message}")
            avatarFile.takeIf(File::exists)?.absolutePath
        }
    }

    private fun persistGroupInviteWallpaper(invite: GroupInvite): String? {
        val context = applicationContext ?: return null
        val wallpaperFile = File(context.filesDir, "group_wallpapers/${invite.groupId}.jpg")
        val encoded = invite.groupWallpaperDataB64?.takeIf { invite.groupWallpaperSigned }
            ?: return wallpaperFile.takeIf(File::exists)?.absolutePath
        return runCatching {
            require(encoded.length <= GroupWireProtocol.MAX_GROUP_WALLPAPER_BASE64_CHARS)
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size <= GroupWireProtocol.MAX_GROUP_WALLPAPER_BYTES)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outMimeType?.startsWith("image/") == true)
            require(bounds.outWidth in 1..4_096 && bounds.outHeight in 1..4_096)
            require(bounds.outWidth.toLong() * bounds.outHeight.toLong() <= 16_000_000L)
            wallpaperFile.parentFile?.mkdirs()
            wallpaperFile.writeBytes(bytes)
            P2PPreferences.prefs(context).edit()
                .putString("group_wallpaper_${invite.groupId}", wallpaperFile.absolutePath)
                .apply()
            wallpaperFile.absolutePath
        }.getOrElse { error ->
            Log.w(TAG, "Rejected invalid group wallpaper for ${invite.groupId}: ${error.message}")
            wallpaperFile.takeIf(File::exists)?.absolutePath
        }
    }

    private fun isDeparturePending(groupId: String): Boolean =
        applicationContext?.let { context ->
            P2PPreferences.prefs(context)
                .getBoolean("$PENDING_DEPARTURE_PREFIX$groupId", false)
        } == true

    private fun finalizeConfirmedDepartures() {
        db().listGroups().forEach { group ->
            if (
                isDeparturePending(group.groupId) &&
                db().getMember(group.groupId, group.localDeviceId)?.status == "LEFT"
            ) {
                purgeLocalGroup(group.groupId)
            }
        }
    }

    private fun purgeLocalGroup(groupId: String) {
        db().deleteGroup(groupId)
        activeGroupChats.remove(groupId)
        timelineLimits.remove(groupId)
        lastReadReceiptTargets.remove(groupId)
        controlAncestorCache.remove(groupId)
        chatFlows.remove(groupId)
        infoFlows.remove(groupId)
        typingMembersByGroup.remove(groupId)
        attachmentBlockStores.remove(groupId)
        attachmentManifests.keys.removeAll { it.startsWith("$groupId\u0000") }
        attachmentRequests.entries.removeAll { it.value.groupId == groupId }
        applicationContext?.let { context ->
            P2PPreferences.prefs(context).edit()
                .remove("$PENDING_DEPARTURE_PREFIX$groupId")
                .remove("pinned_group_$groupId")
                .remove("mute_group_$groupId")
                .apply()
            runCatching {
                File(context.filesDir, "group_attachment_blocks/${sha256Hex(groupId)}")
                    .deleteRecursively()
                File(context.filesDir, "group_downloads/${sha256Hex(groupId)}")
                    .deleteRecursively()
                File(context.filesDir, "group_avatars/$groupId.jpg").delete()
            }
        }
    }

    private fun reactionState(
        groupId: String,
        targetEventIds: Collection<String>,
    ): Map<String, Boolean> =
        db().listActiveReactionKeys(groupId, targetEventIds).associateWith { true }

    private fun deliveryStatus(
        group: StoredGroup,
        message: StoredGroupMessage,
    ): GroupDeliveryStatus {
        if (message.authorDeviceId != group.localDeviceId) return GroupDeliveryStatus.REPLICATED
        val receipts = db().listReceipts(group.groupId, message.messageId)
        if (receipts.any { it.type == "READ" }) return GroupDeliveryStatus.READ
        val tasks = db().listOutboxForEvent(group.groupId, message.messageId)
        if (tasks.isEmpty()) return GroupDeliveryStatus.QUEUED
        val planned = tasks.map { DeviceId(it.recipientDeviceId) }
        val acknowledgements = tasks.map { task ->
            ReplicaAck(
                DeviceId(task.recipientDeviceId),
                when (task.state) {
                    StoredOutboxState.ACKED.name -> ReplicaAckState.STORED
                    StoredOutboxState.FAILED.name -> ReplicaAckState.FAILED
                    else -> ReplicaAckState.PENDING
                },
            )
        }
        val aggregate = ReplicaPlanner.aggregateDelivery(
            planned,
            acknowledgements,
            requiredQuorum = minOf(3, planned.distinct().size),
        )
        return when (aggregate.status) {
            ReplicationStatus.REPLICATED -> GroupDeliveryStatus.REPLICATED
            ReplicationStatus.FAILED -> GroupDeliveryStatus.FAILED
            ReplicationStatus.DEGRADED,
            ReplicationStatus.REPLICATING,
            -> if (aggregate.storedReplicaCount > 0) {
                GroupDeliveryStatus.DELIVERED
            } else {
                GroupDeliveryStatus.REPLICATING
            }
            ReplicationStatus.PENDING -> GroupDeliveryStatus.REPLICATING
        }
    }

    private fun requireTransportMember(groupId: String, peerName: String): StoredGroupMember {
        val fingerprint = transportFingerprint(peerName)
        val transportDeviceId = stableDeviceId(fingerprint)
        db().getGroup(groupId) ?: throw SecurityException("unknown group")
        val existing = db().listMembers(groupId).firstOrNull {
            it.isParticipating() && GroupMembershipTransitions.isReconnectCandidate(
                it.status,
                it.deviceId,
                it.transportFingerprint,
                fingerprint,
                transportDeviceId,
            )
        }
        if (existing != null) {
            if (existing.peerName != peerName) {
                db().upsertMember(
                    existing.copy(
                        peerName = peerName,
                    ),
                )
            }
            return existing.copy(
                peerName = peerName,
            )
        }
        throw SecurityException("group sender is not an active roster member")
    }

    private fun transportFingerprint(peerName: String): String {
        val context = requireNotNull(applicationContext)
        return P2PPreferences.prefs(context)
            .getString(P2PPreferences.peerFingerprint(peerName), null)
            ?.takeIf { it.isNotBlank() }
            ?: throw SecurityException("group sender has no pinned transport identity")
    }

    private fun ensurePinnedGroupRoute(member: StoredGroupMember): Boolean {
        if (
            member.peerName.isBlank() ||
            member.transportFingerprint.isBlank() ||
            member.deviceId != stableDeviceId(member.transportFingerprint)
        ) {
            return false
        }
        val context = applicationContext ?: return false
        val preferences = P2PPreferences.prefs(context)
        val key = P2PPreferences.peerFingerprint(member.peerName)
        val existing = preferences.getString(key, null)
        if (existing.isNullOrBlank()) {
            preferences.edit().putString(key, member.transportFingerprint).apply()
            return true
        }
        return existing == member.transportFingerprint
    }

    private fun signedInviteResponse(
        invite: GroupInvite,
        accepted: Boolean,
    ): GroupInviteResponse {
        val local = localIdentity()
        val unsigned = GroupInviteResponse(
            inviteId = invite.inviteId,
            groupId = invite.groupId,
            accepted = accepted,
            memberFingerprint = local.fingerprint,
            memberPeerName = local.displayName,
            memberDeviceId = local.deviceId,
            memberSigningKey = local.signingKey,
            createdAtMs = System.currentTimeMillis(),
            signatureBase64 = "",
        )
        return unsigned.copy(
            signatureBase64 = GroupIdentitySignatures.sign(unsigned.canonicalForSignature()),
        )
    }

    private fun GroupWireEvent.toStored(
        payload: JSONObject,
        wireJson: JSONObject,
    ): StoredGroupEvent = StoredGroupEvent(
        groupId = groupId,
        eventId = eventId,
        epoch = epoch,
        authorDeviceId = authorDeviceId,
        authorSeq = authorSequence,
        hlcPhysicalMs = hlcPhysicalMs,
        hlcLogical = hlcLogical,
        kind = when (kind) {
            GroupEventKind.MESSAGE,
            GroupEventKind.MEDIA,
            GroupEventKind.REPLY,
            -> if (kind == GroupEventKind.MEDIA) {
                GroupEventKind.MEDIA.name
            } else {
                StoredGroupEventKind.MESSAGE.name
            }
            GroupEventKind.POLL -> GroupEventKind.POLL.name
            GroupEventKind.POLL_VOTE -> GroupEventKind.POLL_VOTE.name
            GroupEventKind.EDIT -> StoredGroupEventKind.EDIT.name
            GroupEventKind.DELETE -> StoredGroupEventKind.DELETE.name
            else -> kind.name
        },
        body = when (kind) {
            GroupEventKind.MESSAGE,
            GroupEventKind.MEDIA,
            GroupEventKind.REPLY,
            GroupEventKind.EDIT,
            -> payload.optString("text")
            GroupEventKind.POLL,
            GroupEventKind.POLL_VOTE,
            -> payload.toString()
            GroupEventKind.REACTION_ADD,
            GroupEventKind.REACTION_REMOVE,
            -> payload.optString("emoji")
            else -> payload.toString()
        },
        targetEventId = targetEventId,
        controlHead = controlHead,
        payload = wireJson.toString().toByteArray(Charsets.UTF_8),
        createdAtMs = hlcPhysicalMs,
    )

    private fun StoredGroupMember.toPolicyMember(): GroupMember {
        val parsedRole = if (status == "RESTRICTED") {
            GroupRole.MEMBER
        } else {
            parseRole(role)
        }
        val removed = removedEpoch ?: if (parsedRole in setOf(GroupRole.BANNED, GroupRole.LEFT)) {
            joinedEpoch
        } else {
            null
        }
        return GroupMember(
            groupId = ConversationId(groupId),
            userId = UserId(accountId),
            role = parsedRole,
            permissions = GroupPermissionSet(permissions and GroupPermission.knownBits),
            joinedEpoch = joinedEpoch,
            removedEpoch = removed,
        )
    }

    private fun memberWasActiveAt(member: StoredGroupMember, epoch: Long): Boolean =
        epoch >= member.joinedEpoch && (member.removedEpoch == null || epoch < member.removedEpoch)

    private fun currentOwnerLineage(group: StoredGroup): OwnerLineageState {
        val currentOwner = db().getMember(group.groupId, group.ownerDeviceId)
            ?: throw SecurityException("group has no owner credential")
        val stored = db().listOwnerLineage(group.groupId)
        if (stored.isEmpty()) {
            return OwnerLineageState(
                anchor = GroupOwnerLineage.rootAnchor(
                    group.groupId,
                    currentOwner.transportFingerprint,
                    currentOwner.deviceId,
                    currentOwner.signingKeyBase64,
                ),
                nextSequence = 1,
                certificates = emptyList(),
            )
        }
        val certificates = stored.map { record ->
            require(record.sequence in 1..GroupOwnerLineage.MAX_TRANSITIONS)
            val certificate = GroupOwnerLineage.parse(
                JSONObject(record.payload.toString(Charsets.UTF_8)),
            )
            require(
                certificate.lineageSequence == record.sequence &&
                    certificate.transitionId() == record.transitionId,
            ) { "persisted owner lineage record does not match its certificate" }
            certificate
        }
        var expectedCredential = certificates.first().oldCredential()
        var expectedAnchor = GroupOwnerLineage.rootAnchor(
            group.groupId,
            expectedCredential.fingerprint,
            expectedCredential.deviceId,
            expectedCredential.signingKey,
        )
        certificates.forEachIndexed { index, certificate ->
            require(
                certificate.groupId == group.groupId &&
                    certificate.lineageSequence == index + 1 &&
                    certificate.previousOwnerAnchor == expectedAnchor &&
                    sameCredential(certificate.oldCredential(), expectedCredential) &&
                    certificate.verify() &&
                    stableDeviceId(certificate.oldOwnerFingerprint) ==
                    certificate.oldOwnerDeviceId &&
                    stableDeviceId(certificate.newOwnerFingerprint) ==
                    certificate.newOwnerDeviceId,
            ) { "persisted owner lineage is invalid" }
            listOf(certificate.oldCredential(), certificate.newCredential()).forEach { credential ->
                db().getMember(group.groupId, credential.deviceId)?.let { known ->
                    require(sameCredential(credential, known.toOwnerCredential())) {
                        "persisted owner lineage changes an established identity"
                    }
                }
            }
            expectedCredential = certificate.newCredential()
            expectedAnchor = GroupOwnerLineage.nextAnchor(certificate)
        }
        require(sameCredential(expectedCredential, currentOwner.toOwnerCredential())) {
            "persisted owner lineage does not end at the current owner"
        }
        return OwnerLineageState(
            anchor = expectedAnchor,
            nextSequence = certificates.size + 1,
            certificates = certificates,
        )
    }

    private fun verifyInviteOwnerContinuity(
        group: StoredGroup,
        establishedOwner: StoredGroupMember,
        invitingOwner: GroupInviteMember,
        invite: GroupInvite,
    ): Boolean {
        if (invite.ownerTransitions.isEmpty()) return false
        val localLineage = currentOwnerLineage(group)
        var expectedCredential: OwnerCredential? = null
        var expectedAnchor: String? = null
        var expectedSequence = 1
        var reachedLocalAnchor = false
        val seenAnchors = hashSetOf<String>()
        invite.ownerTransitions.forEach { certificate ->
            val oldCredential = certificate.oldCredential()
            val newCredential = certificate.newCredential()
            if (expectedCredential == null) {
                expectedCredential = oldCredential
                expectedAnchor = GroupOwnerLineage.rootAnchor(
                    group.groupId,
                    oldCredential.fingerprint,
                    oldCredential.deviceId,
                    oldCredential.signingKey,
                )
            }
            if (
                certificate.groupId != group.groupId ||
                certificate.lineageSequence != expectedSequence ||
                certificate.previousOwnerAnchor != expectedAnchor ||
                !seenAnchors.add(certificate.previousOwnerAnchor) ||
                !sameCredential(oldCredential, expectedCredential) ||
                !certificate.verify() ||
                stableDeviceId(oldCredential.fingerprint) != oldCredential.deviceId ||
                stableDeviceId(newCredential.fingerprint) != newCredential.deviceId ||
                certificate.createdAtMs > invite.createdAtMs + MAX_CLOCK_SKEW_MS
            ) {
                return false
            }
            listOf(oldCredential, newCredential).forEach { credential ->
                db().getMember(group.groupId, credential.deviceId)?.let { known ->
                    if (!sameCredential(credential, known.toOwnerCredential())) return false
                }
            }
            if (
                certificate.previousOwnerAnchor == localLineage.anchor &&
                sameCredential(oldCredential, establishedOwner.toOwnerCredential())
            ) {
                reachedLocalAnchor = true
            }
            expectedCredential = newCredential
            expectedAnchor = GroupOwnerLineage.nextAnchor(certificate)
            expectedSequence++
        }
        return reachedLocalAnchor &&
            seenAnchors.add(checkNotNull(expectedAnchor)) &&
            sameCredential(
                expectedCredential,
                OwnerCredential(
                    invitingOwner.fingerprint,
                    invitingOwner.deviceId,
                    invitingOwner.signingKey,
                ),
            )
    }

    private fun validatedInviteOwnerLineage(
        invite: GroupInvite,
        finalOwner: StoredGroupMember,
    ): List<StoredOwnerLineageCertificate> {
        if (invite.ownerTransitions.isEmpty()) {
            require(db().listOwnerLineage(invite.groupId).isEmpty()) {
                "group invite omits an established owner lineage"
            }
            return emptyList()
        }
        var expectedCredential = invite.ownerTransitions.first().oldCredential()
        var expectedAnchor = GroupOwnerLineage.rootAnchor(
            invite.groupId,
            expectedCredential.fingerprint,
            expectedCredential.deviceId,
            expectedCredential.signingKey,
        )
        val seen = hashSetOf<String>()
        invite.ownerTransitions.forEachIndexed { index, certificate ->
            require(
                certificate.groupId == invite.groupId &&
                    certificate.lineageSequence == index + 1 &&
                    certificate.previousOwnerAnchor == expectedAnchor &&
                    seen.add(expectedAnchor) &&
                    sameCredential(certificate.oldCredential(), expectedCredential) &&
                    certificate.verify() &&
                    stableDeviceId(certificate.oldOwnerFingerprint) ==
                    certificate.oldOwnerDeviceId &&
                    stableDeviceId(certificate.newOwnerFingerprint) ==
                    certificate.newOwnerDeviceId &&
                    certificate.createdAtMs <= invite.createdAtMs + MAX_CLOCK_SKEW_MS,
            ) { "group invite contains an invalid owner lineage" }
            listOf(certificate.oldCredential(), certificate.newCredential()).forEach { credential ->
                db().getMember(invite.groupId, credential.deviceId)?.let { known ->
                    require(sameCredential(credential, known.toOwnerCredential())) {
                        "group invite owner lineage changes an established identity"
                    }
                }
            }
            expectedCredential = certificate.newCredential()
            expectedAnchor = GroupOwnerLineage.nextAnchor(certificate)
        }
        require(
            seen.add(expectedAnchor) &&
                sameCredential(expectedCredential, finalOwner.toOwnerCredential()),
        ) { "group invite owner lineage does not end at its owner" }
        return invite.ownerTransitions.map { certificate ->
                StoredOwnerLineageCertificate(
                    groupId = invite.groupId,
                    sequence = certificate.lineageSequence,
                    transitionId = certificate.transitionId(),
                    payload = GroupOwnerLineage.toJson(certificate)
                        .toString()
                        .toByteArray(Charsets.UTF_8),
                )
            }
    }

    private fun isCanonicalControlEvent(group: StoredGroup, eventId: String): Boolean {
        return eventId in canonicalControlIds(group)
    }

    private fun canonicalControlEvents(group: StoredGroup): List<StoredGroupEvent> {
        val events = mutableListOf<StoredGroupEvent>()
        val visited = hashSetOf<String>()
        var cursor = group.controlHead
        while (cursor != null && visited.add(cursor)) {
            val eventId = cursor
            val event = db().getEvent(group.groupId, eventId) ?: return events
            events += event
            cursor = event.controlHead
        }
        return events
    }

    private fun canonicalControlIds(group: StoredGroup): Set<String?> {
        controlAncestorCache[group.groupId]
            ?.takeIf { it.head == group.controlHead }
            ?.let { return it.ids }
        val ids = linkedSetOf<String?>()
        var cursor = group.controlHead
        while (ids.add(cursor) && cursor != null) {
            cursor = db().getEvent(group.groupId, cursor)?.controlHead ?: break
        }
        return ids.toSet().also {
            controlAncestorCache[group.groupId] = ControlAncestorCache(group.controlHead, it)
        }
    }

    /**
     * Application events may be delayed across a control transition, so their
     * context can be an ancestor of the current head. A missing or forked head
     * is never accepted. Privileged operations are checked separately and must
     * name the exact current head.
     */
    private fun isCanonicalControlContext(group: StoredGroup, context: String?): Boolean {
        return context in canonicalControlIds(group)
    }

    private fun requiresCurrentControlHead(
        group: StoredGroup,
        author: StoredGroupMember,
        event: GroupWireEvent,
        payload: JSONObject,
    ): Boolean {
        if (isSerializedControl(event.kind)) return true
        if (event.kind in setOf(GroupEventKind.PIN, GroupEventKind.UNPIN)) return true
        if (
            event.kind == GroupEventKind.SYSTEM &&
            payload.optString("control_proposal").isNotBlank()
        ) {
            return true
        }
        if (event.kind !in setOf(GroupEventKind.EDIT, GroupEventKind.DELETE)) return false
        val target = event.targetEventId?.let { db().getEvent(group.groupId, it) } ?: return true
        return target.authorDeviceId != author.deviceId
    }

    private fun StoredGroupMember.isParticipating(): Boolean =
        GroupMembershipTransitions.isParticipating(status)

    private fun StoredGroupMember.toOwnerCredential(): OwnerCredential =
        OwnerCredential(transportFingerprint, deviceId, signingKeyBase64)

    private fun GroupOwnerTransitionCertificate.oldCredential(): OwnerCredential =
        OwnerCredential(oldOwnerFingerprint, oldOwnerDeviceId, oldOwnerSigningKey)

    private fun GroupOwnerTransitionCertificate.newCredential(): OwnerCredential =
        OwnerCredential(newOwnerFingerprint, newOwnerDeviceId, newOwnerSigningKey)

    private fun sameCredential(first: OwnerCredential?, second: OwnerCredential?): Boolean =
        first != null &&
            second != null &&
            first.fingerprint == second.fingerprint &&
            first.deviceId == second.deviceId &&
            first.signingKey == second.signingKey

    private fun parseRole(value: String): GroupRole = when (value) {
        "ADMIN" -> GroupRole.ADMINISTRATOR
        else -> runCatching { GroupRole.valueOf(value) }.getOrDefault(GroupRole.MEMBER)
    }

    private fun String?.toUiRole(): com.example.twopchat.group.ui.GroupRole = when (this) {
        GroupRole.OWNER.name -> com.example.twopchat.group.ui.GroupRole.OWNER
        GroupRole.ADMINISTRATOR.name, "ADMIN" -> com.example.twopchat.group.ui.GroupRole.ADMIN
        GroupRole.MODERATOR.name -> com.example.twopchat.group.ui.GroupRole.MODERATOR
        else -> com.example.twopchat.group.ui.GroupRole.MEMBER
    }

    private fun Long.toUiPermissions(): GroupMemberPermissions = GroupMemberPermissions(
        canSendMessages = this and GroupPermission.POST_MESSAGES.bit != 0L,
        canSendMedia = this and GroupPermission.POST_MEDIA.bit != 0L,
        canSendLinks = this and GroupPermission.POST_LINKS.bit != 0L,
        canAddMembers = this and GroupPermission.INVITE_MEMBERS.bit != 0L,
        canPinMessages = this and GroupPermission.PIN_MESSAGES.bit != 0L,
        canDeleteOthersMessages = this and GroupPermission.DELETE_ANY_MESSAGES.bit != 0L,
        canEditGroupInfo = this and GroupPermission.MANAGE_GROUP_INFO.bit != 0L,
        canBanMembers = this and GroupPermission.BAN_MEMBERS.bit != 0L,
        canAssignRoles = this and GroupPermission.ASSIGN_ROLES.bit != 0L,
    )

    private fun buildPermissionSet(permissions: GroupMemberPermissions): GroupPermissionSet {
        // These baseline member capabilities are not exposed as restriction
        // toggles. Preserve them so enabling every visible posting permission
        // produces the canonical MEMBER mask and therefore lifts restriction.
        var bits = GroupPermission.ADD_REACTIONS.bit or
            GroupPermission.EDIT_OWN_MESSAGES.bit or
            GroupPermission.DELETE_OWN_MESSAGES.bit
        if (permissions.canSendMessages) bits = bits or GroupPermission.POST_MESSAGES.bit
        if (permissions.canSendMedia) bits = bits or GroupPermission.POST_MEDIA.bit
        if (permissions.canSendLinks) bits = bits or GroupPermission.POST_LINKS.bit
        if (permissions.canAddMembers) bits = bits or GroupPermission.INVITE_MEMBERS.bit
        if (permissions.canPinMessages) bits = bits or GroupPermission.PIN_MESSAGES.bit
        if (permissions.canDeleteOthersMessages) bits = bits or GroupPermission.DELETE_ANY_MESSAGES.bit
        if (permissions.canEditGroupInfo) bits = bits or GroupPermission.MANAGE_GROUP_INFO.bit
        if (permissions.canBanMembers) bits = bits or GroupPermission.BAN_MEMBERS.bit
        if (permissions.canAssignRoles) bits = bits or GroupPermission.ASSIGN_ROLES.bit
        return GroupPermissionSet(bits)
    }

    private fun String?.roleRank(): Int = when (this) {
        GroupRole.OWNER.name -> 4
        GroupRole.ADMINISTRATOR.name, "ADMIN" -> 3
        GroupRole.MODERATOR.name -> 2
        GroupRole.MEMBER.name -> 1
        else -> 0
    }

    private fun attachmentStore(groupId: String): GroupAttachmentStore =
        attachmentBlockStores.computeIfAbsent(groupId) {
            GroupAttachmentStore(
                File(
                    File(
                        requireNotNull(applicationContext).filesDir,
                        "group_attachment_blocks",
                    ),
                    sha256Hex(groupId),
                ),
            )
        }

    private fun stageAttachment(uri: Uri): StagedAttachment? {
        val context = applicationContext ?: return null
        return runCatching {
            val resolver = context.contentResolver
            val displayName = if (uri.scheme == "file" || (uri.scheme == null && uri.path?.startsWith("/") == true)) {
                uri.lastPathSegment ?: File(uri.path.orEmpty()).name
            } else {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    } else {
                        null
                    }
                } ?: uri.lastPathSegment
            }
            var rawName = File(displayName.orEmpty()).name
            if (rawName.startsWith("sent_file_")) {
                rawName = rawName.replaceFirst(Regex("^sent_file_\\d+_[a-f0-9]+_"), "")
            }
            val mimeFromResolver = resolver.getType(uri)
            val inferredMime = mimeFromResolver ?: when {
                rawName.endsWith(".jpg", true) || rawName.endsWith(".jpeg", true) -> "image/jpeg"
                rawName.endsWith(".png", true) -> "image/png"
                rawName.endsWith(".webp", true) -> "image/webp"
                rawName.endsWith(".gif", true) -> "image/gif"
                rawName.endsWith(".mp4", true) -> "video/mp4"
                rawName.endsWith(".m4a", true) -> "audio/m4a"
                else -> null
            }
            if (!rawName.contains(".") && inferredMime != null) {
                val ext = when {
                    inferredMime.startsWith("image/jpeg") -> ".jpg"
                    inferredMime.startsWith("image/png") -> ".png"
                    inferredMime.startsWith("image/webp") -> ".webp"
                    inferredMime.startsWith("image/gif") -> ".gif"
                    inferredMime.startsWith("video/") -> ".mp4"
                    else -> ""
                }
                rawName += ext
            }
            val safeName = rawName.take(200).ifBlank { "attachment-${UUID.randomUUID()}" }
            val directory = File(
                context.cacheDir,
                "group-upload-${UUID.randomUUID()}",
            ).also { check(it.mkdirs() || it.isDirectory) }
            val destination = File(directory, safeName)
            var total = 0L
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "attachment URI cannot be opened" }
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        require(total <= MAX_ATTACHMENT_BYTES) { "attachment is too large" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val effectiveFile = if (ImageSanitizer.isSanitizableImage(destination.absolutePath)) {
                val sanitized = ImageSanitizer.sanitizeImageExif(context, destination.absolutePath)
                if (sanitized != null) {
                    val sanitizedDest = File(directory, "sanitized_${destination.nameWithoutExtension}.jpg")
                    sanitized.copyTo(sanitizedDest, overwrite = true)
                    TemporaryCacheSanitizer.shredFile(sanitized)
                    TemporaryCacheSanitizer.shredFile(destination)
                    sanitizedDest
                } else {
                    destination
                }
            } else {
                destination
            }
            StagedAttachment(
                directory = directory,
                file = effectiveFile,
                mimeType = if (effectiveFile != destination) "image/jpeg" else inferredMime,
            )
        }.onFailure {
            Log.w(TAG, "Could not stage group attachment: ${it.message}")
        }.getOrNull()
    }

    private fun loadAttachmentManifests(
        groupId: String,
        eventId: String,
    ): List<GroupAttachmentManifest> {
        val stored = db().getEvent(groupId, eventId)
            ?.takeIf { it.kind == GroupEventKind.MEDIA.name }
            ?: return emptyList()
        val wire = stored.payload?.let {
            runCatching {
                GroupWireProtocol.parseEvent(JSONObject(it.toString(Charsets.UTF_8)))
            }.getOrNull()
        } ?: return emptyList()
        val key = db().getEpochKey(groupId, wire.epoch) ?: return emptyList()
        return runCatching {
            val payload = eventFactory.decrypt(wire, key.keyMaterial)
            val list = mutableListOf<GroupAttachmentManifest>()
            val attachmentsArr = payload.optJSONArray("attachments")
            if (attachmentsArr != null && attachmentsArr.length() > 0) {
                for (i in 0 until attachmentsArr.length()) {
                    attachmentsArr.optJSONObject(i)?.let { json ->
                        list.add(GroupAttachmentManifest.fromJson(json))
                    }
                }
            } else {
                payload.optJSONObject("attachment")?.let { json ->
                    list.add(GroupAttachmentManifest.fromJson(json))
                }
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun loadAttachmentManifest(
        groupId: String,
        eventId: String,
    ): GroupAttachmentManifest? {
        val cacheKey = attachmentManifestKey(groupId, eventId)
        attachmentManifests[cacheKey]?.let { return it }
        val manifests = loadAttachmentManifests(groupId, eventId)
        return manifests.firstOrNull()?.also { attachmentManifests[cacheKey] = it }
    }

    private fun assembleAttachmentIfComplete(
        groupId: String,
        eventId: String,
        manifest: GroupAttachmentManifest,
    ): Boolean {
        if (attachmentStore(groupId).missingBlocks(manifest).isNotEmpty()) return false
        val destination = attachmentDestination(groupId, manifest)
        if (isVerifiedAttachmentDestination(destination, manifest)) return true
        return runCatching {
            attachmentStore(groupId).assemble(manifest, destination)
            attachmentVerificationFile(destination).writeText(
                listOf(
                    manifest.plaintextSha256,
                    manifest.plaintextSize,
                    destination.lastModified(),
                ).joinToString(":"),
            )
            true
        }.onFailure {
            Log.w(TAG, "Could not assemble attachment $eventId: ${it.message}")
        }.getOrDefault(false)
    }

    private fun isVerifiedAttachmentDestination(
        destination: File,
        manifest: GroupAttachmentManifest,
    ): Boolean {
        if (!destination.isFile || destination.length() != manifest.plaintextSize) return false
        val expected = listOf(
            manifest.plaintextSha256,
            manifest.plaintextSize,
            destination.lastModified(),
        ).joinToString(":")
        return attachmentVerificationFile(destination).takeIf(File::isFile)
            ?.readText()
            ?.trim() == expected
    }

    private fun attachmentVerificationFile(destination: File): File =
        File(destination.parentFile, "${destination.name}.verified")

    private fun attachmentDestination(
        groupId: String,
        manifest: GroupAttachmentManifest,
    ): File {
        val directory = File(
            requireNotNull(applicationContext).filesDir,
            "group_downloads/${sha256Hex(groupId)}/${manifest.attachmentId}",
        ).also { it.mkdirs() }
        return File(directory, File(manifest.fileName).name)
    }

    private fun formatByteCount(bytes: Long): String = when {
        bytes < 1_024 -> "$bytes B"
        bytes < 1_024L * 1_024L -> String.format(
            Locale.ROOT,
            "%.1f KiB",
            bytes / 1_024.0,
        )
        bytes < 1_024L * 1_024L * 1_024L -> String.format(
            Locale.ROOT,
            "%.1f MiB",
            bytes / (1_024.0 * 1_024.0),
        )
        else -> String.format(
            Locale.ROOT,
            "%.1f GiB",
            bytes / (1_024.0 * 1_024.0 * 1_024.0),
        )
    }

    private fun attachmentManifestKey(groupId: String, eventId: String): String =
        "$groupId\u0000$eventId"

    private fun reserveAttachmentServeBudget(key: String, bytes: Long): Boolean {
        if (bytes < 0L || bytes > ATTACHMENT_SERVE_BYTES_PER_WINDOW) return false
        val now = System.currentTimeMillis()
        var allowed = false
        attachmentServeBudgets.compute(key) { _, previous ->
            val current = previous?.takeIf {
                now - it.windowStartedAtMs in 0 until ATTACHMENT_SERVE_WINDOW_MS
            } ?: AttachmentServeBudget(now, 0L)
            if (current.usedBytes + bytes <= ATTACHMENT_SERVE_BYTES_PER_WINDOW) {
                allowed = true
                current.copy(usedBytes = current.usedBytes + bytes)
            } else {
                current
            }
        }
        return allowed
    }

    private fun isSerializedControl(kind: GroupEventKind): Boolean = kind in setOf(
        GroupEventKind.GROUP_UPDATED,
        GroupEventKind.MEMBER_ADDED,
        GroupEventKind.MEMBER_REMOVED,
        GroupEventKind.ROLE_CHANGED,
        GroupEventKind.MEMBER_RESTRICTED,
        GroupEventKind.OWNERSHIP_TRANSFERRED,
    )

    private fun isAdminLogKind(kind: String): Boolean = kind in setOf(
        GroupEventKind.GROUP_UPDATED.name,
        GroupEventKind.MEMBER_ADDED.name,
        GroupEventKind.MEMBER_REMOVED.name,
        GroupEventKind.ROLE_CHANGED.name,
        GroupEventKind.MEMBER_RESTRICTED.name,
        GroupEventKind.OWNERSHIP_TRANSFERRED.name,
        GroupEventKind.PIN.name,
        GroupEventKind.UNPIN.name,
        StoredGroupEventKind.DELETE.name,
    )

    private data class LocalIdentity(
        val fingerprint: String,
        val displayName: String,
        val signingKey: String,
        val deviceId: String,
    )

    private data class OwnerCredential(
        val fingerprint: String,
        val deviceId: String,
        val signingKey: String,
    )

    private data class OwnerLineageState(
        val anchor: String,
        val nextSequence: Int,
        val certificates: List<GroupOwnerTransitionCertificate>,
    )

    private data class PendingAttachmentRequest(
        val groupId: String,
        val eventId: String,
        val manifest: GroupAttachmentManifest,
        val requestedCids: Set<String>,
    )

    private data class StagedAttachment(
        val directory: File,
        val file: File,
        val mimeType: String?,
    )

    private data class ControlAncestorCache(
        val head: String?,
        val ids: Set<String?>,
    )

    private data class AttachmentServeBudget(
        val windowStartedAtMs: Long,
        val usedBytes: Long,
    )

    private fun localIdentity(): LocalIdentity {
        val context = requireNotNull(applicationContext)
        val fingerprint = P2PBridgeProvider.get(context).getLocalFingerprint()
        val signingKey = GroupIdentitySignatures.localVerificationKey()
        require(fingerprint.isNotBlank() && signingKey.isNotBlank()) {
            "local cryptographic identity is unavailable"
        }
        val name = P2PPreferences.prefs(context)
            .getString("username_profile", null)
            ?.takeIf { it.isNotBlank() }
            ?: "Me"
        return LocalIdentity(fingerprint, name.take(160), signingKey, stableDeviceId(fingerprint))
    }

    private fun stableDeviceId(fingerprint: String): String =
        sha256Hex("2pchat-group-device-v1\u0000$fingerprint")

    private fun outboxTaskId(groupId: String, eventId: String, recipient: String): String =
        sha256Hex("2pchat-group-outbox-v1\u0000$groupId\u0000$eventId\u0000$recipient")

    private fun inviteResponseEventId(inviteId: String): String =
        "$INVITE_RESPONSE_PREFIX$inviteId"

    private fun keyPackageEventId(epoch: Long, controlHead: String): String =
        "key-package:$epoch:$controlHead"

    private fun rosterSnapshotEventId(controlHead: String, pageIndex: Int): String =
        "roster:$pageIndex:$controlHead"

    private fun pendingInviteId(
        group: StoredGroup,
        recipientDeviceId: String,
        validityWindow: Long,
    ): String = sha256Hex(
        "2pchat-group-invite-v1\u0000${group.groupId}\u0000${group.currentEpoch}" +
            "\u0000${group.controlHead.orEmpty()}\u0000${group.ownerDeviceId}" +
            "\u0000$recipientDeviceId\u0000$validityWindow",
    )

    @Synchronized
    private fun inviteCapability(group: StoredGroup): String {
        val prefs = P2PPreferences.prefs(requireNotNull(applicationContext))
        val key = "group_invite_capability_${group.groupId}_${group.currentEpoch}"
        prefs.getString(key, null)?.takeIf { it.length in 43..128 }?.let { return it }
        val random = ByteArray(32).also(SecureRandom()::nextBytes)
        return encodeGroupInviteCapability(random).also { token ->
            prefs.edit().putString(key, token).apply()
        }
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private fun db(): GroupDatabaseHelper =
        database ?: error("group runtime is not initialized")

    private fun updateChatFlow(
        groupId: String,
        update: (GroupChatUiState) -> GroupChatUiState,
    ) {
        chatFlows[groupId]?.let { it.value = update(it.value) }
    }

    private fun emptyInfoState(groupId: String) = GroupInfoUiState(
        metadata = GroupMetadata(groupId, "Group", memberCount = 0),
        currentUserRole = com.example.twopchat.group.ui.GroupRole.MEMBER,
    )

    private val timeFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    }
    private val dateFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    private fun formatGroupLastMessagePreview(last: StoredGroupMessage?): String {
        if (last == null) return "No messages yet"
        if (last.deleted) return "Message deleted"
        val body = last.body
        val pollQuestion = runCatching {
            JSONObject(body)
                .takeIf {
                    it.optString("type") == "poll" &&
                        it.optJSONArray("options")?.length()?.let { count -> count >= 2 } == true
                }
                ?.optString("question")
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
        return when {
            pollQuestion != null -> "📊 $pollQuestion"
            body.startsWith("2psticker_") || body.lowercase().contains("sticker") -> "Стикер"
            body.startsWith("attachment-") -> "Вложение"
            else -> body.take(120)
        }
    }

    private fun formatTime(timestampMs: Long): String =
        timeFormatter.get()?.format(timestampMs) ?: ""

    private fun formatDate(timestampMs: Long): String =
        dateFormatter.get()?.format(timestampMs) ?: ""
}
