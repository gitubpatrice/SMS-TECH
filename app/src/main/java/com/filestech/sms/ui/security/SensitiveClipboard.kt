package com.filestech.sms.ui.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

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
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    manager.setPrimaryClip(clip)
}
