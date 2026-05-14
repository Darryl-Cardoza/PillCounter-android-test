package com.rite.pillcounting.core.utils.constants

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class Dimens(
    val extraSmall: Dp = 4.dp,
    val small: Dp = 10.dp,
    val smallMedium: Dp = 13.dp,
    val medium: Dp = 15.dp,
    val mediumLarge: Dp = 18.dp,
    val large: Dp = 20.dp,
    val extraLarge: Dp = 25.dp,
    val xxLarge: Dp = 30.dp,
    val xxxLarge: Dp = 40.dp,
    val huge: Dp = 50.dp,
    val appBarIconsPadding: Dp = 15.dp,
    val pagePadding: Dp = 16.dp,
    val buttonHeight: Dp = 45.dp,
    val buttonWidth: Dp = 120.dp,
    val dialogButtonWidth: Dp = 130.dp,
    val buttonCornerRadius: Dp = 56.dp,
    val buttonInnerHorizontalPadding: Dp = 60.dp,
    val settingRowVerticalPadding: Dp = 14.dp,
    val menuRowVerticalPadding: Dp = 12.dp,
    val menuRowSpacing: Dp = 10.dp,
    val toggleVerticalPadding: Dp = 8.dp,
    val profileTextFieldHeight: Dp = 62.dp,
    val cameraActionIconSize: Dp = 32.dp,
    val cameraButtonSize: Dp = 72.dp,
)

val phoneDimens = Dimens()

val tabletDimens = Dimens(
    extraSmall = 8.dp,
    small = 16.dp,
    smallMedium = 20.dp,
    medium = 24.dp,
    mediumLarge = 28.dp,
    large = 32.dp,
    extraLarge = 40.dp,
    xxLarge = 48.dp,
    xxxLarge = 60.dp,
    huge = 72.dp,
    appBarIconsPadding = 24.dp,
    pagePadding = 24.dp,
    buttonHeight = 56.dp,
    buttonWidth = 160.dp,
    dialogButtonWidth = 130.dp,
    buttonCornerRadius = 56.dp,
    buttonInnerHorizontalPadding = 80.dp,
    settingRowVerticalPadding = 20.dp,
    menuRowVerticalPadding = 18.dp,
    menuRowSpacing = 14.dp,
    toggleVerticalPadding = 12.dp,
    profileTextFieldHeight = 70.dp,
    cameraActionIconSize = 64.dp,
    cameraButtonSize = 96.dp,
)

val LocalDimens = staticCompositionLocalOf { phoneDimens }
