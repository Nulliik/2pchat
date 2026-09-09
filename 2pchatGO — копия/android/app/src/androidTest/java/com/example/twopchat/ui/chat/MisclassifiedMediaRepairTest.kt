package com.example.twopchat.ui.chat

import android.graphics.Bitmap
import com.example.twopchat.ui.chat.state.repairMisclassifiedLocalImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MisclassifiedMediaRepairTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun extensionlessImagePreviouslyStoredAsFileIsRepaired() {
        val image = temporaryFolder.newFile("sent_file_without_extension")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            image.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        } finally {
            bitmap.recycle()
        }
        val repaired = repairMisclassifiedLocalImage(
            Message(
                id = "legacy-image",
                text = image.name,
                isMe = true,
                timestamp = "22:25",
                attachmentType = "FILE",
                attachmentUri = image.absolutePath,
                attachmentName = image.name,
            ),
        )

        assertEquals("IMAGE", repaired.attachmentType)
    }

    @Test
    fun realDocumentRemainsFile() {
        val document = temporaryFolder.newFile("report.bin").apply {
            writeText("not an image")
        }
        val original = Message(
            id = "document",
            text = document.name,
            isMe = true,
            timestamp = "22:25",
            attachmentType = "FILE",
            attachmentUri = document.absolutePath,
            attachmentName = document.name,
        )

        assertEquals("FILE", repairMisclassifiedLocalImage(original).attachmentType)
    }
}
