package com.hereliesaz.cleanunderwear.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The outline colour marks a boundary (a badge's edge, a progress track), so it must reach the
 * 3:1 that WCAG asks of a user-interface boundary against everything it is drawn on, in both themes.
 */
class ThemeContrastTest {

    private fun channel(argb: Long, shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Long) =
        0.2126 * channel(argb, 16) + 0.7152 * channel(argb, 8) + 0.0722 * channel(argb, 0)

    private fun contrast(a: Long, b: Long): Double {
        val (hi, lo) = luminance(a).let { la -> luminance(b).let { lb -> maxOf(la, lb) to minOf(la, lb) } }
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun theDarkOutlineStandsOutFromEveryDarkBackground() {
        listOf(ThemeArgb.INK, ThemeArgb.COAL, ThemeArgb.GRAPHITE).forEach { bg ->
            val ratio = contrast(ThemeArgb.OUTLINE_DARK, bg)
            assertTrue("outline on #${bg.toString(16)} is $ratio:1", ratio >= 3.0)
        }
    }

    @Test
    fun theLightOutlineStandsOutFromEveryLightBackground() {
        listOf(ThemeArgb.PAPER, ThemeArgb.WHITE, ThemeArgb.LIGHT_CONTAINER_HIGH, ThemeArgb.LIGHT_VARIANT).forEach { bg ->
            val ratio = contrast(ThemeArgb.OUTLINE_LIGHT, bg)
            assertTrue("outline on #${bg.toString(16)} is $ratio:1", ratio >= 3.0)
        }
    }
}
