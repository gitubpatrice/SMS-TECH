package com.filestech.sms.ui.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.concurrent.atomic.AtomicInteger

/**
 * Nombre de surfaces de saisie de secret actuellement composées.
 *
 * ⚠️ Incrémenté **inconditionnellement**, alors que l'effet qu'il pilote est réservé à l'API 31+.
 * Le garder aligné sur la version rendrait le compteur faux le jour où une seconde mesure, sans
 * la même borne, viendrait s'y accrocher.
 */
private val activeSecretInputs = AtomicInteger(0)

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
 * ### Le compteur, et pourquoi il est indispensable
 *
 * ⚠️ Les surfaces se **superposent** : le dialogue de saisie du PIN du coffre s'ouvre au-dessus
 * d'un écran ayant déjà armé la protection. Sans compteur, la fermeture du dialogue appellerait
 * `setHideOverlayWindows(false)` et **désarmerait la protection de l'écran encore affiché** —
 * exactement le piège du refcount `FLAG_SECURE` déjà rencontré sur les applications Flutter du
 * portefeuille. L'effet n'est donc appliqué qu'aux transitions 0 → 1 et 1 → 0.
 */
@Composable
fun ProtectSecretInput() {
    val view = LocalView.current
    DisposableEffect(view) {
        // Filtre de touches posé sur la racine de CETTE surface : une fenêtre de dialogue a sa
        // propre hiérarchie de vues, la poser sur l'activité ne la couvrirait pas.
        val root = view.rootView
        val previousFilter = root?.filterTouchesWhenObscured
        root?.filterTouchesWhenObscured = true

        // Le masquage des superpositions est une propriété de la fenêtre de l'ACTIVITÉ.
        // `view.context` est un `ContextWrapper` dans un dialogue, d'où le déroulage.
        val window = view.context.findActivity()?.window
        val isFirst = activeSecretInputs.incrementAndGet() == 1
        if (isFirst && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window?.setHideOverlayWindows(true)
        }

        onDispose {
            if (previousFilter != null) root.filterTouchesWhenObscured = previousFilter
            val isLast = activeSecretInputs.decrementAndGet() == 0
            if (isLast && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window?.setHideOverlayWindows(false)
            }
        }
    }
}

/** Déroule les `ContextWrapper` jusqu'à l'activité hôte. `null` si la vue n'en a pas (prévisualisations). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
