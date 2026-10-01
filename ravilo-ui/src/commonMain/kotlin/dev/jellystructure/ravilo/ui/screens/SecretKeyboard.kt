package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * R348 (FR-R348-1/4) — the keyboard options of every password field, in one place. `KeyboardType.Password` is what
 * tells the input method the text is secret (Android: `TYPE_TEXT_VARIATION_PASSWORD`; the web: a password input), so
 * the keyboard shows no suggestions and learns nothing. `PasswordVisualTransformation` alone only draws dots: Gboard on
 * a TV showed the typed password in plain text in its suggestion strip.
 */
internal fun secretKeyboardOptions(imeAction: ImeAction): KeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password,
    imeAction = imeAction,
)

/** R348 (FR-R348-2) — a username or similar handle: not prose, so no autocorrect and no capital first letter. */
internal fun handleKeyboardOptions(imeAction: ImeAction): KeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Text,
    imeAction = imeAction,
)
