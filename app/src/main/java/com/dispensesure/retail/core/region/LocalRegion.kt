package com.dispensesure.retail.core.region

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.dispensesure.retail.R

// The logged-in user's region for composables; provided once in MainActivity.
val LocalRegion = staticCompositionLocalOf { Region.US }

/** The bucket to show: the given one or the default, blank where the region hides buckets. */
@Composable
fun bucketLabelForLoggedInRegion(bucketId: String?): String {
    if (!LocalRegion.current.showsBucket) return ""
    return bucketId?.takeIf(String::isNotBlank) ?: stringResource(R.string.default_bucket)
}
