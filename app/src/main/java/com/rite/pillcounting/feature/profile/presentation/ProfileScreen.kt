package com.rite.pillcounting.feature.profile.presentation

import Screen
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.PhoneNumberVisualTransformation
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.FloatingLabelTextField
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.LoadingIndicator
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import com.rite.pillcounting.feature.profile.domain.model.ProfileDeleteUiState
import com.rite.pillcounting.feature.profile.domain.model.ProfileField
import com.rite.pillcounting.feature.profile.domain.model.ProfileUpdateUiState
import com.rite.pillcounting.feature.profile.presentation.viewmodel.ProfileViewModel
import com.rite.pillcounting.navigation.AUTH_GRAPH_ROUTE
import com.rite.pillcounting.ui.theme.AppTheme
import kotlinx.coroutines.delay
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/** Delay before a [LabeledDropdown] search query is applied to the filtered list. */
private const val SEARCH_DEBOUNCE_MS = 300L

@Composable
fun ProfileScreen(
    navController: NavController,
    viewModel: ProfileViewModel = hiltViewModel(),
    fromRoute: String? = navController.previousBackStackEntry?.destination?.route
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val focusManager = LocalFocusManager.current

    // Observe states
    val updateUiState by viewModel.updateUiState.collectAsState()
    val deleteUiState by viewModel.deleteUiState.collectAsState()

    // Local state for delete confirmation
    var showDeleteDialog by remember { mutableStateOf(false) }
    val countryFocusRequester = remember { FocusRequester() }
    val stateFocusRequester = remember { FocusRequester() }

    // Determine navigation type
    val cameFromDashboard =
        fromRoute?.contains(Screen.Dashboard.route, ignoreCase = true) == true
    val context = LocalContext.current
    // First-login flow (came from the dashboard prompt): whether the profile is
    // saved or skipped, offer face enrollment next. Profile is popped so the
    // intro sits directly above the dashboard.
    val navigateToFaceIntro: () -> Unit = {
        navController.navigate(Screen.FaceIntro.route) {
            popUpTo(Screen.Dashboard.route)
            launchSingleTop = true
        }
    }

    // Back handling for system back press
    BackHandler {
        if (cameFromDashboard) {
            navigateToFaceIntro()
        } else {
            navController.popBackStack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.primaryBackground)
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                BackButton(navController) {
                    // BackButton click behavior
                    if (cameFromDashboard) {
                        navigateToFaceIntro()
                    } else {
                        navController.popBackStack()
                    }
                }
                Text(
                    text = stringResource(R.string.profile_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppTheme.extendedColors.textColor,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(48.dp))
            }

            // -------------------- INPUT FIELDS --------------------
            ResponsiveProfileFields(
                isLandscape = isLandscape,
                viewModel = viewModel,
                countryFocusRequester = countryFocusRequester,
                stateFocusRequester = stateFocusRequester
            )

            Spacer(Modifier.height(10.dp))

            // Checkbox
            //need to discuss this feature then after remove this commented code or keep it
//            if (fromRoute?.contains(Screen.Dashboard.route, ignoreCase = true) == true &&
//                updateUiState !is ProfileUpdateUiState.Loading &&
//                updateUiState !is ProfileUpdateUiState.Success) {
//                Row(
//                    verticalAlignment = Alignment.CenterVertically,
//                    modifier = Modifier.padding(horizontal = 16.dp)
//                ) {
//                    Checkbox(
//                        checked = viewModel.doNotAskAgain,
//                        onCheckedChange = { checked -> viewModel.toggleDoNotAskAgain(checked) }
//                    )
//                    Text(
//                        stringResource(R.string.do_not_ask),
//                        color = AppTheme.extendedColors.textColor
//                    )
//                }
//
//                Spacer(Modifier.height(20.dp))
//            }

            // -------------------- STATE FEEDBACK --------------------
            when (updateUiState) {
                is ProfileUpdateUiState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 16.dp)
                    ) {
                        LoadingIndicator()
                    }
                }

                is ProfileUpdateUiState.Success -> {
                    showToast(
                        context = context,
                        message = stringResource(R.string.profile_updated_successfully),
                        duration = Toast.LENGTH_SHORT
                    )
                    LaunchedEffect(updateUiState) {
                        if (cameFromDashboard) {
                            navigateToFaceIntro()
                        } else {
                            navController.navigate(Screen.Menu.route) {
                                popUpTo(Screen.Menu.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                        viewModel.resetUpdateState()
                    }
                }


                is ProfileUpdateUiState.Error -> {
                    Text(
                        text = (updateUiState as ProfileUpdateUiState.Error).message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(start = 16.dp)
                    )
                }

                else -> {}
            }

            when (deleteUiState) {
                is ProfileDeleteUiState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 16.dp)
                    ) {
                        LoadingIndicator()
                    }
                }

                is ProfileDeleteUiState.Success -> {
                    LaunchedEffect(Unit) {
                        navController.navigate(AUTH_GRAPH_ROUTE) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }

                is ProfileDeleteUiState.Error -> {
                    Text(
                        text = (deleteUiState as ProfileDeleteUiState.Error).message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(start = 16.dp)
                    )
                }

                else -> {}
            }

            Spacer(Modifier.weight(1f))

            // -------------------- BUTTONS --------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
            ) {
                HollowButton(
                    text = stringResource(R.string.delete),
                    onClick = { showDeleteDialog = true },
                    color = MaterialTheme.colorScheme.primary,
                )
                ActionButtonPrimary(
                    text = stringResource(R.string.save),
                    onClick = {
                        viewModel.updateProfile()
                        if (viewModel.countryError != null) {
                            countryFocusRequester.requestFocus()
                        } else if (viewModel.stateError != null) {
                            stateFocusRequester.requestFocus()
                        }
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    // -------------------- DELETE CONFIRMATION DIALOG --------------------
    if (showDeleteDialog) {
        CommonDialog(
            title = stringResource(R.string.confirm_delete_title),
            message = stringResource(R.string.confirm_delete_profile),
            confirmText = stringResource(R.string.delete),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteProfile()
            },
            onCancel = { showDeleteDialog = false }
        )
    }
}

/**
 * Reusable profile field with validation error shown under it.
 */
@Composable
private fun ProfileTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    error: String?,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    maxLength: Int? = null,
    paddingStart: androidx.compose.ui.unit.Dp = 20.dp,
    paddingEnd: androidx.compose.ui.unit.Dp = 20.dp,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = paddingStart, end = paddingEnd, top = 8.dp, bottom = 8.dp)
    ) {
        FloatingLabelTextField(
            value = value,
            onValueChange = {
                if (!readOnly) {
                    var input = it
                    if (keyboardType == KeyboardType.Phone) {
                        input = input.filter { char -> char.isDigit() }
                    }
                    onValueChange(input)
                }
            },
            label = label,
            keyboardType = keyboardType,
            imeAction = imeAction,
            visualTransformation = if (keyboardType == KeyboardType.Phone)
                PhoneNumberVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            enabled = !readOnly,
            maxLength = maxLength
        )
        if (!error.isNullOrEmpty()) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}


