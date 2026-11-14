package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CardSurfaceDense
import com.example.ui.theme.MintCta
import com.example.ui.theme.MintCtaContent
import com.example.ui.theme.MintCtaPressed
import com.example.ui.theme.SecondaryGlass
import com.example.ui.theme.SecondaryGlassPressed
import com.example.ui.theme.SecondaryGlassText
import com.example.ui.theme.SpecularStroke
import com.example.ui.theme.SwitchCheckedThumb
import com.example.ui.theme.SwitchCheckedTrack
import com.example.ui.theme.SwitchUncheckedThumb
import com.example.ui.theme.SwitchUncheckedTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Material 3 Expressive spring corner morph: pill (24dp) at rest -> 12dp on press.
 */
@Composable
fun rememberExpressiveMorphShape(
    interactionSource: MutableInteractionSource,
    restRadius: Dp = 24.dp,
    pressedRadius: Dp = 12.dp
): Shape {
    val isPressed by interactionSource.collectIsPressedAsState()
    val cornerRadius by animateDpAsState(
        targetValue = if (isPressed) pressedRadius else restRadius,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "expressive_corner_morph"
    )
    return RoundedCornerShape(cornerRadius)
}

/**
 * Frosted graphite glass outer card (24dp corner radius, 0.75dp hairline specular stroke).
 * Zero colored neon borders.
 */
@Composable
fun FrostedGlassCard(
    modifier: Modifier = Modifier,
    containerColor: Color = CardSurfaceDense,
    cornerRadius: Dp = 24.dp,
    borderColor: Color = SpecularStroke,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(0.75.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        content()
    }
}

/**
 * Primary Mint Mist CTA (#A7F3D0 / pressed #6EE7B7, content #042016) with expressive spring morph.
 */
@Composable
fun MintCtaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = rememberExpressiveMorphShape(interactionSource)
    val bgColor by animateColorAsState(
        targetValue = if (isPressed) MintCtaPressed else MintCta,
        animationSpec = tween(120),
        label = "mint_cta_bg"
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = shape,
        contentPadding = contentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = bgColor,
            contentColor = MintCtaContent,
            disabledContainerColor = MintCta.copy(alpha = 0.42f),
            disabledContentColor = MintCtaContent.copy(alpha = 0.6f)
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
        modifier = modifier.heightIn(min = 48.dp),
        content = content
    )
}

/**
 * Secondary frosted graphite glass button (rgba(255,255,255,0.08) / pressed 0.14, text #E4E4E7)
 * with subtle 0.75dp specular border and expressive spring corner morph.
 */
@Composable
fun GraphiteGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapeOverride: Shape? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val morphShape = rememberExpressiveMorphShape(interactionSource)
    val bgColor by animateColorAsState(
        targetValue = if (isPressed) SecondaryGlassPressed else SecondaryGlass,
        animationSpec = tween(120),
        label = "graphite_btn_bg"
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = shapeOverride ?: morphShape,
        contentPadding = contentPadding,
        border = BorderStroke(
            0.75.dp,
            if (enabled) SpecularStroke else Color.White.copy(alpha = 0.04f)
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = bgColor,
            contentColor = SecondaryGlassText,
            disabledContainerColor = Color.White.copy(alpha = 0.03f),
            disabledContentColor = Color.White.copy(alpha = 0.28f)
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
        modifier = modifier.heightIn(min = 48.dp),
        content = content
    )
}

/**
 * Standard non-player Switch with calm Mint Mist track (#6EE7B7) and #042016 thumb.
 */
@Composable
fun ExpressiveGlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    checkedTrackColor: Color = SwitchCheckedTrack,
    checkedThumbColor: Color = SwitchCheckedThumb
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = checkedTrackColor,
            checkedThumbColor = checkedThumbColor,
            checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = SwitchUncheckedTrack,
            uncheckedThumbColor = SwitchUncheckedThumb,
            uncheckedBorderColor = SpecularStroke
        ),
        modifier = modifier
    )
}

/**
 * Custom animated morphing wave loading indicator compatible with Compose Material3 1.3 BOM.
 */
@Composable
fun WaveLoadingDots(
    color: Color,
    timeSecondsState: State<Float>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val t = timeSecondsState.value
        val barCount = 4
        val barWidth = size.width / (barCount * 2f)
        val gap = (size.width - barCount * barWidth) / (barCount - 1).coerceAtLeast(1)
        val centerY = size.height / 2f
        for (i in 0 until barCount) {
            val wave = 0.35f + 0.65f * ((sin(t * 5.2f + i * 1.1f) + 1f) * 0.5f)
            val h = size.height * wave
            val x = i * (barWidth + gap)
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(x, centerY - h / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
