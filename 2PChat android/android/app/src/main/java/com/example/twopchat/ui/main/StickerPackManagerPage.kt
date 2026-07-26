package com.example.twopchat.ui.main

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.twopchat.BuiltinSticker
import com.example.twopchat.BuiltinStickerPack
import com.example.twopchat.P2PPreferences
import com.example.twopchat.StickerSupport
import com.example.twopchat.ui.chat.AnimatedStickerImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun StickerPackManagerPage(
    appLanguage: String,
    onBackClick: () -> Unit,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val author = remember {
        P2PPreferences.prefs(context).getString("username_profile", "2PChat User")
            .orEmpty()
            .ifBlank { "2PChat User" }
    }
    var packs by remember { mutableStateOf<List<BuiltinStickerPack>>(emptyList()) }
    var selectedPackId by remember { mutableStateOf<String?>(null) }
    var selectedStickerId by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var operationRunning by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEmojiDialog by remember { mutableStateOf(false) }
    var pendingCreate by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun notify(messageRu: String, messageEn: String) {
        Toast.makeText(
            context,
            if (appLanguage == "Русский") messageRu else messageEn,
            Toast.LENGTH_SHORT,
        ).show()
    }

    LaunchedEffect(refreshKey) {
        loading = true
        packs = withContext(Dispatchers.IO) { StickerSupport.managedPacks(context) }
        if (selectedPackId != null && packs.none { it.id == selectedPackId }) {
            selectedPackId = null
            selectedStickerId = null
        }
        loading = false
    }

    val sourcePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val createDraft = pendingCreate
        pendingCreate = null
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        operationRunning = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (createDraft != null) {
                    StickerSupport.createCustomPack(
                        context,
                        createDraft.first,
                        createDraft.second,
                        uris,
                    )
                } else {
                    val packId = selectedPackId
                        ?: return@withContext StickerSupport.PackMutationResult(null)
                    StickerSupport.addStickersToPack(context, packId, uris)
                }
            }
            operationRunning = false
            if (result.pack != null) {
                selectedPackId = result.pack.id
                selectedStickerId = null
                refreshKey += 1
                notify(
                    "Добавлено стикеров: ${result.addedCount}",
                    "Stickers added: ${result.addedCount}",
                )
            } else {
                notify("Не удалось добавить стикеры", "Could not add stickers")
            }
            if (result.rejectedCount > 0) {
                notify(
                    "Пропущено файлов: ${result.rejectedCount}",
                    "Files skipped: ${result.rejectedCount}",
                )
            }
        }
    }

    val archivePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        operationRunning = true
        scope.launch {
            val pack = withContext(Dispatchers.IO) {
                StickerSupport.importPackArchive(context, uri)
            }
            operationRunning = false
            if (pack != null) {
                selectedPackId = pack.id
                refreshKey += 1
                notify("Стикерпак добавлен", "Sticker pack added")
            } else {
                notify(
                    "Не удалось импортировать .2psticker",
                    "Could not import .2psticker",
                )
            }
        }
    }

    fun sharePack(pack: BuiltinStickerPack) {
        operationRunning = true
        scope.launch {
            val archive = withContext(Dispatchers.IO) {
                StickerSupport.createPackArchive(context, pack.id)
            }
            operationRunning = false
            if (archive == null) {
                notify("Не удалось подготовить стикерпак", "Could not prepare sticker pack")
                return@launch
            }
            runCatching {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    archive,
                )
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        if (appLanguage == "Русский") "Поделиться стикерпаком" else "Share sticker pack",
                    ),
                )
            }.onFailure {
                notify("Не удалось открыть меню отправки", "Could not open share menu")
            }
        }
    }

    BackHandler {
        if (selectedPackId != null) {
            selectedPackId = null
            selectedStickerId = null
        } else {
            onBackClick()
        }
    }

    val selectedPack = packs.firstOrNull { it.id == selectedPackId }
    Box(Modifier.fillMaxSize()) {
        if (selectedPack == null) {
            PackList(
                packs = packs,
                loading = loading,
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onBackClick = onBackClick,
                onCreate = { showCreateDialog = true },
                onImport = { archivePicker.launch(arrayOf("*/*")) },
                onSelect = { selectedPackId = it.id },
                onMove = { pack, offset ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            StickerSupport.moveManagedPack(context, pack.id, offset)
                        }
                        refreshKey += 1
                    }
                },
            )
        } else {
            PackEditor(
                pack = selectedPack,
                selectedStickerId = selectedStickerId,
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onBackClick = {
                    selectedPackId = null
                    selectedStickerId = null
                },
                onSelectSticker = { selectedStickerId = it.stickerId },
                onAdd = { sourcePicker.launch(arrayOf("image/*")) },
                onRename = { showRenameDialog = true },
                onCopy = { showCopyDialog = true },
                onShare = { sharePack(selectedPack) },
                onDelete = { showDeleteDialog = true },
                onMoveSticker = { offset ->
                    val stickerId = selectedStickerId ?: return@PackEditor
                    operationRunning = true
                    scope.launch {
                        val updated = withContext(Dispatchers.IO) {
                            StickerSupport.moveSticker(context, selectedPack.id, stickerId, offset)
                        }
                        operationRunning = false
                        if (updated != null) refreshKey += 1
                    }
                },
                onRemoveSticker = {
                    val stickerId = selectedStickerId ?: return@PackEditor
                    operationRunning = true
                    scope.launch {
                        val updated = withContext(Dispatchers.IO) {
                            StickerSupport.removeSticker(context, selectedPack.id, stickerId)
                        }
                        operationRunning = false
                        if (updated != null) {
                            selectedStickerId = null
                            refreshKey += 1
                        }
                    }
                },
                onEditEmoji = { showEmojiDialog = true },
            )
        }

        if (operationRunning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = primaryColor)
            }
        }
    }

    if (showCreateDialog) {
        PackNameDialog(
            title = if (appLanguage == "Русский") "Новый стикерпак" else "New sticker pack",
            initialValue = "",
            appLanguage = appLanguage,
            confirmLabel = if (appLanguage == "Русский") "Выбрать стикеры" else "Choose stickers",
            onDismiss = { showCreateDialog = false },
            onConfirm = { title ->
                showCreateDialog = false
                pendingCreate = title to author
                sourcePicker.launch(arrayOf("image/*"))
            },
        )
    }

    if (showRenameDialog && selectedPack != null) {
        PackNameDialog(
            title = if (appLanguage == "Русский") "Название стикерпака" else "Sticker pack name",
            initialValue = selectedPack.title,
            appLanguage = appLanguage,
            confirmLabel = if (appLanguage == "Русский") "Сохранить" else "Save",
            onDismiss = { showRenameDialog = false },
            onConfirm = { title ->
                showRenameDialog = false
                operationRunning = true
                scope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        StickerSupport.renameOwnedPack(context, selectedPack.id, title)
                    }
                    operationRunning = false
                    if (updated != null) refreshKey += 1
                }
            },
        )
    }

    if (showCopyDialog && selectedPack != null) {
        PackNameDialog(
            title = if (appLanguage == "Русский") "Создать редактируемую копию" else "Create editable copy",
            initialValue = "${selectedPack.title} copy",
            appLanguage = appLanguage,
            confirmLabel = if (appLanguage == "Русский") "Создать" else "Create",
            onDismiss = { showCopyDialog = false },
            onConfirm = { title ->
                showCopyDialog = false
                operationRunning = true
                scope.launch {
                    val copy = withContext(Dispatchers.IO) {
                        StickerSupport.copyPackAsOwned(context, selectedPack.id, title, author)
                    }
                    operationRunning = false
                    if (copy != null) {
                        selectedPackId = copy.id
                        refreshKey += 1
                    } else {
                        notify("Не удалось создать копию", "Could not create a copy")
                    }
                }
            },
        )
    }

    if (showDeleteDialog && selectedPack != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    if (appLanguage == "Русский") {
                        "Удалить «${selectedPack.title}»?"
                    } else {
                        "Delete “${selectedPack.title}”?"
                    },
                )
            },
            text = {
                Text(
                    if (appLanguage == "Русский") {
                        "Пак исчезнет из коллекции. Уже отправленные сообщения останутся в чатах."
                    } else {
                        "The pack will leave your collection. Previously sent messages stay in chats."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        operationRunning = true
                        scope.launch {
                            val deleted = withContext(Dispatchers.IO) {
                                StickerSupport.deleteManagedPack(context, selectedPack.id)
                            }
                            operationRunning = false
                            if (deleted) {
                                selectedPackId = null
                                selectedStickerId = null
                                refreshKey += 1
                            }
                        }
                    },
                ) {
                    Text(
                        if (appLanguage == "Русский") "Удалить" else "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(if (appLanguage == "Русский") "Отмена" else "Cancel")
                }
            },
        )
    }

    val selectedSticker = selectedPack?.stickers?.firstOrNull {
        it.stickerId == selectedStickerId
    }
    if (showEmojiDialog && selectedPack != null && selectedSticker != null) {
        var emoji by remember(selectedPack.id, selectedSticker.stickerId) {
            mutableStateOf(selectedSticker.emoji.ifBlank { "🎭" })
        }
        AlertDialog(
            onDismissRequest = { showEmojiDialog = false },
            title = {
                Text(if (appLanguage == "Русский") "Эмодзи стикера" else "Sticker emoji")
            },
            text = {
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it.take(16) },
                    singleLine = true,
                    supportingText = {
                        Text(
                            if (appLanguage == "Русский") {
                                "Используется для поиска и как запасной значок"
                            } else {
                                "Used for search and as a fallback icon"
                            },
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = emoji.isNotBlank(),
                    onClick = {
                        showEmojiDialog = false
                        operationRunning = true
                        scope.launch {
                            val updated = withContext(Dispatchers.IO) {
                                StickerSupport.updateStickerEmoji(
                                    context,
                                    selectedPack.id,
                                    selectedSticker.stickerId,
                                    emoji,
                                )
                            }
                            operationRunning = false
                            if (updated != null) refreshKey += 1
                        }
                    },
                ) {
                    Text(if (appLanguage == "Русский") "Сохранить" else "Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmojiDialog = false }) {
                    Text(if (appLanguage == "Русский") "Отмена" else "Cancel")
                }
            },
        )
    }
}

