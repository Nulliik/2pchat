package com.example.twopchat.group.attachments

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupAttachmentStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var testRoot: File
    private lateinit var plaintext: ByteArray
    private lateinit var sourceFile: File
    private lateinit var sourceStore: GroupAttachmentStore
    private lateinit var manifest: GroupAttachmentManifest
    private lateinit var encryptedBlocks: Map<String, ByteArray>

    @Before
    fun setUp() {
        testRoot = File(
            context.cacheDir,
            "group-attachment-instrumented-${UUID.randomUUID()}",
        )
        check(testRoot.mkdirs())
        plaintext = ByteArray(MIN_CHUNK_SIZE * 3 + 137) { index ->
            ((index * 29 + 5) and 0xff).toByte()
        }
        sourceFile = File(testRoot, "source.bin").apply { writeBytes(plaintext) }
        sourceStore = GroupAttachmentStore(
            rootDirectory = File(testRoot, "source-blocks"),
            chunkSize = MIN_CHUNK_SIZE,
        )
        manifest = sourceStore.encrypt(sourceFile, "application/octet-stream")
        encryptedBlocks = manifest.blocks.associate { block ->
            block.ciphertextCid to checkNotNull(sourceStore.readBlock(block.ciphertextCid))
        }
    }

    @After
    fun tearDown() {
        val cacheRoot = context.cacheDir.canonicalFile
        val canonicalTestRoot = testRoot.canonicalFile
        check(canonicalTestRoot.path.startsWith(cacheRoot.path + File.separator))
        canonicalTestRoot.deleteRecursively()
    }

    @Test
    fun multiBlockManifestRoundTripAndResumableAssembly() {
        assertEquals(4, manifest.blocks.size)
        assertEquals(manifest, GroupAttachmentManifest.fromJson(manifest.toJson()))

        val remoteStore = GroupAttachmentStore(
            rootDirectory = File(testRoot, "remote-blocks"),
            chunkSize = MIN_CHUNK_SIZE,
        )
        val alreadyReplicated = listOf(manifest.blocks[0], manifest.blocks[2])
        alreadyReplicated.forEach { block ->
            remoteStore.putVerifiedBlock(
                block.ciphertextCid,
                encryptedBlocks.getValue(block.ciphertextCid),
            )
        }
        val expectedMissing = manifest.blocks
            .filterNot { it in alreadyReplicated }
            .map(GroupAttachmentBlock::ciphertextCid)
        assertEquals(expectedMissing, remoteStore.missingBlocks(manifest))

        val fetched = mutableListOf<String>()
        val destination = File(testRoot, "assembled.bin")
        remoteStore.assemble(manifest, destination) { cid ->
            fetched += cid
            encryptedBlocks[cid]
        }

        assertEquals(expectedMissing, fetched)
        assertTrue(remoteStore.missingBlocks(manifest).isEmpty())
        assertArrayEquals(plaintext, destination.readBytes())

        // A second assembly resumes entirely from verified local ciphertext.
        val resumedDestination = File(testRoot, "assembled-again.bin")
        remoteStore.assemble(manifest, resumedDestination) {
            throw AssertionError("resume unexpectedly fetched an already stored block")
        }
        assertArrayEquals(plaintext, resumedDestination.readBytes())
    }

    @Test
    fun missingBlockAbortsWithoutCommittingPartialDestination() {
        val emptyStore = GroupAttachmentStore(
            rootDirectory = File(testRoot, "empty-blocks"),
            chunkSize = MIN_CHUNK_SIZE,
        )
        val destination = File(testRoot, "must-not-exist.bin")

        val error = assertThrows(MissingGroupBlockException::class.java) {
            emptyStore.assemble(manifest, destination) { null }
        }

        assertEquals(manifest.blocks.first().ciphertextCid, error.cid)
        assertFalse(destination.exists())
        assertTrue(
            checkNotNull(destination.parentFile)
                .listFiles()
                .orEmpty()
                .none { it.name.startsWith(".${destination.name}.") && it.name.endsWith(".part") },
        )
    }

    @Test
    fun tamperedFetchedAndLocalCiphertextAreRejectedByCid() {
        val firstBlock = manifest.blocks.first()
        val tampered = encryptedBlocks.getValue(firstBlock.ciphertextCid).copyOf().also {
            it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 0x20).toByte()
        }
        val fetchedStore = GroupAttachmentStore(
            rootDirectory = File(testRoot, "fetched-tamper-blocks"),
            chunkSize = MIN_CHUNK_SIZE,
        )
        val fetchedDestination = File(testRoot, "fetched-tamper.bin")
        assertThrows(IllegalArgumentException::class.java) {
            fetchedStore.assemble(manifest, fetchedDestination) { cid ->
                if (cid == firstBlock.ciphertextCid) tampered else encryptedBlocks[cid]
            }
        }
        assertFalse(fetchedDestination.exists())

        val localRoot = File(testRoot, "local-tamper-blocks")
        val localStore = GroupAttachmentStore(localRoot, MIN_CHUNK_SIZE)
        manifest.blocks.forEach { block ->
            localStore.putVerifiedBlock(
                block.ciphertextCid,
                encryptedBlocks.getValue(block.ciphertextCid),
            )
        }
        File(localRoot, firstBlock.ciphertextCid).writeBytes(tampered)
        val localDestination = File(testRoot, "local-tamper.bin")
        assertThrows(MissingGroupBlockException::class.java) {
            localStore.assemble(manifest, localDestination)
        }
        assertFalse(localDestination.exists())
    }

    @Test
    fun corruptedLocalBlockIsMissingAndCanBeRepaired() {
        val firstBlock = manifest.blocks.first()
        val localRoot = File(testRoot, "repair-corrupt-blocks")
        val localStore = GroupAttachmentStore(localRoot, MIN_CHUNK_SIZE)
        manifest.blocks.forEach { block ->
            localStore.putVerifiedBlock(
                block.ciphertextCid,
                encryptedBlocks.getValue(block.ciphertextCid),
            )
        }
        val corruptFile = File(localRoot, firstBlock.ciphertextCid)
        val originalTimestamp = corruptFile.lastModified()
        val tampered = corruptFile.readBytes().also {
            it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 0x40).toByte()
        }
        corruptFile.writeBytes(tampered)
        assertTrue(corruptFile.setLastModified(originalTimestamp))

        assertEquals(
            listOf(firstBlock.ciphertextCid),
            localStore.missingBlocks(manifest),
        )
        localStore.putVerifiedBlock(
            firstBlock.ciphertextCid,
            encryptedBlocks.getValue(firstBlock.ciphertextCid),
        )
        assertTrue(localStore.missingBlocks(manifest).isEmpty())

        val destination = File(testRoot, "repaired.bin")
        localStore.assemble(manifest, destination)
        assertArrayEquals(plaintext, destination.readBytes())
    }

    @Test
    fun blockAadAndWholePlaintextHashAreVerified() {
        val remoteStore = GroupAttachmentStore(
            rootDirectory = File(testRoot, "aad-hash-blocks"),
            chunkSize = MIN_CHUNK_SIZE,
        )
        manifest.blocks.forEach { block ->
            remoteStore.putVerifiedBlock(
                block.ciphertextCid,
                encryptedBlocks.getValue(block.ciphertextCid),
            )
        }

        val wrongAttachmentId = manifest.copy(attachmentId = "different-attachment-id")
        assertThrows(SecurityException::class.java) {
            remoteStore.assemble(
                wrongAttachmentId,
                File(testRoot, "wrong-aad.bin"),
            )
        }

        val wrongPlaintextHash = manifest.copy(plaintextSha256 = "0".repeat(64))
        val hashDestination = File(testRoot, "wrong-hash.bin")
        assertThrows(IllegalArgumentException::class.java) {
            remoteStore.assemble(wrongPlaintextHash, hashDestination)
        }
        assertFalse(hashDestination.exists())
    }
}
