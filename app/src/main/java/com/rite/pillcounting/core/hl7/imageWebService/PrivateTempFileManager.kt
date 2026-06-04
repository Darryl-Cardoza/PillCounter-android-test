package com.rite.pillcounting.core.hl7.imageWebService

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileOutputStream

class PrivateTempFileManager(
    private val context: Context
) : NanoHTTPD.TempFileManager {

    private val tempFiles = mutableListOf<NanoHTTPD.TempFile>()

    override fun createTempFile(filename_hint: String?): NanoHTTPD.TempFile {
        val file = File.createTempFile(
            "nano_",
            null,
            context.cacheDir  // Private to app — not world-readable
        ).also {
            it.deleteOnExit()  // JVM cleanup on normal exit
        }

        return object : NanoHTTPD.TempFile {
            override fun getName() = file.absolutePath
            override fun open() = FileOutputStream(file)
            override fun delete() {
                try { file.delete() } catch (_: Exception) {}
            }
        }.also { tempFiles.add(it) }
    }

    override fun clear() {
        tempFiles.forEach {
            try { it.delete() } catch (_: Exception) {}
        }
        tempFiles.clear()
    }
}