@Composable
private fun PackList(
    packs: List<BuiltinStickerPack>,
    loading: Boolean,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onBackClick: () -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onSelect: (BuiltinStickerPack) -> Unit,
    onMove: (BuiltinStickerPack, Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        ManagerHeader(
            title = if (appLanguage == "Русский") "Стикерпаки" else "Sticker packs",
            onBackClick = onBackClick,
            onSurfaceColor = onSurfaceColor,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onCreate,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
            ) {
                Text(if (appLanguage == "Русский") "＋ Создать" else "＋ Create")
            }
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                Text(if (appLanguage == "Русский") "Импорт" else "Import")
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (appLanguage == "Русский") {
                "МОИ ПАКИ · ${packs.count { it.isOwned }}   ДОБАВЛЕННЫЕ · ${packs.count { !it.isOwned }}"
            } else {
                "MY PACKS · ${packs.count { it.isOwned }}   ADDED · ${packs.count { !it.isOwned }}"
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = primaryColor)
            }
            packs.isEmpty() -> EmptyPackState(
                appLanguage = appLanguage,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
            )
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(packs, key = { it.id }) { pack ->
                    val index = packs.indexOf(pack)
                    PackCard(
                        pack = pack,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor,
                        onSurfaceVariant = onSurfaceVariant,
                        appLanguage = appLanguage,
                        onClick = { onSelect(pack) },
                        onMoveUp = { if (index > 0) onMove(pack, -1) },
                        onMoveDown = { if (index < packs.lastIndex) onMove(pack, 1) },
                        canMoveUp = index > 0,
                        canMoveDown = index < packs.lastIndex,
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun PackCard(
    pack: BuiltinStickerPack,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    appLanguage: String,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            pack.title,
                            color = onSurfaceColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (pack.isOwned) {
                                if (appLanguage == "Русский") "МОЙ" else "MINE"
                            } else {
                                if (appLanguage == "Русский") "ДОБАВЛЕН" else "ADDED"
                            },
                            color = primaryColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(primaryColor.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                    Text(
                        "${pack.author} · ${pack.stickers.size}",
                        color = onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("↑") }
                TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("↓") }
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pack.stickers.take(6), key = { it.stickerId }) { sticker ->
                    StickerThumbnail(sticker = sticker, size = 54)
                }
            }
        }
    }
}

