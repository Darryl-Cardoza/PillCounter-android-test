package com.dispensesure.retail.core.utils.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.isLandscape
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.isTablet
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveDp
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveSp
import com.dispensesure.retail.ui.theme.AppTheme

/** Card fill, also used by anything inside the card that should blend with it (e.g. OTP boxes). */
@Composable
fun authCardBackground(): Color =
    if (AppTheme.isDarkTheme) AppTheme.extendedColors.primaryBackground else CARD_BACKGROUND_LIGHT

/**
 * Shared frame for Login and OTP: blob background, then a centred card with the logo,
 * the screen's [content] and an optional footer. The card scrolls when the keyboard is up.
 */
@Composable
fun AuthCard(
    footerText: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isTablet = isTablet()
    val showLogoAndFooter = shouldShowLogoAndFooter(isLandscape(), isTablet)
    val isDark = AppTheme.isDarkTheme
    val textColor = AppTheme.extendedColors.textColor
    val cardShape = RoundedCornerShape(CARD_CORNER_RADIUS)
    val outlineColor = if (isDark) {
        Color.White.copy(alpha = CARD_OUTLINE_ALPHA_DARK)
    } else {
        textColor.copy(alpha = CARD_OUTLINE_ALPHA_LIGHT)
    }
    val shadowColor = if (isDark) {
        Color.White.copy(alpha = CARD_GLOW_ALPHA_DARK)
    } else {
        Color.Black.copy(alpha = CARD_SHADOW_ALPHA_LIGHT)
    }

    Box(modifier = modifier.fillMaxSize()) {
        AuthBackground()

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .displayCutoutPadding()
        ) {
            // Min height = viewport, so the card is centred when it fits and scrolls when it doesn't.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = cardMaxWidth(isTablet))
                        .fillMaxWidth()
                        .shadow(
                            elevation = CARD_SHADOW_ELEVATION,
                            shape = cardShape,
                            ambientColor = shadowColor,
                            spotColor = shadowColor
                        )
                        .background(authCardBackground(), cardShape)
                        .border(CARD_OUTLINE_WIDTH, outlineColor, cardShape)
                        .padding(horizontal = responsiveDp(24.dp), vertical = responsiveDp(28.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (showLogoAndFooter) {
                        DispenseSureLogo()
                        Spacer(Modifier.height(responsiveDp(24.dp)))
                    }

                    content()

                    if (showLogoAndFooter && footerText != null) {
                        Spacer(Modifier.height(responsiveDp(16.dp)))
                        Text(
                            text = footerText,
                            color = textColor.copy(alpha = 0.7f),
                            fontSize = responsiveSp(6.sp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
