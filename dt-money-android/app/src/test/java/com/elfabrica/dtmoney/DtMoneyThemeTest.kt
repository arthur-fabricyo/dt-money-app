package com.elfabrica.dtmoney

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtMoneyThemeTest {
    @Test fun surfacesAndTransactionColorsFollowReactNative() {
        assertEquals(0xFF121214.toInt(), DtColors.Base.toArgb())
        assertEquals(0xFF202024.toInt(), DtColors.Secondary.toArgb())
        assertEquals(0xFF29292E.toInt(), DtColors.Surface.toArgb())
        assertEquals(0xFF00875F.toInt(), DtColors.Brand.toArgb())
        assertEquals(0xFF00B37E.toInt(), DtColors.Green.toArgb())
        assertEquals(0xFFF75A68.toInt(), DtColors.Red.toArgb())
    }

    @Test fun cardTextStaysLegibleAgainstTheOriginalDarkSurfaces() {
        assertTrue(contrast(DtColors.Muted, DtColors.Surface) >= 4.5)
        assertTrue(contrast(DtColors.Text, DtColors.Base) >= 4.5)
        assertTrue(contrast(Color.White, DtColors.BrandDark) >= 4.5)
        assertTrue(contrast(Color.White, DtColors.Brand) >= 4.5)
    }

    private fun contrast(first: Color, second: Color): Double {
        val light = maxOf(first.luminance(), second.luminance())
        val dark = minOf(first.luminance(), second.luminance())
        return (light + 0.05) / (dark + 0.05)
    }
}
