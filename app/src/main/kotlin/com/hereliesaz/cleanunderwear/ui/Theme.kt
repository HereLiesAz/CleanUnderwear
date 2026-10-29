package com.hereliesaz.cleanunderwear.ui

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Monochrome: a grey ramp and one accent (white on dark, black on light). Status is told apart
 * by label, icon and fill, never by hue alone; the only colour is [Alert], kept for the states
 * that need the user (a possible match) and for errors.
 */

/**
 * The greys as plain ARGB numbers, so a unit test can check their contrast (ThemeContrastTest)
 * without building a colour scheme. Backgrounds first, then what is drawn on them.
 */
internal object ThemeArgb {
    const val INK = 0xFF0A0A0AL
    const val COAL = 0xFF141414L
    const val GRAPHITE = 0xFF1F1F1FL
    const val ASH = 0xFF9A9A9AL
    const val BONE = 0xFFEDEDEDL
    const val PAPER = 0xFFF7F7F7L
    const val WHITE = 0xFFFFFFFFL
    const val LIGHT_CONTAINER_HIGH = 0xFFF0F0F0L
    const val LIGHT_VARIANT = 0xFFECECECL

    /**
     * Outlines (a badge's border, a progress track, a field's edge). Each is at least 3:1 against
     * every background of its theme, the most the grey ramp lets a boundary be without a new hue:
     * dark 3.3:1 on the lightest dark surface, light 3.3:1 on the darkest light one.
     */
    const val OUTLINE_DARK = 0xFF707070L
    const val OUTLINE_LIGHT = 0xFF808080L
}

private val Ink = Color(ThemeArgb.INK)
private val Coal = Color(ThemeArgb.COAL)
private val Graphite = Color(ThemeArgb.GRAPHITE)
private val Iron = Color(ThemeArgb.OUTLINE_DARK)
private val Ash = Color(ThemeArgb.ASH)
private val Bone = Color(ThemeArgb.BONE)
private val Paper = Color(ThemeArgb.PAPER)

/** The one non-grey: a possible match, an error. Dark red reads as grave without shouting. */
private val AlertDark = Color(0xFFE5675A)
private val AlertLight = Color(0xFFB3261E)

val SurveillanceDarkColorScheme = darkColorScheme(
    primary = Bone,
    onPrimary = Ink,
    secondary = Ash,
    onSecondary = Ink,
    tertiary = Bone,
    onTertiary = Ink,
    background = Ink,
    onBackground = Bone,
    surface = Coal,
    onSurface = Bone,
    surfaceVariant = Graphite,
    onSurfaceVariant = Ash,
    surfaceContainer = Coal,
    surfaceContainerHigh = Graphite,
    primaryContainer = Graphite,
    onPrimaryContainer = Bone,
    secondaryContainer = Graphite,
    onSecondaryContainer = Bone,
    outline = Iron,
    outlineVariant = Graphite,
    error = AlertDark,
    onError = Ink
)

val SurveillanceLightColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    secondary = Color(0xFF555555),
    onSecondary = Paper,
    tertiary = Ink,
    onTertiary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = Color(ThemeArgb.WHITE),
    onSurface = Ink,
    surfaceVariant = Color(ThemeArgb.LIGHT_VARIANT),
    onSurfaceVariant = Color(0xFF555555),
    surfaceContainer = Color(ThemeArgb.WHITE),
    surfaceContainerHigh = Color(ThemeArgb.LIGHT_CONTAINER_HIGH),
    primaryContainer = Color(ThemeArgb.LIGHT_VARIANT),
    onPrimaryContainer = Ink,
    secondaryContainer = Color(ThemeArgb.LIGHT_VARIANT),
    onSecondaryContainer = Ink,
    outline = Color(ThemeArgb.OUTLINE_LIGHT),
    outlineVariant = Color(0xFFDDDDDD),
    error = AlertLight,
    onError = Paper
)

/**
 * Named type roles, so one semantic level always looks the same:
 * - titleLarge: screen heading
 * - titleMedium: card heading
 * - labelLarge: section header (with [androidx.compose.material3.ColorScheme.onSurfaceVariant])
 * - labelMedium: field label
 * - bodyLarge / bodyMedium: content
 * - bodySmall: caption
 */
val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
)

/** The spacing scale. Screens pad [md]; items in a column sit [md] apart; cards pad [md]. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}
