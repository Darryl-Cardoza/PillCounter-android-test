package com.rite.pillcounting.core.scanning.logic

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Debug-only sink for the exact 640×640 image handed to the pill model.
 *
 * Disabled unless [directory] is set; PillCountingApplication sets it to
 * `files/model_input` in debug builds. Every [EVERY_N_FRAMES]th input is written
 * as a JPEG named `<frame>_<label>.jpg`, where the label says whether the input
 * was the tray crop (and its size in frame pixels) or the full frame. Only the
 * newest [KEEP_FILES] files are kept.
 *
 * Pull them off a device with:
 *   adb exec-out run-as com.rite.pillcounting tar c files/model_input > model_input.tar
 */
object ModelInputDump {

    private const val TAG = "ModelInputDump"
    private const val EVERY_N_FRAMES = 10
    private const val KEEP_FILES = 30

    @Volatile
    var directory: File? = null

    private var frame = 0

    fun maybeSave(bitmap: Bitmap, label: String) {
        val dir = directory ?: return
        val index = frame++
        if (index % EVERY_N_FRAMES != 0) return
        try {
            dir.mkdirs()
            val file = File(dir, "%06d_%s.jpg".format(index, label))
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            dir.listFiles()
                ?.sortedBy { it.name }
                ?.dropLast(KEEP_FILES)
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "model input dump failed: ${e.message}")
        }
    }
}
