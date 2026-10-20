package com.dispensesure.retail.core.region

import androidx.compose.runtime.staticCompositionLocalOf

// The logged-in user's region for composables; provided once in MainActivity.
val LocalRegion = staticCompositionLocalOf { Region.US }
