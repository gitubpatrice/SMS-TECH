package com.filestech.sms.system.emergency

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.12 — la table des numéros d'urgence.
 *
 * Ce qui est éprouvé ici n'est pas du code, c'est une TABLE : une liste d'instructions données
 * à quelqu'un qui n'a pas le temps de les vérifier. Un numéro faux ne fait pas planter
 * l'application — il fait perdre des secondes à quelqu'un qui n'en a pas. Les invariants
 * ci-dessous sont donc écrits pour attraper la faute de frappe et l'oubli, pas la régression
 * de logique : ils tiendront quand la table s'allongera, et ils rougiront si elle s'allonge mal.
 *
 * La résolution du pays depuis la téléphonie n'est pas testée ici : elle n'a rien à décider,
 * elle lit `networkCountryIso` puis `simCountryIso`. Ce qui décide — la table — est pur, et
 * c'est exactement ce que [EmergencyNumbers.pourLePays] expose.
 */
class EmergencyNumbersTest {

    // ──────────────── Les invariants qui valent pour TOUS les pays ────────────────

    @Test fun `le 112 est propose dans chaque pays de la table`() {
        for (pays in EmergencyNumbers.PAYS_COUVERTS) {
            assertThat(EmergencyNumbers.pourLePays(pays).map { it.number }).contains("112")
        }
    }

    @Test fun `le 112 est TOUJOURS en premier`() {
        // Il est le seul numéro correct partout, et le seul joignable sans SIM : il doit être
        // la premiere tuile sous le pouce, pas la quatrieme.
        for (pays in EmergencyNumbers.PAYS_COUVERTS + setOf("zz", "")) {
            val premier = EmergencyNumbers.pourLePays(pays).first()
            assertThat(premier.service).isEqualTo(EmergencyNumbers.Service.EUROPEAN)
            assertThat(premier.number).isEqualTo("112")
        }
    }

    @Test fun `aucun pays ne propose deux fois le meme numero`() {
        for (pays in EmergencyNumbers.PAYS_COUVERTS) {
            val numeros = EmergencyNumbers.pourLePays(pays).map { it.number }
            assertThat(numeros).containsNoDuplicates()
        }
    }

    @Test fun `aucun pays ne propose deux fois le meme service`() {
        for (pays in EmergencyNumbers.PAYS_COUVERTS) {
            val services = EmergencyNumbers.pourLePays(pays).map { it.service }
            assertThat(services).containsNoDuplicates()
        }
    }

    @Test fun `tout numero est fait de chiffres et reste court`() {
        // Un numero d'urgence se compose de tete. Au-dela de quatre chiffres, ce n'en est pas un
        // — et un caractere non numerique trahirait une faute de frappe dans la table.
        //
        // La borne basse est DEUX, pas trois : les numeros francais en font deux (15, 17, 18).
        // La premiere ecriture de ce test exigeait trois chiffres et a rougi sur la France —
        // c'etait l'assertion qui avait tort, pas la table.
        for (numero in EmergencyNumbers.NUMEROS_AUTORISES) {
            assertThat(numero).matches("[0-9]{2,4}")
        }
    }

    @Test fun `la liste blanche contient exactement les numeros de la table`() {
        // La propriete de securite : EmergencyCallHelper n'accepte QUE cet ensemble. S'il
        // manquait un numero, la tuile s'afficherait et l'appel serait refuse en silence.
        val depuisLesPays = EmergencyNumbers.PAYS_COUVERTS
            .flatMap { pays -> EmergencyNumbers.pourLePays(pays).map { it.number } }
        val depuisLaTable = (depuisLesPays + EmergencyNumbers.PAR_DEFAUT.map { it.number }).toSet()
        assertThat(EmergencyNumbers.NUMEROS_AUTORISES).isEqualTo(depuisLaTable)
    }

    @Test fun `les codes pays de la table sont en minuscules et a deux lettres`() {
        // networkCountryIso les rend en minuscules ; une cle en majuscules ne serait jamais
        // trouvee et le pays retomberait en silence sur le 112 seul.
        for (pays in EmergencyNumbers.PAYS_COUVERTS) {
            assertThat(pays).matches("[a-z]{2}")
        }
    }

    // ──────────────── Un pays inconnu, et la casse ────────────────

    @Test fun `un pays absent de la table recoit le 112 seul`() {
        assertThat(EmergencyNumbers.pourLePays("jp")).isEqualTo(EmergencyNumbers.PAR_DEFAUT)
        assertThat(EmergencyNumbers.pourLePays("")).isEqualTo(EmergencyNumbers.PAR_DEFAUT)
    }

