package com.filestech.sms.ui.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.ViewParent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import timber.log.Timber
import java.util.WeakHashMap

/**
 * Nombre de surfaces de saisie de secret composées **par fenêtre**.
 *
 * ⚠️ Le compte est **par fenêtre et non global**, parce que `setHideOverlayWindows` est une
 * propriété de la fenêtre — et qu'un `AlertDialog` Compose crée **sa propre fenêtre**. Un compteur
 * global ferait voir « déjà armé » à la seconde surface, dont la fenêtre ne serait alors **jamais**
 * armée : la protection paraîtrait active en étant absente là où elle compte le plus.
 *
 * ⚠️ Clés faibles : une fenêtre détruite ne doit pas retenir d'entrée. Les accès sont
 * `synchronized` — les effets Compose s'exécutent sur le thread principal, mais le coût est nul et
 * l'invariant cesse de dépendre de cette hypothèse.
 */
private val overlayGuardCounts = WeakHashMap<Window, Int>()

/**
 * Arme le masquage des superpositions sur [window], en comptant les surfaces qui la partagent.
 *
 * ⚠️ Une `window` nulle ne compte pas : une surface incapable d'armer ne doit pas occuper un cran
 * du compteur, sinon la surface suivante — elle, valide — verrait « déjà armé » et n'armerait rien.
 */
private fun armOverlayGuard(window: Window?) {
    if (window == null) return
    synchronized(overlayGuardCounts) {
        val count = (overlayGuardCounts[window] ?: 0) + 1
        overlayGuardCounts[window] = count
        if (count == 1) window.setHideOverlayWindowsSafely(true)
    }
}

/** Symétrique d'[armOverlayGuard] : ne désarme qu'à la dernière surface de CETTE fenêtre. */
private fun disarmOverlayGuard(window: Window?) {
    if (window == null) return
    synchronized(overlayGuardCounts) {
        val count = (overlayGuardCounts[window] ?: 0) - 1
        if (count <= 0) {
            overlayGuardCounts.remove(window)
            window.setHideOverlayWindowsSafely(false)
        } else {
            overlayGuardCounts[window] = count
        }
    }
}

/**
 * v1.27.0 (N3) — arme la protection contre la **superposition d'écran** tant que le composable
 * appelant est dans la composition.
 *
 * À appeler depuis **toute** surface qui recueille un secret : verrou de l'application, PIN du
 * coffre, définition du PIN et du code panique, phrases secrètes de sauvegarde.
 *
 * ### Le défaut couvert
 *
 * `FLAG_SECURE` est posé sur toute l'application ([com.filestech.sms.MainActivity]) : il protège
 * ce qui **sort** de l'écran (captures, aperçu multitâche). Rien ne protégeait ce qui **entre**.
 * Une application tierce disposant de `SYSTEM_ALERT_WINDOW` pouvait poser une fenêtre au-dessus
 * de l'écran de verrouillage pour récolter les frappes du PIN, ou masquer un bouton afin d'en
 * faire actionner un autre.
 *
 * C'est la même asymétrie que celle qui a produit la majorité des constats de l'audit du
 * 2026-08-03 : une garde posée d'un seul côté d'une paire. Voir `THREAT-MODEL.md`, N3.
 *
 * ### Deux mesures, complémentaires
 *
 *  - `filterTouchesWhenObscured` : le système **ignore** les touches reçues pendant que la fenêtre
 *    est obscurcie. Disponible depuis l'API 9, il neutralise le clic à travers.
 *  - `Window.setHideOverlayWindows(true)` (API 31+) : le système **masque** les fenêtres des autres
 *    applications tant que l'écran est affiché. Couvre la récolte visuelle, que le filtre de
 *    touches ne traite pas.
 *
 * ### Pourquoi ce n'est pas appliqué globalement
 *
 * `setHideOverlayWindows(true)` en permanence ferait disparaître les superpositions légitimes
 * (bulles de conversation, filtres de lumière bleue, outils d'accessibilité) pendant toute la vie
 * de l'application — un coût d'usage permanent pour un bénéfice qui ne vaut que là où un secret
 * est frappé.
 *
 * La portée est donc **déclarée par la surface**, pas énumérée au centre. Une liste d'écrans
 * vieillit ; une capacité déclarée par l'appelant, non (cf. `THREAT-MODEL.md` §1).
 *
 * ### Le compteur, et pourquoi il est PAR FENÊTRE
 *
 * `setHideOverlayWindows` s'applique à **une fenêtre**. Chaque surface arme donc la sienne : celle
 * du dialogue quand c'en est un, celle de l'activité sinon.
 *
 * ⚠️ Un compteur **global** serait faux, et silencieusement : la seconde surface y verrait « déjà
 * armé » et **sa** fenêtre — celle d'un dialogue, donc celle qui recueille réellement le secret —
 * ne serait jamais armée. La protection paraîtrait active en étant absente là où elle compte.
 *
 * Le compte reste nécessaire lorsque plusieurs surfaces **partagent** une fenêtre (deux
 * composables de saisie dans un même écran plein) : sans lui, la sortie de la première
 * désarmerait la seconde — le piège du refcount `FLAG_SECURE` déjà rencontré sur les applications
 * Flutter du portefeuille. D'où un compte par fenêtre, à clés faibles.
 *
 * @param blockObscuredTouches ⚠️ **`false` sur l'écran de verrouillage, délibérément — ne pas
 * « corriger » cette asymétrie sans lire ce qui suit.**
 *
 * `filterTouchesWhenObscured` fait **ignorer** les touches tant que la fenêtre est obscurcie, y
 * compris par une superposition parfaitement légitime : filtre de lumière bleue tiers, outil
 * d'accessibilité, bulle de conversation. L'utilisateur ne reçoit aucun message — l'écran cesse
 * simplement de répondre.
 *
 * Sur un **dialogue**, le coût est borné : on peut en sortir et réessayer. Sur l'**écran de
 * verrouillage** d'une application qui détient le rôle SMS par défaut, ce serait un utilisateur
 * enfermé hors de sa messagerie, sans explication ni recours. Le coût du faux positif dépasse
 * alors le gain : le tapjacking suppose une application malveillante déjà installée ET autorisée à
 * se superposer, là où le blocage frappe des configurations ordinaires.
 *
 * Le verrou conserve `setHideOverlayWindows`, qui **masque** sans jamais bloquer une touche.
 *
 * ⚠️ Conséquence assumée : **sous Android 12, l'écran de verrouillage n'a aucune protection
 * anti-superposition** — `setHideOverlayWindows` n'y existe pas et le filtre de touches y est
 * volontairement désactivé. Arbitrage tranché avec Patrice le 2026-08-03.
 */
