package com.rite.pillcounting.core.utils.common

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.os.Build
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

class LocationProvider(private val context: Context) {

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocationAsString(): String {
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val cts = CancellationTokenSource()

            // Request a fresh fix (waits up to 5 s, falls back to last known)
            val location: Location? = suspendCancellableCoroutine { cont ->
                val request = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    .setMaxUpdateAgeMillis(30_000L)   // accept a fix up to 30 s old
                    .setDurationMillis(5_000L)        // give up after 5 s
                    .build()

                client.getCurrentLocation(request, cts.token)
                    .addOnSuccessListener { loc -> cont.resume(loc) }
                    .addOnFailureListener { cont.resume(null) }
                    .addOnCanceledListener { cont.resume(null) }

                cont.invokeOnCancellation { cts.cancel() }
            }

            if (location != null) {
                reverseGeocode(location)
            } else {
                // Last-known as final fallback
                val last: Location? = suspendCancellableCoroutine { cont ->
                    client.lastLocation
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(null) }
                }
                if (last != null) reverseGeocode(last) else "Location unavailable"
            }
        } catch (_: Exception) {
            "Location unavailable"
        }
    }

    private suspend fun reverseGeocode(location: Location): String {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(location.latitude, location.longitude, 1) { result ->
                        cont.resume(result)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
            }

            val address = addresses?.firstOrNull()
            if (address != null) {
                listOfNotNull(
                    address.locality,
                    address.postalCode,
                    address.countryName
                ).joinToString(", ")
                    .ifBlank { "%.5f, %.5f".format(location.latitude, location.longitude) }
            } else {
                "%.5f, %.5f".format(location.latitude, location.longitude)
            }
        } catch (_: Exception) {
            "%.5f, %.5f".format(location.latitude, location.longitude)
        }
    }
}
