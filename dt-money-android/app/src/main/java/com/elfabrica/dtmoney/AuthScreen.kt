package com.elfabrica.dtmoney

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun SignIn(state: AppState, authenticate: (String, String, String, String?) -> Unit) {
    var registering by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable(state.baseUrl) { mutableStateOf(state.baseUrl) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val passwordsDiffer = confirmation.isNotEmpty() && confirmation != password
    val mismatchMessage = "As senhas não coincidem."
    val canSubmit = !state.busy && url.isNotBlank() && email.isNotBlank() && password.isNotEmpty() &&
        (!registering || (name.isNotBlank() && confirmation == password))

    fun changeForm() {
        focusManager.clearFocus()
        registering = !registering
        confirmation = ""
        scope.launch { scrollState.scrollTo(0) }
    }

    BackHandler(enabled = registering && !state.busy, onBack = ::changeForm)

    Box(
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(scrollState),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier.widthIn(max = 540.dp).fillMaxWidth(0.82f)
                .padding(top = if (keyboardVisible) 12.dp else 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!keyboardVisible) {
                DtLogo(Modifier.widthIn(max = 255.dp).fillMaxWidth().height(48.dp))
                Spacer(Modifier.height(24.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (registering) {
                    DtField(
                        value = name,
                        onValueChange = { name = it },
                        label = "NOME",
                        placeholder = "Seu nome",
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { AuthIcon(AuthGlyph.Person) }
                    )
                }
                DtField(
                    value = email,
                    onValueChange = { email = it },
                    label = "E-MAIL",
                    keyboard = KeyboardType.Email,
                    placeholder = if (registering) "email@example.com" else "email@example.br",
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { AuthIcon(AuthGlyph.Email) }
                )
                PasswordField(password, { password = it }, "SENHA", "Sua senha")
                if (registering) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PasswordField(
                            value = confirmation,
                            onValueChange = { confirmation = it },
                            label = "CONFIRME SUA SENHA",
                            placeholder = "Confirme sua senha",
                            visibilityTarget = "confirmação de senha",
                            validationError = mismatchMessage.takeIf { passwordsDiffer }
                        )
                        if (passwordsDiffer) {
                            Text(
                                mismatchMessage,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                color = DtColors.Red,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                DtField(
                    value = url,
                    onValueChange = { url = it },
                    label = "URL DA API",
                    keyboard = KeyboardType.Uri,
                    placeholder = "https://",
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { AuthIcon(AuthGlyph.Link) }
                )
            }
            Spacer(Modifier.height(32.dp))
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = if (keyboardVisible) 0.dp else 250.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                DtButton(
                    onClick = {
                        focusManager.clearFocus()
                        authenticate(url, email, password, name.takeIf { registering })
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 57.dp),
                    enabled = canSubmit
                ) {
                    Text(if (registering) "Cadastrar" else "Login", Modifier.weight(1f))
                    AuthIcon(AuthGlyph.Arrow, tint = LocalContentColor.current)
                }
                Column(Modifier.padding(top = 24.dp)) {
                    Text(
                        if (registering) "Já possui uma conta?" else "Ainda não possui uma conta?",
                        color = DtColors.Muted,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(24.dp))
                    DtButton(
                        onClick = ::changeForm,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 57.dp),
                        enabled = !state.busy,
                        outlined = true
                    ) {
                        Text(if (registering) "Acessar" else "Cadastrar", Modifier.weight(1f))
                        AuthIcon(AuthGlyph.Arrow, tint = LocalContentColor.current)
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    visibilityTarget: String = "senha",
    validationError: String? = null
) {
    var showPassword by remember { mutableStateOf(false) }
    DtField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        keyboard = KeyboardType.Password,
        modifier = Modifier.fillMaxWidth().semantics { validationError?.let { error(it) } },
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        leadingIcon = { AuthIcon(AuthGlyph.Lock) },
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                AuthIcon(
                    glyph = if (showPassword) AuthGlyph.VisibilityOff else AuthGlyph.Visibility,
                    description = if (showPassword) "Ocultar $visibilityTarget" else "Mostrar $visibilityTarget"
                )
            }
        },
        isError = validationError != null
    )
}

private enum class AuthGlyph(val pathData: String) {
    Person("M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"),
    Email(
        "M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2z" +
            "m0 14H4V8l8 5 8-5v10zm-8-7L4 6h16l-8 5z"
    ),
    Lock(
        "M18 8h-1V6a5 5 0 0 0-10 0v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2z" +
            "M9 6a3 3 0 0 1 6 0v2H9V6zm9 14H6V10h12v10zm-6-3a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"
    ),
    Link(
        "M3.9 12a3.1 3.1 0 0 1 3.1-3.1h4V7H7a5 5 0 1 0 0 10h4v-1.9H7A3.1 3.1 0 0 1 3.9 12z" +
            "M8 13h8v-2H8v2zm9-6h-4v1.9h4a3.1 3.1 0 0 1 0 6.2h-4V17h4a5 5 0 1 0 0-10z"
    ),
    Arrow("M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8-8-8z"),
    Visibility(
        "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5z" +
            "M12 17a5 5 0 1 1 0-10 5 5 0 0 1 0 10zm0-2a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"
    ),
    VisibilityOff(
        "M12 7a5 5 0 0 1 5 5c0 .65-.13 1.26-.36 1.82l2.92 2.92A11.8 11.8 0 0 0 23 12" +
            "c-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16A4.98 4.98 0 0 1 12 7z" +
            "M2 4.27l2.28 2.28A11.8 11.8 0 0 0 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.85" +
            "l3.44 3.44 1.41-1.41L3.41 2.86 2 4.27zm5.53 5.53 1.55 1.55A3 3 0 0 0 12 15" +
            "c.22 0 .44-.02.65-.07l1.55 1.55A5 5 0 0 1 7.53 9.8zm4.31-.77 3.13 3.13A3 3 0 0 0 11.84 9.03z"
    )
}

@Composable
private fun AuthIcon(glyph: AuthGlyph, tint: Color = DtColors.Muted, description: String? = null) {
    val path = remember(glyph) { PathParser().parsePathString(glyph.pathData).toPath() }
    Canvas(Modifier.size(24.dp).semantics { description?.let { contentDescription = it } }) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawPath(path, tint)
        }
    }
}