@Composable
private fun PackEditor(
    pack: BuiltinStickerPack,
    selectedStickerId: String?,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onBackClick: () -> Unit,
    onSelectSticker: (BuiltinSticker) -> Unit,
    onAdd: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onMoveSticker: (Int) -> Unit,
    onRemoveSticker: () -> Unit,
    onEditEmoji: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        ManagerHeader(pack.title, onBackClick, onSurfaceColor)
        Card(
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(pack.title, color = onSurfaceColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    "${pack.author} · ${pack.stickers.size}/ ${StickerSupport.MAX_PACK_STICKERS}",
                    color = onSurfaceVariant,
                    fontSize = 12.sp,
                )
                if (pack.isOwned) {
                    Text(
                        if (appLanguage == "Русский") {
                            "PNG/JPEG и WebP до 512×512 · анимированный WebP сохраняет анимацию"
                        } else {
                            "PNG/JPEG and WebP up to 512×512 · animated WebP keeps its animation"
                        },
                        color = onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (pack.isOwned) {
                        FilledTonalButton(onClick = onAdd) {
                            Text(if (appLanguage == "Русский") "＋ Стикеры" else "＋ Stickers")
                        }
                        OutlinedButton(onClick = onRename) {
                            Text(if (appLanguage == "Русский") "Переименовать" else "Rename")
                        }
                    } else {
                        FilledTonalButton(onClick = onCopy) {
                            Text(if (appLanguage == "Русский") "Создать копию" else "Make a copy")
                        }
                    }
                    OutlinedButton(onClick = onShare) {
                        Text(if (appLanguage == "Русский") "Поделиться" else "Share")
                    }
                    TextButton(onClick = onDelete) {
                        Text(
                            if (appLanguage == "Русский") "Удалить пак" else "Delete pack",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (pack.isOwned) {
                if (appLanguage == "Русский") {
                    "Выберите стикер, чтобы изменить порядок или удалить его"
                } else {
                    "Select a sticker to reorder or remove it"
                }
            } else {
                if (appLanguage == "Русский") {
                    "Полученный пак доступен только для просмотра"
                } else {
                    "Received packs are read-only"
                }
            },
            color = onSurfaceVariant,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(pack.stickers, key = { it.stickerId }) { sticker ->
                val selected = sticker.stickerId == selectedStickerId
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(78.dp)
                        .background(
                            if (selected) {
                                primaryColor.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            },
                            RoundedCornerShape(18.dp),
                        )
                        .border(
                            if (selected) 2.dp else 0.dp,
                            if (selected) primaryColor else Color.Transparent,
                            RoundedCornerShape(18.dp),
                        )
                        .clickable { onSelectSticker(sticker) },
                    contentAlignment = Alignment.Center,
                ) {
                    StickerThumbnail(sticker = sticker, size = 66)
                }
            }
        }
        if (pack.isOwned && selectedStickerId != null) {
            HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.08f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { onMoveSticker(-1) }) { Text("←") }
                OutlinedButton(onClick = { onMoveSticker(1) }) { Text("→") }
                OutlinedButton(onClick = onEditEmoji) { Text("Emoji") }
                TextButton(
                    onClick = onRemoveSticker,
                    enabled = pack.stickers.size > 1,
                ) {
                    Text(
                        if (appLanguage == "Русский") "Удалить стикер" else "Remove sticker",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun StickerThumbnail(sticker: BuiltinSticker, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedStickerImage(
            filePath = sticker.localFilePath,
            fallbackEmoji = sticker.emoji,
            contentDescription = sticker.emoji.ifBlank { "Sticker" },
            targetSizePx = size * 2,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun EmptyPackState(
    appLanguage: String,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🎭", fontSize = 64.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                if (appLanguage == "Русский") "Здесь появятся ваши стикерпаки" else "Your sticker packs will appear here",
                color = onSurfaceColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (appLanguage == "Русский") {
                    "Создайте новый пак или импортируйте .2psticker"
                } else {
                    "Create a pack or import a .2psticker file"
                },
                color = onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ManagerHeader(
    title: String,
    onBackClick: () -> Unit,
    onSurfaceColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBackClick) { Text("←") }
        Text(
            title,
            color = onSurfaceColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PackNameDialog(
    title: String,
    initialValue: String,
    appLanguage: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(title, initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(80) },
                singleLine = true,
                label = {
                    Text(if (appLanguage == "Русский") "Название" else "Name")
                },
                supportingText = { Text("${value.length}/80") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (appLanguage == "Русский") "Отмена" else "Cancel")
            }
        },
    )
}
