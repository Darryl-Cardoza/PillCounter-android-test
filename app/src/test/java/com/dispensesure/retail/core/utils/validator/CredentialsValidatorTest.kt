package com.dispensesure.retail.core.utils.validator

import android.util.Patterns
import com.dispensesure.retail.R
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
    fun `validateRequiredName punctuation only is required failure`() {
        val r = validator.validateRequiredName("-")
        assertFalse(r.isSuccess)
        assertEquals(R.string.error_name_required, r.errorMessageResId)
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

    @Test
    fun `validatePharmacyName trailing space does not count towards minimum`() {
        val r = validator.validatePharmacyName("A ")
        assertFalse(r.isSuccess)
        assertEquals(R.string.error_pharmacy_name_invalid, r.errorMessageResId)
    }

    @Test
    fun `validatePharmacyName punctuation only failure`() {
        val r = validator.validatePharmacyName("--")
        assertFalse(r.isSuccess)
        assertEquals(R.string.error_pharmacy_name_invalid, r.errorMessageResId)
    }

    @Test
    fun `validatePharmacyName whitespace only is optional success`() {
        assertTrue(validator.validatePharmacyName("  ").isSuccess)
    }

    // ─────────────────────────── sanitizeName ───────────────────────────

    @Test
    fun `sanitizeName strips digits`() {
        assertEquals("John", CredentialsValidator.sanitizeName("Jo3h1n"))
    }

    @Test
    fun `sanitizeName strips special characters`() {
        assertEquals("Pharmacy", CredentialsValidator.sanitizeName("Ph@arm#acy!*"))
    }

    @Test
    fun `sanitizeName keeps apostrophe and hyphen`() {
        assertEquals("Mary-Jane O'Neil", CredentialsValidator.sanitizeName("Mary-Jane O'Neil"))
    }

    @Test
    fun `sanitizeName keeps non-ascii letters`() {
        assertEquals("Zoë Müller", CredentialsValidator.sanitizeName("Zoë Müller"))
    }

    @Test
    fun `sanitizeName collapses runs of spaces`() {
        assertEquals("Smith Sons Drugs", CredentialsValidator.sanitizeName("Smith & Sons  Drugs"))
    }

    @Test
    fun `sanitizeName keeps a single trailing space`() {
        // A trailing space is mid-word typing, not junk — it must survive.
        assertEquals("John ", CredentialsValidator.sanitizeName("John "))
    }

    @Test
    fun `sanitizeName drops a leading space`() {
        assertEquals("Family Pharmacy", CredentialsValidator.sanitizeName("24/7 Family Pharmacy"))
    }

    @Test
    fun `sanitizeName blank stays empty`() {
        assertEquals("", CredentialsValidator.sanitizeName("123 #$%"))
    }

    // ─────────────────────────── validateNpi ───────────────────────────

    @Test
    fun `validateNpi blank is optional success`() {
        assertTrue(validator.validateNpi("", required = false).isSuccess)
    }

    @Test
    fun `validateNpi with letters failure`() {
        val r = validator.validateNpi("12a456", required = true)
        assertFalse(r.isSuccess)
        assertEquals(R.string.error_npi_invalid, r.errorMessageResId)
    }

    @Test
    fun `validateNpi too short failure`() {
        assertFalse(validator.validateNpi("123456789", required = true).isSuccess) // 9 digits
    }

    @Test
    fun `validateNpi too long failure`() {
        assertFalse(validator.validateNpi("12345678901", required = true).isSuccess) // 11 digits
    }

    @Test
    fun `validateNpi ten digits success`() {
        assertTrue(validator.validateNpi("1234567890", required = true).isSuccess)
    }

    @Test
    fun `validateNpi blank required failure`() {
        val r = validator.validateNpi("", required = true)
        assertFalse(r.isSuccess)
        assertEquals(R.string.error_npi_required, r.errorMessageResId)
    }

    @Test
    fun `validateNpi whitespace only required failure`() {
        val r = validator.validateNpi("   ", required = true)
        assertFalse(r.isSuccess)
        assertEquals(R.string.error_npi_required, r.errorMessageResId)
    }

    @Test
    fun `validateNpi surrounding whitespace failure`() {
        assertFalse(validator.validateNpi(" 1234567890 ", required = true).isSuccess)
    }

    @Test
    fun `validateNpi ten characters with a letter failure`() {
        val r = validator.validateNpi("123456789O", required = true) // letter O, not zero
        assertFalse(r.isSuccess)
        assertEquals(R.string.error_npi_invalid, r.errorMessageResId)
    }

    @Test
    fun `validateNpi with separators failure`() {
        assertFalse(validator.validateNpi("12345-6789", required = true).isSuccess)
    }

    @Test
    fun `validateNpi non-ascii digits failure`() {
        // Ten Arabic-Indic digits: Char.isDigit() accepts them, the API does not.
        assertFalse(validator.validateNpi("١٢٣٤٥٦٧٨٩٠", required = true).isSuccess)
    }

    @Test
    fun `validateNpi leading zeros success`() {
        assertTrue(validator.validateNpi("0000000001", required = true).isSuccess)
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
