package com.filestech.sms

import com.filestech.sms.domain.smishing.SmishingDetector
import com.filestech.sms.domain.smishing.SmishingReason
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Le corpus que les AUDITS et les RELECTURES EXTERNES du 2026-09-17 ont produit sur le
 * detecteur anti-smishing.
 *
 * Il vit a part de [SmishingDetectorTest], qui garde les cas d'origine (v1.11.0 -> v1.28.11),
 * pour deux raisons. La premiere est que detekt refusait une classe de neuf cents lignes, et
 * il avait raison. La seconde est que ces deux corpus ne repondent pas a la meme question :
 * l'autre demande « le detecteur voit-il ce qu'il doit voir ? », celui-ci demande
 * « **qu'est-ce qu'il voit et ne devrait pas ?** ».
 *
 * Presque tout ce qui est ici est un FAUX POSITIF mesure sur du texte parfaitement legitime,
 * ou le temoin positif qui l'accompagne. Plusieurs ont ete introduits par la correction du
 * faux positif precedent, le meme jour : ce fichier est autant l'histoire des corrections que
 * celle des defauts.
 *
 * **Priorite doctrine, inchangee** : mieux vaut rater une arnaque que coller un bandeau rouge
 * sur un SMS legitime. Il faut DEUX heuristiques pour afficher quoi que ce soit.
 */
class SmishingDetectorAuditsTest {

    // ════════════ Ce qu'une relecture externe a fait RETIRER, et qui doit le rester ════════════