    @Test fun `le code pays est insensible a la casse`() {
        // getNetworkCountryIso est documente en minuscules, mais getSimCountryIso a deja rendu
        // des majuscules sur certains appareils. Se fier a la documentation coute ici une
        // tuile Police.
        assertThat(EmergencyNumbers.pourLePays("DE")).isEqualTo(EmergencyNumbers.pourLePays("de"))
        assertThat(EmergencyNumbers.pourLePays("Fr")).isEqualTo(EmergencyNumbers.pourLePays("fr"))
    }

    // ──────────────── Les pays nommes, un par un ────────────────

    private fun numeroDe(pays: String, service: EmergencyNumbers.Service): String? =
        EmergencyNumbers.pourLePays(pays).firstOrNull { it.service == service }?.number

    @Test fun `France - les quatre numeros servis depuis la v1_14_1`() {
        assertThat(numeroDe("fr", EmergencyNumbers.Service.MEDICAL)).isEqualTo("15")
        assertThat(numeroDe("fr", EmergencyNumbers.Service.POLICE)).isEqualTo("17")
        assertThat(numeroDe("fr", EmergencyNumbers.Service.FIRE)).isEqualTo("18")
    }

    @Test fun `Allemagne - la police est le 110, et surtout PAS le 17`() {
        // Le defaut que tout ce fichier existe pour empecher.
        assertThat(numeroDe("de", EmergencyNumbers.Service.POLICE)).isEqualTo("110")
        assertThat(EmergencyNumbers.pourLePays("de").map { it.number }).doesNotContain("17")
    }

    @Test fun `Italie - police 113, pompiers 115, secours 118`() {
        assertThat(numeroDe("it", EmergencyNumbers.Service.POLICE)).isEqualTo("113")
        assertThat(numeroDe("it", EmergencyNumbers.Service.FIRE)).isEqualTo("115")
        assertThat(numeroDe("it", EmergencyNumbers.Service.MEDICAL)).isEqualTo("118")
    }

    @Test fun `Espagne - police 091, et rien de regional`() {
        // Le 061 (sanitaire) et le 080 (pompiers) varient d'une communaute autonome a l'autre :
        // ils sont volontairement absents, le 112 les couvre.
        assertThat(numeroDe("es", EmergencyNumbers.Service.POLICE)).isEqualTo("091")
        assertThat(numeroDe("es", EmergencyNumbers.Service.MEDICAL)).isNull()
        assertThat(numeroDe("es", EmergencyNumbers.Service.FIRE)).isNull()
    }

    @Test fun `les trois langues visees par la v1_28_12 sont couvertes`() {
        // L'allemand, l'italien et l'espagnol arrivent dans cette version : leurs pays doivent
        // etre dans la table AVANT, sinon la traduction livre une tuile Polizei qui fait le 17.
        assertThat(EmergencyNumbers.PAYS_COUVERTS).containsAtLeast("de", "it", "es", "fr")
    }

    // ──────────────── La police, telle que la lit le raccourci d'ecran verrouille ────────────────

    @Test fun `police - un pays sans numero dedie retombe sur le 112`() {
        // Le Portugal n'a pas de numero de police distinct dans la table. L'action rapide de la
        // notification ne doit pas disparaitre pour autant : elle compose le 112.
        val portugal = EmergencyNumbers.pourLePays("pt")
        assertThat(portugal.firstOrNull { it.service == EmergencyNumbers.Service.POLICE }).isNull()
        assertThat(portugal.map { it.number }).containsExactly("112")
    }

    @Test fun `Royaume-Uni et Irlande - le 999 est NATIONAL, jamais POLICE`() {
        // Verification externe du 2026-09-17 : le 999 britannique et irlandais n'est pas la ligne
        // de la police, c'est le numero d'urgence GENERAL (le numero specifique de la police y est
        // le 101, et il est NON URGENT). Il reste dans la table parce qu'un Britannique cherche le
        // 999 : une table « par pays » qui n'afficherait rien de national au Royaume-Uni ne ferait
        // pas son travail. C'est le LIBELLE qui etait faux, pas le numero.
        for (pays in listOf("gb", "ie")) {
            val dials = EmergencyNumbers.pourLePays(pays)
            assertThat(dials.map { it.number }).containsExactly("112", "999").inOrder()
            assertThat(numeroDe(pays, EmergencyNumbers.Service.NATIONAL)).isEqualTo("999")
            assertThat(numeroDe(pays, EmergencyNumbers.Service.POLICE)).isNull()
        }
    }

    @Test fun `aucun pays ne melange NATIONAL et un service precis`() {
        // NATIONAL veut dire « le standard toutes urgences ». Un pays qui aurait a la fois un
        // numero general ET des numeros par service afficherait deux promesses contradictoires :
        // soit on sait a qui l'on parle, soit on ne le sait pas.
        for (pays in EmergencyNumbers.PAYS_COUVERTS) {
            val services = EmergencyNumbers.pourLePays(pays).map { it.service }.toSet()
            if (EmergencyNumbers.Service.NATIONAL in services) {
                assertThat(services).containsNoneOf(
                    EmergencyNumbers.Service.POLICE,
                    EmergencyNumbers.Service.FIRE,
                    EmergencyNumbers.Service.MEDICAL,
                )
            }
        }
    }

