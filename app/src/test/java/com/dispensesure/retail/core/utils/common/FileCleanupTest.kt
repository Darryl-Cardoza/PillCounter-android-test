package com.dispensesure.retail.core.utils.common

import com.dispensesure.retail.core.utils.logger.AppLogger
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Unit tests for [deleteFilesQuietly]. */
class FileCleanupTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val logger = AppLogger("FileCleanupTest")

    @Test
    fun `deletes every file it is given`() {
        val firstPhoto = temporaryFolder.newFile("first.jpg")
        val secondPhoto = temporaryFolder.newFile("second.jpg")

        deleteFilesQuietly(listOf(firstPhoto.absolutePath, secondPhoto.absolutePath), logger)

        assertFalse(firstPhoto.exists())
        assertFalse(secondPhoto.exists())
    }

    @Test
    fun `a missing file is skipped and the rest are still deleted`() {
        val existingPhoto = temporaryFolder.newFile("existing.jpg")
        val missingPhotoPath = "${temporaryFolder.root.absolutePath}/missing.jpg"

        deleteFilesQuietly(listOf(missingPhotoPath, existingPhoto.absolutePath), logger)

        assertFalse(existingPhoto.exists())
    }
}