    @Test fun `un code de verification a six chiffres n est PAS un numero surtaxe`() {
        // LE faux positif a ne jamais reintroduire. Le motif italien 892/894 attrapait
        // six chiffres — exactement le format d'un code de verification, le SMS le plus
        // banal et le plus quotidien qui soit. Retire le 2026-09-17.
        val codes = listOf(
            "Il tuo codice di verifica è 892456",
            "Il tuo codice è 894321. Non condividerlo con nessuno.",
        )
        for (body in codes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.PremiumNumber)
        }
    }

    @Test fun `les frais de port d un VRAI commercant ne sont pas un mot d urgence`() {
        // « versandkosten », « spese di spedizione », « gastos de envío » figurent dans
        // toute confirmation de commande legitime ; « immediato » dans « bonifico
        // immediato », un virement instantane. Tous retires le 2026-09-17.
        val legitimes = listOf(
            "Ihre Bestellung ist unterwegs. Versandkosten: 4,90 EUR.",
            "Il suo ordine è partito. Spese di spedizione: 4,90 EUR. Bonifico immediato ricevuto.",
            "Su pedido ha salido. Gastos de envío: 4,90 EUR.",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.UrgencyKeyword)
        }
    }

    // ════════════ La tolérance qui dépend de la longueur, et la concaténation ════════════

    @Test fun `un nom officiel COURT ne signale plus un mot ordinaire`() {
        // `inps` contre `info` : distance 2. C'est ce qui interdisait d'ajouter l'INPS,
        // premiere cible d'Italie. Avec une tolerance de 1 sur les noms courts, la
        // collision disparait — et l'INPS peut entrer.
        assertThat(SmishingDetector.distanceToleree("inps")).isEqualTo(1)
        assertThat(SmishingDetector.distanceToleree("impots")).isEqualTo(2)
        assertThat(SmishingDetector.analyze("Votre facture sur info.it").reasons)
            .doesNotContain(SmishingReason.TyposquattedDomain)
    }

    @Test fun `une usurpation d un nom COURT est quand meme attrapee`() {
        // Le temoin positif du test precedent : resserrer la tolerance ne doit pas
        // rendre la liste inutile. `1nps` est a UNE faute de `inps`.
        val body = "Conto bloccato. Verifichi i suoi dati su 1nps.it"
        assertThat(SmishingDetector.analyze(body).reasons)
            .contains(SmishingReason.TyposquattedDomain)
    }

    @Test fun `un nom officiel AUGMENTE d un mot est signale (inps-sicurezza)`() {
        // La forme que Levenshtein ne voit pas : la distance entre `inps` et
        // `inps-sicurezza` vaut dix. Signalee par une relecture externe le 2026-09-17.
        val body = "Accesso anomalo. Verifichi i suoi dati su inps-sicurezza.com"
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.reasons).contains(SmishingReason.TyposquattedDomain)
        assertThat(verdict.shouldWarn).isTrue()
    }

    @Test fun `un segment qui RESSEMBLE au nom officiel sans l etre n est pas signale`() {
        // Le contrôle negatif de la regle precedente. `mi-correo.es` : « correo » est le
        // mot espagnol pour courrier, et ce n'est PAS `correos`. Exiger un segment ENTIER
        // — et non une sous-chaine — est ce qui fait la difference.
        assertThat(SmishingDetector.porteLeNomOfficiel("mi-correo", "correos")).isFalse()
        assertThat(SmishingDetector.porteLeNomOfficiel("correos-es", "correos")).isTrue()
        assertThat(SmishingDetector.analyze("Su pedido está en mi-correo.es").reasons)
            .doesNotContain(SmishingReason.TyposquattedDomain)
    }

    // ════════════ L'anglais : la langue SOURCE n'avait aucune liste ════════════

    @Test fun `arnaque BRITANNIQUE - HMRC usurpe plus urgence anglaise`() {
        // `hmrc-refund` est la forme que prennent reellement ces campagnes : le nom exact
        // augmente d'un mot. Une simple faute de frappe sur quatre lettres (`hmcr`, une
        // TRANSPOSITION, donc distance 2) n'est volontairement PAS attrapee — c'est le
        // prix de la tolerance resserree qui a rendu `hmrc.gov.uk` ajoutable.
        val body = "Your account has been suspended. Verify your details at hmrc-refund.com"
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isTrue()
        assertThat(verdict.reasons).contains(SmishingReason.UrgencyKeyword)
        assertThat(verdict.reasons).contains(SmishingReason.TyposquattedDomain)
    }

    @Test fun `la lacune britannique est ASSUMEE, pas oubliee`() {
        // Les motifs `09\d{9}` et `08[47]\d{8}` ont ete ecrits pour le premium britannique,
        // puis retrecis a `090\d{8}`, puis RETIRES : ils decrivent aussi le fixe allemand et
        // l'indicatif de Messine. Aucun chiffre ne separe les deux, seul le pays le fait, et
        // l'anti-smishing applique toutes les listes a tout le monde.
        //
        // Ce test fige le COUT de cet arbitrage plutot que de le laisser se perdre : un
        // surtaxe britannique n'est plus reconnu comme tel. Le jour ou quelqu'un voudra le
        // ramener, ce test rougira et il lira pourquoi.
        val body = "Act now: your parcel is held. Call 09012345678 to release it."
        assertThat(SmishingDetector.analyze(body).reasons)
            .doesNotContain(SmishingReason.PremiumNumber)
    }

    @Test fun `un surtaxe d un pays COUVERT reste reconnu`() {
        // Le temoin positif du test precedent : sans lui, un detecteur qui aurait cesse de
        // regarder les numeros passerait pour « lacune assumee » sur toute la ligne.
        val arnaques = listOf(
            "URGENT : rappelez le 0899 12 34 56 immédiatement",
            "Ihr Paket wartet. Bestätigen Sie ihre Adresse: 0900 1234567",
            "Urgente: il suo pacco è bloccato, chiami 899 123 456",
            "Urgente: confirme sus datos llamando al 806 123 456",
        )
        for (body in arnaques) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .contains(SmishingReason.PremiumNumber)
        }
    }

    @Test fun `un mobile britannique ordinaire n est pas un numero surtaxe`() {
        // 07xxx = mobile UK, 11 chiffres comme les premium 09xx : c'est exactement le
        // genre de voisinage ou un motif trop large ferait un degat quotidien.
        val body = "Call me back on 07700 900123 when you can."
        assertThat(SmishingDetector.analyze(body).reasons)
            .doesNotContain(SmishingReason.PremiumNumber)
    }

    // ═══════ Ce qu'une SECONDE relecture externe a trouvé — defauts PREEXISTANTS ═══════
    //
    // Les quatre cas ci-dessous n'ont pas ete introduits par l'ajout des langues : ils
    // dormaient depuis la v1.11.0. Ils affichaient un bandeau d'arnaque sur des messages
    // parmi les plus banals qui soient.

    @Test fun `un colis EN COURS DE DISTRIBUTION n est pas une arnaque`() {
        // LE faux positif le plus couteux du fichier : « rib » etait cherche comme
        // sous-chaine, et vit dans distribution, contribution, tribunal. Avec un lien
        // raccourci — que de vrais transporteurs emploient — le bandeau s'affichait.
        val legitimes = listOf(
            "Votre colis est en cours de distribution. Suivi : https://bit.ly/3Livraison",
            "Votre contribution a bien été enregistrée : https://bit.ly/recu2026",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).shouldWarn).isFalse()
        }
    }

    @Test fun `un amendement n est pas une amende, un contact n est pas un act now`() {
        val legitimes = listOf(
            "L'amendement a été adopté. Compte rendu : https://bit.ly/seanceAN",
            "You can contact now our support team: https://bit.ly/support-hours",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.UrgencyKeyword)
        }
    }

    @Test fun `une vraie amende reste detectee malgre la frontiere de mot`() {
        // Le temoin positif du test precedent : poser une frontiere ne doit pas rendre
        // le mot inutile. Le pluriel doit passer aussi.
        //
        // ⚠️ Le cas singulier disait « Amende impayee, MAJORATION sous 48h ». Or
        // « majoration » est lui-meme dans URGENCY_KEYWORDS : si la frontiere de mot de
        // `amende` cassait, l'assertion serait restee VERTE. Un temoin positif doit
        // isoler la regle qu'il temoigne. Corrige le 2026-09-17 ; cf. memoire
        // `tests-qui-ne-peuvent-pas-echouer`.
        val amendes = listOf(
            "Amende impayée : https://bit.ly/x",
            "Vos amendes impayées : https://bit.ly/x",
        )
        for (body in amendes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .contains(SmishingReason.UrgencyKeyword)
        }
    }

    @Test fun `deux nombres voisins ne fusionnent plus en numero surtaxe`() {
        // Le compactage retirait TOUS les espaces et tirets du message : « 32 - 11 »
        // devenait 3211, un numero court surtaxe francais.
        val legitimes = listOf(
            "Urgent : résultat du match, 32 - 11. Compte rendu demain.",
            "Action requise pour la commande réf. 08 - 99 - 123 - 456.",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.PremiumNumber)
        }
    }

    @Test fun `un vrai numero surtaxe espace reste detecte`() {
        // Le temoin positif : la compaction doit continuer de marcher sur les vraies
        // ecritures d'un numero, ou le separateur est ENTRE deux chiffres.
        for (body in listOf("Rappelez le 08 99 12 34 56", "Rappelez le 0899-123-456")) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .contains(SmishingReason.PremiumNumber)
        }
    }

    @Test fun `un www devant un domaine usurpe ne le cache plus`() {
        // Seul le PREMIER label etait examine : `www.paypa1.fr` echappait entierement,
        // alors que c'est la forme la plus courante d'une URL.
        val body = "Confirmez votre compte : http://www.paypa1.fr/update"
        assertThat(SmishingDetector.analyze(body).reasons)
            .contains(SmishingReason.TyposquattedDomain)
    }

    @Test fun `le nom officiel EXACT sur un domaine a bas cout est signale`() {
        // Distance zero : ni Levenshtein ni la concatenation ne le voyaient, et la garde
        // « le nom doit differer du label » l'ecartait explicitement.
        val body = "Your account has been suspended: https://paypal.top/verify"
        assertThat(SmishingDetector.analyze(body).reasons)
            .contains(SmishingReason.TyposquattedDomain)
    }

    @Test fun `le nom officiel exact sur un domaine de PAYS reste legitime`() {
        // Le controle negatif du test precedent. `amazon.co.uk` et `paypal.be` sont
        // parfaitement legitimes et simplement absents de notre liste : les signaler
        // serait exactement le faux positif que ce fichier refuse.
        val legitimes = listOf(
            "Votre commande : https://amazon.co.uk/orders",
            "Votre reçu : https://paypal.be/recu",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.TyposquattedDomain)
        }
    }

    // ──────── Suites de l'audit de securite du 2026-09-17 ────────

    @Test fun `un mot ordinaire qui porte le nom d un operateur n est pas une usurpation`() {
        // La regle de concatenation acceptait le nom officiel a N'IMPORTE QUELLE position
        // du label. Neuf des noms officiels sont aussi des mots ordinaires — `free` le
        // premier. « duty-free » etait donc lu comme une usurpation de `free.fr`, et avec
        // un seul mot d'urgence le SEUIL DE DEUX etait atteint : bandeau rouge sur un SMS
        // commercial. C'est le faux positif que ce fichier refuse par-dessus tout.
        val legitimes = listOf(
            "Última oportunidad: 50% en todo el duty-free.es",
            "Limited time offer on tax-free.com",
            "Dernière chance : location sur auto-bahn.de",
            "Votre facture est disponible sur free-mobile.fr",
            "Votre espace pro : orange-pro.fr",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.TyposquattedDomain)
        }
    }

    // ──── Suites des deux relectures externes du 2026-09-17 (troisieme passe) ────

    @Test fun `un montant avec separateur de milliers n est pas un numero surtaxe`() {
        // Le numero court francais fait QUATRE chiffres. Compacter avant de chercher
        // transformait « 3 211 € » en 3211, et « 35.50 euros » en 3550 : TOUT prix entre
        // 32,00 et 36,99 affichait un bandeau. Les quatre langues latines separent les
        // milliers par une espace insecable ou un point.
        val legitimes = listOf(
            "Paiement reçu : 3${INSECABLE}211 €. Facture : https://bit.ly/3Facture",
            "Paiement reçu : 3${FINE_INSECABLE}211 €. Facture : https://bit.ly/3Facture",
            "Última oportunidad: usa tus 3.211 puntos antes del domingo.",
            "Votre abonnement expire demain. Reste à payer : 35.50 euros.",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.PremiumNumber)
        }
    }

    @Test fun `un code ou une reference alphanumerique n est pas un numero surtaxe`() {
        // Les frontieres ne regardaient que les CHIFFRES : une lettre collee au numero
        // passait. Un code de validation et une reference de commande sont parmi les SMS
        // les plus courants qui soient.
        val legitimes = listOf(
            "Action immédiate : G-3211 est votre code de validation",
            "Action requise : confirmez la commande réf. AB08-99-123-456CD.",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.PremiumNumber)
        }
    }

    @Test fun `un vrai numero surtaxe reste reconnu, court ou long`() {
        // Le temoin positif des deux tests precedents : sans lui, un detecteur qui aurait
        // simplement cesse de regarder les numeros les passerait tous les deux.
        val arnaques = listOf(
            "URGENT : envoyez STOP au 3211 pour arreter le prelevement",
            "URGENT : rappelez le 0899 12 34 56 immédiatement",
        )
        for (body in arnaques) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .contains(SmishingReason.PremiumNumber)
        }
    }

    @Test fun `un mot ordinaire proche d un nom officiel n est pas une usurpation`() {
        // Onze mots innocents sur onze essayes etaient signales : la tolerance de
        // Levenshtein s'appliquait sans egard au fait que le label soit un MOT.
        val legitimes = listOf(
            "Action requise : connectez-vous sur france.com",
            "Colis en attente, suivi sur ma-porte.fr",
            "Click here to view your booking: https://www.engine.com/",
            "Limited time offer: https://www.avenue.com/",
            "Dernière chance : catalogue sur imports.example",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.TyposquattedDomain)
        }
    }

    @Test fun `une usurpation par CHIFFRE reste vue, elle`() {
        // Le temoin positif du test precedent, et la raison d'etre des deux voies : une
        // vraie usurpation substitue un chiffre a une lettre, ce qu'aucun mot ne fait.
        val usurpations = listOf(
            "Vous avez des impôts impayés : https://1mpots.gouv.fr/payer",
            "Votre attestation : https://amel1.fr/connexion",
            "Ihre Steuererklärung: https://e1ster.de/login",
        )
        for (body in usurpations) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .contains(SmishingReason.TyposquattedDomain)
        }
    }

    @Test fun `une banque regionale allemande n est pas une usurpation`() {
        // L'Allemagne compte des CENTAINES de caisses d'epargne et de banques populaires
        // regionales sur ce modele. La regle de concatenation les signalait toutes.
        val legitimes = listOf(
            "Sparkasse KölnBonn: Bitte bestätigen Sie Ihre neue Mobilnummer unter " +
                "https://www.sparkasse-koelnbonn.de/online-banking",
            "Volksbank Stuttgart: Bitte bestätigen Sie Ihre Daten unter " +
                "https://www.volksbank-stuttgart.de/",
            "PayPal Community: verify your email settings at https://www.paypal-community.com/",
            "ENGIE Home Services : confirmez votre rendez-vous sur https://www.engie-homeservices.fr/",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.TyposquattedDomain)
        }
    }

    @Test fun `le vrai domaine du Credit Agricole n est pas une usurpation de lui-meme`() {
        // Le domaine canonique de la banque porte un TIRET, et il etait absent de la liste
        // officielle : a distance 1 de `creditagricole`, il etait donc signale. Un SMS de
        // la banque, vers le site de la banque, avec un bandeau rouge dessus.
        val body = "Crédit Agricole : action requise pour finaliser votre souscription " +
            "sur https://www.credit-agricole.fr/"
        assertThat(SmishingDetector.analyze(body).reasons)
            .doesNotContain(SmishingReason.TyposquattedDomain)
    }

    // ──── Suites de l'audit de securite du 2026-09-17 (soir) ────

    @Test fun `un nom officiel augmente est signale A TRAVERS analyze, pas seulement en isolation`() {
        // ⚠️ Le test voisin asserte `porteLeNomOfficiel` EN ISOLATION, et il restait vert alors
        // que la regle etait MORTE pour `impots` : la garde des mots trop communs etait posee
        // devant les DEUX voies, et `impots` est le seul nom present dans les deux listes.
        // `mon-impots.fr` — la cible de smishing la plus usurpee du pays — n'etait jamais
        // signale. L'aide fonctionnait ; personne ne l'atteignait.
        val usurpations = listOf(
            "Action requise : regularisez sur https://mon-impots.fr/payer",
            "Remboursement en attente : https://impots-remboursement.fr",
            "Accesso anomalo. Verifichi i suoi dati su inps-sicurezza.com",
            "Su envio esta retenido: https://correos-envio.es/pagar",
        )
        for (body in usurpations) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .contains(SmishingReason.TyposquattedDomain)
        }
    }

    @Test fun `un numero au milieu d une suite de groupes n est pas un code court`() {
        // Lire le code court sur le corps BRUT a ferme « 3 211 € » et ouvert les IBAN : dans une
        // suite de groupes separes par des espaces, chaque groupe devenait un candidat isole.
        // Un IBAN francais a six ou sept groupes de quatre chiffres.
        val legitimes = listOf(
            "Votre virement vers FR76 3000 4000 0312 3456 7890 143. Confirmez avant minuit.",
            "carte 4970 3312 8899 1234",
            "Numero de commande 2024 3311 8890",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.PremiumNumber)
        }
    }

    @Test fun `un code court isole dans la phrase reste reconnu`() {
        // Le temoin positif du test precedent : un vrai code court n'est jamais au milieu d'une
        // suite de groupes.
        val arnaques = listOf(
            "URGENT : envoyez STOP au 3211 pour arreter le prelevement",
            "Code court 3611 pour desabonnement immediat",
            "Urgent, votre code est 3456.",
        )
        for (body in arnaques) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .contains(SmishingReason.PremiumNumber)
        }
    }

    @Test fun `un mot de quatre lettres n usurpe pas un sigle de trois`() {
        // Le filtre de longueur etait applique au LABEL et pas au nom OFFICIEL : les cinq sigles
        // de trois lettres entraient en comparaison floue, a tolerance 1, contre tout label de
        // quatre caracteres.
        val legitimes = listOf(
            "Derniere chance : nos nouveautes sur chic.fr",
            "Limited time: catalogue on dahl.de",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.TyposquattedDomain)
        }
        // Et le temoin : le sigle exact sur un TLD jetable reste vu.
        assertThat(SmishingDetector.analyze("Ihr Paket: https://dhl.top/tracking").reasons)
            .contains(SmishingReason.TyposquattedDomain)
    }

    @Test fun `le webmail d une universite espagnole n est pas une usurpation`() {
        // `correo.<x>.es` est la forme canonique du webmail des universites et administrations
        // espagnoles. Distance 1 de `correos`, donc signale — avec « verifique sus datos » dans
        // le meme message, c'etait le bandeau rouge sur un courriel institutionnel.
        val body = "Webmail: acceda a correo.uned.es y verifique sus datos"
        assertThat(SmishingDetector.analyze(body).reasons)
            .doesNotContain(SmishingReason.TyposquattedDomain)
    }

    @Test fun `le nom officiel en PREMIER segment reste signale`() {
        // Le temoin positif du test precedent. C'est la forme que prennent reellement les
        // campagnes, et les quatre assertions d'origine la respectent deja.
        assertThat(SmishingDetector.porteLeNomOfficiel("inps-sicurezza", "inps")).isTrue()
        assertThat(SmishingDetector.porteLeNomOfficiel("hmrc-refund", "hmrc")).isTrue()
        assertThat(SmishingDetector.porteLeNomOfficiel("correos-es", "correos")).isTrue()
        // Le nom officiel en SECOND segment compte aussi : `mon-impots.fr` et
        // `espace-ameli.fr` sont des formes d'usurpation au moins aussi plausibles que
        // `impots-mon.fr`. Un premier correctif exigeait le nom en premier ; le controle
        // negatif a montre que cette garde ne mesurait rien, et qu'elle perdait ces deux-la.
        assertThat(SmishingDetector.porteLeNomOfficiel("mon-impots", "impots")).isTrue()
        assertThat(SmishingDetector.porteLeNomOfficiel("espace-ameli", "ameli")).isTrue()
        // Et la garde : la regle ne vaut QUE pour les organismes qui publient un seul
        // domaine et ne le declinent pas. Une marque commerciale a de vrais domaines a
        // tiret — c'est ce qui signalait toutes les caisses d'epargne allemandes.
        assertThat(SmishingDetector.porteLeNomOfficiel("duty-free", "free")).isFalse()
        assertThat(SmishingDetector.porteLeNomOfficiel("free-mobile", "free")).isFalse()
        assertThat(SmishingDetector.porteLeNomOfficiel("ad-revenue", "revenue")).isFalse()
        assertThat(SmishingDetector.porteLeNomOfficiel("playa-santander", "santander")).isFalse()
        assertThat(SmishingDetector.porteLeNomOfficiel("mi-correo", "correos")).isFalse()
        assertThat(SmishingDetector.porteLeNomOfficiel("sparkasse-koelnbonn", "sparkasse")).isFalse()
        assertThat(SmishingDetector.porteLeNomOfficiel("volksbank-stuttgart", "volksbank")).isFalse()
        assertThat(SmishingDetector.porteLeNomOfficiel("paypal-community", "paypal")).isFalse()
        assertThat(SmishingDetector.porteLeNomOfficiel("engie-homeservices", "engie")).isFalse()
    }

    @Test fun `un nom officiel COURT sur un domaine jetable est signale`() {
        // Le filtre `length >= 4` n'existe que pour borner Levenshtein, et il etait pose
        // DEVANT la regle du nom exact, qui est une EGALITE. Les cinq noms officiels de
        // trois lettres — dhl, cic, lcl, edf, sfr — etaient donc invisibles sur un TLD
        // jetable, alors qu'une egalite ne peut pas creer de faux positif.
        val usurpations = listOf(
            "Ihr Paket konnte nicht zugestellt werden: https://dhl.top/tracking",
            "Votre facture : https://edf.online/payer",
            "Votre espace client : https://sfr.click/connexion",
        )
        for (body in usurpations) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .contains(SmishingReason.TyposquattedDomain)
        }
    }

    @Test fun `un nom officiel court sur son VRAI domaine reste legitime`() {
        // Le controle negatif du test precedent : c'est le TLD a bas cout qui decide,
        // pas la longueur du nom.
        val legitimes = listOf(
            "Ihre Sendung: https://dhl.de/verfolgen",
            "Votre facture : https://edf.fr/espace-client",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.TyposquattedDomain)
        }
    }

    @Test fun `iban reste dehors parce qu il est un mot espagnol courant`() {
        // `iban` a ete remis dans les mots d'urgence le 2026-09-17, avec une frontiere de
        // mot qui reglait bien sa collision avec « Taliban » — puis RETIRE le meme jour.
        // Le raisonnement etait mene sur une application francaise ; elle parle cinq
        // langues. En ESPAGNOL, « iban » est l'imparfait du verbe `ir`. Une frontiere de mot
        // protege d'une sous-chaine, pas d'un homographe dans une autre langue.
        //
        // ⚠️ Les phrases ci-dessous n'emploient AUCUN autre mot de la liste. La premiere
        // ecriture de ce test disait « por favor confirme sus datos », qui est lui-meme un
        // mot d'urgence espagnol legitime : le test rougissait pour la mauvaise raison, et
        // il serait reste rouge meme apres correction du code.
        val legitimes = listOf(
            "Tus entradas iban a llegar hoy. Descárgalas: https://bit.ly/3AbC9",
            "Los paquetes iban en el coche del repartidor.",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.UrgencyKeyword)
        }
    }

    @Test fun `un numero surtaxe ecrit avec des espaces insecables est reconnu`() {
        // `\s` de Java ne contient NI U+00A0 NI U+202F, que les claviers et traitements de
        // texte posent entre les groupes d'un numero francais. `\p{Zs}` les couvre.
        // Concatene plutot qu'interpole : « $insecable99 » se lirait comme l'identifiant
        // `insecable99`, et des accolades a cet endroit sont refusees par detekt ailleurs.
        val groupes = listOf("08", "99", "12", "34", "56")
        val numeros = listOf(
            "Rappelez le " + groupes.joinToString(INSECABLE),
            "Rappelez le " + groupes.joinToString(FINE_INSECABLE),
        )
        for (body in numeros) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .contains(SmishingReason.PremiumNumber)
        }
    }

    @Test fun `deux nombres voisins separes par une insecable ne fusionnent toujours pas`() {
        // Le controle negatif du test precedent : elargir la classe de separateurs ne doit
        // pas ramener le faux positif « 32 - 11 » corrige le meme jour. L'espace qui
        // precede le tiret n'est pas suivi d'un chiffre, quelle que soit sa forme.
        val body = "Urgent : résultat du match, 32" + INSECABLE + "-" + INSECABLE +
            "11. Compte rendu demain."
        assertThat(SmishingDetector.analyze(body).reasons)
            .doesNotContain(SmishingReason.PremiumNumber)
    }

    // ──── Suites de l'audit de securite du 2026-09-17 : S8 (tiret) et S6 (fixe allemand) ────

    @Test
    fun `un tiret colle devant ou derriere un numero surtaxe ne le cache plus`() {
        // S8 — le tiret faisait frontiere des DEUX cotes, y compris pour les numeros longs.
        // Un seul caractere colle au numero neutralisait donc l'heuristique 3, et c'est le
        // genre de detail qu'un envoyeur d'arnaques trouve en une soiree.
        val arnaques = listOf(
            "URGENT : rappelez le -0899123456 immédiatement",
            "URGENT : rappelez le 0899123456- immédiatement",
        )
        for (body in arnaques) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .contains(SmishingReason.PremiumNumber)
        }
    }

    @Test
    fun `un tiret PRECEDE d une lettre referme la frontiere`() {
        // Releve par GPT et Gemini le meme jour, sur MA correction du matin : retirer le tiret
        // des frontieres longues ouvrait « commande Réf-0899123456 ». Le regard arriere ne
        // voyait qu'UN caractere — le tiret — et la lettre qui le precede restait hors de
        // portee. Le commentaire affirmait pourtant que « la LETTRE ferme la reference ».
        val legitimes = listOf(
            "Action requise : commande Réf-0899123456 prête au retrait.",
            "Action immédiate : facture INV-0899123456 à régler.",
            "Action requise : dossier 0899123456-SAV en cours.",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.PremiumNumber)
        }
    }

    @Test
    fun `le tiret garde toujours les numeros COURTS, eux`() {
        // Le controle negatif du test precedent : les numeros courts se lisent sur le texte
        // BRUT, ou un tiret separe vraiment les groupes d'une reference. Retirer la garde
        // des deux cotes aurait ramene « G-3211 » et « 12-3456-78 ».
        val legitimes = listOf(
            "Action immédiate : G-3211 est votre code de validation",
            "Action requise : votre commande réf. 12-3456-78 est prête.",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.PremiumNumber)
        }
    }

    @Test
    fun `un fixe allemand de onze chiffres n est pas un surtaxe britannique`() {
        // S6 — `09\d{9}` et `08[47]\d{8}` visaient le premium britannique, et decrivaient mot
        // pour mot le fixe allemand : Nuremberg, Bayreuth, Ansbach, Ingolstadt, Landshut.
        // Chacun de ces messages porte un mot d'urgence allemand : sans ce correctif, c'est
        // le bandeau rouge sur le SMS d'un commercant de Nuremberg.
        val legitimes = listOf(
            "Bestätigen Sie Ihren Termin, Rückfragen unter 0911 1234567",
            "Bestätigen Sie Ihren Termin, Rückfragen unter 0921 1234567",
            "Bestätigen Sie Ihren Termin, Rückfragen unter 0981 1234567",
            "Bestätigen Sie Ihren Termin, Rückfragen unter 0841 1234567",
            "Bestätigen Sie Ihren Termin, Rückfragen unter 0871 1234567",
            // Les voisins que le retrecissement a `090\d{8}` manquait encore, trouves par les
            // deux relectures externes : toute une famille d'indicatifs allemands en 090xx...
            "Bestätigen Sie Ihren Termin, Rückfragen unter 09071 123456",
            "Bestätigen Sie Ihren Termin, Rückfragen unter 09081 123456",
            "Bestätigen Sie Ihren Termin, Rückfragen unter 09091 123456",
            // ...et surtout MESSINE, dont l'indicatif est 090, dans une langue livree.
            "Urgente: confermi l'appuntamento allo 090 12345678",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.PremiumNumber)
        }
    }

    private companion object {
        /** U+00A0, l'espace insecable ordinaire. */
        const val INSECABLE = " "

        /** U+202F, la fine insecable — celle des claviers francais entre groupes de chiffres. */
        const val FINE_INSECABLE = " "
    }
}
