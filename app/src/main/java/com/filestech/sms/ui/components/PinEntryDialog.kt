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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.filestech.sms.security.PinVerdict
import com.filestech.sms.ui.security.ProtectSecretInput
import kotlinx.coroutines.delay
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
 *  - v1.27.10 (revue externe GitLab !38458, constat 3) — la temporisation apres une serie
 *    d'echecs est en revanche ANNONCEE, avec son compte a rebours. Ce n'est pas une fuite :
 *    l'attaquant la constate de toute facon en voyant ses essais refuses, et la taire
 *    faisait passer un blocage en cours pour une enieme saisie fausse. Le bouton de
 *    validation est desactive tant qu'elle court — meme traitement que l'ecran de verrouillage
 *    de l'application (audit I1 + R5).
 *
 * @param title titre du dialog (ex. "Coffre — PIN ou pass").
 * @param description sous-titre explicatif (optionnel).
 * @param confirmLabel label du bouton de validation (ex. "Déverrouiller").
 * @param onVerify suspend lambda qui prend la saisie en `CharArray` (déjà
 *   wipé par le composable APRÈS appel) et retourne un [PinVerdict]. L'UI gère
 *   le feedback : message unique sur [PinVerdict.Invalid], compte a rebours sur
 *   [PinVerdict.LockedOut].
 * @param probeLockout `null` = ce flux n'a pas de temporisation. Non-null = interroge, a
 *   l'ouverture du dialogue, le nombre de millisecondes de blocage restantes (`0` si aucun).
 *   Sans cette sonde, un dialogue rouvert pendant un blocage afficherait un champ actif qui
 *   refuse toute saisie sans jamais dire pourquoi.
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
    onVerify: suspend (CharArray) -> PinVerdict,
    onVerified: () -> Unit,
    onCancel: () -> Unit,
    onUseBiometric: (() -> Unit)? = null,
    probeLockout: (suspend () -> Long)? = null,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    val lockout = rememberLockoutState(probeLockout)
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val errorMessage = androidx.compose.ui.res.stringResource(R.string.pin_error_invalid)

    // Focus automatique sur le champ à l'ouverture du dialog (UX standard).
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val lockedOut = lockout.active
    val lockoutMessage = lockout.message()

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            // v1.27.1 (N3) — DANS le contenu du dialogue, jamais avant `AlertDialog(...)` :
            // c'est ici seulement que `LocalView` désigne la fenêtre DU DIALOGUE. Cf. KDoc.
            ProtectSecretInput()
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
                    isError = error != null || lockedOut,
                    supportingText = error?.let { msg -> { Text(msg) } },
                    enabled = !verifying && !lockedOut,
                )
                if (lockedOut) LockoutNotice(lockoutMessage)
                if (onUseBiometric != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onUseBiometric,
                        enabled = !verifying && !lockedOut,
                    ) {
                        Text(androidx.compose.ui.res.stringResource(R.string.vault_use_biometric))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = pin.isNotEmpty() && !verifying && !lockedOut,
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
                        val verdict = try { onVerify(snapshot) } finally {
                            // Le caller wipe son CharArray — on wipe AUSSI ici
                            // par défense (le contrat dit qu'il peut le faire,
                            // mais s'il oublie, on n'aura pas laissé trainer).
                            for (i in snapshot.indices) snapshot[i] = '\u0000'
                        }
                        verifying = false
                        // pin deja vide ci-dessus (audit SEC-1).
                        when (verdict) {
                            is PinVerdict.Ok -> onVerified()
                            is PinVerdict.Invalid -> error = errorMessage
                            is PinVerdict.LockedOut -> {
                                error = null
                                lockout.arm(verdict.untilWall)
                            }
                        }
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

/**
 * v1.27.10 (revue externe GitLab !38458) — temporisation partagee par les dialogues qui
 * demandent un secret : [PinEntryDialog] et le dialogue de PIN du coffre des Reglages.
 *
 * Factorise ici, et pas recopie de part et d'autre : les deux ont exactement le meme besoin —
 * armer un blocage au verdict [PinVerdict.LockedOut], le retrouver arme a la reouverture, et
 * rendre la main quand il expire.
 *
 * ⚠️ **Ce minuteur n'a AUCUNE autorite.** Il affiche et il reactive le bouton ; c'est
 * `verifyVaultPin` qui decide, sur l'instantane persistant qui croise horloge murale et horloge
 * monotone (audit R7). Avancer l'horloge du telephone raccourcit ce compte a rebours, pas le
 * blocage : le verdict suivant reste [PinVerdict.LockedOut].
 *
 * Sans lui, en revanche, le bouton restait desactive pour toujours une fois le delai ecoule —
 * exactement le defaut ferme en v1.26.0 sur l'ecran de verrouillage de l'application.
 */
@Stable
internal class LockoutState {

    /** Instant de fin, en horloge murale. `0L` = aucun blocage. */
    var untilWall by mutableLongStateOf(0L)
        private set

    /** Secondes restantes, arrondies au superieur pour ne jamais afficher « 0 s ». */
    var remainingSec by mutableIntStateOf(0)
        internal set

    val active: Boolean get() = untilWall > 0L

    fun arm(untilWallMs: Long) {
        untilWall = untilWallMs
    }

    internal fun disarm() {
        untilWall = 0L
        remainingSec = 0
    }

    @Composable
    fun message(): String = androidx.compose.ui.res.stringResource(
        R.string.lock_lockout_message,
        remainingSec,
    )
}

/**
 * Cree l'etat et l'entretient : sonde initiale puis tic d'affichage.
 *
 * @param probeLockout `null` = ce flux n'a pas de temporisation. Non-null = millisecondes
 *   restantes a l'ouverture (`0` si aucune).
 */
@Composable
internal fun rememberLockoutState(probeLockout: (suspend () -> Long)?): LockoutState {
    val state = remember { LockoutState() }

    // Un blocage arme lors d'une session precedente doit etre visible DES l'ouverture, sans
    // attendre un premier essai voue au refus.
    LaunchedEffect(probeLockout) {
        val remaining = probeLockout?.invoke() ?: 0L
        if (remaining > 0L) state.arm(System.currentTimeMillis() + remaining)
    }

    LaunchedEffect(state.untilWall) {
        if (state.untilWall <= 0L) {
            state.remainingSec = 0
            return@LaunchedEffect
        }
        while (true) {
            val left = state.untilWall - System.currentTimeMillis()
            if (left <= 0L) {
                state.disarm()
                break
            }
            state.remainingSec = ((left + 999L) / 1000L).toInt()
            delay(500L)
        }
    }
    return state
}

/**
 * v1.27.10 — le compte a rebours de temporisation, rendu LISIBLE.
 *
 * Il vivait d'abord dans le `supportingText` du champ de saisie. Material 3 rend ce texte avec
 * la couleur « desactive » des que le champ l'est — or le champ est precisement desactive
 * pendant le blocage. Le seul message expliquant pourquoi plus rien ne repond etait donc le plus
 * pale de l'ecran : l'utilisateur voyait une interface morte, sans savoir qu'elle comptait.
 * Constate sur S9 le 2026-09-07, apres la correction de la revue !38458.
 *
 * Rendu hors du champ, ce `Text` n'herite d'aucun etat desactive : couleur d'erreur, pleine
 * opacite. La lecon vaut au-dela d'ici — un message d'etat ne doit pas vivre dans le composant
 * que cet etat eteint.
 */
@Composable
internal fun LockoutNotice(message: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}
