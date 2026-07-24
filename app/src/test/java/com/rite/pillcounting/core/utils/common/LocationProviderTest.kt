package com.rite.pillcounting.core.utils.common

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.OnCanceledListener
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [LocationProvider].
 *
 * Runs under Robolectric because the class constructs real [Location]/[Geocoder] objects and
 * touches [Build.VERSION.SDK_INT]. The GMS [FusedLocationProviderClient] is mocked and its
 * [Task]-returning methods are stubbed to return a fake [Task] whose listener-registration
 * methods synchronously invoke the captured listener, so the suspendCancellableCoroutine
 * callback chain in the production code resolves deterministically without a real Task/Looper.
 * [Geocoder] construction is intercepted with [mockkConstructor] to avoid depending on a real
 * geocoding backend.
 */
@RunWith(RobolectricTestRunner::class)
class LocationProviderTest {

    private val context: Context = mockk(relaxed = true)
    private lateinit var client: FusedLocationProviderClient
    private lateinit var provider: LocationProvider

    private fun <T> fakeTask(
        successValue: T? = null,
        failWith: Exception? = null,
        cancelled: Boolean = false
    ): Task<T> {
        val task: Task<T> = mockk(relaxed = true)
        every { task.addOnSuccessListener(any()) } answers {
            if (failWith == null && !cancelled) {
                @Suppress("UNCHECKED_CAST")
                (firstArg() as OnSuccessListener<T>).onSuccess(successValue)
            }
            task
        }
        every { task.addOnFailureListener(any()) } answers {
            if (failWith != null) {
                (firstArg() as OnFailureListener).onFailure(failWith)
            }
            task
        }
        every { task.addOnCanceledListener(any()) } answers {
            if (cancelled) {
                (firstArg() as OnCanceledListener).onCanceled()
            }
            task
        }
        return task
    }

    private fun mockLocation(lat: Double, lng: Double): Location {
        val location = Location("fused")
        location.latitude = lat
        location.longitude = lng
        return location
    }

    @Before
    fun setup() {
        mockkStatic(LocationServices::class)
        client = mockk(relaxed = true)
        every { LocationServices.getFusedLocationProviderClient(context) } returns client
        provider = LocationProvider(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `getCurrentLocationAsString returns geocoded address when fresh location and address available`() = runTest {
        val location = mockLocation(12.34, 56.78)
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any()) } returns fakeTask<Location>(location)

        val address = mockk<Address>(relaxed = true)
        every { address.locality } returns "Springfield"
        every { address.postalCode } returns "12345"
        every { address.countryName } returns "USA"

        mockkConstructor(Geocoder::class)
        every {
            anyConstructed<Geocoder>().getFromLocation(12.34, 56.78, 1)
        } returns listOf(address)

        val result = provider.getCurrentLocationAsString()

        assertEquals("Springfield, 12345, USA", result)
    }