@Composable
private fun ResponsiveProfileFields(
    isLandscape: Boolean,
    viewModel: ProfileViewModel,
    countryFocusRequester: FocusRequester,
    stateFocusRequester: FocusRequester
) {
    val fields = listOf(
        ProfileField(
            viewModel.firstName,
            { v -> viewModel.onFirstNameChanged(v) },
            R.string.first_name,
            viewModel.firstNameError
        ),

        ProfileField(
            viewModel.lastName,
            { v -> viewModel.onLastNameChanged(v) },
            R.string.last_name,
            viewModel.lastNameError
        ),

        ProfileField(
            viewModel.pharmacyName,
            { v -> viewModel.onPharmacyNameChanged(v) },
            R.string.pharmacy_name,
            viewModel.pharmacyNameError
        ),
        ProfileField(
            viewModel.phoneNumber,
            { v -> viewModel.onPhoneChanged(v) },
            R.string.phone_number,
            viewModel.phoneError,
            keyboardType = KeyboardType.Phone,
            maxLength = 10
        ),
        ProfileField(
            viewModel.email,
            { _ -> },
            R.string.email,
            viewModel.emailError,
            readOnly = true
        ), // <— Email read-only
        ProfileField(
            viewModel.npi,
            { v -> viewModel.npi = v },
            R.string.npi_number,
            viewModel.npiError,
            keyboardType = KeyboardType.Number,
            maxLength = 10
        )
    )

    if (isLandscape) {
        for (i in fields.indices step 2) {
            Row(modifier = Modifier.fillMaxWidth()) {
                val field1 = fields[i]
                ProfileTextField(
                    value = field1.value,
                    onValueChange = field1.onChange,
                    label = stringResource(field1.labelRes),
                    keyboardType = field1.keyboardType,
                    error = field1.error?.let { stringResource(it) },
                    modifier = Modifier.weight(1f),
                    readOnly = field1.readOnly,
                    maxLength = field1.maxLength,
                    paddingStart = 20.dp,
                    paddingEnd = 10.dp
                )

                if (i + 1 < fields.size) {
                    val field2 = fields[i + 1]
                    ProfileTextField(
                        value = field2.value,
                        onValueChange = field2.onChange,
                        label = stringResource(field2.labelRes),
                        keyboardType = field2.keyboardType,
                        error = field2.error?.let { stringResource(it) },
                        modifier = Modifier.weight(1f),
                        readOnly = field2.readOnly,
                        maxLength = field2.maxLength,
                        paddingStart = 10.dp,
                        paddingEnd = 20.dp
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

    } else {
        fields.forEach { field ->
            ProfileTextField(
                value = field.value,
                onValueChange = field.onChange,
                label = stringResource(field.labelRes),
                keyboardType = field.keyboardType,
                error = field.error?.let { stringResource(it) },
                readOnly = field.readOnly,
                maxLength = field.maxLength
            )
        }
    }

    // Terminal and Pharmacy Type dropdowns: side by side in landscape, each on
    // its own row in portrait.
    val context = LocalContext.current
    val showTerminalDropdown = viewModel.terminals.isNotEmpty()

    val terminalDropdown: @Composable (Modifier) -> Unit = { modifier ->
        val hl7Enabled = viewModel.isHl7Enabled()
        LabeledDropdown(
            label = stringResource(R.string.terminal),
            selectedText = viewModel.selectedTerminal?.terminalName,
            placeholder = stringResource(R.string.select_terminal),
            items = viewModel.terminals,
            itemLabel = { it.terminalName ?: stringResource(R.string.unknown) },
            isSelected = { it.terminalId == viewModel.selectedTerminal?.terminalId },
            onItemSelected = { viewModel.onTerminalSelected(it) },
            enabled = hl7Enabled,
            onDisabledClick = { showToast(context, R.string.enable_hl7_from_portal_toast) },
            modifier = modifier
        )
    }

    val pharmacyTypeDropdown: @Composable (Modifier) -> Unit = { modifier ->
        LabeledDropdown(
            label = stringResource(R.string.pharmacy_type),
            selectedText = viewModel.selectedPharmacyType?.label,
            placeholder = stringResource(R.string.select_pharmacy_type),
            items = viewModel.pharmacyTypes,
            itemLabel = { it.label },
            isSelected = { it == viewModel.selectedPharmacyType },
            onItemSelected = { viewModel.onPharmacyTypeSelected(it) },
            modifier = modifier
        )
    }

    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (showTerminalDropdown) {
                terminalDropdown(Modifier.weight(1f))
            }
            pharmacyTypeDropdown(Modifier.weight(1f))
        }
    } else {
        if (showTerminalDropdown) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                terminalDropdown(Modifier.fillMaxWidth())
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            pharmacyTypeDropdown(Modifier.fillMaxWidth())
        }
    }

    // Country dropdown.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LabeledDropdown(
            label = stringResource(R.string.country),
            selectedText = viewModel.selectedCountry?.let {
                stringResource(R.string.code_name_format, it.code.orEmpty(), it.name.orEmpty())
            },
            placeholder = stringResource(R.string.select_country),
            items = viewModel.countries,
            itemLabel = { stringResource(R.string.code_name_format, it.code.orEmpty(), it.name.orEmpty()) },
            isSelected = { it.code == viewModel.selectedCountry?.code },
            onItemSelected = { viewModel.onCountrySelected(it) },
            modifier = Modifier.weight(1f),
            isSearchable = true,
            error = viewModel.countryError?.let { stringResource(it) },
            focusRequester = countryFocusRequester,
            onQueryChanged = { viewModel.onCountryQueryChanged(it) }
        )
        LabeledDropdown(
            label = stringResource(R.string.state),
            selectedText = viewModel.selectedState?.let {
                stringResource(R.string.code_name_format, it.code.orEmpty(), it.name.orEmpty())
            },
            placeholder = stringResource(R.string.select_state),
            items = viewModel.states,
            itemLabel = { stringResource(R.string.code_name_format, it.code.orEmpty(), it.name.orEmpty()) },
            isSelected = { it.code == viewModel.selectedState?.code },
            onItemSelected = { viewModel.onStateSelected(it) },
            modifier = Modifier.weight(1f),
            isSearchable = true,
            error = viewModel.stateError?.let { stringResource(it) },
            focusRequester = stateFocusRequester,
            onQueryChanged = { viewModel.onStateQueryChanged(it) }
        )
    }

}


