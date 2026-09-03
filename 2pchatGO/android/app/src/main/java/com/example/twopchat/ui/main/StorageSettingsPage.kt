package com.example.twopchat.ui.main

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.data.Localizations
import com.example.twopchat.media.AttachmentCategory
import com.example.twopchat.media.AttachmentCategoryUsage
import com.example.twopchat.media.AttachmentStorageManager
import com.example.twopchat.media.StickerSupport
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.ui.chat.AttachmentImageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun calculateDirSize(file: File?): Long {
    if (file == null || !file.exists()) return 0L
    if (file.isFile) return file.length()
    var total = 0L
    val children = file.listFiles() ?: return 0L
    for (child in children) {
        total += calculateDirSize(child)
    }
    return total
}

private fun deleteDirContents(file: File?, keepDir: Boolean = true) {
    if (file == null || !file.exists()) return
    if (file.isDirectory) {
        val children = file.listFiles() ?: return
        for (child in children) {
            deleteDirContents(child, keepDir = false)
        }
    }
    if (!keepDir) {
        file.delete()
    }
}

fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 3)
    return "%.1f %s".format(bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

data class StorageSnapshot(
    val cacheBytes: Long,
    val receivedStickerBytes: Long,
    val avatarsBytes: Long,
    val logsBytes: Long,
    val databaseBytes: Long,
    val mediaUsage: Map<AttachmentCategory, AttachmentCategoryUsage>,
)

@Composable
fun StorageSettingsPage(
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val storageScope = rememberCoroutineScope()
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var receivedStickerBytes by remember { mutableLongStateOf(0L) }
    var avatarsBytes by remember { mutableLongStateOf(0L) }
    var logsBytes by remember { mutableLongStateOf(0L) }
    var dbBytes by remember { mutableLongStateOf(0L) }
    var mediaUsage by remember {
        mutableStateOf(
            AttachmentCategory.entries.associateWith {
                AttachmentCategoryUsage()
            },
        )
    }
    var isCalculating by remember { mutableStateOf(true) }
    var isClearingMedia by remember { mutableStateOf(false) }
    var stickerCacheLimitMb by remember {
        mutableIntStateOf(P2PPreferences.stickerCacheLimitMb(context))
    }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showMediaCleanupDialog by remember { mutableStateOf(false) }
    var selectedMediaCategories by remember {
        mutableStateOf(emptySet<AttachmentCategory>())
    }

    fun refreshStorageSizes() {
        isCalculating = true
        storageScope.launch {
            val sizes = runCatching {
                withContext(Dispatchers.IO) {
                    val cacheDir = context.cacheDir
                    val downloadsDir = File(context.filesDir, "config/downloads")
                    val attachmentsDir = File(context.filesDir, "attachments")
                    val cSize = calculateDirSize(cacheDir) +
                        calculateDirSize(downloadsDir) +
                        calculateDirSize(attachmentsDir)
                    val receivedStickersSize = calculateDirSize(
                        StickerSupport.receivedCacheDirectory(context),
                    )

                    val avatarsDir = File(context.filesDir, "avatars")
                    val aSize = calculateDirSize(avatarsDir)

                    val logFile = File(
                        File(context.filesDir, "config"),
                        "app.log",
                    )
                    val lSize = if (logFile.exists()) logFile.length() else 0L

                    val dbDir = context.getDatabasePath("twopchat.db").parentFile
                    val dSize = calculateDirSize(dbDir)
                    val usage = AttachmentStorageManager.calculateUsage(context)
                    StorageSnapshot(
                        cSize,
                        receivedStickersSize,
                        aSize,
                        lSize,
                        dSize,
                        usage,
                    )
                }
            }.getOrNull()
            if (sizes != null) {
                cacheBytes = sizes.cacheBytes
                receivedStickerBytes = sizes.receivedStickerBytes
                avatarsBytes = sizes.avatarsBytes
                logsBytes = sizes.logsBytes
                dbBytes = sizes.databaseBytes
                mediaUsage = sizes.mediaUsage
            }
            isCalculating = false
        }
    }

    LaunchedEffect(Unit) {
        refreshStorageSizes()
    }

    if (showClearConfirmDialog) {
        val dangerRed = Color(0xFFE53935)
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = {
                Text(
                    text = if (appLanguage == "Русский") "Очистить кэш и память?" else "Clear cache & storage?",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            },
            text = {
                Text(
                    text = if (appLanguage == "Русский") {
                        "Будут удалены временные файлы, кэш аватарок, полученные стикеры, загруженные файлы и логи. История сообщений останется нетронутой."
                    } else {
                        "Temporary files, cached avatars, received stickers, downloaded media, and logs will be deleted. Message history will remain intact."
                    },
                    fontSize = 14.sp,
                    color = onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmDialog = false
                        isClearingMedia = true
                        storageScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    AttachmentStorageManager.clear(
                                        context,
                                        AttachmentCategory.entries.toSet(),
                                    )
                                    deleteDirContents(context.cacheDir, keepDir = true)
                                    deleteDirContents(
                                        File(context.filesDir, "config/downloads"),
                                        keepDir = true,
                                    )
                                    deleteDirContents(
                                        File(context.filesDir, "avatars"),
                                        keepDir = true,
                                    )
                                    val logFile = File(
                                        File(context.filesDir, "config"),
                                        "app.log",
                                    )
                                    if (logFile.exists()) {
                                        logFile.writeText("")
                                    }
                                }
                                P2PMessageRelay.peerAvatars.clear()
                                AttachmentImageCache.clear()
                                Toast.makeText(
                                    context,
                                    if (appLanguage == "Русский") {
                                        "Память успешно очищена"
                                    } else {
                                        "Storage cleared successfully"
                                    },
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } catch (e: Exception) {
                                e.printStackTrace()
                                Toast.makeText(
                                    context,
                                    if (appLanguage == "Русский") {
                                        "Не удалось очистить память"
                                    } else {
                                        "Could not clear storage"
                                    },
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } finally {
                                isClearingMedia = false
                                refreshStorageSizes()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = dangerRed,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (appLanguage == "Русский") "Очистить" else "Clear",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text(
                        text = if (appLanguage == "Русский") "Отмена" else "Cancel",
                        color = primaryColor
                    )
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showMediaCleanupDialog) {
        val categoryLabels = mapOf(
            AttachmentCategory.VIDEO to (
                if (appLanguage == "Русский") "Видео" else "Videos"
            ),
            AttachmentCategory.IMAGE to (
                if (appLanguage == "Русский") "Изображения" else "Images"
            ),
            AttachmentCategory.FILE to (
                if (appLanguage == "Русский") "Документы и файлы" else "Documents & files"
            ),
            AttachmentCategory.VOICE to (
                if (appLanguage == "Русский") "Голосовые сообщения" else "Voice messages"
            ),
            AttachmentCategory.STICKER to (
                if (appLanguage == "Русский") "Стикеры собеседников" else "Peer stickers"
            ),
        )
        AlertDialog(
            onDismissRequest = {
                if (!isClearingMedia) showMediaCleanupDialog = false
            },
            title = {
                Text(
                    text = if (appLanguage == "Русский") {
                        "Удалить медиа по типу"
                    } else {
                        "Delete media by type"
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor,
                )
            },
            text = {
                Column {
                    Text(
                        text = if (appLanguage == "Русский") {
                            "Файлы будут удалены с устройства, но сообщения, подписи и даты останутся в чатах."
                        } else {
                            "Files will be removed from this device, while messages, captions, and dates remain in chats."
                        },
                        color = onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    AttachmentCategory.entries.forEach { category ->
                        val usage = mediaUsage[category] ?: AttachmentCategoryUsage()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isClearingMedia) {
                                    selectedMediaCategories =
                                        if (category in selectedMediaCategories) {
                                            selectedMediaCategories - category
                                        } else {
                                            selectedMediaCategories + category
                                        }
                                }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = category in selectedMediaCategories,
                                onCheckedChange = { checked ->
                                    selectedMediaCategories = if (checked) {
                                        selectedMediaCategories + category
                                    } else {
                                        selectedMediaCategories - category
                                    }
                                },
                                enabled = !isClearingMedia,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = primaryColor,
                                ),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = categoryLabels.getValue(category),
                                    color = onSurfaceColor,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "${usage.fileCount} • ${formatStorageSize(usage.bytes)}",
                                    color = onSurfaceVariant,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val categories = selectedMediaCategories
                        isClearingMedia = true
                        storageScope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    AttachmentStorageManager.clear(context, categories)
                                }
                                AttachmentImageCache.clear()
                                showMediaCleanupDialog = false
                                selectedMediaCategories = emptySet()
                                val deletedCount = result.deletedFiles
                                val skipped = result.skippedActiveTransfers > 0
                                val message = com.example.twopchat.data.Localizations.tr(
                                    appLanguage,
                                    ru = "Удалено: $deletedCount" + if (skipped) ". Активные передачи пропущены" else "",
                                    en = "Deleted: $deletedCount" + if (skipped) ". Active transfers were skipped" else "",
                                    de = "Gelöscht: $deletedCount" + if (skipped) ". Aktive Übertragungen übersprungen" else "",
                                    es = "Eliminado: $deletedCount" + if (skipped) ". Se omitieron las transferencias activas" else "",
                                    fr = "Supprimé : $deletedCount" + if (skipped) ". Les transferts actifs ont été ignorés" else "",
                                    pt = "Excluído: $deletedCount" + if (skipped) ". As transferências ativas foram ignoradas" else "",
                                    tr = "Silindi: $deletedCount"
                                )
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            } catch (error: Exception) {
                                error.printStackTrace()
                                Toast.makeText(
                                    context,
                                    com.example.twopchat.data.Localizations.tr(
                                        appLanguage,
                                        ru = "Не удалось удалить выбранные файлы",
                                        en = "Could not delete selected files",
                                        de = "Ausgewählte Dateien konnten nicht gelöscht werden",
                                        es = "No se pudieron eliminar los archivos seleccionados",
                                        fr = "Impossible de supprimer les fichiers sélectionnés",
                                        pt = "Não foi possível excluir os arquivos selecionados",
                                        tr = "Seçilen dosyalar silinemedi"
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } finally {
                                isClearingMedia = false
                                refreshStorageSizes()
                            }
                        }
                    },
                    enabled = selectedMediaCategories.isNotEmpty() && !isClearingMedia,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE53935),
                        contentColor = Color.White,
                    ),
                ) {
                    if (isClearingMedia) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Text(
                            if (appLanguage == "Русский") "Удалить" else "Delete",
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showMediaCleanupDialog = false },
                    enabled = !isClearingMedia,
                ) {
                    Text(
                        if (appLanguage == "Русский") "Отмена" else "Cancel",
                        color = primaryColor,
                    )
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SubPageLayout(
            title = if (appLanguage == "Русский") "Данные и память" else "Data & Storage",
            appLanguage = appLanguage,
            onBackClick = onBackClick,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor
        ) {
            val totalBytes =
                cacheBytes + receivedStickerBytes + avatarsBytes + logsBytes + dbBytes

            // Storage breakdown Card
            Text(
                text = if (appLanguage == "Русский") "Использование памяти" else "Storage Usage",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = onSurfaceColor,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "Всего занято" else "Total Used",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = onSurfaceColor
                        )
                        Text(
                            text = if (isCalculating) "..." else formatStorageSize(totalBytes),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = primaryColor
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                    // Item: Cache & Media
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(if (appLanguage == "Русский") "Временные файлы и медиа" else "Temporary Cache & Media", fontSize = 14.sp, color = onSurfaceColor)
                            Text(if (appLanguage == "Русский") "Кэш загрузок и медиафайлов" else "Downloads and media cache", fontSize = 11.sp, color = onSurfaceVariant)
                        }
                        Text(if (isCalculating) "..." else formatStorageSize(cacheBytes), fontSize = 14.sp, color = onSurfaceVariant)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Item: received sticker cache. Installed and owned packs are excluded.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                if (appLanguage == "Русский") {
                                    "Стикеры собеседников"
                                } else {
                                    "Peer stickers"
                                },
                                fontSize = 14.sp,
                                color = onSurfaceColor,
                            )
                            Text(
                                if (appLanguage == "Русский") {
                                    "Полученные в чатах, без добавленных паков"
                                } else {
                                    "Received in chats, excluding installed packs"
                                },
                                fontSize = 11.sp,
                                color = onSurfaceVariant,
                            )
                        }
                        Text(
                            if (isCalculating) "..." else {
                                formatStorageSize(receivedStickerBytes)
                            },
                            fontSize = 14.sp,
                            color = onSurfaceVariant,
                        )
                    }

                    Text(
                        text = if (appLanguage == "Русский") {
                            "Лимит кэша"
                        } else {
                            "Cache limit"
                        },
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        fontSize = 12.sp,
                        color = onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        P2PPreferences.STICKER_CACHE_LIMIT_OPTIONS_MB.forEach { limitMb ->
                            FilterChip(
                                selected = stickerCacheLimitMb == limitMb,
                                onClick = {
                                    if (stickerCacheLimitMb != limitMb) {
                                        stickerCacheLimitMb = limitMb
                                        P2PPreferences.setStickerCacheLimitMb(
                                            context,
                                            limitMb,
                                        )
                                        storageScope.launch {
                                            withContext(Dispatchers.IO) {
                                                StickerSupport.trimReceivedCache(context)
                                            }
                                            refreshStorageSizes()
                                        }
                                    }
                                },
                                label = { Text("$limitMb MB") },
                                leadingIcon = if (stickerCacheLimitMb == limitMb) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Item: Avatars
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(if (appLanguage == "Русский") "Кэш аватарок" else "Cached Avatars", fontSize = 14.sp, color = onSurfaceColor)
                            Text(if (appLanguage == "Русский") "Аватарки контактов" else "Peer profile pictures", fontSize = 11.sp, color = onSurfaceVariant)
                        }
                        Text(if (isCalculating) "..." else formatStorageSize(avatarsBytes), fontSize = 14.sp, color = onSurfaceVariant)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Item: Logs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(if (appLanguage == "Русский") "Логи приложения" else "App Logs", fontSize = 14.sp, color = onSurfaceColor)
                            Text(if (appLanguage == "Русский") "Файл системных логов" else "System log file", fontSize = 11.sp, color = onSurfaceVariant)
                        }
                        Text(if (isCalculating) "..." else formatStorageSize(logsBytes), fontSize = 14.sp, color = onSurfaceVariant)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Item: Database
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(if (appLanguage == "Русский") "База данных сообщений" else "Message Database", fontSize = 14.sp, color = onSurfaceColor)
                            Text(if (appLanguage == "Русский") "Зашифрованная история чатов" else "Encrypted chat history", fontSize = 11.sp, color = onSurfaceVariant)
                        }
                        Text(if (isCalculating) "..." else formatStorageSize(dbBytes), fontSize = 14.sp, color = onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = if (appLanguage == "Русский") {
                    "Локальные медиафайлы"
                } else {
                    "Local media files"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = onSurfaceColor,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        0.5.dp,
                        onSurfaceColor.copy(alpha = 0.04f),
                        RoundedCornerShape(16.dp),
                    ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val categoryLabels = mapOf(
                        AttachmentCategory.VIDEO to (
                            if (appLanguage == "Русский") "Видео" else "Videos"
                        ),
                        AttachmentCategory.IMAGE to (
                            if (appLanguage == "Русский") "Изображения" else "Images"
                        ),
                        AttachmentCategory.FILE to (
                            if (appLanguage == "Русский") "Документы и файлы" else "Documents & files"
                        ),
                        AttachmentCategory.VOICE to (
                            if (appLanguage == "Русский") "Голосовые сообщения" else "Voice messages"
                        ),
                        AttachmentCategory.STICKER to (
                            if (appLanguage == "Русский") {
                                "Стикеры собеседников"
                            } else {
                                "Peer stickers"
                            }
                        ),
                    )
                    AttachmentCategory.entries.forEachIndexed { index, category ->
                        val usage = mediaUsage[category] ?: AttachmentCategoryUsage()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                categoryLabels.getValue(category),
                                color = onSurfaceColor,
                                fontSize = 14.sp,
                            )
                            Text(
                                if (isCalculating) {
                                    "..."
                                } else {
                                    "${usage.fileCount} • ${formatStorageSize(usage.bytes)}"
                                },
                                color = onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                        if (index != AttachmentCategory.entries.lastIndex) {
                            Spacer(modifier = Modifier.height(9.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = {
                            selectedMediaCategories = emptySet()
                            showMediaCleanupDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCalculating && !isClearingMedia,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") {
                                "Выбрать типы для удаления"
                            } else {
                                "Choose media types to delete"
                            },
                            fontWeight = FontWeight.SemiBold,
                            color = primaryColor,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action: Clear Storage Button Card
            val dangerRed = Color(0xFFFF5252)
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, dangerRed.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = { showClearConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = dangerRed.copy(alpha = 0.18f),
                            contentColor = dangerRed
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCalculating && !isClearingMedia
                    ) {
                        Icon(
                            painter = painterResource(id = com.example.twopchat.R.drawable.ic_database_storage),
                            contentDescription = "Clear Storage",
                            tint = dangerRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (appLanguage == "Русский") "Очистить кэш и память" else "Clear Storage & Cache",
                            fontWeight = FontWeight.Bold,
                            color = dangerRed
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
