package com.filestech.sms.ui.screens.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.filestech.sms.R

/**
 * 2026-11-06 (issue #76) — **à qui un message a été envoyé.**
 *
 * Un MMS adressé à plusieurs, rangé dans la conversation de son expéditeur — mode « MMS de groupe » coupé
 * (le défaut), « Mon numéro » absent, ou message importé d'une autre app —, ne disait pas qu'il était parti
 * à d'autres. Les autres applications montrent tout le monde : SMS Tech ne montrait que l'expéditeur.
 * L'en-tête du PDU est désormais gardé avec le message (`Message.mmsRecipients`), et la bulle porte une
 * ligne qui ouvre la liste.
 */

/** Un destinataire tel que la fenêtre l'affiche. [estMoi] : c'est le numéro de l'utilisateur. */
data class DestinataireAffiche(val numero: String, val estMoi: Boolean)

/** Ce que la fenêtre explique sous la liste. */
enum class AideGroupe {
    /** Reçu, mode groupe coupé. */
    ACTIVER_MMS_DE_GROUPE,

    /** Reçu, mode groupe activé mais « Mon numéro » inconnu. */
    RENSEIGNER_MON_NUMERO,

    /** Reçu, tout est réglé : le message est arrivé avant, ou vient d'une autre app. */
    DEJA_REGLE,

    /** Envoyé par l'utilisateur (importé d'une autre app) : les réglages n'y changent rien. */
    ENVOI_SORTANT,
}

/** Ce que la fenêtre montre : préparé par le ViewModel, qui seul connaît l'identité des numéros. */
data class DestinatairesUi(val destinataires: List<DestinataireAffiche>, val aide: AideGroupe)

/** Les destinataires dans l'ordre de l'en-tête ; l'utilisateur est marqué, pas retiré. */
internal fun destinatairesAffiches(bruts: List<String>, estMoi: (String) -> Boolean): List<DestinataireAffiche> =
    bruts.map { DestinataireAffiche(numero = it, estMoi = estMoi(it)) }

/**
 * Pourquoi un message REÇU est resté dans la conversation de l'expéditeur : mode groupe coupé, ou numéro de
 * l'utilisateur inconnu (sans lui, `GroupMmsMembers` ne reconstitue pas de groupe). Les deux réglés, le
 * message est arrivé avant, ou vient d'une autre app : l'import ne regroupe pas.
 */
internal fun aideGroupe(mmsDeGroupe: Boolean, monNumeroConnu: Boolean): AideGroupe = when {
    !mmsDeGroupe -> AideGroupe.ACTIVER_MMS_DE_GROUPE
    !monNumeroConnu -> AideGroupe.RENSEIGNER_MON_NUMERO
    else -> AideGroupe.DEJA_REGLE
}

/**
 * La fenêtre des destinataires d'un message, ou `null` s'il n'a pas été adressé à plusieurs.
 *
 * L'utilisateur est reconnu à la clé d'identité de « Mon numéro » ([cle], `PhoneIdentity.Snapshot.key` — la
 * règle de `GroupMmsMembers`), jamais à l'écriture brute : `+33607…` et `06 07…` sont le même numéro. Sans
 * numéro renseigné, ou sans clé, personne n'est marqué.
 */
internal fun preparerDestinataires(
    destinataires: List<String>,
    envoye: Boolean,
    monNumero: String?,
    mmsDeGroupe: Boolean,
    cle: ((String) -> String)?,
): DestinatairesUi? {
    if (destinataires.size < 2) return null
    val cleMoi = monNumero?.takeIf { it.isNotBlank() }
        ?.let { moi -> cle?.invoke(moi) }
        ?.takeIf { it.isNotBlank() }
    val liste = destinatairesAffiches(destinataires) { brut -> cleMoi != null && cle?.invoke(brut) == cleMoi }
    val aide = if (envoye) AideGroupe.ENVOI_SORTANT else aideGroupe(mmsDeGroupe, monNumeroConnu = cleMoi != null)
    return DestinatairesUi(liste, aide)
}

