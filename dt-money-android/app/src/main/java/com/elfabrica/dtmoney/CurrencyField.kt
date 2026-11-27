package com.elfabrica.dtmoney

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Keeps the editable value in cents while displaying the Brazilian currency format. */
internal object CurrencyTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        if (digits.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val formatted = money(digits.toLong())
        val digitPositions = formatted.indices.filter { formatted[it] in '0'..'9' }.takeLast(digits.length)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                if (offset == digits.length) formatted.length else digitPositions[offset]

            override fun transformedToOriginal(offset: Int): Int =
                digitPositions.count { it < offset }
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }
}

@Composable
internal fun CurrencyField(value: String, change: (String) -> Unit) {
    DtField(
        value = value,
        onValueChange = { input ->
            val digits = input.filter { it in '0'..'9' }.trimStart('0')
            if (digits.length <= 18) change(digits)
        },
        label = "Valor em reais",
        keyboard = KeyboardType.Number,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = CurrencyTransformation
    )
}
