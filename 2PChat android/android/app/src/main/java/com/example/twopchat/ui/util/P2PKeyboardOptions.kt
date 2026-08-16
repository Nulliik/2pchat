package com.example.twopchat.ui.util

import android.content.Context
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PlatformImeOptions
import com.example.twopchat.P2PPreferences

object P2PKeyboardOptions {
    const val INCOGNITO_PRIVATE_IME_OPTIONS =
        "org.chromium.chrome.keyboard.incognito=true," +
        "com.google.android.inputmethod.latin.incognito=true," +
        "com.touchtype.swiftkey.incognito=true," +
        "com.samsung.android.inputmethod.incognito=true," +
        "com.google.android.inputmethod.latin.noMicrophoneKey=false"

    fun create(
        context: Context,
        capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
        keyboardType: KeyboardType = KeyboardType.Text,
        imeAction: ImeAction = ImeAction.Default,
    ): KeyboardOptions {
        val isIncognito = P2PPreferences.isIncognitoKeyboardEnabled(context)
        return KeyboardOptions(
            capitalization = capitalization,
            autoCorrectEnabled = !isIncognito,
            keyboardType = keyboardType,
            imeAction = imeAction,
            platformImeOptions = if (isIncognito) {
                PlatformImeOptions(privateImeOptions = INCOGNITO_PRIVATE_IME_OPTIONS)
            } else {
                null
            }
        )
    }
}
