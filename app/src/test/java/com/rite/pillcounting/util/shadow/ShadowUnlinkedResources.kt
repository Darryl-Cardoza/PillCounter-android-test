package com.rite.pillcounting.util.shadow

import android.content.res.Resources
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

/**
 * Works around a Robolectric/AGP limitation for unit tests that do not enable
 * `testOptions.unitTests.isIncludeAndroidResources` (that flag is currently unusable project-wide
 * because it forces Robolectric to bootstrap the real [android.app.Application] from the merged
 * manifest, which registers a receiver from the debug-only Chucker library; that library's jar
 * is compiled to Java 21 class files (version 65.0) and the Gradle unit-test JVM used by this
 * project only supports up to Java 17 (version 61.0), so any test that pulls in the real
 * Application crashes with `UnsupportedClassVersionError` regardless of what it actually
 * exercises).
 *
 * Without `isIncludeAndroidResources`, Robolectric only has framework (`android:`) resources
 * loaded, so any `context.getString(R.string.xxx)` call for an app-module resource ID throws
 * `Resources$NotFoundException` ("No package ID 7f found for ID 0x7f...."). Production code such
 * as [com.rite.pillcounting.core.utils.common.PDFHelperExporter.generateDrugHistoryPdf] calls
 * `context.getString(...)` while drawing PDF content and catches any `Exception`, so this
 * manifests in tests as the method silently returning null instead of throwing.
 *
 * This shadow intercepts only the two-argument-free lookups Robolectric fails on (unresolvable
 * app resource IDs) and returns a stable placeholder instead of throwing, so production code
 * under test can proceed past `getString`/`getText` calls. Real framework resource IDs are
 * delegated to the original implementation so unrelated Robolectric behavior is unaffected.
 */
@Implements(Resources::class)
class ShadowUnlinkedResources {

    @RealObject
    private lateinit var realResources: Resources

    @Implementation
    fun getText(id: Int): CharSequence {
        return try {
            org.robolectric.shadow.api.Shadow.directlyOn(
                realResources,
                Resources::class.java
            ).getText(id)
        } catch (e: Resources.NotFoundException) {
            "resource_0x${Integer.toHexString(id)}"
        }
    }

    @Implementation
    fun getText(id: Int, def: CharSequence): CharSequence {
        return try {
            org.robolectric.shadow.api.Shadow.directlyOn(
                realResources,
                Resources::class.java
            ).getText(id, def)
        } catch (e: Resources.NotFoundException) {
            def
        }
    }

    @Implementation
    fun getString(id: Int): String {
        return getText(id).toString()
    }

    @Implementation
    fun getString(id: Int, vararg formatArgs: Any): String {
        return try {
            org.robolectric.shadow.api.Shadow.directlyOn(
                realResources,
                Resources::class.java
            ).getString(id, *formatArgs)
        } catch (e: Resources.NotFoundException) {
            String.format(getText(id).toString(), *formatArgs)
        }
    }
}
