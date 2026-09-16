package com.filestech.sms.upgrade

/**
 * Marque les deux moitiés du contrôle de mise à jour EN PLACE : [UpgradeSeedTest], qui écrit le jeu
 * d'essai depuis la version précédente, et [UpgradeVerifyTest], qui le relit depuis la version
 * courante installée par-dessus.
 *
 * # Pourquoi elles sont exclues de la campagne ordinaire
 *
 * `app/build.gradle.kts` pose `testInstrumentationRunnerArguments["notAnnotation"]` sur cette
 * annotation. Hors du job dédié, le semis n'a aucun lecteur et la vérification n'a rien à lire :
 * elle échouerait sur une base vide — ce qui est exactement le comportement que son contrôle
 * négatif EXIGE d'elle. Une vérification qui passerait sur une base vide ne prouverait rien.
 *
 * # Pourquoi le job ne passe pas par `connectedDebugAndroidTest`
 *
 * Cette tâche **désinstalle le paquet en fin de course** (mesuré le 2026-09-14 : elle retire du
 * même coup le rôle SMS). Elle effacerait donc le jeu d'essai entre le semis et sa relecture,
 * c'est-à-dire précisément ce que le contrôle cherche à observer. Le job installe par
 * `adb install -r` et lance chaque moitié par `am instrument -e class …`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class UpgradeTest
