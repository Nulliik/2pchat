package com.example.twopchat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.twopchat.security.AccountDataWiper
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountDataWiperInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun cleanUp() {
        AccountDataWiper.wipe(context)
    }

    @Test
    fun deletesConversationsStickersAndEveryAccountStorageArea() {
        val conversation = File(context.filesDir, "account-wipe-test/conversation.json").apply {
            parentFile?.mkdirs()
            writeText("old conversation")
        }
        val sticker = File(context.filesDir, "sticker_packs/account-wipe-test.webp").apply {
            parentFile?.mkdirs()
            writeText("old sticker")
        }
        val cachedSticker = File(context.cacheDir, "account-wipe-test.webp").apply { writeText("cached sticker") }
        val externalArtifact = context.externalCacheDir?.let { root ->
            File(root, "account-wipe-test.bin").apply { parentFile?.mkdirs(); writeText("external") }
        }
        val databaseName = "account-wipe-test.db"
        context.openOrCreateDatabase(databaseName, android.content.Context.MODE_PRIVATE, null).close()
        context.getSharedPreferences("account-wipe-test-preferences", android.content.Context.MODE_PRIVATE)
            .edit().putString("conversation", "old message").commit()
        context.getSharedPreferences("2pchat_notification_ids", android.content.Context.MODE_PRIVATE)
            .edit().putString("history_test", "old notification").commit()

        assertTrue(AccountDataWiper.wipe(context))

        assertFalse(conversation.exists())
        assertFalse(sticker.exists())
        assertFalse(cachedSticker.exists())
        assertFalse(externalArtifact?.exists() == true)
        assertFalse(context.databaseList().contains(databaseName))
        assertTrue(
            context.getSharedPreferences("account-wipe-test-preferences", android.content.Context.MODE_PRIVATE)
                .all.isEmpty(),
        )
        assertTrue(
            context.getSharedPreferences("2pchat_notification_ids", android.content.Context.MODE_PRIVATE)
                .all.isEmpty(),
        )
    }
}
