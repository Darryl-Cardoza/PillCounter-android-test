package com.rite.pillcounting.core.utils.common

import Screen
import android.content.Context
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.constants.Dimens.buttonCornerRadius
import com.rite.pillcounting.core.utils.constants.Dimens.buttonHeight
import com.rite.pillcounting.core.utils.constants.Dimens.extraSmall
import com.rite.pillcounting.core.utils.constants.Dimens.small
import com.rite.pillcounting.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Formats raw digit input as US phone number: (XXX) XXX-XXXX.
 * Stores and receives raw digits; the display transformation is visual-only.
 */
class PhoneNumberVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val formatted = buildString {
            digits.forEachIndexed { i, c ->
                when (i) {
                    0 -> append("($c")
                    3 -> append(") $c")
                    6 -> append("-$c")
                    else -> append(c)
                }
            }
        }

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = when {
                offset == 0 -> 0
                offset <= 3 -> offset + 1   // after '('
                offset <= 6 -> offset + 3   // after '(XXX) '
                else -> offset + 4          // after '(XXX) XXX-'
            }.coerceAtMost(formatted.length)

            override fun transformedToOriginal(offset: Int): Int = when {
                offset <= 1 -> 0
                offset <= 4 -> offset - 1
                offset <= 6 -> 3            // inside ') ' — snap to after digit 3
                offset <= 9 -> offset - 3
                offset == 10 -> 6           // on '-' — snap to after digit 6
                else -> offset - 4
            }.coerceIn(0, digits.length)
        }

        return TransformedText(AnnotatedString(formatted), offsetMapping)
    }
}

/**
 * **UserInterfaceUtils**
 *
 * Provides reusable UI helper utilities and lightweight Composables for:
 * - Common dialogs
 * - Toasts
 * - Text fields
 * - App branding info blocks
 * - Date/time formatting utilities
 *
 * Centralizes shared UI logic to maintain consistent look and behavior across screens.
 */
object UserInterfaceUtils {

    // ───────────────────────────── Toast Helpers ─────────────────────────────

