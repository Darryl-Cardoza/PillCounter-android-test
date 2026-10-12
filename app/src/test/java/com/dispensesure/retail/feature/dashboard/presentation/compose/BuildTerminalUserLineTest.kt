package com.dispensesure.retail.feature.dashboard.presentation.compose

import com.dispensesure.retail.feature.dashboard.domain.model.DashboardUiState
import com.dispensesure.retail.feature.dashboard.domain.model.UserDetail
import com.dispensesure.retail.feature.dashboard.domain.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class BuildTerminalUserLineTest {

    private val operatorLine: (String) -> String = { "Operator: $it" }

    private val accountUser = UserDetail(
        profile = UserProfile(fName = "Jane", lName = "Doe"),
        settings = null,
    )

    @Test
    fun operatorNameGetsTheLabel() {
        val state = DashboardUiState(operatorName = "John Smith", userDetail = accountUser)
        assertEquals("Operator: John Smith", buildTerminalUserLine(state, operatorLine, includeTerminal = false))
    }

    @Test
    fun accountNameFallbackGetsTheLabel() {
        val state = DashboardUiState(userDetail = accountUser)
        assertEquals("Operator: Jane Doe", buildTerminalUserLine(state, operatorLine, includeTerminal = false))
    }

    @Test
    fun nothingSetGivesDash() {
        assertEquals("—", buildTerminalUserLine(DashboardUiState(), operatorLine))
    }
}
