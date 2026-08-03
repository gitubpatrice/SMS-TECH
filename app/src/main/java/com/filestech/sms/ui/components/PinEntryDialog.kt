package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.ui.security.ProtectSecretInput
import kotlinx.coroutines.launch

/**
 * v1.13.0 — dialog réutilisable de saisie PIN ou passphrase. Trois cas d'usage :
 *  - Saisie PIN/pass coffre à l'entrée [com.filestech.sms.ui.screens.vault.VaultScreen]
 *    (second-factor). Bouton biométrie additionnel si disponible.
 *  - Setup / change / clear du PIN coffre dans Réglages → Sécurité.
 *  - (Futur) Tout autre flow qui nécessite une réauth ponctuelle.
 *
 * **Sécurité** :
 *  - `PasswordVisualTransformation` masque la saisie (jamais en clair même
 *    si l'écran est filmé / screen-recorded).
 *  - `KeyboardType.Password` accepte n'importe quel caractère (l'user choisit
 *    PIN purement numérique ou passphrase alphanumérique selon son goût ;
 *    aucune autosuggestion ni autofill IME — le clavier en mode Password
 *    désactive ces fonctions sur tous les claviers Android compatibles).
 *  - Le `String` du `value` est local au composable et n'est jamais persisté.
 *    Quand l'user valide, on convertit en `CharArray` (cf. callback `onVerify`)
 *    et on laisse le caller le wipe — la signature `suspend (CharArray) -> Boolean`
 *    rend le contrat explicite.
 *  - Pas de feedback différentié entre "absent" et "faux" — le message d'erreur
 *    est unique (cf. `pin_error_invalid`) pour ne pas leaker si un hash est
 *    configuré ou pas.
 *
 * @param title titre du dialog (ex. "Coffre — PIN ou pass").
 * @param description sous-titre explicatif (optionnel).
 * @param confirmLabel label du bouton de validation (ex. "Déverrouiller").
 * @param onVerify suspend lambda qui prend la saisie en `CharArray` (déjà
 *   wipé par le composable APRÈS appel) et retourne `true` si OK. L'UI gère
 *   le feedback erreur si `false`.
 * @param onVerified appelé APRÈS qu'`onVerify` a renvoyé `true`. Le caller
 *   est responsable de fermer le dialog (typiquement en flippant son state).
 *   Distinct de [onCancel] pour permettre des actions différentes (en succès,
 *   on enchaîne le flow ; en annulation, on revient en arrière).
 * @param onCancel appelé sur tap Cancel ou systemback. NE ferme PAS le dialog
 *   tout seul ; le caller doit changer son state de visibilité.
 * @param onUseBiometric `null` = pas de bouton biométrie. Non-null = affiche
 *   un bouton "Utiliser la biométrie" qui appelle ce callback ; le caller est
 *   responsable de lancer BiometricPrompt et de dismisser le dialog en cas
 *   de succès. v1.13.0 — permet de proposer un fallback biométrique pour le
 *   coffre quand l'appareil le supporte, conformément à la demande user.
 */
@Composable
fun PinEntryDialog(
    title: String,
    description: String? = null,
    confirmLabel: String,
    onVerify: suspend (CharArray) -> Boolean,
    onVerified: () -> Unit,
    onCancel: () -> Unit,
    onUseBiometric: (() -> Unit)? = null,
) {
    // v1.27.0 (N3) — saisie de secret : anti-superposition armé tant que le dialogue est composé.
    ProtectSecretInput()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val errorMessage = androidx.compose.ui.res.stringResource(R.string.pin_error_invalid)

    // Focus automatique sur le champ à l'ouverture du dialog (UX standard).
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column {
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        // Cap à 64 chars : confortable pour une passphrase
                        // tout en prévenant un DoS UI si paste géant. PIN ET
                        // pass acceptés (KeyboardType.Password permet les
                        // deux ; l'user choisit son format).
                        pin = it.take(64)
                        error = null
                    },
                    modifier = Modifier.focusRequester(focusRequester),
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.pin_entry_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    isError = error != null,
                    supportingText = error?.let { msg -> { Text(msg) } },
                    enabled = !verifying,
                )
                if (onUseBiometric != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onUseBiometric,
                        enabled = !verifying,
                    ) {
                        Text(androidx.compose.ui.res.stringResource(R.string.vault_use_biometric))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = pin.isNotEmpty() && !verifying,
                onClick = {
                    verifying = true
                    val snapshot = pin.toCharArray()
                    // v1.13.0 audit SEC-1 — vide le String pin AVANT le launch
                    // PBKDF2 (~100 ms). Sans ca, le String reste en heap JVM
                    // pendant toute la derivation, expose a un heap dump. Le
                    // snapshot CharArray porte le secret pendant l'appel suspend
                    // et est wipe dans le finally (y compris si la coroutine est
                    // annulee par rotation Activity).
                    pin = ""
                    scope.launch {
                        val ok = try { onVerify(snapshot) } finally {
                            // Le caller wipe son CharArray — on wipe AUSSI ici
                            // par défense (le contrat dit qu'il peut le faire,
                            // mais s'il oublie, on n'aura pas laissé trainer).
                            for (i in snapshot.indices) snapshot[i] = '\u0000'
                        }
                        verifying = false
                        // pin deja vide ci-dessus (audit SEC-1).
                        if (ok) onVerified() else error = errorMessage
                    }
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !verifying) {
                Text(androidx.compose.ui.res.stringResource(R.string.action_cancel))
            }
        },
    )
}