    @Test
    fun `getCurrentLocationAsString falls back to lastLocation when fresh location is null`() = runTest {
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any()) } returns fakeTask<Location>(null)
        val last = mockLocation(1.0, 2.0)
        every { client.lastLocation } returns fakeTask<Location>(last)

        mockkConstructor(Geocoder::class)
        every { anyConstructed<Geocoder>().getFromLocation(1.0, 2.0, 1) } returns null

        val result = provider.getCurrentLocationAsString()

        assertEquals(String.format("%.5f, %.5f", 1.0, 2.0), result)
    }

    @Test
    fun `getCurrentLocationAsString returns Location unavailable when both fresh and last location are null`() = runTest {
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any()) } returns fakeTask<Location>(null as Location?)
        every { client.lastLocation } returns fakeTask<Location>(null as Location?)

        val result = provider.getCurrentLocationAsString()

        assertEquals("Location unavailable", result)
    }

    @Test
    fun `getCurrentLocationAsString falls back to lastLocation when fresh location fails`() = runTest {
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any()) } returns fakeTask<Location>(failWith = RuntimeException("boom"))
        val last = mockLocation(3.0, 4.0)
        every { client.lastLocation } returns fakeTask<Location>(last)

        mockkConstructor(Geocoder::class)
        every { anyConstructed<Geocoder>().getFromLocation(3.0, 4.0, 1) } returns emptyList()

        val result = provider.getCurrentLocationAsString()

        assertEquals(String.format("%.5f, %.5f", 3.0, 4.0), result)
    }

    @Test
    fun `getCurrentLocationAsString falls back to lastLocation when fresh location is cancelled`() = runTest {
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any()) } returns fakeTask<Location>(cancelled = true)
        val last = mockLocation(5.0, 6.0)
        every { client.lastLocation } returns fakeTask<Location>(last)

        mockkConstructor(Geocoder::class)
        every { anyConstructed<Geocoder>().getFromLocation(5.0, 6.0, 1) } returns emptyList()

        val result = provider.getCurrentLocationAsString()

        assertEquals(String.format("%.5f, %.5f", 5.0, 6.0), result)
    }

    @Test
    fun `getCurrentLocationAsString returns Location unavailable when getFusedLocationProviderClient throws`() = runTest {
        every { LocationServices.getFusedLocationProviderClient(context) } throws RuntimeException("no client")

        val result = provider.getCurrentLocationAsString()

        assertEquals("Location unavailable", result)
    }

    @Test
    fun `reverseGeocode joins only non-null address parts and skips missing ones`() = runTest {
        val location = mockLocation(10.0, 20.0)
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any()) } returns fakeTask<Location>(location)

        val address = mockk<Address>(relaxed = true)
        every { address.locality } returns null
        every { address.postalCode } returns "99999"
        every { address.countryName } returns null

        mockkConstructor(Geocoder::class)
        every { anyConstructed<Geocoder>().getFromLocation(10.0, 20.0, 1) } returns listOf(address)

        val result = provider.getCurrentLocationAsString()

        assertEquals("99999", result)
    }

    @Test
    fun `reverseGeocode falls back to coordinates when address fields are all blank`() = runTest {
        val location = mockLocation(11.11, 22.22)
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any()) } returns fakeTask<Location>(location)

        val address = mockk<Address>(relaxed = true)
        every { address.locality } returns null
        every { address.postalCode } returns null
        every { address.countryName } returns null

        mockkConstructor(Geocoder::class)
        every { anyConstructed<Geocoder>().getFromLocation(11.11, 22.22, 1) } returns listOf(address)

        val result = provider.getCurrentLocationAsString()

        assertEquals(String.format("%.5f, %.5f", 11.11, 22.22), result)
    }

    @Test
    fun `reverseGeocode falls back to coordinates when no address returned`() = runTest {
        val location = mockLocation(7.5, 8.5)
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any()) } returns fakeTask<Location>(location)

        mockkConstructor(Geocoder::class)
        every { anyConstructed<Geocoder>().getFromLocation(7.5, 8.5, 1) } returns emptyList()

        val result = provider.getCurrentLocationAsString()

        assertEquals(String.format("%.5f, %.5f", 7.5, 8.5), result)
    }

    @Test
    fun `reverseGeocode falls back to coordinates when Geocoder throws`() = runTest {
        val location = mockLocation(9.9, 8.8)
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any()) } returns fakeTask<Location>(location)

        mockkConstructor(Geocoder::class)
        every { anyConstructed<Geocoder>().getFromLocation(9.9, 8.8, 1) } throws RuntimeException("geocoder failure")

        val result = provider.getCurrentLocationAsString()

        assertEquals(String.format("%.5f, %.5f", 9.9, 8.8), result)
    }

    @Test
    fun `getCurrentLocationAsString returns geocoded address using first result when multiple addresses returned`() = runTest {
        val location = mockLocation(15.0, 25.0)
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any()) } returns fakeTask<Location>(location)

        val first = mockk<Address>(relaxed = true)
        every { first.locality } returns "CityA"
        every { first.postalCode } returns null
        every { first.countryName } returns "CountryA"

        val second = mockk<Address>(relaxed = true)
        every { second.locality } returns "CityB"

        mockkConstructor(Geocoder::class)
        every { anyConstructed<Geocoder>().getFromLocation(15.0, 25.0, 1) } returns listOf(first, second)

        val result = provider.getCurrentLocationAsString()

        assertEquals("CityA, CountryA", result)
    }
}
