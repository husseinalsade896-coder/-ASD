package com.example.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocalLanguage
import com.example.core.i18n.LocalStrings
import com.example.data.model.ChatRoom
import com.example.data.model.DirectoryProfile
import com.example.data.model.UiState
import com.example.ui.components.WChatAvatar
import com.example.ui.viewmodel.WChatViewModel
import java.text.SimpleDateFormat
import java.util.*

enum class HomeTab {
    CHATS, STATUSES, CALLS, SETTINGS, ADMIN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: WChatViewModel,
    isWideScreen: Boolean,
    onOpenChat: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val strings = LocalStrings.current
    val language = LocalLanguage.current
    var selectedTab by remember { mutableStateOf(HomeTab.CHATS) }
    val banner by viewModel.statusBanner.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(banner) {
        val msg = banner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    val tabs = remember(viewModel.isAdmin) {
        buildList {
            add(HomeTab.CHATS)
            add(HomeTab.STATUSES)
            add(HomeTab.CALLS)
            add(HomeTab.SETTINGS)
            if (viewModel.isAdmin) add(HomeTab.ADMIN)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!isWideScreen) {
                NavigationBar(modifier = Modifier.testTag("home_bottom_nav")) {
                    tabs.forEach { tab ->
                        val (icon, label) = tabMeta(tab, strings)
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}"),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (isWideScreen) {
                NavigationRail(modifier = Modifier.testTag("home_nav_rail")) {
                    Spacer(modifier = Modifier.height(12.dp))
                    tabs.forEach { tab ->
                        val (icon, label) = tabMeta(tab, strings)
                        NavigationRailItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    HomeTab.CHATS -> ChatsTabContent(
                        viewModel = viewModel,
                        onOpenChat = onOpenChat,
                        onToggleLanguage = {
                            viewModel.setLanguage(
                                if (language == AppLanguage.ARABIC) AppLanguage.ENGLISH else AppLanguage.ARABIC
                            )
                        },
                    )
                    HomeTab.STATUSES -> StatusScreen(viewModel = viewModel)
                    HomeTab.CALLS -> CallsHistoryTab(viewModel = viewModel)
                    HomeTab.SETTINGS -> SettingsScreen(viewModel = viewModel, onSignOut = onSignOut)
                    HomeTab.ADMIN -> if (viewModel.isAdmin) {
                        AdminDashboardScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}

private fun tabMeta(tab: HomeTab, strings: com.example.core.i18n.WChatStrings) = when (tab) {
    HomeTab.CHATS -> Icons.Rounded.Chat to strings.chatsTab
    HomeTab.STATUSES -> Icons.Rounded.DonutLarge to strings.statusesTab
    HomeTab.CALLS -> Icons.Rounded.Call to strings.callsTab
    HomeTab.SETTINGS -> Icons.Rounded.Settings to strings.settingsTab
    HomeTab.ADMIN -> Icons.Rounded.AdminPanelSettings to strings.adminTab
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsTabContent(
    viewModel: WChatViewModel,
    onOpenChat: (String) -> Unit,
    onToggleLanguage: () -> Unit,
) {
    val strings = LocalStrings.current
    val chatsState by viewModel.chatsState.collectAsStateWithLifecycle()
    val directoryState by viewModel.directoryState.collectAsStateWithLifecycle()
    val directory = (directoryState as? UiState.Success)?.data ?: emptyList()

    var searchQuery by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    var showNewChatSheet by remember { mutableStateOf(false) }
    var showNewGroupSheet by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(strings.appName, fontWeight = FontWeight.Bold)
                    Text(
                        text = strings.e2eeBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = onToggleLanguage,
                    modifier = Modifier.testTag("home_lang_button"),
                ) {
                    Icon(Icons.Rounded.Translate, contentDescription = strings.languageLabel)
                }
                IconButton(
                    onClick = { showNewGroupSheet = true },
                    modifier = Modifier.testTag("new_group_button"),
                ) {
                    Icon(Icons.Rounded.GroupAdd, contentDescription = strings.newGroup)
                }
                IconButton(
                    onClick = { showNewChatSheet = true },
                    modifier = Modifier.testTag("new_chat_button"),
                ) {
                    Icon(Icons.Rounded.EditNote, contentDescription = strings.newChat)
                }
            },
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(strings.searchChatsPlaceholder) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("search_chats_input"),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { showArchived = !showArchived }) {
                Icon(Icons.Rounded.Archive, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (showArchived) strings.hideArchived else strings.showArchived)
            }
            AssistChip(
                onClick = { showNewChatSheet = true },
                label = { Text("${directory.size}") },
                leadingIcon = { Icon(Icons.Rounded.Public, null, modifier = Modifier.size(16.dp)) },
            )
        }

        when (val state = chatsState) {
            is UiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            is UiState.Error -> {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }

            is UiState.Success -> {
                val filtered = state.data.filter { room ->
                    val archived = viewModel.currentUserId in room.archivedByUids
                    val matchesArchive = if (showArchived) archived else !archived
                    val displayName = resolveRoomDisplayName(room, viewModel.currentUserId, directory)
                    val matchesQuery = searchQuery.isBlank() ||
                        displayName.contains(searchQuery, ignoreCase = true) ||
                        room.lastMessagePreview.contains(searchQuery, ignoreCase = true)
                    matchesArchive && matchesQuery
                }

                val pinned = filtered.filter { viewModel.currentUserId in it.pinnedByUids }
                val regular = filtered.filter { viewModel.currentUserId !in it.pinnedByUids }

                if (filtered.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_empty_chats),
                            contentDescription = strings.noChatsTitle,
                            modifier = Modifier
                                .size(190.dp)
                                .clip(RoundedCornerShape(24.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(strings.noChatsTitle, style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            strings.noChatsSubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { showNewChatSheet = true },
                            modifier = Modifier.testTag("empty_start_chat_button"),
                        ) {
                            Icon(Icons.Rounded.AddComment, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.newChat)
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (pinned.isNotEmpty()) {
                            item {
                                Text(
                                    text = strings.pinnedSection,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                            items(pinned, key = { it.id }) { room ->
                                ChatRoomRow(
                                    room = room,
                                    myUid = viewModel.currentUserId,
                                    directory = directory,
                                    onClick = {
                                        viewModel.setActiveChat(room.id)
                                        onOpenChat(room.id)
                                    },
                                    onTogglePin = { viewModel.togglePin(room) },
                                    onToggleArchive = { viewModel.toggleArchive(room) },
                                )
                            }
                        }
                        if (regular.isNotEmpty()) {
                            item {
                                Text(
                                    text = strings.allChatsSection,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                            items(regular, key = { it.id }) { room ->
                                ChatRoomRow(
                                    room = room,
                                    myUid = viewModel.currentUserId,
                                    directory = directory,
                                    onClick = {
                                        viewModel.setActiveChat(room.id)
                                        onOpenChat(room.id)
                                    },
                                    onTogglePin = { viewModel.togglePin(room) },
                                    onToggleArchive = { viewModel.toggleArchive(room) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewChatSheet) {
        NewDirectChatDialog(
            myUid = viewModel.currentUserId,
            directory = directory,
            onDismiss = { showNewChatSheet = false },
            onSelectPeer = { peer ->
                showNewChatSheet = false
                viewModel.openDirectChat(peer) { chatId -> onOpenChat(chatId) }
            },
        )
    }

    if (showNewGroupSheet) {
        NewGroupDialog(
            myUid = viewModel.currentUserId,
            directory = directory,
            onDismiss = { showNewGroupSheet = false },
            onCreateGroup = { title, members ->
                showNewGroupSheet = false
                viewModel.createGroup(title, members) { chatId -> onOpenChat(chatId) }
            },
        )
    }
}

private fun resolveRoomDisplayName(
    room: ChatRoom,
    myUid: String,
    directory: List<DirectoryProfile>,
): String {
    if (room.chatType == "GROUP") return room.title
    val peerId = room.memberUids.firstOrNull { it != myUid } ?: myUid
    return directory.firstOrNull { it.userId == peerId }?.displayName ?: room.title
}

@Composable
private fun ChatRoomRow(
    room: ChatRoom,
    myUid: String,
    directory: List<DirectoryProfile>,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    val strings = LocalStrings.current
    val peer = if (room.chatType == "DIRECT") {
        val peerId = room.memberUids.firstOrNull { it != myUid } ?: myUid
        directory.firstOrNull { it.userId == peerId }
    } else null
    val title = peer?.displayName ?: room.title
    val isPinned = myUid in room.pinnedByUids
    val isArchived = myUid in room.archivedByUids
    val isTyping = room.typingUids.any { it != myUid }
    val timeLabel = remember(room.updatedAt) {
        val d = room.updatedAt?.toDate() ?: Date()
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
    }
    var menuOpen by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag("chat_item_${room.id}"),
        leadingContent = {
            WChatAvatar(
                name = title,
                online = peer?.onlineStatus == "ONLINE",
                isGroup = room.chatType == "GROUP",
                accentHex = peer?.avatarColorHex ?: "#0B7A67",
            )
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (peer?.countryCode?.isNotBlank() == true) {
                    Text(
                        text = peer.countryCode,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        supportingContent = {
            Text(
                text = if (isTyping) strings.typingNow else room.lastMessagePreview,
                color = if (isTyping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(timeLabel, style = MaterialTheme.typography.labelSmall)
                    if (isPinned) {
                        Icon(
                            Icons.Rounded.PushPin,
                            contentDescription = strings.pinChat,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (isPinned) strings.unpinChat else strings.pinChat) },
                            onClick = { menuOpen = false; onTogglePin() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (isArchived) strings.unarchiveChat else strings.archiveChat) },
                            onClick = { menuOpen = false; onToggleArchive() },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun NewDirectChatDialog(
    myUid: String,
    directory: List<DirectoryProfile>,
    onDismiss: () -> Unit,
    onSelectPeer: (DirectoryProfile) -> Unit,
) {
    val strings = LocalStrings.current
    var filter by remember { mutableStateOf("") }
    val candidates = directory.filter {
        it.userId != myUid && (filter.isBlank() ||
            it.displayName.contains(filter, true) ||
            it.username.contains(filter, true) ||
            it.countryCode.contains(filter, true))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.newChat) },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp)) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text(strings.searchChatsPlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn {
                    items(candidates, key = { it.userId }) { user ->
                        ListItem(
                            modifier = Modifier.clickable { onSelectPeer(user) },
                            leadingContent = { WChatAvatar(user.displayName, user.onlineStatus == "ONLINE") },
                            headlineContent = { Text(user.displayName) },
                            supportingContent = { Text("@${user.username} • ${user.countryCode}") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(strings.close) }
        },
    )
}

@Composable
private fun NewGroupDialog(
    myUid: String,
    directory: List<DirectoryProfile>,
    onDismiss: () -> Unit,
    onCreateGroup: (String, List<String>) -> Unit,
) {
    val strings = LocalStrings.current
    var groupTitle by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    val peers = directory.filter { it.userId != myUid }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.newGroup) },
        text = {
            Column(modifier = Modifier.heightIn(max = 380.dp)) {
                OutlinedTextField(
                    value = groupTitle,
                    onValueChange = { groupTitle = it },
                    label = { Text(strings.groupName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("group_name_input"),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(strings.selectMembers, style = MaterialTheme.typography.labelLarge)
                LazyColumn {
                    items(peers, key = { it.userId }) { user ->
                        val checked = user.userId in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (checked) selected.remove(user.userId) else selected.add(user.userId)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    if (it) selected.add(user.userId) else selected.remove(user.userId)
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("${user.displayName} (@${user.username})")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (groupTitle.isNotBlank()) onCreateGroup(groupTitle, selected.toList()) },
                enabled = groupTitle.isNotBlank(),
                modifier = Modifier.testTag("create_group_confirm"),
            ) {
                Text(strings.createGroup)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallsHistoryTab(viewModel: WChatViewModel) {
    val strings = LocalStrings.current
    val callsState by viewModel.callsState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(strings.callsTab, fontWeight = FontWeight.Bold) })
        when (val state = callsState) {
            is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
            is UiState.Success -> {
                if (state.data.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.Call,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(strings.callHistoryEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.data, key = { it.id }) { call ->
                            val outgoing = call.callerId == viewModel.currentUserId
                            val isMissed = call.status == "MISSED" || call.status == "DECLINED"
                            val dateStr = remember(call.createdAt) {
                                SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                                    .format(call.createdAt?.toDate() ?: Date())
                            }
                            ListItem(
                                leadingContent = {
                                    WChatAvatar(call.callerName, isGroup = call.callType.startsWith("GROUP"))
                                },
                                headlineContent = { Text(call.callerName, fontWeight = FontWeight.SemiBold) },
                                supportingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(
                                            imageVector = if (outgoing) Icons.Rounded.CallMade else Icons.Rounded.CallReceived,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (isMissed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        )
                                        Text("${call.callType} • ${call.status} • ${call.durationSeconds}s • $dateStr")
                                    }
                                },
                                trailingContent = {
                                    IconButton(
                                        onClick = {
                                            viewModel.startCall(call.participantUids, call.callType)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (call.callType.contains("VIDEO")) Icons.Rounded.Videocam else Icons.Rounded.Call,
                                            contentDescription = strings.voiceCall,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
