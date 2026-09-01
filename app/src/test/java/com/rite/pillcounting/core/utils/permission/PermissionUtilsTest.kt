package com.rite.pillcounting.core.utils.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the non-Composable helpers in [PermissionUtils]:
 * [markPermissionRequested], [isPermanentlyDenied], and [openAppSettings].
 *
 * Runs under Robolectric because these functions rely on
 * `Context.getSharedPreferences` and `Context.startActivity`, which the plain-JVM
 * Android stub jar cannot fully back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PermissionUtilsTest {

    private val permission = Manifest.permission.CAMERA

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { context.getSharedPreferences("permission_prefs", Context.MODE_PRIVATE) } returns sharedPreferences
        every { sharedPreferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor

        mockkStatic(ContextCompat::class)
        mockkStatic(ActivityCompat::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // -------------------- markPermissionRequested --------------------

    @Test
    fun `markPermissionRequested persists true for the given permission`() {
        markPermissionRequested(context, permission)

        verify { editor.putBoolean(permission, true) }
        verify { editor.apply() }
    }

    // -------------------- isPermanentlyDenied --------------------

    @Test
    fun `isPermanentlyDenied returns false when context is not an Activity`() {
        // context here is a plain mockk(Context::class), not an Activity, so the
        // `as? Activity` cast in isPermanentlyDenied fails and short-circuits to false.
        assertFalse(isPermanentlyDenied(context, permission))
    }

    @Test
    fun `isPermanentlyDenied returns false when permission is already granted`() {
        val activity: Activity = mockk(relaxed = true)
        every { ContextCompat.checkSelfPermission(activity, permission) } returns
            PackageManager.PERMISSION_GRANTED

        assertFalse(isPermanentlyDenied(activity, permission))
    }

    @Test
    fun `isPermanentlyDenied returns false when rationale can still be shown`() {
        val activity: Activity = mockk(relaxed = true)
        every { ContextCompat.checkSelfPermission(activity, permission) } returns
            PackageManager.PERMISSION_DENIED
        every { ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) } returns true

        assertFalse(isPermanentlyDenied(activity, permission))
    }

    @Test
    fun `isPermanentlyDenied returns false when denied but never requested before`() {
        val activity: Activity = mockk(relaxed = true)
        every { activity.getSharedPreferences("permission_prefs", Context.MODE_PRIVATE) } returns sharedPreferences
        every { ContextCompat.checkSelfPermission(activity, permission) } returns
            PackageManager.PERMISSION_DENIED
        every { ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) } returns false
        every { sharedPreferences.getBoolean(permission, false) } returns false

        assertFalse(isPermanentlyDenied(activity, permission))
    }

    @Test
    fun `isPermanentlyDenied returns true when denied, rationale unavailable, and previously requested`() {
        val activity: Activity = mockk(relaxed = true)
        every { activity.getSharedPreferences("permission_prefs", Context.MODE_PRIVATE) } returns sharedPreferences
        every { ContextCompat.checkSelfPermission(activity, permission) } returns
            PackageManager.PERMISSION_DENIED
        every { ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) } returns false
        every { sharedPreferences.getBoolean(permission, false) } returns true

        assertTrue(isPermanentlyDenied(activity, permission))
    }

    // -------------------- isLocationServicesEnabled --------------------

    @Test
    fun `isLocationServicesEnabled returns true when the device location toggle is on`() {
        val locationManager: LocationManager = mockk(relaxed = true)
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
        every { locationManager.isLocationEnabled } returns true

        assertTrue(isLocationServicesEnabled(context))
    }

    @Test
    fun `isLocationServicesEnabled returns false when the device location toggle is off`() {
        val locationManager: LocationManager = mockk(relaxed = true)
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
        every { locationManager.isLocationEnabled } returns false

        assertFalse(isLocationServicesEnabled(context))
    }

    @Test
    fun `isLocationServicesEnabled returns false when no LocationManager is available`() {
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns null

        assertFalse(isLocationServicesEnabled(context))
    }

    // -------------------- openAppSettings --------------------

    @Test
    fun `openAppSettings starts an intent pointed at this app's details screen`() {
        every { context.packageName } returns "com.rite.pillcounting"
        val intentSlot = slot<android.content.Intent>()
        every { context.startActivity(capture(intentSlot)) } returns Unit

        openAppSettings(context)

        verify { context.startActivity(any()) }
        val intent = intentSlot.captured
        assertTrue(intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertTrue(intent.data == Uri.fromParts("package", "com.rite.pillcounting", null))
    }

    // -------------------- openLocationSettings --------------------

    @Test
    fun `openLocationSettings starts an intent pointed at the device location screen`() {
        val intentSlot = slot<android.content.Intent>()
        every { context.startActivity(capture(intentSlot)) } returns Unit

        openLocationSettings(context)

        verify { context.startActivity(any()) }
        assertTrue(intentSlot.captured.action == Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    }
}
