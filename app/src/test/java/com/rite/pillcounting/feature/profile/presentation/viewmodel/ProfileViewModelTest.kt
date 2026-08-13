package com.rite.pillcounting.feature.profile.presentation.viewmodel

import android.content.Context
import android.util.Log
import app.cash.turbine.test
import com.google.gson.Gson
import com.rite.pillcounting.R
import com.rite.pillcounting.core.hl7.service.HL7Config
import com.rite.pillcounting.core.models.ErrorResponse
import com.rite.pillcounting.core.models.ValidationResult
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.models.UserEntity
import com.rite.pillcounting.core.utils.common.NetworkUtils
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.core.utils.validator.CredentialsValidator
import com.rite.pillcounting.feature.dashboard.data.TerminalRepository
import com.rite.pillcounting.feature.dashboard.domain.model.Terminal
import com.rite.pillcounting.feature.dashboard.domain.model.TerminalUpdateRequest
import com.rite.pillcounting.feature.dashboard.domain.model.TerminalUpdateResponse
import com.rite.pillcounting.feature.hl7.core.Hl7ServiceManager
import com.rite.pillcounting.feature.profile.data.ProfileRepository
import com.rite.pillcounting.feature.profile.domain.model.Country
import com.rite.pillcounting.feature.profile.domain.model.ProfileDeleteResponse
import com.rite.pillcounting.feature.profile.domain.model.ProfileDeleteUiState
import com.rite.pillcounting.feature.profile.domain.model.ProfileUpdateResponse
import com.rite.pillcounting.feature.profile.domain.model.ProfileUpdateUiState
import com.rite.pillcounting.feature.profile.domain.model.PharmacyTypeData
import com.rite.pillcounting.feature.profile.domain.model.PharmacyTypeOption
import com.rite.pillcounting.feature.profile.domain.model.PharmacyTypeResponse
import com.rite.pillcounting.feature.profile.domain.model.State
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var repository: ProfileRepository
    private lateinit var terminalRepository: TerminalRepository
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var userDao: UserDao
    private lateinit var validator: CredentialsValidator
    private lateinit var hl7ServiceManager: Hl7ServiceManager
    private lateinit var context: Context

    private val activeTerminal =
        Terminal(terminalId = "t1", terminalName = "Front Desk", isActive = true)
    private val otherTerminal =
        Terminal(terminalId = "t2", terminalName = "Back Desk", isActive = false)

    private val usStates = listOf(State("CA", "California"), State("NY", "New York"))
    private val caStates = listOf(State("ON", "Ontario"))
    private val testCountries = listOf(
        Country("US", "United States", usStates),
        Country("CA", "Canada", caStates)
    )

    private val updateResponse = ProfileUpdateResponse(200, "ok", true)
    private val deleteResponse = ProfileDeleteResponse(200, "ok", true)
    private val terminalResponse = TerminalUpdateResponse(message = "ok", success = true)

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        Dispatchers.setMain(testDispatcher)

        mockkObject(NetworkUtils)
        every { NetworkUtils.isNetworkAvailable(any()) } returns true

        repository = mockk(relaxed = true)
        terminalRepository = mockk(relaxed = true)
        preferenceHelper = mockk(relaxed = true)
        userDao = mockk(relaxed = true)
        validator = mockk(relaxed = true)
        hl7ServiceManager = mockk(relaxed = true)
        context = mockk(relaxed = true)

        every { context.getString(any()) } returns "msg"

        // Default init stubs
        every { preferenceHelper.getLocalId() } returns 1L
        every { preferenceHelper.isDoNotAskAgain() } returns false
        every { preferenceHelper.getTerminals() } returns listOf(activeTerminal, otherTerminal)
        every { preferenceHelper.getSelectedTerminalId() } returns null
        every { preferenceHelper.getUserId() } returns "user-1"
        every { userDao.observeByLocalId(any()) } returns flowOf(null)
        every { preferenceHelper.getCountries() } returns testCountries
        coEvery { repository.getCountries() } returns Result.success(testCountries)
        every { preferenceHelper.getPharmacyType() } returns null
        coEvery { repository.getPharmacyTypes() } returns Result.success(
            PharmacyTypeResponse(
                data = PharmacyTypeData(
                    pharmacyTypes = listOf(PharmacyTypeOption("retail", "Retail"))
                )
            )
        )

        // Validation success by default
        every { validator.validateRequiredName(any()) } returns ValidationResult(true, null)
        every { validator.validatePharmacyName(any()) } returns ValidationResult(true, null)
        every { validator.validatePhone(any()) } returns ValidationResult(true, null)
        every { validator.validateEmail(any()) } returns ValidationResult(true, null)
        every { validator.validateNpi(any()) } returns ValidationResult(true, null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): ProfileViewModel =
        ProfileViewModel(
            repository,
            terminalRepository,
            preferenceHelper,
            userDao,
            validator,
            hl7ServiceManager,
            context
        )

    private fun httpException(code: Int, body: String = "error"): HttpException {
        val responseBody = body.toResponseBody("application/json".toMediaTypeOrNull())
        return HttpException(Response.error<Any>(code, responseBody))
    }

    /** Selects US/CA so [ProfileViewModel.validateInputs] country/state checks pass. */
    private fun selectValidLocation(vm: ProfileViewModel) {
        vm.onCountrySelected(testCountries.first { it.code == "US" })
        vm.onStateSelected(vm.states.first { it.code == "CA" })
    }

    private fun userEntity(country: String? = null, state: String? = null) = UserEntity(
        localId = 1L,
        userId = "user-1",
        email = "john@x.com",
        fName = "John",
        lName = "Doe",
        phoneNumber = "1234567890",
        pharmacyName = "Pharma",
        npiId = "123456",
        country = country,
        state = state,
    )

    // ────────────────────────────── init ──────────────────────────────

    @Test
    fun `init skips prefill when localId is zero`() = runTest(testDispatcher) {
        every { preferenceHelper.getLocalId() } returns 0L

        val vm = createViewModel()
        advanceUntilIdle()

        verify(exactly = 0) { userDao.observeByLocalId(any()) }
        assertEquals("", vm.firstName)
    }

    @Test
    fun `init observes user but null user leaves fields empty`() = runTest(testDispatcher) {
        every { userDao.observeByLocalId(1L) } returns flowOf(null)

        val vm = createViewModel()
        advanceUntilIdle()

        verify { userDao.observeByLocalId(1L) }
        assertEquals("", vm.firstName)
        assertEquals("", vm.email)
    }

    @Test
    fun `init prefills fields from emitted user`() = runTest(testDispatcher) {
        every { userDao.observeByLocalId(1L) } returns flowOf(userEntity())

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("John", vm.firstName)
        assertEquals("Doe", vm.lastName)
        assertEquals("Pharma", vm.pharmacyName)
        assertEquals("1234567890", vm.phoneNumber)
        assertEquals("john@x.com", vm.email)
        assertEquals("123456", vm.npi)
    }

    @Test
    fun `init prefills selectedCountry from emitted user country`() = runTest(testDispatcher) {
        every { userDao.observeByLocalId(1L) } returns flowOf(userEntity(country = "CA"))

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("CA", vm.selectedCountry?.code)
    }

    @Test
    fun `init leaves selectedCountry null when user country is unknown`() = runTest(testDispatcher) {
        every { userDao.observeByLocalId(1L) } returns flowOf(userEntity(country = "XX"))

        val vm = createViewModel()
        advanceUntilIdle()

        assertNull(vm.selectedCountry)
    }

    @Test
    fun `init resolves user country against fresh cache when local cache was empty`() = runTest(testDispatcher) {
        every { preferenceHelper.getCountries() } returns emptyList()
        every { userDao.observeByLocalId(1L) } returns flowOf(userEntity(country = "CA", state = "ON"))

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("CA", vm.selectedCountry?.code)
        assertEquals("ON", vm.selectedState?.code)
    }

    @Test
    fun `fetchCountries failure keeps the cached list and selection`() = runTest(testDispatcher) {
        coEvery { repository.getCountries() } returns Result.failure(RuntimeException("network"))
        every { userDao.observeByLocalId(1L) } returns flowOf(userEntity(country = "US"))

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(testCountries, vm.countries)
        assertEquals("US", vm.selectedCountry?.code)
    }

    @Test
    fun `fetchCountries empty response leaves current selection as is`() = runTest(testDispatcher) {
        coEvery { repository.getCountries() } returns Result.success(emptyList())
        every { userDao.observeByLocalId(1L) } returns flowOf(userEntity(country = "US"))

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(testCountries, vm.countries)
        assertEquals("US", vm.selectedCountry?.code)
    }

    @Test
    fun `init resolves selected terminal by saved id`() = runTest(testDispatcher) {
        every { preferenceHelper.getSelectedTerminalId() } returns "t2"

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("t2", vm.selectedTerminal?.terminalId)
        verify { preferenceHelper.saveSelectedTerminalName("Back Desk") }
    }

    @Test
    fun `init falls back to active terminal when no saved id matches`() = runTest(testDispatcher) {
        every { preferenceHelper.getSelectedTerminalId() } returns "nope"

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("t1", vm.selectedTerminal?.terminalId)
    }

    @Test
    fun `init selectedTerminal null when no match and none active`() = runTest(testDispatcher) {
        every { preferenceHelper.getSelectedTerminalId() } returns null
        every { preferenceHelper.getTerminals() } returns
            listOf(otherTerminal.copy(isActive = false))

        val vm = createViewModel()
        advanceUntilIdle()

        assertNull(vm.selectedTerminal)
    }

    // ────────────────────────────── simple setters ──────────────────────────────

    @Test
    fun `toggleDoNotAskAgain updates state and saves`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleDoNotAskAgain(true)

        assertTrue(vm.doNotAskAgain)
        verify { preferenceHelper.saveDoNotAskAgain(true) }
    }

    @Test
    fun `onTerminalSelected updates selectedTerminal`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onTerminalSelected(otherTerminal)

        assertEquals("t2", vm.selectedTerminal?.terminalId)
    }

    @Test
    fun `countries defaults to cached countries`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(testCountries, vm.countries)
    }

    @Test
    fun `selectedCountry defaults to null`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        assertNull(vm.selectedCountry)
    }

    @Test
    fun `onCountrySelected updates selectedCountry`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val canada = testCountries.first { it.code == "CA" }
        vm.onCountrySelected(canada)

        assertEquals("CA", vm.selectedCountry?.code)
    }

    @Test
    fun `states defaults to empty when no country selected`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(emptyList<State>(), vm.states)
        assertNull(vm.selectedState)
    }

    @Test
    fun `onCountrySelected refreshes states for new country`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val canada = testCountries.first { it.code == "CA" }
        vm.onCountrySelected(canada)

        assertEquals(caStates, vm.states)
        assertNull(vm.selectedState)
    }

    @Test
    fun `onStateSelected updates selectedState`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onCountrySelected(testCountries.first { it.code == "US" })

        val california = vm.states.first { it.code == "CA" }
        vm.onStateSelected(california)

        assertEquals("CA", vm.selectedState?.code)
    }

    @Test
    fun `init prefills selectedState from emitted user state`() = runTest(testDispatcher) {
        every { userDao.observeByLocalId(1L) } returns
            flowOf(userEntity(country = "US", state = "NY"))

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("NY", vm.selectedState?.code)
    }

    @Test
    fun `onPhoneChanged filters digits and limits to 10`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onPhoneChanged("ab12-345 6789012345")

        assertEquals("1234567890", vm.phoneNumber)
    }

    @Test
    fun `onFirstNameChanged filters and limits to 50`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onFirstNameChanged("Jo3hn-O'Br1ien")

        assertEquals("John-O'Brien", vm.firstName)
        assertTrue(vm.firstName.length <= 50)
    }

    @Test
    fun `onLastNameChanged filters and limits to 50`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onLastNameChanged("D9oe2")

        assertEquals("Doe", vm.lastName)
    }

    // ────────────────────────────── updateProfile ──────────────────────────────

    @Test
    fun `updateProfile aborts when validation fails`() = runTest(testDispatcher) {
        every { validator.validateEmail(any()) } returns ValidationResult(false, 42)

        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateProfile()
        advanceUntilIdle()

        assertEquals(ProfileUpdateUiState.Idle, vm.updateUiState.value)
        assertEquals(42, vm.emailError)
        coVerify(exactly = 0) { repository.updateProfile(any()) }
    }

    @Test
    fun `updateProfile aborts when no country selected`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateProfile()
        advanceUntilIdle()

        assertEquals(ProfileUpdateUiState.Idle, vm.updateUiState.value)
        assertEquals(R.string.please_select_country, vm.countryError)
        coVerify(exactly = 0) { repository.updateProfile(any()) }
    }

    @Test
    fun `updateProfile aborts when country selected but state is not`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onCountrySelected(testCountries.first { it.code == "US" })

        vm.updateProfile()
        advanceUntilIdle()

        assertEquals(ProfileUpdateUiState.Idle, vm.updateUiState.value)
        assertEquals(R.string.please_select_state, vm.stateError)
        coVerify(exactly = 0) { repository.updateProfile(any()) }
    }

    @Test
    fun `updateProfile errors when no internet`() = runTest(testDispatcher) {
        every { NetworkUtils.isNetworkAvailable(any()) } returns false

        val vm = createViewModel()
        advanceUntilIdle()
        selectValidLocation(vm)

        vm.updateProfile()
        advanceUntilIdle()

        assertTrue(vm.updateUiState.value is ProfileUpdateUiState.Error)
        coVerify(exactly = 0) { repository.updateProfile(any()) }
    }

    @Test
    fun `updateProfile success updates user and terminal unchanged`() = runTest(testDispatcher) {
        // selected terminal == initial terminal (active one) → unchanged branch
        coEvery { repository.updateProfile(any()) } returns Result.success(updateResponse)

        val vm = createViewModel()
        advanceUntilIdle()
        selectValidLocation(vm)

        vm.updateUiState.test {
            assertEquals(ProfileUpdateUiState.Idle, awaitItem())
            vm.updateProfile()
            advanceUntilIdle()
            assertEquals(ProfileUpdateUiState.Loading, awaitItem())
            assertEquals(ProfileUpdateUiState.Success, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { userDao.update(any<UserEntity>()) }
        verify { preferenceHelper.saveDoNotAskAgain(any()) }
        coVerify(exactly = 0) { terminalRepository.updateTerminal(any(), any()) }
    }

    @Test
    fun `updateProfile sends selected country in request and persists it`() = runTest(testDispatcher) {
        coEvery { repository.updateProfile(any()) } returns Result.success(updateResponse)

        val vm = createViewModel()
        advanceUntilIdle()

        val canada = vm.countries.first { it.code == "CA" }
        vm.onCountrySelected(canada)
        vm.onStateSelected(vm.states.first { it.code == "ON" })

        vm.updateProfile()
        advanceUntilIdle()

        coVerify { repository.updateProfile(match { it.country == "CA" }) }
        coVerify { userDao.update(match<UserEntity> { it.country == "CA" }) }
    }

    @Test
    fun `updateProfile sends selected state in request and persists it`() = runTest(testDispatcher) {
        coEvery { repository.updateProfile(any()) } returns Result.success(updateResponse)

        val vm = createViewModel()
        advanceUntilIdle()
        vm.onCountrySelected(testCountries.first { it.code == "US" })

        val california = vm.states.first { it.code == "CA" }
        vm.onStateSelected(california)

        vm.updateProfile()
        advanceUntilIdle()

        coVerify { repository.updateProfile(match { it.state == "CA" }) }
        coVerify { userDao.update(match<UserEntity> { it.state == "CA" }) }
    }

    @Test
    fun `updateProfile success skips user update when localId zero`() = runTest(testDispatcher) {
        every { preferenceHelper.getLocalId() } returns 0L
        coEvery { repository.updateProfile(any()) } returns Result.success(updateResponse)

        val vm = createViewModel()
        advanceUntilIdle()
        selectValidLocation(vm)

        vm.updateProfile()
        advanceUntilIdle()

        assertEquals(ProfileUpdateUiState.Success, vm.updateUiState.value)
        coVerify(exactly = 0) { userDao.update(any<UserEntity>()) }
    }

    @Test
    fun `updateProfile terminal changed success updates terminals and hl7`() = runTest(testDispatcher) {
        every { preferenceHelper.getSelectedTerminalId() } returns "t1" // initial = t1
        coEvery { repository.updateProfile(any()) } returns Result.success(updateResponse)
        coEvery { terminalRepository.updateTerminal("t2", any()) } returns
            Result.success(terminalResponse)
        // Enable HL7 full path
        every { preferenceHelper.isHl7Enabled() } returns true
        every { preferenceHelper.isUserLoggedIn() } returns true
        every { preferenceHelper.getNsdBroadcastType() } returns "_pc._tcp"
        every { preferenceHelper.getNsdDiscoveryType() } returns "_srv._tcp"

        val vm = createViewModel()
        advanceUntilIdle()
        selectValidLocation(vm)
        vm.onTerminalSelected(otherTerminal) // change to t2

        vm.updateProfile()
        advanceUntilIdle()

        assertEquals(ProfileUpdateUiState.Success, vm.updateUiState.value)
        coVerify { terminalRepository.updateTerminal("t2", any<TerminalUpdateRequest>()) }
        verify { preferenceHelper.saveSelectedTerminalId("t2") }
        verify { preferenceHelper.saveTerminals(any()) }
        verify { hl7ServiceManager.updateConfigAndRebroadcast(any<HL7Config>()) }
    }

    @Test
    fun `updateProfile terminal changed but update fails does not crash`() = runTest(testDispatcher) {
        every { preferenceHelper.getSelectedTerminalId() } returns "t1"
        coEvery { repository.updateProfile(any()) } returns Result.success(updateResponse)
        coEvery { terminalRepository.updateTerminal("t2", any()) } returns
            Result.failure(RuntimeException("terminal boom"))

        val vm = createViewModel()
        advanceUntilIdle()
        selectValidLocation(vm)
        vm.onTerminalSelected(otherTerminal)

        vm.updateProfile()
        advanceUntilIdle()

        assertEquals(ProfileUpdateUiState.Success, vm.updateUiState.value)
        verify(exactly = 0) { hl7ServiceManager.updateConfigAndRebroadcast(any()) }
    }

    @Test
    fun `updateProfile hl7 early return when disabled`() = runTest(testDispatcher) {
        every { preferenceHelper.getSelectedTerminalId() } returns "t1"
        coEvery { repository.updateProfile(any()) } returns Result.success(updateResponse)
        coEvery { terminalRepository.updateTerminal("t2", any()) } returns
            Result.success(terminalResponse)
        every { preferenceHelper.isHl7Enabled() } returns false
        every { preferenceHelper.isUserLoggedIn() } returns true

        val vm = createViewModel()
        advanceUntilIdle()
        selectValidLocation(vm)
        vm.onTerminalSelected(otherTerminal)

        vm.updateProfile()
        advanceUntilIdle()

        verify(exactly = 0) { hl7ServiceManager.updateConfigAndRebroadcast(any()) }
    }

    @Test
    fun `updateProfile hl7 early return when service names empty`() = runTest(testDispatcher) {
        every { preferenceHelper.getSelectedTerminalId() } returns "t1"
        coEvery { repository.updateProfile(any()) } returns Result.success(updateResponse)
        coEvery { terminalRepository.updateTerminal("t2", any()) } returns
            Result.success(terminalResponse)
        every { preferenceHelper.isHl7Enabled() } returns true
        every { preferenceHelper.isUserLoggedIn() } returns true
        every { preferenceHelper.getNsdBroadcastType() } returns ""
        every { preferenceHelper.getNsdDiscoveryType() } returns ""

        val vm = createViewModel()
        advanceUntilIdle()
        selectValidLocation(vm)
        vm.onTerminalSelected(otherTerminal)

        vm.updateProfile()
        advanceUntilIdle()

        verify(exactly = 0) { hl7ServiceManager.updateConfigAndRebroadcast(any()) }
    }

    @Test
    fun `updateProfile failure maps to error state`() = runTest(testDispatcher) {
        coEvery { repository.updateProfile(any()) } returns
            Result.failure(RuntimeException("network"))

        val vm = createViewModel()
        advanceUntilIdle()
        selectValidLocation(vm)

        vm.updateProfile()
        advanceUntilIdle()

        val state = vm.updateUiState.value
        assertTrue(state is ProfileUpdateUiState.Error)
        assertEquals("network", (state as ProfileUpdateUiState.Error).message)
    }

    // ────────────────────────────── deleteProfile ──────────────────────────────

    @Test
    fun `deleteProfile errors when no internet`() = runTest(testDispatcher) {
        every { NetworkUtils.isNetworkAvailable(any()) } returns false

        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteProfile()
        advanceUntilIdle()

        assertTrue(vm.deleteUiState.value is ProfileDeleteUiState.Error)
        coVerify(exactly = 0) { repository.deleteProfile() }
    }

    @Test
    fun `deleteProfile success`() = runTest(testDispatcher) {
        coEvery { repository.deleteProfile() } returns Result.success(deleteResponse)

        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteUiState.test {
            assertEquals(ProfileDeleteUiState.Idle, awaitItem())
            vm.deleteProfile()
            advanceUntilIdle()
            assertEquals(ProfileDeleteUiState.Loading, awaitItem())
            assertEquals(ProfileDeleteUiState.Success, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleteProfile failure maps to error`() = runTest(testDispatcher) {
        coEvery { repository.deleteProfile() } returns
            Result.failure(RuntimeException("del boom"))

        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteProfile()
        advanceUntilIdle()

        val state = vm.deleteUiState.value
        assertTrue(state is ProfileDeleteUiState.Error)
        assertEquals("del boom", (state as ProfileDeleteUiState.Error).message)
    }

    // ────────────────────────────── getFriendlyErrorMessage ──────────────────────────────

    @Test
    fun `error message http 400 with parsed body`() = runTest(testDispatcher) {
        val body = Gson().toJson(
            ErrorResponse(400, false, "bad input", null, emptyMap())
        )
        coEvery { repository.deleteProfile() } returns Result.failure(httpException(400, body))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.deleteProfile()
        advanceUntilIdle()

        assertEquals("bad input", (vm.deleteUiState.value as ProfileDeleteUiState.Error).message)
    }

    @Test
    fun `error message http 400 unparseable body falls back`() = runTest(testDispatcher) {
        coEvery { repository.deleteProfile() } returns
            Result.failure(httpException(400, "not-json"))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.deleteProfile()
        advanceUntilIdle()

        assertEquals("msg", (vm.deleteUiState.value as ProfileDeleteUiState.Error).message)
    }

    @Test
    fun `error message http 401`() = runTest(testDispatcher) {
        coEvery { repository.deleteProfile() } returns Result.failure(httpException(401))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.deleteProfile()
        advanceUntilIdle()

        assertEquals("msg", (vm.deleteUiState.value as ProfileDeleteUiState.Error).message)
    }

    @Test
    fun `error message http 404`() = runTest(testDispatcher) {
        coEvery { repository.deleteProfile() } returns Result.failure(httpException(404))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.deleteProfile()
        advanceUntilIdle()

        assertEquals("msg", (vm.deleteUiState.value as ProfileDeleteUiState.Error).message)
    }

    @Test
    fun `error message http 500`() = runTest(testDispatcher) {
        coEvery { repository.deleteProfile() } returns Result.failure(httpException(500))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.deleteProfile()
        advanceUntilIdle()

        assertEquals("msg", (vm.deleteUiState.value as ProfileDeleteUiState.Error).message)
    }

    @Test
    fun `error message http else code with parsed body`() = runTest(testDispatcher) {
        val body = Gson().toJson(
            ErrorResponse(418, false, "teapot", null, emptyMap())
        )
        coEvery { repository.deleteProfile() } returns Result.failure(httpException(418, body))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.deleteProfile()
        advanceUntilIdle()

        assertEquals("teapot", (vm.deleteUiState.value as ProfileDeleteUiState.Error).message)
    }

    @Test
    fun `error message http else code null body falls back`() = runTest(testDispatcher) {
        coEvery { repository.deleteProfile() } returns
            Result.failure(httpException(418, "bad"))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.deleteProfile()
        advanceUntilIdle()

        assertEquals("msg", (vm.deleteUiState.value as ProfileDeleteUiState.Error).message)
    }

    @Test
    fun `error message unknown host`() = runTest(testDispatcher) {
        coEvery { repository.deleteProfile() } returns
            Result.failure(UnknownHostException("no host"))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.deleteProfile()
        advanceUntilIdle()

        assertEquals("msg", (vm.deleteUiState.value as ProfileDeleteUiState.Error).message)
    }

    @Test
    fun `error message socket timeout`() = runTest(testDispatcher) {
        coEvery { repository.deleteProfile() } returns
            Result.failure(SocketTimeoutException("slow"))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.deleteProfile()
        advanceUntilIdle()

        assertEquals("msg", (vm.deleteUiState.value as ProfileDeleteUiState.Error).message)
    }

    @Test
    fun `error message generic with non-blank message`() = runTest(testDispatcher) {
        coEvery { repository.deleteProfile() } returns
            Result.failure(IllegalStateException("custom message"))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.deleteProfile()
        advanceUntilIdle()

        assertEquals(
            "custom message",
            (vm.deleteUiState.value as ProfileDeleteUiState.Error).message
        )
    }

    @Test
    fun `error message generic with blank message falls back`() = runTest(testDispatcher) {
        coEvery { repository.deleteProfile() } returns
            Result.failure(IllegalStateException("   "))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.deleteProfile()
        advanceUntilIdle()

        assertEquals("msg", (vm.deleteUiState.value as ProfileDeleteUiState.Error).message)
    }

    // ────────────────────────────── reset ──────────────────────────────

    @Test
    fun `resetUpdateState sets idle`() = runTest(testDispatcher) {
        coEvery { repository.updateProfile(any()) } returns
            Result.failure(RuntimeException("x"))

        val vm = createViewModel()
        advanceUntilIdle()
        selectValidLocation(vm)
        vm.updateProfile()
        advanceUntilIdle()
        assertTrue(vm.updateUiState.value is ProfileUpdateUiState.Error)

        vm.resetUpdateState()

        assertEquals(ProfileUpdateUiState.Idle, vm.updateUiState.value)
    }

    @Test
    fun `resetDeleteState sets idle`() = runTest(testDispatcher) {
        coEvery { repository.deleteProfile() } returns
            Result.failure(RuntimeException("x"))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.deleteProfile()
        advanceUntilIdle()
        assertTrue(vm.deleteUiState.value is ProfileDeleteUiState.Error)

        vm.resetDeleteState()

        assertEquals(ProfileDeleteUiState.Idle, vm.deleteUiState.value)
    }
}
