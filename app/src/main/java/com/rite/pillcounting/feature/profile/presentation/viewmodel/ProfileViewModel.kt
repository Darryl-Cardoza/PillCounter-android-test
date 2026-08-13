package com.rite.pillcounting.feature.profile.presentation.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.rite.pillcounting.R
import com.rite.pillcounting.core.hl7.service.HL7Config
import com.rite.pillcounting.core.models.ErrorResponse
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.models.UserEntity
import com.rite.pillcounting.core.utils.common.HelperFunctions.plain
import com.rite.pillcounting.core.utils.common.HelperFunctions.secure
import com.rite.pillcounting.core.utils.common.NetworkUtils
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.core.utils.validator.CredentialsValidator
import com.rite.pillcounting.feature.dashboard.data.TerminalRepository
import com.rite.pillcounting.feature.dashboard.domain.model.Terminal
import com.rite.pillcounting.feature.dashboard.domain.model.TerminalUpdateRequest
import com.rite.pillcounting.feature.hl7.core.Hl7ServiceManager
import com.rite.pillcounting.feature.profile.data.ProfileRepository
import com.rite.pillcounting.feature.profile.domain.model.PharmacyTypeOption
import com.rite.pillcounting.feature.profile.domain.model.Country
import com.rite.pillcounting.feature.profile.domain.model.ProfileDeleteUiState
import com.rite.pillcounting.feature.profile.domain.model.ProfileUpdateRequest
import com.rite.pillcounting.feature.profile.domain.model.ProfileUpdateUiState
import com.rite.pillcounting.feature.profile.domain.model.State
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

