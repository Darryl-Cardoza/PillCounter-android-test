package com.rite.pillcounting.core.utils.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field

/**
 * Unit tests for [NetworkUtils.isNetworkAvailable].
 *
 * Context / ConnectivityManager / NetworkCapabilities are mocked with mockk.
 * Build.VERSION.SDK_INT is overridden via Unsafe (same approach as Hl7NotifierTest) to
 * exercise both the modern (>= M) capabilities branch and the legacy activeNetworkInfo branch.
 *
 * NOTE: getIpAddressForInterface() relies on java.net.NetworkInterface static methods that
 * cannot be deterministically controlled on the CI JVM, so it is documented as skipped.
 */
class NetworkUtilsTest {

    private val context: Context = mockk()
    private val cm: ConnectivityManager = mockk()
    private var originalSdkInt: Int = 0

    @Before
    fun setup() {
        originalSdkInt = Build.VERSION.SDK_INT
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
    }

    @After
    fun tearDown() {
        setSdkInt(originalSdkInt)
        unmockkAll()
    }

    // ───────────── modern (SDK >= M) capabilities branch ─────────────

    @Test
    fun isNetworkAvailable_wifiTransport_returnsTrue() {
        setSdkInt(Build.VERSION_CODES.Q)
        val network: Network = mockk()
        val caps: NetworkCapabilities = mockk()
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns caps
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true

        assertTrue(NetworkUtils.isNetworkAvailable(context))
    }

    @Test
    fun isNetworkAvailable_cellularTransport_returnsTrue() {
        setSdkInt(Build.VERSION_CODES.Q)
        val network: Network = mockk()
        val caps: NetworkCapabilities = mockk()
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns caps
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns true

        assertTrue(NetworkUtils.isNetworkAvailable(context))
    }

    @Test
    fun isNetworkAvailable_ethernetTransport_returnsTrue() {
        setSdkInt(Build.VERSION_CODES.Q)
        val network: Network = mockk()
        val caps: NetworkCapabilities = mockk()
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns caps
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns false
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns true

        assertTrue(NetworkUtils.isNetworkAvailable(context))
    }

    @Test
    fun isNetworkAvailable_noTransport_returnsFalse() {
        setSdkInt(Build.VERSION_CODES.Q)
        val network: Network = mockk()
        val caps: NetworkCapabilities = mockk()
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns caps
        every { caps.hasTransport(any()) } returns false

        assertFalse(NetworkUtils.isNetworkAvailable(context))
    }

    @Test
    fun isNetworkAvailable_noActiveNetwork_returnsFalse() {
        setSdkInt(Build.VERSION_CODES.Q)
        every { cm.activeNetwork } returns null

        assertFalse(NetworkUtils.isNetworkAvailable(context))
    }

    @Test
    fun isNetworkAvailable_nullCapabilities_returnsFalse() {
        setSdkInt(Build.VERSION_CODES.Q)
        val network: Network = mockk()
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns null

        assertFalse(NetworkUtils.isNetworkAvailable(context))
    }

    // ───────────── legacy (SDK < M) branch ─────────────

    @Test
    fun isNetworkAvailable_legacyConnected_returnsTrue() {
        setSdkInt(Build.VERSION_CODES.LOLLIPOP) // 22, < M
        val info: NetworkInfo = mockk()
        every { cm.activeNetworkInfo } returns info
        every { info.isConnected } returns true

        assertTrue(NetworkUtils.isNetworkAvailable(context))
    }

    @Test
    fun isNetworkAvailable_legacyNotConnected_returnsFalse() {
        setSdkInt(Build.VERSION_CODES.LOLLIPOP)
        val info: NetworkInfo = mockk()
        every { cm.activeNetworkInfo } returns info
        every { info.isConnected } returns false

        assertFalse(NetworkUtils.isNetworkAvailable(context))
    }

    @Test
    fun isNetworkAvailable_legacyNullInfo_returnsFalse() {
        setSdkInt(Build.VERSION_CODES.LOLLIPOP)
        every { cm.activeNetworkInfo } returns null

        assertFalse(NetworkUtils.isNetworkAvailable(context))
    }

    // ───────────── exception path ─────────────

    @Test
    fun isNetworkAvailable_getSystemServiceThrows_returnsFalse() {
        setSdkInt(Build.VERSION_CODES.Q)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } throws RuntimeException("boom")

        assertFalse(NetworkUtils.isNetworkAvailable(context))
    }

    // ───────────── SDK_INT override helper (Unsafe) ─────────────

    private fun setSdkInt(value: Int) {
        val unsafeField: Field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val unsafeClass = unsafe.javaClass
        val sdkField: Field = Build.VERSION::class.java.getField("SDK_INT")
        val base = unsafeClass.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, sdkField)
        val offset = unsafeClass.getMethod("staticFieldOffset", Field::class.java).invoke(unsafe, sdkField) as Long
        unsafeClass
            .getMethod("putInt", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(unsafe, base, offset, value)
    }
}
