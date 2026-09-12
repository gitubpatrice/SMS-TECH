package com.filestech.sms.ui.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard

/**
 * v1.27.0 (N4) — copie dans le presse-papier en marquant le contenu **sensible**.
 *
 * ### Le défaut couvert
 *
 * Depuis Android 13, le système affiche une **vignette d'aperçu** du contenu copié, en surimpression
 * et en clair. Copier un message issu du **coffre** l'exposait donc à l'écran, hors de tout ce que
 * le coffre protège : ni SQLCipher, ni le second facteur, ni `FLAG_SECURE` ne couvrent cette
 * vignette, dessinée par le système.
 *
 * C'est l'invariant I7 de `THREAT-MODEL.md` : **ce qui sort de l'application sort du périmètre du
 * coffre.** `ClipDescription.EXTRA_IS_SENSITIVE` demande au système de remplacer l'aperçu par un
 * texte neutre, et signale aux claviers de ne pas mémoriser la valeur dans leur historique.
 *
 * ### Pourquoi TOUTES les copies sont marquées, et pas seulement celles du coffre
 *
 * Deux raisons, dans cet ordre :
 *
 *  1. **Marquer seulement le coffre exigerait de propager « ce message est au coffre » jusqu'au
 *     bouton copier.** Ce serait un drapeau de plus à ne pas oublier sur chaque nouveau chemin de
 *     copie — c'est-à-dire précisément la fabrique d'asymétries que l'audit du 2026-08-03 a
 *     documentée. Un prédicat uniforme ne peut pas être oublié sur une branche.
 *  2. Le contenu d'un SMS ordinaire — code à usage unique, adresse, identifiant — mérite de toute
 *     façon de ne pas s'afficher en surimpression ni d'entrer dans l'historique du clavier.
 *
 * Le coût est une vignette d'aperçu générique au lieu du texte copié. La copie elle-même est
 * inchangée : le collage rend exactement le même contenu.
 *
 * ### Portée réelle
 *
 * ⚠️ `EXTRA_IS_SENSITIVE` n'existe qu'à partir d'Android 13 (API 33). En deçà, l'appel reste
 * fonctionnellement correct — la copie a lieu — mais **aucune protection n'est obtenue**, le
 * système n'affichant de toute façon pas de vignette avant cette version.
 *
 * ⚠️ Le marquage est une **demande** adressée au système et aux claviers. Un clavier tiers qui
 * l'ignore n'est pas couvert. Cela ne remplace pas la règle d'usage : ne pas copier ce qu'on ne
 * veut pas voir quitter l'application.
 *
 * ⚠️ **Best-effort assumé** : si le service de presse-papier est indisponible, la fonction rend la
 * main sans rien copier et **sans le signaler**. Les appelants affichent leur confirmation
 * « Copié » sans condition. Le cas ne se produit pas depuis un contexte d'activité ; le contrat est
 * écrit ici plutôt que supposé, pour qu'un futur appelant depuis un contexte plus exotique sache
 * qu'il doit vérifier lui-même.
 *
 * @param label étiquette technique du `ClipData`, non affichée à l'utilisateur.
 * @param text contenu à copier.
 */
fun Context.copyToClipboardSensitive(label: String, text: String) {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text).markSensitive())
}

/**
 * v1.28.6 — la marque « sensible » posée sur un [ClipData], **quel que soit l'appareil**.
 *
 * Avant, la marque n'était posée qu'à partir d'Android 13, seule version qui la lit. Elle est
 * désormais posée partout : en deçà, le système l'ignore et rien ne change ; au-delà, le
 * comportement est identique. Ce qui change, c'est que la marque devient **vérifiable sur les
 * appareils de mesure** (S9 sous Android 10), là où un `if` sur la version rendait tout test
 * vert sans rien prouver. La clé est copiée en clair : la constante `EXTRA_IS_SENSITIVE`
 * n'existe qu'en API 33 et son inlining aurait valu un avertissement lint sans rien apporter.
 *
 * Mutation en place, sur la description du clip reçu, et retour du même objet pour chaîner.
 */
fun ClipData.markSensitive(): ClipData = apply {
    description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
}

/** Valeur de `ClipDescription.EXTRA_IS_SENSITIVE` (API 33), utilisable sur toute version. */
const val EXTRA_IS_SENSITIVE: String = "android.content.extra.IS_SENSITIVE"

/**
 * v1.28.6 — le même invariant, pour les copies faites **par Compose** et non par l'application.
 *
 * Le menu système d'une sélection de texte (`SelectionContainer`) copie via [Clipboard], pas via
 * [copyToClipboardSensitive] : sans cette enveloppe, sélectionner un extrait d'un message du
 * coffre le faisait ressortir en vignette d'aperçu sous Android 13+, exactement le défaut N4 que
 * la copie totale avait fermé en v1.27.0. À fournir par `CompositionLocalProvider(LocalClipboard
 * provides SensitiveClipboard(LocalClipboard.current))` autour de tout conteneur sélectionnable.
 *
 * La lecture et le presse-papiers natif sont délégués tels quels : seule l'écriture est marquée.
 */
class SensitiveClipboard(private val delegate: Clipboard) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        clipEntry?.clipData?.markSensitive()
        delegate.setClipEntry(clipEntry)
    }

    override val nativeClipboard: NativeClipboard get() = delegate.nativeClipboard
}
