package com.elfabrica.dtmoney

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyTransformationTest {
    @Test
    fun formatsCentsAndPreservesCursorOffsets() {
        val result = CurrencyTransformation.filter(AnnotatedString("123456"))
        assertEquals("R$ 1.234,56", result.text.text.replace('\u00a0', ' '))
        for (offset in 0..6) {
            assertEquals(offset, result.offsetMapping.transformedToOriginal(result.offsetMapping.originalToTransformed(offset)))
        }
    }

    @Test
    fun formatsSmallAmounts() {
        val result = CurrencyTransformation.filter(AnnotatedString("5"))
        assertEquals("R$ 0,05", result.text.text.replace('\u00a0', ' '))
        assertEquals(0, result.offsetMapping.transformedToOriginal(result.offsetMapping.originalToTransformed(0)))
        assertEquals(1, result.offsetMapping.transformedToOriginal(result.offsetMapping.originalToTransformed(1)))
    }
    @Test
    fun emptyInputKeepsAnEmptyDisplay() {
        val result = CurrencyTransformation.filter(AnnotatedString(""))
        assertEquals("", result.text.text)
        assertEquals(0, result.offsetMapping.originalToTransformed(0))
    }

    @Test
    fun formatsWholeReaisAndLargeValues() {
        val samples = mapOf("100" to "R$ 1,00", "123456789" to "R$ 1.234.567,89")
        samples.forEach { (input, expected) ->
            val result = CurrencyTransformation.filter(AnnotatedString(input))
            assertEquals(expected, result.text.text.replace('\u00a0', ' '))
            for (offset in 0..input.length) {
                assertEquals(offset, result.offsetMapping.transformedToOriginal(result.offsetMapping.originalToTransformed(offset)))
            }
            for (offset in 0..result.text.length) {
                val mapped = result.offsetMapping.transformedToOriginal(offset)
                org.junit.Assert.assertTrue(mapped in 0..input.length)
            }
        }
    }
}
