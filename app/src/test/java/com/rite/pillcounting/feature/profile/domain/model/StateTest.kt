package com.rite.pillcounting.feature.profile.domain.model

import com.rite.pillcounting.core.di.NetworkModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StateTest {

    private val moshi = NetworkModule.provideMoshi()
    private val adapter = moshi.adapter(State::class.java)

    @Test
    fun `parses full json into State`() {
        val state = adapter.fromJson("""{"code":"CA","name":"California"}""")

        assertEquals("CA", state?.code)
        assertEquals("California", state?.name)
    }

    @Test
    fun `missing fields deserialize to null instead of throwing`() {
        val state = adapter.fromJson("{}")

        assertNull(state?.code)
        assertNull(state?.name)
    }

    @Test
    fun `explicit json nulls deserialize to null instead of throwing`() {
        val state = adapter.fromJson("""{"code":null,"name":null}""")

        assertNull(state?.code)
        assertNull(state?.name)
    }
}
