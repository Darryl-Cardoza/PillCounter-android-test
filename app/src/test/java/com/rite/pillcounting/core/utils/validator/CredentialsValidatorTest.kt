package com.rite.pillcounting.core.utils.validator

import android.util.Patterns
import com.rite.pillcounting.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.util.regex.Pattern

/**
 * Unit tests for [CredentialsValidator].
 *
 * `Patterns.EMAIL_ADDRESS` / `Patterns.PHONE` are `public static final Pattern` fields that are
 * `null` in the android.jar unit-test stub (returnDefaultValues only affects method calls, not
 * field reads). They are installed here with real [Pattern] instances via sun.misc.Unsafe
 * (accessed reflectively — the `--add-opens` for java.base is configured for unit tests in
 * app/build.gradle.kts), so the Patterns-based branches of validateEmail/validatePhone run for
 * real on the JVM.
 */
class CredentialsValidatorTest {

    private val validator = CredentialsValidator()

    @Before
    fun setup() {
        setStaticFinalField(
            Patterns::class.java.getField("EMAIL_ADDRESS"),
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"),
        )
        setStaticFinalField(
            Patterns::class.java.getField("PHONE"),
            Pattern.compile("[0-9+\\-() .]+"),
        )
    }

    // ─────────────────────────── validateEmail ───────────────────────────

    @Test
    fun `validateEmail blank is optional success`() {
        val r = validator.validateEmail("   ")
        assertTrue(r.isSuccess)
        assertNull(r.errorMessageResId)
    }

    @Test
    fun `validateEmail valid format success`() {
        assertTrue(validator.validateEmail("john.doe@example.com").isSuccess)
    }

    @Test
    fun `validateEmail malformed failure`() {
        val r = validator.validateEmail("not-an-email")
        assertFalse(r.isSuccess)
        assertEquals(R.string.error_email_invalid, r.errorMessageResId)
    }

    // ─────────────────────────── validatePhone ───────────────────────────

    @Test
    fun `validatePhone blank is optional success`() {
        assertTrue(validator.validatePhone("").isSuccess)
    }

    @Test
    fun `validatePhone valid success`() {
        assertTrue(validator.validatePhone("1234567").isSuccess)
    }

    @Test
    fun `validatePhone too short failure`() {
        // matches the PHONE pattern but length < 7
        val r = validator.validatePhone("123456")
        assertFalse(r.isSuccess)
        assertEquals(R.string.error_phone_invalid, r.errorMessageResId)
    }

    @Test
    fun `validatePhone invalid characters failure`() {
        // letters fail the PHONE pattern
        val r = validator.validatePhone("12ab567")
        assertFalse(r.isSuccess)
        assertEquals(R.string.error_phone_invalid, r.errorMessageResId)
    }

    // ─────────────────────────── validateRequiredName ───────────────────────────

    @Test
    fun `validateRequiredName blank is required failure`() {
        val r = validator.validateRequiredName("  ")
        assertFalse(r.isSuccess)
        assertEquals(R.string.error_name_required, r.errorMessageResId)
    }

    @Test
    fun `validateRequiredName with digit failure`() {
        val r = validator.validateRequiredName("John2")
        assertFalse(r.isSuccess)
        assertEquals(R.string.error_name_invalid, r.errorMessageResId)
    }

    @Test
    fun `validateRequiredName valid success`() {
        assertTrue(validator.validateRequiredName("Mary-Jane O'Neil").isSuccess)
    }

    // ─────────────────────────── validatePharmacyName ───────────────────────────

    @Test
    fun `validatePharmacyName blank is optional success`() {
        assertTrue(validator.validatePharmacyName("").isSuccess)
    }

    @Test
    fun `validatePharmacyName single char failure`() {
        val r = validator.validatePharmacyName("A")
        assertFalse(r.isSuccess)
        assertEquals(R.string.error_pharmacy_name_invalid, r.errorMessageResId)
    }

    @Test
    fun `validatePharmacyName two chars boundary success`() {
        assertTrue(validator.validatePharmacyName("CV").isSuccess)
    }

    // ─────────────────────────── validateNpi ───────────────────────────

    @Test
    fun `validateNpi blank is optional success`() {
        assertTrue(validator.validateNpi("").isSuccess)
    }

    @Test
    fun `validateNpi with letters failure`() {
        val r = validator.validateNpi("12a456")
        assertFalse(r.isSuccess)
        assertEquals(R.string.error_npi_invalid, r.errorMessageResId)
    }

    @Test
    fun `validateNpi too short failure`() {
        assertFalse(validator.validateNpi("12345").isSuccess) // 5 digits
    }

    @Test
    fun `validateNpi too long failure`() {
        assertFalse(validator.validateNpi("1234567890123").isSuccess) // 13 digits
    }

    @Test
    fun `validateNpi valid boundaries success`() {
        assertTrue(validator.validateNpi("123456").isSuccess) // 6
        assertTrue(validator.validateNpi("123456789012").isSuccess) // 12
    }

    // ─────────────────────────── validatePassword ───────────────────────────

    @Test
    fun `validatePassword too short failure`() {
        val r = validator.validatePassword("Aa1!")
        assertFalse(r.isSuccess)
        assertEquals(R.string.error_password_too_short, r.errorMessageResId)
    }

    @Test
    fun `validatePassword no uppercase failure`() {
        assertEquals(
            R.string.error_password_no_uppercase,
            validator.validatePassword("abcdefg1!").errorMessageResId,
        )
    }

    @Test
    fun `validatePassword no lowercase failure`() {
        assertEquals(
            R.string.error_password_no_lowercase,
            validator.validatePassword("ABCDEFG1!").errorMessageResId,
        )
    }

    @Test
    fun `validatePassword no digit failure`() {
        assertEquals(
            R.string.error_password_no_digit,
            validator.validatePassword("Abcdefg!").errorMessageResId,
        )
    }

    @Test
    fun `validatePassword no special failure`() {
        assertEquals(
            R.string.error_password_no_special,
            validator.validatePassword("Abcdefg1").errorMessageResId,
        )
    }

    @Test
    fun `validatePassword valid success`() {
        assertTrue(validator.validatePassword("Abcdefg1!").isSuccess)
    }

    // ─────────────────────────── helpers ───────────────────────────

    /**
     * Sets a `public static final` reference field via sun.misc.Unsafe (reached reflectively so
     * the `sun.misc` package needn't be on the compile classpath). Needs the java.base opens that
     * app/build.gradle.kts configures for unit tests.
     */
    private fun setStaticFinalField(field: Field, value: Any) {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val unsafeClass = unsafe.javaClass
        val base = unsafeClass.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, field)
        val offset = unsafeClass.getMethod("staticFieldOffset", Field::class.java)
            .invoke(unsafe, field) as Long
        unsafeClass
            .getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            .invoke(unsafe, base, offset, value)
    }
}
