package com.dispensesure.retail.feature.hl7.data

import android.content.Context
import com.dispensesure.retail.R
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.hl7.util.HL7Config
import com.dispensesure.retail.feature.hl7.util.Hl7Format
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The outbound MSH identity (sender, receiver, version, format) both HL7 repositories send with. */
class Hl7ConfigProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferenceHelper: PreferenceHelper,
) {

    /**
     * Builds outbound HL7 config from the persisted terminal/PMS host/HL7 version
     * preferences, so RDS/INR messages are versioned per the value fetched from
     * auth/me (settings.hl7Version) rather than the hardcoded default.
     */
    fun current(): HL7Config {
        val hl7Format = preferenceHelper.getHl7Format()
        // Eyecon's PMS-side routing keys off MSH-3 == "EYECON" (its own format
        // name) rather than this app's display name — DispenseSure/Vivid keep
        // using the app's display name (context.getString(R.string.app_name)),
        // unchanged, since PMS already matches on that for those formats.
        val sendingApplicationName = if (hl7Format == Hl7Format.EYECON) {
            hl7Format.sendingApplication
        } else {
            context.getString(R.string.app_name)
        }
        return HL7Config.current(
            selectedTerminalName = preferenceHelper.getSelectedTerminalName() ?: "PILLCOUNTER",
            pmsHostName = preferenceHelper.getHl7PmsHost().ifBlank { "PMS" },
            hl7Version = preferenceHelper.getHl7Version(),
            hl7Format = hl7Format,
            sendingApplicationName = sendingApplicationName
        )
    }
}
