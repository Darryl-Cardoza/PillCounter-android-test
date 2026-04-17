package com.rite.pillcounting.core.utils.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.R

/**
 * A composable for displaying the medical icon with configurable outer circle and inner content colors.
 *
 * The inner content (medical box + plus sign) can have its color customized.
 *
 * @param outerCircleColor The color for the outer circular border.
 * @param innerColor The color for the inner medical icon content. Defaults to the original pink color.
 * @param modifier Modifier to be applied to the Box containing the icons.
 * @param size The size of the icon.
 * @param contentDescription Content description for accessibility.
 */
@Composable
fun MedicalIcon(
    outerCircleColor: Color,
    innerColor: Color = Color(0xFFFD82B5),
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    contentDescription: String? = null
) {
    Box(modifier = modifier.size(size)) {
        Icon(
            painter = painterResource(id = R.drawable.fixed_count_outer),
            contentDescription = contentDescription,
            tint = outerCircleColor,
            modifier = Modifier.fillMaxSize()
        )
        Icon(
            painter = painterResource(id = R.drawable.regular_count_inner),
            contentDescription = null,
            tint = innerColor,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * A composable for displaying the regular count icon with configurable outer circle and inner content colors.
 *
 * The inner content (quick count symbol) can have its color customized.
 *
 * @param outerCircleColor The color for the outer circular border.
 * @param innerColor The color for the inner quick count icon content. Defaults to the original cyan color.
 * @param modifier Modifier to be applied to the Box containing the icons.
 * @param size The size of the icon.
 * @param contentDescription Content description for accessibility.
 */
@Composable
fun StockIcon(
    outerCircleColor: Color,
    innerColor: Color = Color(0xFF01BBD3),
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    contentDescription: String? = null
) {
    Box(modifier = modifier.size(size)) {
        Icon(
            painter = painterResource(id = R.drawable.fixed_count_outer),
            contentDescription = contentDescription,
            tint = outerCircleColor,
            modifier = Modifier.fillMaxSize()
        )
        Icon(
            painter = painterResource(id = R.drawable.fixed_count_inner),
            contentDescription = null,
            tint = innerColor,
            modifier = Modifier.fillMaxSize()
        )
    }
}