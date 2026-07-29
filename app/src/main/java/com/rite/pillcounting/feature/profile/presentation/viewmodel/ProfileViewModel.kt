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
import com.rite.pillcounting.feature.profile.domain.model.PharmacyType
import com.rite.pillcounting.feature.profile.domain.model.ProfileDeleteUiState
import com.rite.pillcounting.feature.profile.domain.model.ProfileUpdateRequest
import com.rite.pillcounting.feature.profile.domain.model.ProfileUpdateUiState
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
    val pharmacyTypes: List<PharmacyType> = PharmacyType.entries
    var selectedPharmacyType by mutableStateOf<PharmacyType?>(null)

    // ─────────────────────────── Validation Errors ───────────────────────────
    var firstNameError by mutableStateOf<Int?>(null)
    var lastNameError by mutableStateOf<Int?>(null)
    var pharmacyNameError by mutableStateOf<Int?>(null)
    var phoneError by mutableStateOf<Int?>(null)
    var emailError by mutableStateOf<Int?>(null)
    var npiError by mutableStateOf<Int?>(null)

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

        // Restore previously selected pharmacy type
        selectedPharmacyType = PharmacyType.fromApiValue(preferenceHelper.getPharmacyType())
        logger.i("Loaded pharmacy type: ${selectedPharmacyType?.apiValue}")
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
                }
            }
        }
    }
    
    fun onTerminalSelected(terminal: Terminal) {
        selectedTerminal = terminal
        logger.i("Terminal selected: ${terminal.terminalName} (ID: ${terminal.terminalId})")
    }

    fun onPharmacyTypeSelected(pharmacyType: PharmacyType) {
        selectedPharmacyType = pharmacyType
        logger.i("Pharmacy type selected: ${pharmacyType.apiValue}")
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

        return listOf(
            firstNameError, lastNameError, pharmacyNameError,
            phoneError, emailError, npiError
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
                    terminalId = selectedTerminal?.terminalId,
                    pharmacyType = selectedPharmacyType?.apiValue
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
                            preferenceHelper.savePharmacyType(it.apiValue)
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

                                viewModelScope.launch {
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
