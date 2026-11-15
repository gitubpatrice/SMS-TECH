package com.filestech.sms.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/**
 * 2026-11-12 (issue #87) — **afficher ou masquer, à la demande, la ligne « heure • état » d'un message.**
 *
 * Cette ligne ne s'affiche d'office qu'en tête d'une rafale et sous le dernier message envoyé d'une série
 * (cf. `ligneHeureDOffice`). Pour tout autre message, l'heure et l'état se demandent : par un toucher sur une bulle
 * de texte, ou par l'entrée du menu ⋮ — seul chemin pour une photo, dont le toucher ouvre la visionneuse, et pour
 * un message vocal, dont le toucher lance la lecture.
 *
 * Un seul objet, porté par un seul paramètre : `MessageBubble` est à 19 paramètres sur un plafond detekt de 20.
 *
 * @property libelle « Afficher » ou « Masquer l'heure et l'état » : l'entrée du menu et l'action d'accessibilité
 *   du toucher.
 * @property basculer l'action.
 */
@Immutable
class ActionHeure(@StringRes val libelle: Int, val basculer: () -> Unit)
