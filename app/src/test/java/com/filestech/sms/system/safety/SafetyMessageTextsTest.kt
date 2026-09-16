package com.filestech.sms.system.safety

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.domain.emergency.EmergencyTemplate
import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.filestech.sms.domain.safetycall.SafetyCallTemplate
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * v1.28.12 — **les textes qui PARTENT, dans les trois langues, tels qu'ils sortiront.**
 *
 * Ce que ce fichier remplace
 * ---------------------------
 * Des gardes existaient déjà sur ces messages (`AuditV1100Test`, `SafetyCallRelanceTest`,
 * `AuditV190Test`) : pas de tiret cadratin, un segment GSM-7, la relance nomme l'application, la
 * dernière s'annonce. Elles étaient justes — et elles ne regardaient **qu'une seule langue**,
 * parce qu'il n'y en avait qu'une : les textes étaient écrits en français dans le code et
 * partaient en français à tout le monde, anglophones compris.
 *
 * Les mêmes garanties sont donc vérifiées ici sur les RESSOURCES RÉELLES, **langue par langue**,
 * à travers l'implémentation qui sert aussi à l'envoi. Une langue ajoutée sans respecter les
 * contraintes fait rougir ce fichier, et non l'écran de quelqu'un en situation d'urgence.
 *
 * ⚠️ Ajouter une langue = ajouter son code dans [LANGUES]. Le contrôle de parité
 * (`.github/scripts/i18n-parite.py`) garantit par ailleurs qu'aucune clé ne manque.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [33])
class SafetyMessageTextsTest {

    private companion object {
        /** Les langues livrées. En ajouter une ici quand `values-XX/` apparaît. */
        val LANGUES = listOf("en", "fr", "de")

        /** Une URL Maps réaliste : elle compte dans le budget de caractères du SMS. */
        const val URL_MAPS = "https://maps.google.com/?q=48.85661,2.35222"

        /** U+2014. Il bascule le message en UCS-2, donc 70 caractères par segment au lieu de 160. */
        const val TIRET_CADRATIN = "—"
    }

