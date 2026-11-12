package com.dispensesure.retail.core.utils.common

import com.dispensesure.retail.core.utils.logger.AppLogger
import java.io.File

/** Best-effort file removal; a missing file is already the desired state. */
fun deleteFilesQuietly(paths: List<String>, logger: AppLogger) {
    paths.forEach { path ->
        runCatching { File(path).takeIf { file -> file.exists() }?.delete() }
            .onFailure { error -> logger.w("Could not delete $path: ${error.message}") }
    }
}