@Composable
fun ProtectSecretInput(blockObscuredTouches: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(view, blockObscuredTouches) {
        // Filtre de touches posé sur la racine de CETTE surface : une fenêtre de dialogue a sa
        // propre hiérarchie de vues, la poser sur l'activité ne la couvrirait pas.
        val root = view.rootView
        val previousFilter = root?.filterTouchesWhenObscured
        if (blockObscuredTouches) root?.filterTouchesWhenObscured = true

        // ⚠️ La fenêtre de CETTE surface, et non celle de l'activité.
        //
        // `setHideOverlayWindows` s'applique à UNE fenêtre. Or cinq des six surfaces de saisie de
        // secret sont des `AlertDialog`, et un dialogue Compose possède sa PROPRE fenêtre : armer
        // celle de l'activité les laisserait toutes découvertes, alors que ce sont précisément
        // elles qui recueillent les PIN et les phrases secrètes.
        //
        // Compose expose la fenêtre d'un dialogue via `DialogWindowProvider`, porté par la vue
        // parente. Hors dialogue (écran plein comme `LockScreen`), on retombe sur la fenêtre de
        // l'activité, obtenue en déroulant le `ContextWrapper`.
        val window = view.findDialogWindow() ?: view.context.findActivity()?.window
        armOverlayGuard(window)

        onDispose {
            // Ne restaurer que si l'on a effectivement posé le filtre : réécrire une valeur qu'on
            // n'a pas changée écraserait celle qu'une autre surface aurait posée entre-temps.
            if (blockObscuredTouches && previousFilter != null) {
                root.filterTouchesWhenObscured = previousFilter
            }
            disarmOverlayGuard(window)
        }
    }
}

/**
 * Applique `setHideOverlayWindows` sans jamais pouvoir faire tomber l'application.
 *
 * ⚠️ **Cet appel exige la permission `HIDE_OVERLAY_WINDOWS` au manifeste.** Sans elle il lève
 * `SecurityException` — ce qui a fait **crasher l'application** sur Galaxy S24 FE / Android 14 le
 * 2026-08-03, sur l'écran de définition du PIN **et** sur l'écran de verrouillage. Un utilisateur
 * dont le verrou est actif se serait retrouvé **enfermé hors de sa messagerie**.
 *
 * La permission est désormais déclarée, mais l'appel reste protégé : rien ne garantit qu'un OEM
 * l'honore, et **une garde de sécurité qui fait tomber l'application qu'elle protège est pire que
 * son absence**. En cas de refus, on renonce silencieusement au masquage — le filtre de touches et
 * `FLAG_SECURE` restent en place.
 *
 * `try`/`catch` explicite et non `runCatching` : la doctrine du projet proscrit ce dernier dès
 * qu'un appel peut se trouver dans un contexte annulable, pour ne pas avaler `CancellationException`.
 */
private fun Window.setHideOverlayWindowsSafely(hide: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    try {
        setHideOverlayWindows(hide)
    } catch (e: SecurityException) {
        Timber.w(e, "setHideOverlayWindows(%b) refusé — masquage des superpositions indisponible", hide)
    }
}

/** Déroule les `ContextWrapper` jusqu'à l'activité hôte. `null` si la vue n'en a pas (prévisualisations). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Fenêtre du dialogue Compose hébergeant cette vue, `null` hors dialogue.
 *
 * ⚠️ On **remonte** la hiérarchie au lieu de tester le parent direct. `DialogWindowProvider` est
 * aujourd'hui porté par le parent immédiat, mais s'en remettre à cette profondeur ferait retomber
 * le repli sur la fenêtre de l'activité — **en silence** — le jour où Compose insérerait une vue
 * intermédiaire. Un repli silencieux sur une garde de sécurité est précisément ce que
 * `THREAT-MODEL.md` I5 proscrit.
 */
private fun View.findDialogWindow(): Window? {
    var parent: ViewParent? = this.parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = (parent as? View)?.parent
    }
    return null
}