    /** Displays a short Toast with plain text. */
    fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(context, message, duration).show()
    }

    /** Displays a short Toast using a string resource ID. */
    fun showToast(context: Context, @StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(context, context.getString(resId), duration).show()
    }

    // ───────────────────────────── Color Extensions ─────────────────────────────

    /** Converts a hex color string (e.g. `#FF5733`) to a Compose [Color]. */
    fun String.toColor(): Color = Color(this.toColorInt())

    // ───────────────────────────── Date/Time Formatting ─────────────────────────────

    /** Formats a nullable epoch millis timestamp into `dd-MM-yyyy hh:mm a` format. */
    fun Long?.toFormattedDate(): String {
        return if (this != null && this > 0) {
            try {
                val formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm a")
                    .withLocale(Locale.getDefault())
                    .withZone(ZoneId.systemDefault())
                formatter.format(Instant.ofEpochMilli(this))
            } catch (e: Exception) {
                "-"
            }
        } else "-"
    }

    /** Converts a timestamp to a formatted date string. Default: `"dd MMM yyyy"`. */
    fun Long.toDateString(pattern: String = "dd MMM yyyy"): String {
        val formatter = SimpleDateFormat(pattern, Locale.getDefault())
        return formatter.format(Date(this))
    }

    /** Converts a timestamp to a formatted time string. Default: `"hh:mm a"`. */
    fun Long.toTimeString(pattern: String = "hh:mm a"): String {
        val formatter = SimpleDateFormat(pattern, Locale.getDefault())
        return formatter.format(Date(this))
    }

    // ───────────────────────────── Security Dialog ─────────────────────────────

    /**
     * Displays a blocking dialog listing detected runtime security violations.
     * Calls [HelperFunctions.exitApp] on confirm or cancel.
     */
    @Composable
    fun SecurityErrorDialog(violations: List<String>) {
        val title = stringResource(R.string.security_alert_title)
        val confirmText = stringResource(R.string.exit_app)
        val cancelText = stringResource(R.string.close_app)

        val message = buildString {
            append(stringResource(R.string.security_violation_intro))
            append("\n\n")
            violations.forEach { append("• $it\n") }
        }

        CommonDialog(
            title = title,
            message = message,
            confirmText = confirmText,
            cancelText = cancelText,
            onConfirm = { HelperFunctions.exitApp() },
            onCancel = { HelperFunctions.exitApp() }
        )
    }

    // ───────────────────────────── Static Info Blocks ─────────────────────────────

    /** Displays app logo, title, and version in a vertically centered column. */
    @Composable
    fun AppInfo() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {

                Icon(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = stringResource(R.string.app_name),
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(responsiveDp(100.dp))
                )

                Spacer(modifier = Modifier.height(responsiveDp(20.dp)))

                Text(
                    text = stringResource(R.string.pill_count_app_title),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.weight(1f))


            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.rite_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.extendedColors.textColor
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "${stringResource(R.string.version)} ${stringResource(R.string.app_version_name)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.extendedColors.textColor
                )
            }
        }
    }


    // ───────────────────────────── Text Fields ─────────────────────────────

    /** A simple text input with rounded corners and adaptive background. */
    @Composable
    fun AppTextField(
        value: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier = Modifier,
        cornerRadius: Dp = 8.dp,
        height: Dp = 56.dp,
        cursorColor: Color = AppTheme.extendedColors.textColor,
        keyboardType: KeyboardType = KeyboardType.Text,
        imeAction: ImeAction = ImeAction.Done,
        visualTransformation: VisualTransformation = VisualTransformation.None
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height)
                .background(
                    AppTheme.extendedColors.inputBackground,
                    RoundedCornerShape(cornerRadius)
                )
                .padding(horizontal = 15.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                cursorBrush = SolidColor(cursorColor),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    keyboardType = keyboardType,
                    imeAction = imeAction
                ),
                textStyle = LocalTextStyle.current.copy(color = AppTheme.extendedColors.textColor),
                visualTransformation = visualTransformation
            )
        }
    }

    /**
     * Text field with an embedded icon, optional password toggle, and trailing action.
     */
    @Composable
    fun DrawableIconTextField(
        value: String,
        onValueChange: (String) -> Unit,
        placeholder: String,
        @DrawableRes iconRes: Int,
        iconColor: Color = MaterialTheme.colorScheme.primary,
        modifier: Modifier = Modifier,
        cornerRadius: Dp = 8.dp,
        height: Dp = 56.dp,
        cursorColor: Color = AppTheme.extendedColors.textColor,
        isPassword: Boolean = false,
        keyboardType: KeyboardType = KeyboardType.Text,
        imeAction: ImeAction = ImeAction.Done,
        trailingIcon: (@Composable (() -> Unit))? = null
    ) {
        var passwordVisible by remember { mutableStateOf(!isPassword) }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(responsiveDp(height))
                .background(
                    AppTheme.extendedColors.inputBackground,
                    RoundedCornerShape(cornerRadius)
                )
                .padding(horizontal = responsiveDp(15.dp)),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(responsiveDp(30.dp))
                )

                Spacer(modifier = Modifier.width(15.dp))
                Box(
                    modifier = Modifier
                        .width(0.5.dp)
                        .fillMaxHeight()
                        .background(Color.Gray)
                )
                Spacer(modifier = Modifier.width(15.dp))

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    cursorBrush = SolidColor(cursorColor),
                    singleLine = true,
                    visualTransformation = if (isPassword && !passwordVisible)
                        PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = imeAction
                    ),
                    textStyle = LocalTextStyle.current.copy(color = AppTheme.extendedColors.textColor),
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier.fillMaxHeight(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (value.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    color = AppTheme.extendedColors.textColor.copy(alpha = 0.6f)
                                )
                            }
                            inner()
                        }
                    }
                )

                if (isPassword) {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible)
                                Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                trailingIcon?.invoke()
            }
        }
    }

    // ───────────────────────────── Buttons & Dialogs ─────────────────────────────

    /** Displays a simple back button that navigates up or runs a custom action. */
    @Composable
    fun BackButton(
        navController: NavController,
        modifier: Modifier = Modifier,
        backIcon: Int = R.drawable.back,
        showBox: Boolean = false,
        onClick: (() -> Unit)? = null
    ) {

        val clickAction = { onClick?.invoke() ?: navController.popBackStack() }

        if (showBox) {
            // ---- Circle background version ----
            Box(
                modifier = modifier
                    .padding(small)
                    .size(responsiveDp(40.dp))
                    .background(Color.White, CircleShape)
                    .clickable { clickAction() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = backIcon),
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(responsiveDp(25.dp))
                )
            }
        } else {
            // ---- Normal version without background ----
            IconButton(
                onClick = { onClick?.invoke() ?: navController.popBackStack() },
                modifier = modifier
                    .padding(small)
                    .size(responsiveDp(40.dp))
            ) {
                Icon(
                    painter = painterResource(id = backIcon),
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(extraSmall)
                )
            }
        }
    }


    /** Displays a confirm/cancel dialog with customizable buttons and title. */
    @Composable
    fun CommonDialog(
        message: String,
        confirmText: String,
        cancelText: String,
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
        title: String? = null,
        shape: RoundedCornerShape = RoundedCornerShape(12.dp),
        isSingleButton: Boolean = false // Flag to control single button
    ) {
        AlertDialog(
            onDismissRequest = {},
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    title?.let {
                        Text(
                            text = it,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.extendedColors.textColor,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )
                    }
                    Text(
                        text = message,
                        fontSize = 16.sp,
                        color = AppTheme.extendedColors.textColor,
                        textAlign = TextAlign.Center
                    )
                }
            },
            shape = shape,
            containerColor = AppTheme.extendedColors.primaryBackground,
            confirmButton = {
                if (!isSingleButton) {
                    // Show both Cancel and Confirm buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        HollowButton(
                            text = cancelText.uppercase(),
                            onClick = onCancel,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        ActionButtonPrimary(
                            text = confirmText.uppercase(),
                            onClick = onConfirm,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    // Show only the Confirm button and center it using Box
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        ActionButtonPrimary(
                            text = confirmText.uppercase(),
                            onClick = onConfirm,
                            modifier = Modifier.fillMaxWidth(0.5f)
                        )
                    }
                }
            }
        )
    }

    /** Displays a single-selection dialog with radio buttons and confirm/cancel actions. */
    @Composable
    fun CommonSingleSelectDialog(
        title: String,
        options: List<String>,
        selectedIndex: Int? = null,
        onCancel: () -> Unit,
        onOk: (Int) -> Unit,
        distanceBetweenOptions: Dp = 8.dp,
    ) {
        var currentSelection by remember { mutableStateOf(selectedIndex) }

        AlertDialog(
            onDismissRequest = {},
            shape = RoundedCornerShape(12.dp),
            containerColor = AppTheme.extendedColors.primaryBackground,
            text = {
                Column {
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.extendedColors.textColor,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    options.forEachIndexed { index, option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentSelection = index }
                                .padding(vertical = distanceBetweenOptions)
                        ) {
                            RadioButton(
                                selected = currentSelection == index,
                                onClick = { currentSelection = index },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            Spacer(modifier = Modifier.width(small))
                            Text(
                                text = option,
                                fontSize = 16.sp,
                                color = AppTheme.extendedColors.textColor
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    HollowButton(
                        text = stringResource(R.string.cancel).uppercase(),
                        onClick = onCancel,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    ActionButtonPrimary(
                        text = stringResource(R.string.ok).uppercase(),
                        onClick = { currentSelection?.let { onOk(it) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        )
    }

    /** A reusable filled button with customizable color, height, and rounded corners. */
    @Composable
    fun FilledButton(
        text: String,
        onClick: () -> Unit,
        color: Color,
        modifier: Modifier = Modifier,
        buttonHeightDefault: Dp = buttonHeight,
    ) {
        Button(
            onClick = onClick,
            modifier = modifier
                .height(buttonHeightDefault)
                .widthIn(min = 100.dp)
                .border(
                    width = 1.dp,
                    color = color,
                    shape = RoundedCornerShape(buttonCornerRadius)
                ),
            colors = ButtonDefaults.buttonColors(
                containerColor = color,
                contentColor = Color.White,
            ),
        ) {
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp
            )
        }
    }

    /**
     * A custom text input composable with a floating label, optional password visibility toggle,
     * and support for IME actions (Next / Done).
     */
    @Composable
    fun FloatingLabelTextField(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        modifier: Modifier = Modifier,
        cornerRadius: Dp = 8.dp,
        height: Dp = 62.dp,
        cursorColor: Color = AppTheme.extendedColors.textColor,
        isPassword: Boolean = false,
        visualTransformation: VisualTransformation = VisualTransformation.None,
        keyboardType: KeyboardType = KeyboardType.Text,
        imeAction: ImeAction = ImeAction.Done,
        onImeAction: (() -> Unit)? = null,
        enabled: Boolean = true
    ) {
        val focusManager = LocalFocusManager.current
        var passwordVisible by remember { mutableStateOf(!isPassword) }
        var isFocused by remember { mutableStateOf(false) }

        // Internal TextFieldValue lets us move the cursor to end when focus arrives
        var textFieldValue by remember { mutableStateOf(TextFieldValue(value)) }

        // Keep internal state in sync when the caller updates the value externally
        LaunchedEffect(value) {
            if (textFieldValue.text != value) {
                textFieldValue = textFieldValue.copy(text = value)
            }
        }

        val isActive = isFocused || value.isNotEmpty()
        val horizontalPadding = 15.dp

        // Label moves from vertical center (empty) to top-inside (active)
        val labelOffsetY by animateDpAsState(
            targetValue = if (isActive) 6.dp else 20.dp,
            label = "labelOffsetY"
        )
        val labelFontSize by animateFloatAsState(
            targetValue = if (isActive) 12f else 16f,
            label = "labelFontSize"
        )

        // Single container — label and input both live inside the dark box
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height)
                .background(AppTheme.extendedColors.inputBackground, RoundedCornerShape(cornerRadius))
                .padding(horizontal = horizontalPadding)
        ) {
            // Label inside the box
            Text(
                text = label,
                color = if (isFocused) MaterialTheme.colorScheme.primary
                else AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
                fontSize = labelFontSize.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = labelOffsetY)
            )

            // Input text at the bottom portion of the box
            BasicTextField(
                value = textFieldValue,
                onValueChange = { newValue ->
                    textFieldValue = newValue
                    onValueChange(newValue.text)
                },
                singleLine = true,
                enabled = enabled,
                readOnly = !enabled,
                textStyle = LocalTextStyle.current.copy(
                    color = AppTheme.extendedColors.textColor,
                    fontSize = 16.sp
                ),
                visualTransformation = when {
                    isPassword && !passwordVisible -> PasswordVisualTransformation()
                    else -> visualTransformation
                },
                cursorBrush = SolidColor(cursorColor),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Next) },
                    onDone = {
                        if (onImeAction != null) {
                            onImeAction()
                        } else {
                            focusManager.clearFocus()
                        }
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(bottom = 10.dp)
                    .onFocusChanged { focusState ->
                        isFocused = focusState.isFocused
                        if (focusState.isFocused) {
                            // Move cursor to end when this field gains focus
                            textFieldValue = textFieldValue.copy(
                                selection = TextRange(textFieldValue.text.length)
                            )
                        }
                    }
            )

            if (isPassword) {
                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.Visibility
                            else Icons.Filled.VisibilityOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    /** A reusable hollow (outlined) button with transparent background and customizable color. */
    @Composable
    fun HollowButton(
        text: String,
        onClick: () -> Unit,
        color: Color,
        modifier: Modifier = Modifier,
        buttonHeightDefault: Dp = buttonHeight,
    ) {
        Button(
            onClick = onClick,
            modifier = modifier
                .height(buttonHeightDefault)
                .widthIn(min = 100.dp)
                .border(
                    width = 1.dp,
                    color = color,
                    shape = RoundedCornerShape(buttonCornerRadius)
                ),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = color,
            ),
        ) {
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp
            )
        }
    }

    /**
     * A simple composable that displays a centered circular progress indicator
     * with a semi-transparent background, suitable for overlaying content.
     */
    @Composable
    fun LoadingIndicator() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
        }
    }

    /** A primary action button with customizable color, width, and enabled state. */
    @Composable
    fun ActionButtonPrimary(
        text: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        color: Color = MaterialTheme.colorScheme.primary,
        enabled: Boolean = true,
        width: Int = 100,
        fontSize: Int = 13,
    ) {
        Button(
            onClick = onClick,
            modifier = modifier
                .height(buttonHeight)
                .widthIn(min = width.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = color,
                contentColor = Color.White,
                disabledContainerColor = Color.Gray,
                disabledContentColor = Color.White // or use a theme color
            ),
            //CodeReview - Static Color
            shape = RoundedCornerShape(buttonCornerRadius),
            enabled = enabled
        ) {
            Text(text = text, fontSize = fontSize.sp)
        }
    }

    /** A navigation icon button that opens the app’s main menu screen. */
    @Composable
    fun MenuButton(
        navController: NavController,
        modifier: Modifier = Modifier,
        backIcon: Int = R.drawable.menu,
    ) {
        IconButton(
            onClick = {
                navController.navigate(Screen.Menu.route)
            },
            modifier = modifier
                .padding(small)
                .size(responsiveDp(40.dp))
        ) {
            Icon(
                painter = painterResource(id = backIcon),
                contentDescription = "Menu",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(extraSmall)
            )
        }
    }

    /** A PMS icon button that shows pms connection status. */

    @Composable
    fun PmsConnectionIcon(
        modifier: Modifier = Modifier,
        icon: Int = R.drawable.pms_connection_icon,
        isPmsConnected: Boolean,
    ) {
        IconButton(
            onClick = {},
            modifier = modifier
                .padding(small)
                .size(responsiveDp(50.dp))
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = "Menu",
                tint = if(isPmsConnected) MaterialTheme.colorScheme.secondary else Color.Gray,
                modifier = Modifier.padding(extraSmall)
            )
        }
    }

    /** A customizable OTP input field with multiple boxes, auto-focus, and optional password masking. */
    @Composable
    fun OTPTextField(
        otp: String,
        onOtpChange: (String) -> Unit,
        boxCount: Int = 4,
        boxSize: Dp = 56.dp,
        cornerRadius: Dp = 8.dp,
        boxBackground: Color = AppTheme.extendedColors.inputBackground,
        textColor: Color = AppTheme.extendedColors.textColor
    ) {
        // focus requesters for each box
        val focusRequesters = remember { List(boxCount) { FocusRequester() } }

        // track per-box focus states to show only one cursor
        val focusStates: SnapshotStateList<Boolean> = remember {
            mutableStateListOf<Boolean>().apply { repeat(boxCount) { add(false) } }
        }

        // helper to compute the desired focus index:
        // if empty -> 0, else next after last entered (or last index if full)
        fun desiredFocusIndex(): Int {
            return if (otp.isEmpty()) 0 else otp.length.coerceAtMost(boxCount - 1)
        }

        // on OTP change ensure we don't exceed boxCount
        fun sanitizeAndEmit(list: MutableList<Char>) {
            val sb = StringBuilder()
            for (c in list.take(boxCount)) {
                if (c != ' ') sb.append(c)
            }
            onOtpChange(sb.toString())
        }

        // convert otp to mutable list for edits
        fun otpToList(): MutableList<Char> = otp.toMutableList()

        // When otp changes, auto-focus desired index but only request if not already focused
        LaunchedEffect(otp) {
            val target = desiredFocusIndex()
            if (!focusStates.getOrNull(target).orFalse()) {
                focusRequesters[target].requestFocus()
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (i in 0 until boxCount) {
                val char = otp.getOrNull(i)?.toString() ?: ""

                BasicTextField(
                    value = char,
                    onValueChange = { value ->
                        if (value.isNotEmpty()) {
                            val ch = value.first()
                            if (!ch.isDigit()) return@BasicTextField

                            val list = otpToList()
                            // ensure list has capacity up to i
                            while (list.size < i) list.add(' ')
                            if (i < list.size) {
                                list[i] = ch
                            } else {
                                list.add(ch)
                            }
                            sanitizeAndEmit(list)

                            // move focus to next logical spot
                            val next = (otp.length + 1).coerceAtMost(boxCount - 1) // after insert
                            if (!focusStates.getOrNull(next).orFalse()) {
                                focusRequesters[next].requestFocus()
                            }
                        }
                    },
                    modifier = Modifier
                        .size(boxSize)
                        // when user taps anywhere, we want to redirect focus according to your rule:
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                val desired = desiredFocusIndex()
                                // if tapped box is not the desired box, request focus to desired
                                if (desired != i && !focusStates.getOrNull(desired).orFalse()) {
                                    focusRequesters[desired].requestFocus()
                                } else {
                                    // else let this box gain focus normally
                                    if (!focusStates.getOrNull(i).orFalse()) {
                                        focusRequesters[i].requestFocus()
                                    }
                                }
                            })
                        }
                        .focusRequester(focusRequesters[i])
                        .onFocusChanged { state ->
                            focusStates[i] = state.isFocused
                            // If box gained focus due to user tap but we should redirect, do it:
                            if (state.isFocused) {
                                val desired = desiredFocusIndex()
                                if (desired != i && !focusStates.getOrNull(desired).orFalse()) {
                                    // programmatically move to desired index (will update focusStates accordingly)
                                    focusRequesters[desired].requestFocus()
                                }
                            }
                        }
                        .onKeyEvent { event ->
                            if (event.key == Key.Backspace) {
                                val list = otpToList()
                                if (char.isNotEmpty()) {
                                    // delete the digit at this index
                                    if (i < list.size) {
                                        list.removeAt(i)
                                        sanitizeAndEmit(list)
                                        // focus: try to focus this index (which now points to next digit),
                                        // or previous if we're past the end
                                        val target = i.coerceAtMost(list.size.coerceAtLeast(0))
                                        if (!focusStates.getOrNull(target).orFalse()) {
                                            focusRequesters[target.coerceAtLeast(0)].requestFocus()
                                        }
                                    }
                                } else {
                                    // empty current box -> delete previous
                                    if (i > 0 && list.isNotEmpty()) {
                                        val removeIndex = (i - 1).coerceAtMost(list.size - 1)
                                        list.removeAt(removeIndex)
                                        sanitizeAndEmit(list)
                                        if (!focusStates.getOrNull(removeIndex).orFalse()) {
                                            focusRequesters[removeIndex.coerceAtLeast(0)].requestFocus()
                                        }
                                    }
                                }
                                true
                            } else false
                        }
                        .background(boxBackground, RoundedCornerShape(cornerRadius)),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = if (i == boxCount - 1) ImeAction.Done else ImeAction.Next
                    ),
                    textStyle = TextStyle(
                        color = textColor,
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center
                    ),
                    // show cursor only for the truly-focused box
                    cursorBrush = if (focusStates.getOrNull(i).orFalse()) {
                        SolidColor(AppTheme.extendedColors.textColor)
                    } else {
                        SolidColor(Color.Transparent)
                    },
                    decorationBox = { innerTextField ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            innerTextField()
                        }
                    }
                )
            }
        }
    }

    // helpers
    private fun <T> SnapshotStateList<T>.getOrNull(index: Int): T? =
        if (index in 0 until size) this[index] else null

    private fun Boolean?.orFalse(): Boolean = this ?: false


    @Composable
    fun responsiveButtonHeight(baseDp: Dp): Dp {
        val config = LocalConfiguration.current
        val sw = minOf(config.screenWidthDp, config.screenHeightDp)

        val scale = when {
            sw < 400 -> 0.8f   // very small phones
            sw < 600 -> 1f     // normal phones
            sw < 840 -> 1.15f  // tablets
            else -> 1.3f       // large tablets
        }
        return baseDp * scale
    }

    @Composable
    fun responsiveBadgeWidth(baseDp: Dp): Dp {
        val config = LocalConfiguration.current
        val sw = minOf(config.screenWidthDp, config.screenHeightDp)

        val scale = when {
            sw < 400 -> 0.9f   // very small phones
            sw < 600 -> 1f     // normal phones
            sw < 840 -> 1.15f  // tablets
            else -> 1.3f       // large tablets
        }
        return baseDp * scale
    }


    @Composable
    fun responsiveDp(baseDp: Dp): Dp {
        val config = LocalConfiguration.current
        val sw = minOf(config.screenWidthDp, config.screenHeightDp)

        val scale = when {
            sw < 360 -> 0.9f   // very small phones
            sw < 600 -> 1f     // normal phones
            sw < 840 -> 1.15f  // tablets
            else -> 1.3f       // large tablets
        }
        return baseDp * scale
    }

    @Composable
    fun responsiveDpForCircularCountProgressPortrait(baseDp: Dp): Dp {
        val config = LocalConfiguration.current
        val sw = minOf(config.screenWidthDp, config.screenHeightDp)

        val scale = when {
            sw < 400 -> 1f   // very small phones
            sw < 500 -> 1f     // normal phones
            sw < 840 -> 1.1f  // tablets
            else -> 1.3f       // large tablets
        }
        return baseDp * scale
    }

    @Composable
    fun responsiveDpForCircularCountProgressPortrait(percent: Float): Dp {
        val config = LocalConfiguration.current
        val sw = minOf(config.screenWidthDp, config.screenHeightDp)

        return (sw * percent).dp
    }

    @Composable
    fun responsiveDpForCircularCountProgressLandscape(baseDp: Dp): Dp {
        val config = LocalConfiguration.current
        val sw = minOf(config.screenWidthDp, config.screenHeightDp)

        val scale = when {
            sw < 400 -> 0.8f   // very small phones
            sw < 500 -> 1f     // normal phones
            sw < 840 -> 1.15f  // tablets
            else -> 1.3f       // large tablets
        }
        return baseDp * scale
    }

    @Composable
    fun responsiveSp(baseSp: TextUnit): TextUnit {
        val configuration = LocalConfiguration.current
        val smallestWidthDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
        val scale = when {
//            smallestWidthDp < 400 -> 0.8f //small phone
            smallestWidthDp < 600 -> 1f   //phone
            smallestWidthDp < 840 -> 1.5f //small tablets
            else -> 2f          //large tablets
        }
        return (baseSp.value * scale).sp
    }



}
