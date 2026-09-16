package com.example.twopchat

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.twopchat.config.P2PPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class AppLanguagePreferencesTest {
    @After
    fun tearDown() {
        P2PPreferences.setCachedPrefsForTesting(null)
    }

    @Test
    fun setAppLanguageAppliesBothCompatibilityKeysInOneEdit() {
        val values = mutableMapOf<String, String>()
        val appliedBatches = mutableListOf<Map<String, String>>()
        var editCalls = 0
        var commitCalls = 0
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0] as String] ?: args[1]
                "edit" -> {
                    editCalls++
                    val pending = mutableMapOf<String, String>()
                    Proxy.newProxyInstance(
                        SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java),
                    ) { editor, editorMethod, editorArgs ->
                        when (editorMethod.name) {
                            "putString" -> {
                                pending[editorArgs[0] as String] = editorArgs[1] as String
                                editor
                            }
                            "apply" -> {
                                appliedBatches.add(pending.toMap())
                                values.putAll(pending)
                                pending.clear()
                                null
                            }
                            "commit" -> {
                                commitCalls++
                                false
                            }
                            else -> error("Unexpected editor method: ${editorMethod.name}")
                        }
                    }
                }
                else -> error("Unexpected preferences method: ${method.name}")
            }
        } as SharedPreferences
        P2PPreferences.setCachedPrefsForTesting(prefs)
        val context = object : ContextWrapper(null) {}

        listOf("Русский", "English").forEachIndexed { index, language ->
            val result: Unit = P2PPreferences.setAppLanguage(context, language)

            assertEquals(Unit, result)
            assertEquals(index + 1, editCalls)
            assertEquals(index + 1, appliedBatches.size)
            assertEquals(0, commitCalls)
            assertEquals(
                mapOf("app_language" to language, "settings_language" to language),
                appliedBatches.last(),
            )
            assertEquals(language, P2PPreferences.getAppLanguage(context))
            assertEquals(language, prefs.getString("app_language", null))
            assertEquals(language, prefs.getString("settings_language", null))
        }
    }
}
