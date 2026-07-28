package com.rite.pillcounting.feature.profile.domain.model

import com.rite.pillcounting.core.di.NetworkModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountryTest {

    private val moshi = NetworkModule.provideMoshi()
    private val adapter = moshi.adapter(Country::class.java)

    @Test
    fun `parses full json into Country with nested states`() {
        val json = """
            {"code":"US","name":"United States","states":[{"code":"CA","name":"California"}]}
        """.trimIndent()

        val country = adapter.fromJson(json)

        assertEquals("US", country?.code)
        assertEquals("United States", country?.name)
        assertEquals("CA", country?.states?.first()?.code)
    }

    @Test
    fun `missing fields deserialize to null instead of throwing`() {
        val country = adapter.fromJson("{}")

        assertNull(country?.code)
        assertNull(country?.name)
        assertNull(country?.states)
    }

    @Test
    fun `explicit json nulls deserialize to null instead of throwing`() {
        val json = """{"code":null,"name":null,"states":null}"""

        val country = adapter.fromJson(json)

        assertNull(country?.code)
        assertNull(country?.name)
        assertNull(country?.states)
    }
}
