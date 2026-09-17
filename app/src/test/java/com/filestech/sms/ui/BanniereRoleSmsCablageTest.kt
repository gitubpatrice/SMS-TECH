package com.filestech.sms.ui

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v1.28.12 (audit 3 axes, U1) — **les écrans d'où part une alerte doivent dire que rien ne
 * partira sans le rôle d'application SMS par défaut.**
 *
 * `SendSmsUseCase` refuse tout envoi quand l'application n'est pas l'application SMS par défaut.
 * Le Safety call et le mode urgence passent par là. Le 2026-09-17, [BanniereRoleSmsManquant] a
 * été câblée sur les deux écrans d'ARMEMENT — et oubliée sur `EmergencyScreen`, celui où vit
 * réellement le bouton maintenu 3 secondes, et le seul que la notification de l'écran verrouillé
 * atteint DIRECTEMENT. Le bouton restait plein et le statut annonçait « prêt ».
 *
 * C'est le motif de défaut le plus fréquent de ce dépôt : une garde posée sur un seul de plusieurs
 * jumeaux. Le compilateur ne peut rien contre lui, alors ce test le fige.
 *
 * ⚠️ **Ce que ce test ne peut PAS faire, et il faut le savoir** : la liste est explicite. Un
 * CINQUIÈME écran d'où l'on déclencherait une alerte ne s'y ajouterait pas tout seul, et ce test
 * resterait vert. Il protège contre le retrait d'un câblage existant, pas contre l'oubli d'un
 * écran futur. La seule parade à celui-là est de venir écrire son nom ici.
 */
class BanniereRoleSmsCablageTest {

    /**
     * Racine du module, quel que soit l'endroit d'où Gradle lance la JVM de test. Sans cette
     * recherche, un test qui ne trouve pas ses fichiers rendrait une liste vide — et une
     * assertion sur une liste vide passe. Cf. la leçon « résultat vide pris pour une mesure ».
     */
    private fun racineDuModule(): File {
        var dossier: File? = File("").absoluteFile
        while (dossier != null) {
            val candidat = File(dossier, "app/src/main/java/com/filestech/sms/ui/screens")
            if (candidat.isDirectory) return File(dossier, "app")
            if (File(dossier, "src/main/java/com/filestech/sms/ui/screens").isDirectory) return dossier
            dossier = dossier.parentFile
        }
        error("racine du module introuvable depuis ${File("").absolutePath}")
    }

    @Test
    fun `tout ecran qui arme ou declenche une alerte cable la banniere du role SMS`() {
        val racine = racineDuModule()
        val ecrans = listOf(
            "ui/screens/emergency/EmergencyScreen.kt",
            "ui/screens/emergency/EmergencySetupScreen.kt",
            "ui/screens/safetycall/SafetyCallSetupScreen.kt",
        )

        val sansBanniere = ecrans.filter { chemin ->
            val f = File(racine, "src/main/java/com/filestech/sms/$chemin")
            // Le témoin : un chemin faux rendrait « pas de bannière » pour une mauvaise raison.
            assertThat(f.exists()).isTrue()
            !f.readText().contains("BanniereRoleSmsManquant(")
        }

        assertThat(sansBanniere).isEmpty()
    }

    /**
     * Le contrôle négatif du test précédent : la bannière EXISTE et porte bien le nom cherché.
     * Sans lui, renommer le composable rendrait les trois écrans « sans bannière » — ou, si
     * l'assertion était inversée un jour, ferait passer un test qui ne mesure rien.
     */
    @Test
    fun `la banniere cherchee existe bien sous ce nom`() {
        val f = File(
            racineDuModule(),
            "src/main/java/com/filestech/sms/ui/components/BanniereRoleSmsManquant.kt",
        )
        assertThat(f.exists()).isTrue()
        assertThat(f.readText()).contains("fun BanniereRoleSmsManquant(")
    }
}