    private fun textesPour(langue: String): SafetyMessageTextsSousLangue {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(langue))
        }
        return SafetyMessageTextsSousLangue(
            langue,
            AndroidSafetyMessageTexts(base.createConfigurationContext(config)),
        )
    }

    private class SafetyMessageTextsSousLangue(
        val langue: String,
        val textes: AndroidSafetyMessageTexts,
    )

    private fun pourChaqueLangue(bloc: (SafetyMessageTextsSousLangue) -> Unit) {
        // Le témoin : si la liste était vide, chaque test ci-dessous passerait sans rien mesurer.
        assertThat(LANGUES).isNotEmpty()
        LANGUES.forEach { bloc(textesPour(it)) }
    }

    // ──────────────── Le message d'urgence ────────────────

    @Test
    fun `chaque langue rend les trois messages d urgence, non vides et avec la position`() {
        pourChaqueLangue { l ->
            EmergencyTemplate.entries.forEach { modele ->
                val corps = l.textes.emergencyBody(modele, URL_MAPS)
                assertThat(corps.trim()).isNotEmpty()
                assertThat(corps).contains(URL_MAPS)
            }
        }
    }

    @Test
    fun `une position absente devient une mention explicite, jamais un trou`() {
        // Le destinataire doit pouvoir distinguer « je n'ai pas pu donner ma position » d'un
        // défaut de l'application. Vaut pour `null` comme pour une chaîne vide.
        pourChaqueLangue { l ->
            listOf(null, "", "   ").forEach { absente ->
                val corps = l.textes.emergencyBody(EmergencyTemplate.NEED_HELP, absente)
                assertThat(corps).doesNotContain("http")
                // Quelque chose occupe la place de la position : le message ne s'arrête pas net.
                assertThat(corps.trim().length).isGreaterThan(20)
            }
        }
    }

    @Test
    fun `aucun message d urgence ne porte de tiret cadratin (SEC-5)`() {
        pourChaqueLangue { l ->
            EmergencyTemplate.entries.forEach { modele ->
                assertThat(l.textes.emergencyBody(modele, URL_MAPS))
                    .doesNotContain(TIRET_CADRATIN)
            }
        }
    }

    @Test
    fun `chaque message d urgence tient dans 160 caracteres, URL comprise`() {
        // Cap absolu hérité de la v1.10.0 : au-delà, le SMS part en plusieurs segments et le
        // second peut se perdre en zone radio faible — la situation même que ce message vise.
        pourChaqueLangue { l ->
            EmergencyTemplate.entries.forEach { modele ->
                val corps = l.textes.emergencyBody(modele, URL_MAPS)
                assertThat(corps.length).isAtMost(160)
            }
        }
    }

    @Test
    fun `le modele DISCREET ne porte PAS le triangle d alerte`() {
        // Il sert à signaler un malaise sans alarmer, et sans se trahir devant quelqu'un qui
        // regarde l'écran. Un triangle rouge défait exactement ce but.
        pourChaqueLangue { l ->
            assertThat(l.textes.emergencyBody(EmergencyTemplate.DISCREET, URL_MAPS))
                .doesNotContain("⚠")
        }
    }

    @Test
    fun `les deux modeles pressants portent le triangle d alerte`() {
        pourChaqueLangue { l ->
            listOf(EmergencyTemplate.NEED_HELP, EmergencyTemplate.DANGER).forEach { modele ->
                assertThat(l.textes.emergencyBody(modele, URL_MAPS)).contains("⚠")
            }
        }
    }

    @Test
    fun `les trois messages d urgence different dans chaque langue`() {
        // Trois choix qui produiraient le même SMS ne seraient pas trois choix.
        pourChaqueLangue { l ->
            val corps = EmergencyTemplate.entries.map { l.textes.emergencyBody(it, URL_MAPS) }
            assertThat(corps.toSet()).hasSize(EmergencyTemplate.entries.size)
        }
    }

    // ──────────────── Le message de Safety Call ────────────────

    @Test
    fun `chaque langue rend les trois modeles de Safety Call avec la duree`() {
        pourChaqueLangue { l ->
            val duree = l.textes.durationLabel(SafetyCallConfig.TIMEOUT_48H_MS)
            listOf(
                SafetyCallTemplate.CHECK_IN,
                SafetyCallTemplate.URGENT,
                SafetyCallTemplate.FOLLOW_UP,
            ).forEach { modele ->
                val corps = l.textes.safetyCallBody(modele, SafetyCallConfig.TIMEOUT_48H_MS, "")
                assertThat(corps.trim()).isNotEmpty()
                assertThat(corps).contains(duree)
                assertThat(corps).doesNotContain(TIRET_CADRATIN)
                // Aucun jeton de durée ne doit survivre au rendu.
                SafetyCallTemplate.JETONS_DE_DUREE.forEach { assertThat(corps).doesNotContain(it) }
            }
        }
    }

    @Test
    fun `un message personnalise vide rend un corps vide, et ne part donc pas`() {
        // La séquence s'appuie dessus : un corps vide désarme au lieu d'envoyer du blanc.
        pourChaqueLangue { l ->
            val corps = l.textes.safetyCallBody(
                SafetyCallTemplate.CUSTOM,
                SafetyCallConfig.TIMEOUT_24H_MS,
                "   ",
            )
            assertThat(corps.trim()).isEmpty()
        }
    }

    @Test
    fun `un message personnalise garde les mots de l utilisateur et recoit la duree`() {
        pourChaqueLangue { l ->
            val duree = l.textes.durationLabel(SafetyCallConfig.TIMEOUT_24H_MS)
            val corps = l.textes.safetyCallBody(
                SafetyCallTemplate.CUSTOM,
                SafetyCallConfig.TIMEOUT_24H_MS,
                "Inactif depuis [DURÉE], appelle-moi.",
            )
            assertThat(corps).isEqualTo("Inactif depuis $duree, appelle-moi.")
        }
    }

    // ──────────────── Les relances ────────────────

    @Test
    fun `chaque relance nomme l application dans chaque langue`() {
        // Un SMS reçu en pleine nuit disant « vérifie que je vais bien », sans émetteur
        // identifiable, ressemble à du hameçonnage — et se fait ignorer.
        pourChaqueLangue { l ->
            for (index in 1..SafetyCallConfig.RELANCE_COUNT) {
                assertThat(l.textes.safetyCallRelance(index)).contains("SMS Tech")
            }
        }
    }

    @Test
    fun `chaque relance annonce le vrai delai, et la derniere se distingue`() {
        pourChaqueLangue { l ->
            val textes = (1..SafetyCallConfig.RELANCE_COUNT).map { l.textes.safetyCallRelance(it) }
            // Le délai annoncé doit être le vrai : quelqu'un décide d'agir sur sa foi.
            textes.forEachIndexed { i, texte ->
                assertThat(texte).contains(SafetyCallTemplate.minutesDeRelance(i + 1).toString())
            }
            // La dernière doit se distinguer des précédentes : sans cela un contact attend une
            // suite qui ne viendra jamais au lieu d'agir.
            assertThat(textes.last()).isNotEqualTo(textes.first())
            assertThat(textes.toSet()).hasSize(SafetyCallConfig.RELANCE_COUNT)
        }
    }

    @Test
    fun `aucune relance ne porte de tiret cadratin`() {
        pourChaqueLangue { l ->
            for (index in 1..SafetyCallConfig.RELANCE_COUNT) {
                assertThat(l.textes.safetyCallRelance(index)).doesNotContain(TIRET_CADRATIN)
            }
        }
    }

    @Test
    fun `une relance ne se fait pas passer pour le message initial`() {
        pourChaqueLangue { l ->
            val initial = l.textes.safetyCallBody(
                SafetyCallTemplate.CHECK_IN,
                SafetyCallConfig.TIMEOUT_24H_MS,
                "",
            )
            for (index in 1..SafetyCallConfig.RELANCE_COUNT) {
                assertThat(l.textes.safetyCallRelance(index)).isNotEqualTo(initial)
            }
        }
    }

    // ──────────────── Le libellé de durée ────────────────

    @Test
    fun `la duree est rendue dans chaque langue, et 24 h se dit en jours`() {
        pourChaqueLangue { l ->
            val unJour = l.textes.durationLabel(SafetyCallConfig.TIMEOUT_24H_MS)
            val deuxJours = l.textes.durationLabel(SafetyCallConfig.TIMEOUT_48H_MS)
            val cinqHeures = l.textes.durationLabel(5 * 60 * 60 * 1000L)
            val moinsDUneHeure = l.textes.durationLabel(0L)
            listOf(unJour, deuxJours, cinqHeures, moinsDUneHeure).forEach {
                assertThat(it.trim()).isNotEmpty()
            }
            // Le singulier et le pluriel doivent différer : « 1 jour » / « 2 jours ».
            assertThat(unJour).isNotEqualTo(deuxJours)
            // Un jour ne se dit pas comme cinq heures, sinon l'arrondi ne sert à rien.
            assertThat(unJour).isNotEqualTo(cinqHeures)
            // Le nombre est présent dès qu'il y en a un.
            assertThat(unJour).contains("1")
            assertThat(deuxJours).contains("2")
            assertThat(cinqHeures).contains("5")
        }
    }

    // ──────────────── Le témoin négatif de ce fichier ────────────────

    @Test
    fun `les langues rendent bien des textes DIFFERENTS`() {
        // Si `createConfigurationContext` ne prenait pas, tous les tests ci-dessus passeraient en
        // ne mesurant qu'une seule langue trois fois — exactement le genre de vert creux que ce
        // projet a déjà payé. Ici on exige que l'anglais, le français et l'allemand diffèrent.
        val corps = LANGUES.map {
            textesPour(it).textes.emergencyBody(EmergencyTemplate.NEED_HELP, URL_MAPS)
        }
        assertThat(corps.toSet()).hasSize(LANGUES.size)
    }
}