    // ──────────────── Le filet de l'OS, et ce qu'il n'a pas le droit de retirer ────────────────

    @Test fun `le 112 traverse le filtre de l OS meme quand l OS refuse TOUT`() {
        // LA propriete la plus critique du fichier. Elle tenait a l'ordre des operandes d'un `||`
        // que rien ne verrouillait : `pour()` exige un Context, donc aucun test JVM ne pouvait
        // l'atteindre. Deux audits independants l'ont releve le meme jour.
        for (pays in EmergencyNumbers.PAYS_COUVERTS) {
            val filtres = EmergencyNumbers.filtrerParLOS(EmergencyNumbers.pourLePays(pays)) { false }
            assertThat(filtres.map { it.number }).containsExactly("112")
        }
    }

    @Test fun `un OS qui reconnait tout ne retire rien`() {
        // Le temoin positif du test precedent : sans lui, un filtre qui viderait tout
        // SAUF le 112 passerait les deux.
        for (pays in EmergencyNumbers.PAYS_COUVERTS) {
            val attendus = EmergencyNumbers.pourLePays(pays)
            assertThat(EmergencyNumbers.filtrerParLOS(attendus) { true }).isEqualTo(attendus)
        }
    }

    @Test fun `un OS qui ne reconnait que la police ne garde que le 112 et la police`() {
        // Le cas intermediaire : le filtre doit faire son travail, pas seulement tout garder
        // ou tout jeter.
        val france = EmergencyNumbers.pourLePays("fr")
        val filtres = EmergencyNumbers.filtrerParLOS(france) { it == "17" }
        assertThat(filtres.map { it.number }).containsExactly("112", "17").inOrder()
    }

    // ──────── Le raccourci « forces de l'ordre » de l'ecran verrouille ────────

    @Test fun `le raccourci compose le 999 au Royaume-Uni et en Irlande, pas le 112`() {
        // Le repli sautait DIRECTEMENT sur le 112 quand le pays n'avait pas de POLICE. En
        // deplacant le 999 vers NATIONAL — il est la ligne d'urgence generale, pas la police
        // — l'action etiquetee « Police » composait donc le 112 au Royaume-Uni, et le 999
        // n'etait plus atteignable depuis l'ecran verrouille. Releve le 2026-09-17.
        for (pays in listOf("gb", "ie")) {
            val raccourci = EmergencyNumbers.raccourciDans(EmergencyNumbers.pourLePays(pays))
            assertThat(raccourci.number).isEqualTo("999")
            assertThat(raccourci.service).isEqualTo(EmergencyNumbers.Service.NATIONAL)
        }
    }

    @Test fun `le raccourci prefere la police quand le pays en publie une`() {
        // Le temoin positif : sans lui, un repli qui rendrait TOUJOURS la ligne nationale
        // passerait le test precedent.
        val france = EmergencyNumbers.raccourciDans(EmergencyNumbers.pourLePays("fr"))
        assertThat(france.number).isEqualTo("17")
        assertThat(france.service).isEqualTo(EmergencyNumbers.Service.POLICE)
        val allemagne = EmergencyNumbers.raccourciDans(EmergencyNumbers.pourLePays("de"))
        assertThat(allemagne.number).isEqualTo("110")
        assertThat(allemagne.service).isEqualTo(EmergencyNumbers.Service.POLICE)
    }

    @Test fun `le raccourci rend le 112 quand le pays ne publie rien d autre`() {
        // Le Portugal n'a ni police ni ligne nationale distincte dans la table : le repli
        // final doit tenir, et il doit se NOMMER correctement.
        val portugal = EmergencyNumbers.raccourciDans(EmergencyNumbers.pourLePays("pt"))
        assertThat(portugal.number).isEqualTo("112")
        assertThat(portugal.service).isEqualTo(EmergencyNumbers.Service.EUROPEAN)
    }

    @Test fun `le raccourci ne ment jamais sur le service qu il compose`() {
        // La propriete generale, sur les onze pays : le numero rendu appartient TOUJOURS au
        // service annonce. C'est ce qui permet d'etiqueter le bouton sans rien synchroniser.
        for (pays in EmergencyNumbers.PAYS_COUVERTS) {
            val dials = EmergencyNumbers.pourLePays(pays)
            val raccourci = EmergencyNumbers.raccourciDans(dials)
            assertThat(dials).contains(raccourci)
        }
    }
}
