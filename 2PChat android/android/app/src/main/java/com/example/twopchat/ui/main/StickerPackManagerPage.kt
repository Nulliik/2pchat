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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
                val newlyAdded = result.pack.stickers.lastOrNull()
                if (newlyAdded != null) {
                    selectedStickerId = newlyAdded.stickerId
                    showEmojiDialog = true
                } else {
                    selectedStickerId = null
                }
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

    var packToExport by remember { mutableStateOf<BuiltinStickerPack?>(null) }
    val exportFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val pack = packToExport
        packToExport = null
        if (uri == null || pack == null) return@rememberLauncherForActivityResult
        operationRunning = true
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val tempArchive = StickerSupport.createPackArchive(context, pack.id)
                        ?: return@withContext false
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        tempArchive.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            operationRunning = false
            if (success) {
                notify(
                    "Стикерпак сохранён в память устройства",
                    "Sticker pack saved to device storage",
                )
            } else {
                notify("Не удалось сохранить файл", "Could not save file")
            }
        }
    }

    fun exportPack(pack: BuiltinStickerPack) {
        packToExport = pack
        val safeName = pack.title.replace(Regex("[^a-zA-Z0-9А-Яа-я_ -]"), "_").trim()
            .ifEmpty { "sticker_pack" } + ".2psticker"
        exportFileLauncher.launch(safeName)
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
                onExport = { exportPack(selectedPack) },
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
        EmojiAssignDialog(
            sticker = selectedSticker,
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariant = onSurfaceVariant,
            onDismiss = { showEmojiDialog = false },
            onConfirm = { newEmoji ->
                showEmojiDialog = false
                operationRunning = true
                scope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        StickerSupport.updateStickerEmoji(
                            context,
                            selectedPack.id,
                            selectedSticker.stickerId,
                            newEmoji,
                        )
                    }
                    operationRunning = false
                    if (updated != null) refreshKey += 1
                }
            },
        )
    }
}

@Composable
private fun EmojiAssignDialog(
    sticker: BuiltinSticker,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var emoji by remember(sticker.stickerId) {
        mutableStateOf(sticker.emoji.ifBlank { "" })
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = surfaceColor,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.12f), RoundedCornerShape(24.dp)),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Sticker Thumbnail Preview
                StickerThumbnail(sticker = sticker, size = 76)

                Spacer(Modifier.height(14.dp))

                Text(
                    text = if (appLanguage == "Русский") "Эмодзи стикера" else "Sticker Emoji",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor,
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = if (appLanguage == "Русский") {
                        "Выберите или введите эмодзи для быстрой отправки"
                    } else {
                        "Select or type an emoji for quick search"
                    },
                    fontSize = 12.sp,
                    color = onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(14.dp))

                // Quick Tap Emoji Row
                val popularEmojis = listOf("😀", "😂", "❤️", "🔥", "👍", "🎉", "😎", "😍", "✨", "🙏", "💯", "🥳")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(popularEmojis) { itemEmoji ->
                        val isSelected = emoji == itemEmoji
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) primaryColor.copy(alpha = 0.22f)
                                    else onSurfaceColor.copy(alpha = 0.05f),
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.dp,
                                    color = if (isSelected) primaryColor else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable { emoji = itemEmoji },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(itemEmoji, fontSize = 20.sp)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Custom Input Field (No "🎭" fallback!)
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it.take(16) },
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = if (appLanguage == "Русский") "Выберите или введите эмодзи..." else "Type emoji...",
                            fontSize = 13.sp,
                            color = onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primaryColor,
                        unfocusedBorderColor = onSurfaceColor.copy(alpha = 0.15f),
                        focusedContainerColor = onSurfaceColor.copy(alpha = 0.03f),
                        unfocusedContainerColor = onSurfaceColor.copy(alpha = 0.02f),
                    ),
                    trailingIcon = {
                        if (emoji.isNotEmpty()) {
                            IconButton(onClick = { emoji = "" }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(20.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (appLanguage == "Русский") "Пропустить" else "Skip")
                    }

                    Button(
                        onClick = { onConfirm(emoji) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (appLanguage == "Русский") "Сохранить" else "Save")
                    }
                }
            }
        }
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
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Move Up",
                        tint = if (canMoveUp) primaryColor else onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Move Down",
                        tint = if (canMoveDown) primaryColor else onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
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
    onExport: () -> Unit,
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pack.isOwned) {
                        Button(
                            onClick = onAdd,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (appLanguage == "Русский") "Стикеры" else "Stickers", maxLines = 1, fontSize = 13.sp)
                        }

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(onSurfaceColor.copy(alpha = 0.08f))
                                .clickable { onRename() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = if (appLanguage == "Русский") "Переименовать" else "Rename",
                                tint = primaryColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        Button(
                            onClick = onCopy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(if (appLanguage == "Русский") "Создать копию" else "Make a copy", maxLines = 1, fontSize = 13.sp)
                        }
                    }

                    // Export
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(onSurfaceColor.copy(alpha = 0.08f))
                            .clickable { onExport() },
                        contentAlignment = Alignment.Center
                    ) {
                        CustomExportIcon(tint = primaryColor, modifier = Modifier.size(16.dp))
                    }

                    // Share
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(onSurfaceColor.copy(alpha = 0.08f))
                            .clickable { onShare() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = if (appLanguage == "Русский") "Поделиться" else "Share",
                            tint = primaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Delete
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
                            .clickable { onDelete() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = if (appLanguage == "Русский") "Удалить пак" else "Delete pack",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
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
                IconButton(onClick = { onMoveSticker(-1) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Move Left",
                        tint = primaryColor
                    )
                }
                IconButton(onClick = { onMoveSticker(1) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Move Right",
                        tint = primaryColor
                    )
                }
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
        IconButton(onClick = onBackClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = onSurfaceColor
            )
        }
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

@Composable
private fun CustomExportIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = w * 0.12f
        drawPath(
            path = Path().apply {
                moveTo(w * 0.18f, h * 0.65f)
                lineTo(w * 0.18f, h * 0.85f)
                lineTo(w * 0.82f, h * 0.85f)
                lineTo(w * 0.82f, h * 0.65f)
            },
            color = tint,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = Path().apply {
                moveTo(w * 0.5f, h * 0.15f)
                lineTo(w * 0.5f, h * 0.62f)
                moveTo(w * 0.3f, h * 0.44f)
                lineTo(w * 0.5f, h * 0.64f)
                lineTo(w * 0.7f, h * 0.44f)
            },
            color = tint,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