/**
 * Une ligne « texte + bouton copier ». La copie passe par l'appelant (`copyToClipboardSensitive`, N4). L'icône
 * devient une coche, annoncée par TalkBack : un snackbar s'afficherait SOUS la fenêtre.
 *
 * @param principal le texte de la ligne (nom, ou numéro) ; [secondaire], en dessous, le numéro quand
 *   [principal] est un nom.
 */
@Composable
internal fun LigneNumeroCopiable(
    principal: String,
    numero: String,
    descriptionCopie: String,
    onCopy: (String) -> Unit,
    secondaire: String? = null,
) {
    var copie by remember(numero) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(principal, style = MaterialTheme.typography.bodyMedium)
            if (secondaire != null) {
                Text(
                    secondaire,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = {
                onCopy(numero)
                copie = true
            },
            // TalkBack lit « Numéro copié » : sans région vivante, le libellé changerait en silence.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Icon(
                imageVector = if (copie) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                contentDescription = if (copie) {
                    stringResource(R.string.thread_details_number_copied)
                } else {
                    descriptionCopie
                },
            )
        }
    }
}

/** La ligne sous la bulle d'un message adressé à plusieurs. */
@Composable
internal fun LigneMessageDeGroupe(nombre: Int, isOutgoing: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        TextButton(onClick = onClick) {
            Text(
                pluralStringResource(R.plurals.thread_group_message_recipients, nombre, nombre),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * La liste des destinataires d'un message, chacun copiable, puis, pour un message reçu, ce qu'il faut régler
 * pour que les prochains messages de groupe ouvrent une conversation avec tout le monde. [noms] : les noms de
 * contact connus, par numéro brut.
 */
@Composable
internal fun DestinatairesMmsDialog(
    ui: DestinatairesUi,
    noms: Map<String, String>,
    onCopy: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val reglages = stringResource(R.string.settings_title)
    val section = stringResource(R.string.settings_section_sending)
    val groupe = stringResource(R.string.settings_group_mms)
    val monNumero = stringResource(R.string.settings_my_number)
    val aide = when (ui.aide) {
        AideGroupe.ACTIVER_MMS_DE_GROUPE ->
            stringResource(R.string.mms_recipients_hint_enable, groupe, monNumero, reglages, section)
        AideGroupe.RENSEIGNER_MON_NUMERO ->
            stringResource(R.string.mms_recipients_hint_number, groupe, monNumero, reglages, section)
        AideGroupe.DEJA_REGLE -> stringResource(R.string.mms_recipients_hint_before, groupe, monNumero)
        AideGroupe.ENVOI_SORTANT -> stringResource(R.string.mms_recipients_hint_outgoing)
    }
    val versLesReglages = ui.aide == AideGroupe.ACTIVER_MMS_DE_GROUPE || ui.aide == AideGroupe.RENSEIGNER_MON_NUMERO
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mms_recipients_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ui.destinataires.forEach { d ->
                    val nom = noms[d.numero]
                    val principal = (nom ?: d.numero).let {
                        if (d.estMoi) stringResource(R.string.mms_recipients_you, it) else it
                    }
                    LigneNumeroCopiable(
                        principal = principal,
                        secondaire = d.numero.takeIf { nom != null },
                        numero = d.numero,
                        // Le numéro, pas le nom : deux numéros d'un même contact se distinguent à l'oreille.
                        descriptionCopie = stringResource(R.string.thread_details_copy_number_of, d.numero),
                        onCopy = onCopy,
                    )
                }
                Spacer(Modifier.padding(top = 8.dp))
                Text(aide, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (versLesReglages) {
                TextButton(onClick = onOpenSettings) { Text(reglages) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
        dismissButton = if (versLesReglages) {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
        } else {
            null
        },
    )
}