/**
 * Reusable labeled dropdown used for selecting an item from a list.
 *
 * Shows [label] as a floating caption and [selectedText] (or [placeholder] when
 * nothing is selected) as the value. When [enabled] is false the field is dimmed,
 * never expands, and a tap routes to [onDisabledClick] instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> LabeledDropdown(
    label: String,
    selectedText: String?,
    placeholder: String,
    items: List<T>,
    itemLabel: @Composable (T) -> String,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    isSelected: (T) -> Boolean = { false },
    enabled: Boolean = true,
    onDisabledClick: () -> Unit = {},
    isSearchable: Boolean = false,
    error: String? = null,
    focusRequester: FocusRequester? = null,
    onQueryChanged: (String) -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf(selectedText ?: "") }
    var debouncedQuery by remember { mutableStateOf("") }

    LaunchedEffect(searchQuery) {
        delay(SEARCH_DEBOUNCE_MS)
        debouncedQuery = searchQuery
    }

    // Single source of truth for searchQuery/debouncedQuery once collapsed —
    // reruns on either the dropdown closing or the underlying selection changing.
    LaunchedEffect(expanded, selectedText) {
        if (!expanded) {
            searchQuery = selectedText ?: ""
            debouncedQuery = ""
        } else {
            searchQuery = ""
        }
    }

    val contentAlpha = if (enabled) 1f else 0.4f
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(10.dp)
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded && enabled) 180f else 0f,
        label = "dropdownArrow"
    )

    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded && enabled,
            onExpandedChange = {
                if (enabled) expanded = !expanded else onDisabledClick()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
        val anchorModifier = Modifier
            .fillMaxWidth()
            .height(AppTheme.dimens.profileTextFieldHeight)
            .clip(shape)
            .background(AppTheme.extendedColors.inputBackground)
            .border(
                width = if (expanded && enabled) 1.5.dp else 1.dp,
                color = if (expanded && enabled) accent
                else AppTheme.extendedColors.textColor.copy(alpha = 0.12f),
                shape = shape
            )
            .padding(horizontal = 15.dp)

        if (isSearchable && enabled) {
            BasicTextField(
                value = searchQuery,
                onValueChange = {
                    // Not filtered: this is a filter box, not a saved name, and
                    // labels like "St. Kitts" need the punctuation to match.
                    searchQuery = it
                    expanded = true
                    onQueryChanged(it)
                },
                singleLine = true,
                textStyle = TextStyle(
                    color = AppTheme.extendedColors.textColor,
                    fontSize = 16.sp,
                ),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryEditable, true)
                    .then(anchorModifier)
                    .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
                    .onFocusChanged { if (it.isFocused) expanded = true },
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = label,
                            color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(top = 6.dp),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(bottom = 10.dp, end = 28.dp),
                        ) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    color = AppTheme.extendedColors.textColor.copy(alpha = 0.4f),
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = AppTheme.extendedColors.textColor,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .rotate(arrowRotation),
                        )
                    }
                }
            )
        } else {
            Box(
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                    .then(anchorModifier),
            ) {
                Text(
                    text = label,
                    color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f * contentAlpha),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 6.dp),
                )
                Text(
                    text = selectedText ?: placeholder,
                    color = if (selectedText == null)
                        AppTheme.extendedColors.textColor.copy(alpha = 0.4f * contentAlpha)
                    else AppTheme.extendedColors.textColor.copy(alpha = contentAlpha),
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 10.dp, end = 28.dp),
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = AppTheme.extendedColors.textColor.copy(alpha = contentAlpha),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .rotate(arrowRotation),
                )
            }
        }

        val itemLabels = items.map { itemLabel(it) }
        val filteredItems = remember(items, debouncedQuery, itemLabels) {
            if (isSearchable && debouncedQuery.isNotBlank()) {
                items.zip(itemLabels)
                    .filter { (_, label) -> label.contains(debouncedQuery, ignoreCase = true) }
                    .map { (item, _) -> item }
            } else {
                items
            }
        }

        // Custom-styled menu: white rounded surface, compact rows, selected highlight.
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            containerColor = AppTheme.extendedColors.inputBackground,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.heightIn(max = 280.dp),
        ) {
            filteredItems.forEachIndexed { index, item ->
                val selected = isSelected(item)
                DropdownMenuItem(
                    text = {
                        Text(
                            text = itemLabel(item),
                            color = if (selected) accent else AppTheme.extendedColors.textColor,
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    trailingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = accent,
                            )
                        }
                    } else null,
                    onClick = {
                        onItemSelected(item)
                        expanded = false
                        if (isSearchable) {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                )
                if (index < filteredItems.lastIndex) {
                    HorizontalDivider(
                        color = AppTheme.extendedColors.textColor.copy(alpha = 0.08f),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }
        if (!error.isNullOrEmpty()) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}
