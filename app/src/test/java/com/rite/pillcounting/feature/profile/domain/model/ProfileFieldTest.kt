package com.rite.pillcounting.feature.profile.domain.model

import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFieldTest {

    @Test
    fun getters_returnValues_andDefaults() {
        var captured = ""
        val field = ProfileField(
            value = "hello",
            onChange = { captured = it },
            labelRes = 42,
            error = null
        )
        assertEquals("hello", field.value)
        assertEquals(42, field.labelRes)
        assertNull(field.error)
        assertFalse(field.readOnly)
        assertEquals(KeyboardType.Text, field.keyboardType)
        assertNull(field.maxLength)

        // Invoke the onChange lambda to cover that line.
        field.onChange("x")
        assertEquals("x", captured)
    }

    @Test
    fun nonDefault_values() {
        val field = ProfileField(
            value = "hello",
            onChange = {},
            labelRes = 1,
            error = 99,
            readOnly = true,
            keyboardType = KeyboardType.Number,
            maxLength = 10
        )
        assertEquals(99, field.error)
        assertTrue(field.readOnly)
        assertEquals(KeyboardType.Number, field.keyboardType)
        assertEquals(10, field.maxLength)
    }

    @Test
    fun equals_and_hashCode_forSharedLambda() {
        val lambda: (String) -> Unit = {}
        val a = ProfileField("v", lambda, 1, null)
        val b = ProfileField("v", lambda, 1, null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun toString_containsValue() {
        val field = ProfileField("hello", {}, 1, null)
        assertTrue(field.toString().contains("value=hello"))
    }

    @Test
    fun copy_overridesValue() {
        val field = ProfileField("hello", {}, 1, null)
        assertEquals("world", field.copy(value = "world").value)
    }

    @Test
    fun componentN_returnValues() {
        val lambda: (String) -> Unit = {}
        val field = ProfileField("hello", lambda, 5, 7, true, KeyboardType.Number, 12)
        assertEquals("hello", field.component1())
        assertEquals(lambda, field.component2())
        assertEquals(5, field.component3())
        assertEquals(7, field.component4())
        assertEquals(true, field.component5())
        assertEquals(KeyboardType.Number, field.component6())
        assertEquals(12, field.component7())
    }
}
