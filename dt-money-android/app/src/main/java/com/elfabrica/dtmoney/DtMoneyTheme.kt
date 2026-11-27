package com.elfabrica.dtmoney

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object DtColors {
    val Base = Color(0xFF121214)
    val Secondary = Color(0xFF202024)
    val Surface = Color(0xFF29292E)
    val Border = Color(0xFF323238)
    val Muted = Color(0xFF949494)
    val Text = Color(0xFFE1E1E6)
    val Brand = Color(0xFF00875F)
    val BrandDark = Color(0xFF015F43)
    val Green = Color(0xFF00B37E)
    val Red = Color(0xFFF75A68)
    val RedDark = Color(0xFFAA2834)
}

private val controlShape = RoundedCornerShape(6.dp)
private val buttonShape = RoundedCornerShape(12.dp)
private val colorScheme = darkColorScheme(
    primary = DtColors.Green,
    onPrimary = DtColors.Base,
    primaryContainer = DtColors.BrandDark,
    onPrimaryContainer = Color.White,
    secondary = DtColors.Green,
    background = DtColors.Secondary,
    onBackground = DtColors.Text,
    surface = DtColors.Surface,
    onSurface = DtColors.Text,
    surfaceVariant = DtColors.Surface,
    onSurfaceVariant = DtColors.Muted,
    surfaceContainer = DtColors.Surface,
    surfaceContainerHigh = DtColors.Surface,
    surfaceContainerHighest = DtColors.Border,
    outline = DtColors.Muted,
    outlineVariant = DtColors.Border,
    error = DtColors.Red,
    onError = DtColors.Base
)

private fun roboto(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = lineHeight.sp, fontWeight = weight)

private val typography = Typography(
    headlineMedium = roboto(30, 38, FontWeight.Bold),
    headlineSmall = roboto(24, 32, FontWeight.Bold),
    titleLarge = roboto(20, 28, FontWeight.Bold),
    titleMedium = roboto(18, 24, FontWeight.Bold),
    bodyLarge = roboto(16, 24),
    bodyMedium = roboto(16, 24),
    bodySmall = roboto(14, 20),
    labelLarge = roboto(14, 20, FontWeight.Bold),
    labelMedium = roboto(14, 20)
)

@Composable
internal fun DtMoneyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = Shapes(extraSmall = controlShape, small = controlShape, medium = controlShape,
            large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(16.dp)),
        content = content
    )
}

@Composable
internal fun DtLogo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.dt_money_logo), contentDescription = "DT Money", modifier = modifier)
}

@Composable
internal fun DtField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotEmpty()) Text(label, color = DtColors.Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label.ifEmpty { placeholder.orEmpty() } },
            singleLine = true,
            isError = isError,
            placeholder = placeholder?.let { text -> { Text(text, color = DtColors.Muted) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            leadingIcon = leadingIcon,
            shape = controlShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DtColors.Base,
                unfocusedContainerColor = DtColors.Base,
                errorContainerColor = DtColors.Base,
                focusedBorderColor = DtColors.Green,
                unfocusedBorderColor = DtColors.Border
            )
        )
    }
}

@Composable
internal fun DtButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val size = modifier.heightIn(min = 50.dp)
    if (outlined) {
        OutlinedButton(onClick, size, enabled, shape = buttonShape,
            border = BorderStroke(1.dp, if (enabled) DtColors.Brand else DtColors.Border),
            content = content)
    } else {
        Button(onClick, size, enabled, shape = buttonShape,
            colors = ButtonDefaults.buttonColors(containerColor = DtColors.Brand, contentColor = Color.White,
                disabledContainerColor = DtColors.Border, disabledContentColor = DtColors.Muted),
            content = content)
    }
}

@Composable
internal fun DtCard(
    modifier: Modifier = Modifier,
    color: Color = DtColors.Surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(modifier, shape = controlShape, color = color, contentColor = DtColors.Text) {
        Column(content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DtSheet(
    title: String,
    onClose: () -> Unit,
    closeEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val allowClose by rememberUpdatedState(closeEnabled)
    ModalBottomSheet(
        onDismissRequest = { if (closeEnabled) onClose() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { allowClose || it != SheetValue.Hidden }),
        containerColor = DtColors.Surface,
        contentColor = DtColors.Text
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClose, enabled = closeEnabled) { Text("Fechar") }
            }
            content()
        }
    }
}
