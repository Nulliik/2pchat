package com.example.twopchat.ui.util

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PlatformImeOptions
import com.example.twopchat.config.*

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

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun IncognitoKeyboardScope(
        isIncognito: Boolean,
        content: @Composable () -> Unit
    ) {
        if (!isIncognito) {
            content()
            return
        }
        InterceptPlatformTextInput(
            interceptor = { request, nextHandler ->
                val incognitoRequest = PlatformTextInputMethodRequest { outAttributes ->
                    val connection = request.createInputConnection(outAttributes)
                    outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    outAttributes.inputType = outAttributes.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    val currentPrivate = outAttributes.privateImeOptions
                    outAttributes.privateImeOptions = if (currentPrivate.isNullOrEmpty()) {
                        INCOGNITO_PRIVATE_IME_OPTIONS
                    } else if (!currentPrivate.contains("incognito=true")) {
                        "$currentPrivate,$INCOGNITO_PRIVATE_IME_OPTIONS"
                    } else {
                        currentPrivate
                    }
                    connection
                }
                nextHandler.startInputMethod(incognitoRequest)
            },
            content = content
        )
    }
}

