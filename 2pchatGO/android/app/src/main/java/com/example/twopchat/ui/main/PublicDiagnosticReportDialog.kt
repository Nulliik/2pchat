package com.example.twopchat.ui.main

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.data.Localizations
import com.example.twopchat.diagnostics.DiagnosticsStore
import com.example.twopchat.diagnostics.PublicDiagnostics
import com.example.twopchat.diagnostics.ReportPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PublicDiagnosticReportDialog(appLanguage: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val text: (String) -> String = { Localizations.getString("diagnostics_$it", appLanguage) }
    var store by remember { mutableStateOf<DiagnosticsStore?>(null) }
    var enabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ReportPreview?>(null) }
    var pendingSave by remember { mutableStateOf<ReportPreview?>(null) }

    fun perform(operation: suspend () -> Unit) {
        scope.launch {
            busy = true
            failed = false
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally {
                enabled = store?.enabled == true
                if (!enabled) preview = null
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            store = withContext(Dispatchers.IO) { PublicDiagnostics.initialize(context) }
            enabled = store?.enabled == true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { busy = false }
    }

    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val approved = pendingSave
        pendingSave = null
        if (uri != null && approved != null) perform {
            withContext(Dispatchers.IO) {
                val reportStore = checkNotNull(store)
                val file = reportStore.export(approved)
                // A user-selected document provider receives only the approved ZIP.
                context.contentResolver.openOutputStream(uri, "wt").use { output ->
                    checkNotNull(output)
                    file.inputStream().use { it.copyTo(output) }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(text("title")) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text("description"))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(text("collect"), Modifier.weight(1f))
                    Switch(checked = enabled, enabled = !busy && store != null, onCheckedChange = { value ->
                        preview = null
                        perform { withContext(Dispatchers.IO) { checkNotNull(store).setEnabled(value) } }
                    })
                }
                Text(text("retention"), style = MaterialTheme.typography.bodySmall)
                Text(text("privacy"), style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(text("error"), color = MaterialTheme.colorScheme.error)
                if (enabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = {
                            perform { preview = withContext(Dispatchers.IO) { checkNotNull(store).preview() } }
                        }) { Text(text("preview")) }
                        TextButton(enabled = !busy, onClick = {
                            preview = null
                            perform { withContext(Dispatchers.IO) { checkNotNull(store).clear() } }
                        }) { Text(text("clear")) }
                    }
                }
                preview?.let { approved ->
                    Text(text("preview_description"), style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Text(approved.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = {
                            perform {
                                val reviewed = withContext(Dispatchers.IO) { checkNotNull(store).reviewedText(approved) }
                                clipboard.setText(AnnotatedString(reviewed))
                            }
                        }) { Text(text("copy")) }
                        TextButton(enabled = !busy, onClick = {
                            pendingSave = approved
                            saveDocument.launch(DiagnosticsStore.EXPORT_NAME)
                        }) { Text(text("save")) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = enabled && preview != null && !busy, onClick = {
                val approved = preview ?: return@TextButton
                perform {
                    val intent = withContext(Dispatchers.IO) { PublicDiagnostics.shareIntent(context, approved) }
                    withContext(Dispatchers.IO) { checkNotNull(store).reviewedText(approved) } // Recheck consent.
                    context.startActivity(Intent.createChooser(intent, text("share")))
                }
            }) { Text(text("share")) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(text("close")) } },
    )
}
