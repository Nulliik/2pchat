package com.example.twopchat.config

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.twopchat.tor.TorTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class TorPreferencesApplyTest {
    @After
    fun tearDown() {
        P2PPreferences.setCachedPrefsForTesting(null)
    }

    @Test
    fun torPreferenceSettersUseApplyWhileOnionIndexRetainsCommit() {
        val stringValues = mutableMapOf<String, String>()
        val boolValues = mutableMapOf<String, Boolean>()
        val intValues = mutableMapOf<String, Int>()

        var applyCalls = 0
        var commitCalls = 0

        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> stringValues[args[0] as String] ?: args[1]
                "getBoolean" -> boolValues[args[0] as String] ?: (args[1] as Boolean)
                "getInt" -> intValues[args[0] as String] ?: (args[1] as Int)
                "edit" -> {
                    Proxy.newProxyInstance(
                        SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java),
                    ) { editor, editorMethod, editorArgs ->
                        when (editorMethod.name) {
                            "putString" -> {
                                stringValues[editorArgs[0] as String] = editorArgs[1] as String
                                editor
                            }
                            "putBoolean" -> {
                                boolValues[editorArgs[0] as String] = editorArgs[1] as Boolean
                                editor
                            }
                            "putInt" -> {
                                intValues[editorArgs[0] as String] = editorArgs[1] as Int
                                editor
                            }
                            "apply" -> {
                                applyCalls++
                                null
                            }
                            "commit" -> {
                                commitCalls++
                                true
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

        // UI toggles / setters: must call apply() and NOT commit()
        assertTrue(P2PPreferences.setTorBridgeLines(context, listOf("obfs4 1.2.3.4:443")))
        assertTrue(P2PPreferences.setPublicTorBridgesEnabled(context, false))
        assertTrue(P2PPreferences.setTorTransport(context, TorTransport.OBFS4))
        assertTrue(P2PPreferences.setTorOnionHostname(context, "test.onion"))
        assertTrue(P2PPreferences.setTorStrictMode(context, true))
        assertTrue(P2PPreferences.setTorHiddenServiceEnabled(context, false))
        assertTrue(P2PPreferences.setTorDeterministicOnionEnabled(context, true))

        assertEquals(7, applyCalls)
        assertEquals(0, commitCalls)

        // Durable monotonic rotation index: must call commit()
        assertTrue(P2PPreferences.setTorOnionIndex(context, 42))
        assertEquals(1, commitCalls)
    }
}
