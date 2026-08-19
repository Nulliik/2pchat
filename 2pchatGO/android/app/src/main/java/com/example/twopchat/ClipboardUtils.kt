package com.example.twopchat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

private const val CLIPBOARD_CLEAR_DELAY_MS = 30_000L
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
private val clipboardTokenLock = Any()
private val clipboardClearTokens = java.util.WeakHashMap<ClipboardManager, Any>()

fun copyTextToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
    val clearToken = Any()
    synchronized(clipboardTokenLock) { clipboardClearTokens[clipboard] = clearToken }
    Handler(Looper.getMainLooper()).postDelayed({
        val isLatestCopy = synchronized(clipboardTokenLock) {
            clipboardClearTokens[clipboard] === clearToken
        }
        val currentText = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
        if (isLatestCopy && currentText == text) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            synchronized(clipboardTokenLock) { clipboardClearTokens.remove(clipboard) }
        }
    }, CLIPBOARD_CLEAR_DELAY_MS)
}

fun readTextFromClipboard(context: Context): String =
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        .orEmpty()
