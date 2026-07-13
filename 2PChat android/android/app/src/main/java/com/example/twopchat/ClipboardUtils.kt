package com.example.twopchat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

fun copyTextToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun readTextFromClipboard(context: Context): String =
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        .orEmpty()