/**
 * ViewModel for managing Profile UI state, validation, and business logic.
 *
 * Responsibilities:
 * - Prefill the profile form from [UserDao].
 * - Validate fields using [CredentialsValidator].
 * - Handle profile update and delete operations via [ProfileRepository].
 * - Manage state flows ([ProfileUpdateUiState], [ProfileDeleteUiState]) for Compose UI.
 * - Provide user-friendly network and API error messages.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: ProfileRepository,
    private val terminalRepository: TerminalRepository,
    private val preferenceHelper: PreferenceHelper,
    private val userDao: UserDao,
    private val validator: CredentialsValidator,
    private val hl7ServiceManager: Hl7ServiceManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val logger = AppLogger.create<ProfileViewModel>()

    // ─────────────────────────── UI States ───────────────────────────
    private val _updateUiState = MutableStateFlow<ProfileUpdateUiState>(ProfileUpdateUiState.Idle)
    val updateUiState = _updateUiState.asStateFlow()

    private val _deleteUiState = MutableStateFlow<ProfileDeleteUiState>(ProfileDeleteUiState.Idle)
    val deleteUiState = _deleteUiState.asStateFlow()

    // ─────────────────────────── Profile Fields ───────────────────────────
    var firstName by mutableStateOf("")
        private set
    var lastName by mutableStateOf("")
        private set
    var pharmacyName by mutableStateOf("")
    var phoneNumber by mutableStateOf("")
    var email by mutableStateOf("")
    var npi by mutableStateOf("")
    var doNotAskAgain by mutableStateOf(false)
    
    // Terminal selection
    var terminals by mutableStateOf<List<Terminal>>(emptyList())
    var selectedTerminal by mutableStateOf<Terminal?>(null)
    private var initialTerminal: Terminal? = null // Track initial value to detect changes

    // Pharmacy type selection
    var pharmacyTypes by mutableStateOf<List<PharmacyTypeOption>>(emptyList())
        private set
    var selectedPharmacyType by mutableStateOf<PharmacyTypeOption?>(null)

    // Country selection — prefilled from cache immediately, refreshed from the API in init.
    var countries by mutableStateOf<List<Country>>(preferenceHelper.getCountries())
        private set
    var selectedCountry by mutableStateOf<Country?>(null)
        private set

    // Raw country/state codes from the user's saved profile row, kept independent of
    // [selectedCountry]/[selectedState] so they survive being resolved against an
    // empty (not-yet-fetched) [countries] cache and can be re-resolved once populated.
    private var userCountryCode: String? = null
    private var userStateCode: String? = null

    // State/province selection — options come from the selected country's nested list.
    var states by mutableStateOf<List<State>>(selectedCountry?.states.orEmpty())
        private set
    var selectedState by mutableStateOf<State?>(null)
        private set

    // ─────────────────────────── Validation Errors ───────────────────────────
    var firstNameError by mutableStateOf<Int?>(null)
    var lastNameError by mutableStateOf<Int?>(null)
    var pharmacyNameError by mutableStateOf<Int?>(null)
    var phoneError by mutableStateOf<Int?>(null)
    var emailError by mutableStateOf<Int?>(null)
    var npiError by mutableStateOf<Int?>(null)
    var countryError by mutableStateOf<Int?>(null)
        private set
    var stateError by mutableStateOf<Int?>(null)
        private set

    init {
        val localId = preferenceHelper.getLocalId()
        if (localId != null && localId != 0L) {
            observeUser(localId)
        } else {
            logger.w("No localId found in preferences — skipping prefill.")
        }

        doNotAskAgain = preferenceHelper.isDoNotAskAgain()
        logger.i("Initialized doNotAskAgain = $doNotAskAgain")

        // Load terminals from SharedPreferences
        terminals = preferenceHelper.getTerminals()
        val savedTerminalId = preferenceHelper.getSelectedTerminalId()
        selectedTerminal = terminals.firstOrNull { it.terminalId == savedTerminalId }
            ?: terminals.firstOrNull { it.isActive == true }
        initialTerminal = selectedTerminal // Store initial value to detect changes
        
        // Ensure terminal name is saved for the selected terminal
        selectedTerminal?.terminalName?.let { terminalName ->
            preferenceHelper.saveSelectedTerminalName(terminalName)
        }
        
        logger.i("Loaded ${terminals.size} terminals, selected: ${selectedTerminal?.terminalName}")

        // Load cached pharmacy type options and restore the previously selected one
        pharmacyTypes = preferenceHelper.getPharmacyTypes()
        val savedPharmacyTypeCode = preferenceHelper.getPharmacyType()
        selectedPharmacyType = pharmacyTypes.firstOrNull { it.code == savedPharmacyTypeCode }
        logger.i("Loaded ${pharmacyTypes.size} pharmacy types, selected: ${selectedPharmacyType?.code}")

        fetchPharmacyTypes()

        fetchCountries()
    }

    /**
     * Refreshes the selectable pharmacy-type options from the API on every profile screen load
     * and re-caches them, so server-side label/list changes are picked up and a saved code that
     * was missing from a stale cache can resolve to a selection.
     */
    private fun fetchPharmacyTypes() {
        viewModelScope.launch {
            repository.getPharmacyTypes()
                .onSuccess { response ->
                    val options = response.data?.pharmacyTypes.orEmpty()
                    preferenceHelper.savePharmacyTypes(options)
                    pharmacyTypes = options
                    selectedPharmacyType = options.firstOrNull { it.code == preferenceHelper.getPharmacyType() }
                    logger.i("Fetched and cached ${options.size} pharmacy types")
                }
                .onFailure { e ->
                    logger.e("Failed to fetch pharmacy types", e)
                }
        }
    }

    /**
     * Refreshes the countries/states reference list from the API on every profile screen load.
     *
     * Cached values (loaded synchronously in the [countries] initializer) are shown immediately
     * so the dropdown never blocks on the network; a successful response then replaces the
     * cache and re-resolves the current selection against the fresh list.
     */
    private fun fetchCountries() {
        viewModelScope.launch {
            repository.getCountries()
                .onSuccess { fetched ->
                    if (fetched.isEmpty()) return@onSuccess

                    countries = fetched
                    preferenceHelper.saveCountries(fetched)

                    selectedCountry = fetched.firstOrNull {
                        it.code == (selectedCountry?.code ?: userCountryCode)
                    }
                    states = selectedCountry?.states.orEmpty()
                    selectedState = states.firstOrNull {
                        it.code == (selectedState?.code ?: userStateCode)
                    }

                    logger.i("Countries refreshed from API (count=${fetched.size})")
                }
                .onFailure { e ->
                    logger.w("Failed to refresh countries from API, keeping cached list: ${e.message}")
                }
        }
    }

    fun toggleDoNotAskAgain(value: Boolean) {
        doNotAskAgain = value
        preferenceHelper.saveDoNotAskAgain(value)
        logger.i("DoNotAskAgain updated → $value")
    }

    /** HL7 toggle from the portal (cached in prefs). */
    fun isHl7Enabled(): Boolean = preferenceHelper.isHl7Enabled()

    private fun observeUser(localId: Long) {
        viewModelScope.launch {
            userDao.observeByLocalId(localId).collect { user ->
                user?.let {
                    logger.i("Prefilling profile UI with user (localId=$localId, email=${it.email})")
                    firstName = it.fName ?: ""
                    lastName = it.lName ?: ""
                    pharmacyName = it.pharmacyName.orEmpty()
                    phoneNumber = it.phoneNumber.plain().orEmpty()
                    email = it.email.plain().orEmpty()
                    npi = it.npiId.orEmpty()
                    doNotAskAgain = preferenceHelper.isDoNotAskAgain()
                    userCountryCode = it.country
                    userStateCode = it.state
                    selectedCountry = countries.firstOrNull { c -> c.code == it.country }
                    states = selectedCountry?.states.orEmpty()
                    selectedState = states.firstOrNull { s -> s.code == it.state }
                }
            }
        }
    }
    
    fun onTerminalSelected(terminal: Terminal) {
        selectedTerminal = terminal
        logger.i("Terminal selected: ${terminal.terminalName} (ID: ${terminal.terminalId})")
    }

    fun onPharmacyTypeSelected(pharmacyType: PharmacyTypeOption) {
        selectedPharmacyType = pharmacyType
        logger.i("Pharmacy type selected: ${pharmacyType.code}")
    }

    fun onCountrySelected(country: Country) {
        selectedCountry = country
        states = country.states.orEmpty()
        selectedState = states.firstOrNull { it.code == selectedState?.code }
        logger.i("Country selected: ${country.code}")
    }

    fun onStateSelected(state: State) {
        selectedState = state
        logger.i("State selected: ${state.code}")
    }

    /**
     * Invalidates the confirmed country selection once the user edits the search text
     * without picking an item from the dropdown, so a stale selection can't be saved
     * under mismatched displayed text.
     *
     * @param query Raw text currently typed into the country search field.
     *
     * Example Usage:
     * onCountryQueryChanged("Ind")
     */
    fun onCountryQueryChanged(query: String) {
        if (selectedCountry != null) {
            selectedCountry = null
            states = emptyList()
            selectedState = null
        }
    }

    /**
     * Invalidates the confirmed state selection once the user edits the search text
     * without picking an item from the dropdown, so a stale selection can't be saved
     * under mismatched displayed text.
     *
     * @param query Raw text currently typed into the state search field.
     *
     * Example Usage:
     * onStateQueryChanged("Cal")
     */
    fun onStateQueryChanged(query: String) {
        if (selectedState != null) {
            selectedState = null
        }
    }

    fun onPhoneChanged(input: String) {
        val digits = input.filter { it.isDigit() }

        val limited = digits.take(10)

        phoneNumber = limited
    }

    private val allowedNameChars = Regex("[\\p{L} '-]")

    fun onFirstNameChanged(input: String) {
        firstName = input
            .filter { it.toString().matches(allowedNameChars) }
            .take(50)
    }

    fun onLastNameChanged(input: String) {
        lastName = input
            .filter { it.toString().matches(allowedNameChars) }
            .take(50)
    }
    // ─────────────────────────── Validation ───────────────────────────
    private fun validateInputs(): Boolean {
        firstNameError = validator.validateRequiredName(firstName).errorMessageResId
        lastNameError = validator.validateRequiredName(lastName).errorMessageResId
        pharmacyNameError = validator.validatePharmacyName(pharmacyName).errorMessageResId
        phoneError = validator.validatePhone(phoneNumber).errorMessageResId
        emailError = validator.validateEmail(email).errorMessageResId
        npiError = validator.validateNpi(npi).errorMessageResId
        countryError = if (selectedCountry?.code.isNullOrBlank()) {
            R.string.please_select_country
        } else {
            null
        }
        stateError = if (states.isNotEmpty() && selectedState?.code.isNullOrBlank()) {
            R.string.please_select_state
        } else {
            null
        }

        return listOf(
            firstNameError, lastNameError, pharmacyNameError,
            phoneError, emailError, npiError, countryError, stateError
        ).all { it == null }
    }

    // ─────────────────────────── API Actions ───────────────────────────
        fun updateProfile() {
            if (!validateInputs()) {
                logger.w("Validation failed. Aborting update.")
                return
            }

            // Network check using NetworkUtils
            if (!NetworkUtils.isNetworkAvailable(context)) {
                _updateUiState.value =
                    ProfileUpdateUiState.Error(context.getString(R.string.error_no_internet))
                return
            }

            viewModelScope.launch {
                _updateUiState.value = ProfileUpdateUiState.Loading
                val request = ProfileUpdateRequest(
                    pharmacyName = pharmacyName,
                    phoneNumber = phoneNumber,
                    npiId = npi,
                    isProfileComplete = true,
                    avatarUrl = "",
                    notificationsEnabled = !doNotAskAgain,
                    language = "en",
                    timezone = "Asia/Kolkata",
                    fName = firstName.trim(),
                    lName = lastName.trim(),
                    pharmacyType = selectedPharmacyType?.code ?: preferenceHelper.getPharmacyType(),
                    terminalId = selectedTerminal?.terminalId,
                    country = selectedCountry?.code,
                    state = selectedState?.code
                )

                repository.updateProfile(request)
                    .onSuccess {
                        logger.i("Profile update success")

                        val localId = preferenceHelper.getLocalId()
                        if (localId != null && localId != 0L) {
                            val entity = UserEntity(
                                localId = localId,
                                userId = preferenceHelper.getUserId().orEmpty(),
                                email = email.secure(),
                                fName = firstName.trim(),
                                lName = lastName.trim(),
                                phoneNumber = phoneNumber.secure(),
                                pharmacyName = pharmacyName,
                                npiId = npi,
                                notifications = !doNotAskAgain,
                                country = selectedCountry?.code,
                                state = selectedState?.code,
                                isVerified = true,
                                isProfileCompleted = true,
                                createdAt = System.currentTimeMillis()
                            )
                            userDao.update(entity)
                            logger.i("User entity updated in Room via localId=$localId")
                        }

                        preferenceHelper.saveDoNotAskAgain(doNotAskAgain)

                        // Persist selected pharmacy type so it prefills on next visit
                        selectedPharmacyType?.let {
                            preferenceHelper.savePharmacyType(it.code)
                        }

                        // Update terminal if it has changed
                        if (selectedTerminal != null && selectedTerminal?.terminalId != initialTerminal?.terminalId) {
                            val terminalId = selectedTerminal?.terminalId
                            if (terminalId != null) {
                                logger.i("Terminal changed from ${initialTerminal?.terminalName} to ${selectedTerminal?.terminalName}, updating...")

                                val terminalRequest = TerminalUpdateRequest(
                                    terminalName = selectedTerminal?.terminalName ?: "Unknown",
                                    isActive = true
                                )

                                terminalRepository.updateTerminal(terminalId, terminalRequest)
                                    .onSuccess { _ ->
                                        logger.i("Terminal ${selectedTerminal?.terminalName} updated successfully")

                                        // Update local terminals list - mark selected as active, others as inactive
                                        terminals = terminals.map { t ->
                                            t.copy(isActive = t.terminalId == terminalId)
                                        }

                                        // Save updated terminal selection to preferences
                                        preferenceHelper.saveSelectedTerminalId(terminalId)
                                        preferenceHelper.saveSelectedTerminalName(selectedTerminal?.terminalName ?: "Unknown")
                                        preferenceHelper.saveTerminals(terminals)

                                        // Update initial terminal to current selection
                                        initialTerminal = selectedTerminal

                                        // Update HL7 service with new terminal name and rebroadcast NSD
                                        updateHl7ConfigWithNewTerminal(selectedTerminal?.terminalName ?: "Unknown")
                                    }
                                    .onFailure { e ->
                                        logger.e("Failed to update terminal ${selectedTerminal?.terminalName}", e)
                                        // Don't fail the entire profile update if terminal update fails
                                    }
                            }
                        } else {
                            logger.i("Terminal unchanged, skipping terminal update API call")
                        }

                        _updateUiState.value = ProfileUpdateUiState.Success
                    }
                    .onFailure { e ->
                        logger.e("Profile update failed", e)
                        _updateUiState.value =
                            ProfileUpdateUiState.Error(getFriendlyErrorMessage(e))
                    }
            }
        }

        fun deleteProfile() {
            // Check internet before delete
            if (!NetworkUtils.isNetworkAvailable(context)) {
                _deleteUiState.value =
                    ProfileDeleteUiState.Error(context.getString(R.string.error_no_internet))
                return
            }

            viewModelScope.launch {
                _deleteUiState.value = ProfileDeleteUiState.Loading

                repository.deleteProfile()
                    .onSuccess {
                        logger.i("Profile delete success")
                        _deleteUiState.value = ProfileDeleteUiState.Success
                    }
                    .onFailure { e ->
                        logger.e("Profile delete failed", e)
                        _deleteUiState.value =
                            ProfileDeleteUiState.Error(getFriendlyErrorMessage(e))
                    }
            }
        }

    // ─────────────────────────── Friendly Error Mapping ───────────────────────────
    private fun getFriendlyErrorMessage(exception: Throwable): String {
        return when (exception) {
            is HttpException -> {
                val errorBody = exception.response()?.errorBody()?.string()
                val parsedMessage = errorBody?.let {
                    try {
                        val errorResponse = Gson().fromJson(it, ErrorResponse::class.java)
                        errorResponse.message
                    } catch (e: Exception) {
                        logger.e("Failed to parse error response", e)
                        null
                    }
                }
                when (exception.code()) {
                    400 -> parsedMessage ?: context.getString(R.string.error_invalid_input)
                    401 -> context.getString(R.string.error_unauthorized)
                    404 -> context.getString(R.string.error_not_found)
                    500 -> context.getString(R.string.error_server_unavailable)
                    else -> parsedMessage ?: context.getString(R.string.error_generic)
                }
            }

            is UnknownHostException -> context.getString(R.string.error_no_internet)
            is SocketTimeoutException -> context.getString(R.string.error_timeout)
            else -> exception.message?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.error_generic)
        }
    }

    // ─────────────────────────── State Reset ───────────────────────────
    fun resetUpdateState() {
        _updateUiState.value = ProfileUpdateUiState.Idle
    }

    fun resetDeleteState() {
        _deleteUiState.value = ProfileDeleteUiState.Idle
    }

    // ─────────────────────────── HL7 Config Update ───────────────────────────
    /**
     * Updates the HL7 service configuration with the new terminal name
     * and triggers NSD rebroadcast.
     */
    private fun updateHl7ConfigWithNewTerminal(terminalName: String) {
        // Check if HL7 is enabled and user is logged in
        if (!preferenceHelper.isHl7Enabled() || !preferenceHelper.isUserLoggedIn()) {
            return
        }

        val broadCastServiceName = preferenceHelper.getNsdBroadcastType()
        val discoverServiceName = preferenceHelper.getNsdDiscoveryType()

        if (broadCastServiceName.isEmpty() || discoverServiceName.isEmpty()) {
            return
        }

        val config = HL7Config(
            serverPort = 2575,
            autoResponseDelayMs = 10_000L,
            nsdBroadcastServiceName = terminalName,
            nsdBroadcastType = broadCastServiceName,
            nsdDiscoveryType = discoverServiceName,
            imageServicePort = 8080,
            imageServiceSecurePort = 8443,
            hl7Version = preferenceHelper.getHl7Version(),
            bypassTls = preferenceHelper.isBypassTlsEnabled(),
            useStaticPmsConnection = preferenceHelper.isUseStaticPmsConnection(),
            pmsIp = preferenceHelper.getPmsIP(),
            pmsPort = preferenceHelper.getPmsPort(),
        )

        hl7ServiceManager.updateConfigAndRebroadcast(config)
    }
}
