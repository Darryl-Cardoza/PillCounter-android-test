package com.dispensesure.retail.core.utils.validator

import android.util.Patterns
import com.dispensesure.retail.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // ─────────────────────────── normalizedNameKey ───────────────────────────

    @Test
    fun `normalizedNameKey ignores case`() {
        assertEquals(
            CredentialsValidator.normalizedNameKey("John", "Smith"),
            CredentialsValidator.normalizedNameKey("john", "SMITH"),
        )
    }

    @Test
    fun `normalizedNameKey ignores surrounding and repeated spaces`() {
        // The field keeps a trailing space while typing, so it must not count.
        assertEquals(
            CredentialsValidator.normalizedNameKey("John", "Smith"),
            CredentialsValidator.normalizedNameKey("  John ", "Smith  "),
        )
        assertEquals(
            CredentialsValidator.normalizedNameKey("Mary Jane", "Smith"),
            CredentialsValidator.normalizedNameKey("Mary  Jane", "Smith"),
        )
    }

    @Test
    fun `normalizedNameKey keeps different people apart`() {
        assertNotEquals(
            CredentialsValidator.normalizedNameKey("John", "Smith"),
            CredentialsValidator.normalizedNameKey("John", "Smyth"),
        )
        assertNotEquals(
            CredentialsValidator.normalizedNameKey("John", "Smith"),
            CredentialsValidator.normalizedNameKey("Jane", "Smith"),
        )
    }

    @Test
    fun `normalizedNameKey does not merge the two fields`() {
        assertNotEquals(
            CredentialsValidator.normalizedNameKey("John", "Smith"),
            CredentialsValidator.normalizedNameKey("John Smith", ""),
        )
    }

    @Test
    fun `normalizedNameKey keeps hyphens and apostrophes significant`() {
        assertNotEquals(
            CredentialsValidator.normalizedNameKey("Mary-Jane", "O'Neil"),
            CredentialsValidator.normalizedNameKey("Mary Jane", "ONeil"),
        )
    }

    // ───────────────────────── validateNameNotTaken ─────────────────────────

    private val taken = setOf(CredentialsValidator.normalizedNameKey("John", "Smith"))

    @Test
    fun `validateNameNotTaken enrolled name failure`() {
        val r = CredentialsValidator.validateNameNotTaken("john", "SMITH ", taken)
        assertFalse(r.isSuccess)
        assertEquals(R.string.face_registration_name_taken, r.errorMessageResId)
    }

    @Test
    fun `validateNameNotTaken new name success`() {
        assertTrue(CredentialsValidator.validateNameNotTaken("John", "Smyth", taken).isSuccess)
        assertTrue(CredentialsValidator.validateNameNotTaken("Jane", "Smith", taken).isSuccess)
    }

    @Test
    fun `validateNameNotTaken half-typed name is not judged`() {
        // A blank half is the presence gate's job, not this rule's — and
        // "John" + "" must never collide with the enrolled "John Smith".
        assertTrue(CredentialsValidator.validateNameNotTaken("John", "", taken).isSuccess)
        assertTrue(CredentialsValidator.validateNameNotTaken("", "Smith", taken).isSuccess)
        assertTrue(CredentialsValidator.validateNameNotTaken(" ", " ", taken).isSuccess)
    }

    @Test
    fun `validateNameNotTaken empty gallery success`() {
        assertTrue(CredentialsValidator.validateNameNotTaken("John", "Smith", emptySet()).isSuccess)
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
