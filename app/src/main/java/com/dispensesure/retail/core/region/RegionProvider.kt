package com.dispensesure.retail.core.region

import android.content.SharedPreferences
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Read side of the stored region. Login writes it and logout clears it through [PreferenceHelper]. */
@Singleton
class RegionProvider @Inject constructor(
    private val preferenceHelper: PreferenceHelper,
) {
    private val _region = MutableStateFlow(preferenceHelper.getRegion())

    /** Null between logout and the next login. */
    val region: StateFlow<Region?> = _region.asStateFlow()

    /** Region for logged-in work. After logout (PMS requests only): the last logged-in region, else US. */
    val current: Region get() = _region.value ?: preferenceHelper.getLastRegion() ?: Region.US

    // Held as a field: SharedPreferences only keeps a weak reference to listeners.
    private val regionPrefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
        if (changedKey == preferenceHelper.regionKey) _region.value = preferenceHelper.getRegion()
    }

    init {
        preferenceHelper.registerOnChangeListener(regionPrefListener)
    }
}
