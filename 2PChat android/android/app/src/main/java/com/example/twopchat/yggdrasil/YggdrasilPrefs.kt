package com.example.twopchat.yggdrasil

import android.content.Context

private const val PREFS_NAME = "2pchat_prefs"

fun yggdrasilPrefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

