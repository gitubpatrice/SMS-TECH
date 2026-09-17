#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Controle de parite des traductions - il ECHOUE, il n'avertit pas.

Ce que ce controle protege
--------------------------
L'objection posee publiquement sur l'issue #15 etait la bonne : « l'application change a chaque
version ». Une traduction livree une fois puis oubliee ne devient pas incomplete au grand jour,
elle le devient EN SILENCE - l'ecran retombe en anglais, mot par mot, et personne ne le voit
avant l'utilisateur. Une langue qui decroche doit donc casser la build, pas produire un
avertissement de plus dans un journal que personne ne lit.

Ce qu'il verifie, et pourquoi chacun est necessaire
---------------------------------------------------
1. CLES       - une cle manquante = un libelle anglais au milieu d'un ecran allemand ;
                une cle EN TROP = de la traduction sur une chaine que plus rien n'affiche.
2. PARAMETRES - %1$s, %2$d... perdus ou inventes : l'application PLANTE a l'execution
                (IllegalFormatException). C'est le seul defaut de ce fichier qui ne se voit pas
                a la relecture et qui ne pardonne pas. L'ORDRE peut changer - c'est a cela que
                servent les 1$ / 2$ - mais pas l'ensemble.
3. PLURIELS   - toute quantite presente en anglais doit exister dans la traduction.
                /!\\ Le PLANCHER seulement : les categories CLDR supplementaires exigees par une
                langue (many en francais) appartiennent a lint (MissingQuantity), qui en est la
                source d'autorite. Ne pas dupliquer cette regle ici, elle se contredirait.
4. FICHES DE STORE - les plafonds de fastlane, comptes en OCTETS (un accent en vaut deux).
                Rien ne les regardait : la fiche espagnole a depasse de 29 octets sans que
                personne ne le voie, la verification manuelle ayant compte des caracteres.
5. LES QUATRE GESTES SOLIDAIRES - une traduction n'est livree que si les quatre sont faits :
                a. values-XX/strings.xml         la traduction
                b. localeFilters                 sans quoi AGP RETIRE les ressources de l'APK
                c. locales_config.xml            sans quoi pas de selecteur de langue Android 13+
                d. src/debug/res/values-XX/      sans quoi la build de debug porte le nom de la
                                                 release sur un appareil dans cette langue
                Et la RECIPROQUE : une langue annoncee (b ou c) sans traduction derriere offre a
                l'utilisateur une langue que l'application ne parle pas.

Utilisable en local :  python3 .github/scripts/i18n-parite.py
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

RACINE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(RACINE, "app", "src", "main", "res")
RES_DEBUG = os.path.join(RACINE, "app", "src", "debug", "res")
GRADLE = os.path.join(RACINE, "app", "build.gradle.kts")
LOCALES_CONFIG = os.path.join(RES, "xml", "locales_config.xml")
# Le test qui verifie les CORPS DE SMS de securite, langue par langue. Il porte sa propre liste
# de langues ; ce script verifie qu'elle est complete, faute de quoi une langue ajoutee passerait
# a cote de ses controles sans que rien ne le dise. Cf. la fonction verifier_test_des_sms.
TEST_SMS = os.path.join(RACINE, "app", "src", "test", "java", "com", "filestech", "sms",
                        "system", "safety", "SafetyMessageTextsTest.kt")

# values-de, values-pt-rBR, values-b+sr+Latn - et surtout PAS values-night, values-v29,
# values-land, values-sw600dp... qui sont des qualificateurs de configuration, pas des langues.
LANGUE = re.compile(r"^values-(?:(b\+[A-Za-z+]+)|([a-z]{2,3})(?:-r([A-Z]{2}))?)$")
# Seules les conversions Java reelles : « 100% private » n'est pas un parametre %p, et
# le drapeau ESPACE est volontairement absent de la classe - « 100 % des » n'en est pas
# un non plus. Un %1$p malformé compte alors comme parametre PERDU, ce qui est exact.
PARAMETRE = re.compile(r"%(\d+\$)?[-#+0,(]*\d*(?:\.\d+)?([bBhHsScCdoxXeEfgGaAtTn])")

erreurs = []


def echec(langue, message):
    erreurs.append((langue, message))


def texte_brut(element):
    """Le contenu d'une chaine, balises de style comprises - c'est la que vivent les parametres."""
    return "".join(element.itertext())


def lire(chemin):
    """Rend ({cle: texte}, {cle: {quantite: texte}})."""
    racine = ET.parse(chemin).getroot()
    etiquette_dossier = os.path.basename(os.path.dirname(chemin))
    chaines, pluriels = {}, {}
    for e in racine.findall("string"):
        nom = e.get("name")
        if nom in chaines:
            echec(etiquette_dossier, "cle EN DOUBLE : %s" % nom)
        chaines[nom] = texte_brut(e)
    for e in racine.findall("plurals"):
        pluriels[e.get("name")] = {i.get("quantity"): texte_brut(i) for i in e.findall("item")}
    return chaines, pluriels


def parametres(texte):
    """L'ensemble des parametres de format, independamment de leur ordre.

    %% est un pourcentage litteral et n'en est pas un ; il est neutralise d'abord.
    """
    return frozenset(
        (p or "", t) for p, t in PARAMETRE.findall(texte.replace("%%", "\x00"))
    )


def joli(ensemble):
    return "{" + ", ".join(sorted("%%%s%s" % (p, t) for p, t in ensemble)) + "}"


def langues_declarees_gradle():
    with open(GRADLE, encoding="utf-8") as f:
        contenu = f.read()
    m = re.search(r"localeFilters\s*\+?=\s*listOf\(([^)]*)\)", contenu)
    if not m:
        echec("build.gradle.kts", "bloc localeFilters introuvable - le controle serait aveugle")
        return set()
    return set(re.findall(r'"([^"]+)"', m.group(1)))


def langues_declarees_locales_config():
    if not os.path.exists(LOCALES_CONFIG):
        echec("locales_config.xml",
              "fichier absent - le selecteur de langue Android 13+ n'existe pas")
        return set()
    racine = ET.parse(LOCALES_CONFIG).getroot()
    android = "{http://schemas.android.com/apk/res/android}name"
    return {e.get(android) for e in racine.findall("locale")}


def etiquette(dossier):
    """values-pt-rBR -> pt-rBR, la forme qu'attendent localeFilters et locales_config."""
    return dossier[len("values-"):]


def verifier_test_des_sms(traduites):
    """La liste de langues du test des corps de SMS doit couvrir TOUTES les langues livrees.

    Les corps de SMS d'urgence et de Safety Call sont les textes qui PARTENT. Leurs contraintes
    — pas de tiret cadratin, un segment, la relance nomme l'application — sont verifiees langue
    par langue par SafetyMessageTextsTest, qui porte sa propre liste. Une langue ajoutee sans y
    figurer serait livree sans qu'aucun de ces controles ne l'ait regardee, et la suite resterait
    verte. C'est le genre de vert creux que ce depot connait ; on le ferme ici.
    """
    if not os.path.exists(TEST_SMS):
        echec("SafetyMessageTextsTest",
              "fichier introuvable (%s) : les corps de SMS ne sont plus verifies par langue"
              % os.path.relpath(TEST_SMS, RACINE).replace(os.sep, "/"))
        return
    with open(TEST_SMS, encoding="utf-8") as f:
        contenu = f.read()
    m = re.search(r"val\s+LANGUES\s*=\s*listOf\(([^)]*)\)", contenu)
    if not m:
        echec("SafetyMessageTextsTest", "liste LANGUES introuvable - le controle serait aveugle")
        return
    listees = set(re.findall(r'"([^"]+)"', m.group(1)))
    for lg in sorted(traduites - listees):
        echec("SafetyMessageTextsTest",
              "langue %s absente de LANGUES : ses corps de SMS d'urgence et de Safety Call "
              "ne seraient verifies par personne" % lg)
    for lg in sorted(listees - traduites):
        echec("SafetyMessageTextsTest",
              "langue %s listee dans LANGUES mais non traduite : le test mesurerait un repli "
              "sur l'anglais en croyant mesurer %s" % (lg, lg))


# Les categories CLDR exigees par chaque langue livree. Une categorie manquante fait
# rendre le mauvais texte pour ces quantites-la ; une categorie en trop est du texte
# mort, jamais affiche. La source est le CLDR, et le lint `MissingQuantity` d'Android
# dit la meme chose — ce controle existe pour que le gate le dise AUSSI, sans lint.
QUANTITES_CLDR = {
    "en": {"one", "other"},
    "de": {"one", "other"},
    "fr": {"one", "many", "other"},
    "it": {"one", "many", "other"},
    "es": {"one", "many", "other"},
}

PLAFONDS_FASTLANE = {
    "title.txt": 50,
    "short_description.txt": 80,
    "full_description.txt": 4000,
}


def verifier_fins_de_ligne(dossiers):
    """Aucun strings.xml ne doit porter de \\r\\r\\n, ni melanger les conventions.

    Pourquoi ce garde existe : values-es a ete commit\u00e9 avec un \\r\\r\\n a chaque ligne
    (2026-09-17, commit 2e8991b), et values-it avec deux lignes dans le meme cas. Rien
    ne l'a signal\u00e9 : l'application compilait, la parite passait, les tests aussi. Le
    prix est venu a la relecture suivante — un double retour chariot compte pour DEUX
    fins de ligne en mode texte, si bien que reecrire le fichier doublait son
    interlignage et rendait le diff illisible.

    Le depot stocke ces fichiers en LF (`core.autocrlf` s'occupe du poste Windows).
    On refuse donc le \\r\\r\\n partout, et le melange LF/CRLF dans un meme fichier.
    """
    for dossier in ["values"] + list(dossiers):
        chemin = os.path.join(RES, dossier, "strings.xml")
        if not os.path.exists(chemin):
            continue
        # `etiquette("values")` rendrait une chaine vide : la source anglaise se nomme "en".
        langue = "en" if dossier == "values" else etiquette(dossier)
        octets = open(chemin, "rb").read()
        if b"\r\r\n" in octets:
            echec(langue,
                  "strings.xml porte des fins de ligne \\r\\r\\n (%d) : un outil y a ajoute "
                  "un retour chariot de trop" % octets.count(b"\r\r\n"))
            continue
        crlf = octets.count(b"\r\n")
        lf = octets.count(b"\n")
        if crlf and crlf != lf:
            echec(langue,
                  "strings.xml melange CRLF (%d) et LF (%d) : une seule convention par "
                  "fichier" % (crlf, lf - crlf))


def verifier_fastlane():
    """Les plafonds des fiches de store, comptes en OCTETS.

    Pourquoi ici : rien d'autre ne les regardait. La fiche espagnole a depasse le
    plafond de 4000 octets sans que personne ne le voie, parce que la verification
    manuelle avait compte des CARACTERES - 3928, donc « conforme » - alors que le
    fichier pesait 4029 octets. Un accent vaut deux octets, et les cinq langues
    livrees en sont pleines.

    Ce qui n'est PAS verifie ici, et pourquoi : la taille des CHANGELOGS. Les 500
    caracteres que la documentation annoncait sont une regle de Google Play, pas de
    F-Droid - fdroidserver ne valide pas ce champ et le client affiche le texte entier.
    Verifie de deux facons independantes le 2026-09-17 : une relecture externe, et le
    depot lui-meme, qui a publie cinquante versions avec des changelogs allant jusqu'a
    2000 octets, relues plusieurs fois par les mainteneurs F-Droid, sans que personne ne
    le signale. Un controle qui rougit sur du sain finit par ne plus etre lu.

    Un plafond ecrit dans la documentation et verifie nulle part est une promesse, pas
    une regle ; un plafond verifie mais inexistant est un faux positif permanent.
    """
    racine = os.path.join(RACINE, "fastlane", "metadata", "android")
    if not os.path.isdir(racine):
        return
    version = version_publiee()
    for locale in sorted(os.listdir(racine)):
        dossier = os.path.join(racine, locale)
        if not os.path.isdir(dossier):
            continue
        for nom, plafond in sorted(PLAFONDS_FASTLANE.items()):
            chemin = os.path.join(dossier, nom)
            # v1.28.12 — un fichier ABSENT etait ignore par un `continue`. Une langue
            # livree sans `title.txt` passait donc le controle en silence : le trou le
            # plus facile a ne pas voir, puisqu'il ne produit aucune sortie.
            if not os.path.isfile(chemin):
                echec(locale, "fastlane/%s MANQUANT" % nom)
                continue
            octets = len(open(chemin, "rb").read().strip())
            if octets > plafond:
                echec(locale, "fastlane/%s : %d octets pour un plafond de %d "
                              "(depasse de %d)" % (nom, octets, plafond, octets - plafond))
        # v1.28.12 — les changelogs n'etaient jamais regardes. Les trois langues livrees
        # cette semaine n'en avaient AUCUN : leur fiche F-Droid aurait montre un nom, un
        # resume et une description dans leur langue, et un « Quoi de neuf » en anglais.
        # Une langue qui a une description de store doit avoir le changelog de la version
        # publiee — sinon elle n'est pas complete, elle est seulement commencee.
        if version is not None and os.path.isfile(os.path.join(dossier, "full_description.txt")):
            journal = os.path.join(dossier, "changelogs", "%d.txt" % version)
            if not os.path.isfile(journal):
                echec(locale, "fastlane/changelogs/%d.txt MANQUANT (version publiee)" % version)


def version_publiee():
    """Le versionCode de `version.properties`, ou None s'il est illisible.

    None n'est pas un echec : ce fichier peut manquer dans un depot de test. Le
    controle des changelogs est alors simplement saute, et il le dit.
    """
    chemin = os.path.join(RACINE, "version.properties")
    if not os.path.isfile(chemin):
        return None
    for ligne in open(chemin, encoding="utf-8"):
        cle, _, valeur = ligne.partition("=")
        if cle.strip() == "versionCode":
            try:
                return int(valeur.strip())
            except ValueError:
                return None
    return None


def verifier_langue(dossier, ref_chaines, ref_pluriels, gradle, config):
    lg = etiquette(dossier)
    avant = len(erreurs)
    chaines, pluriels = lire(os.path.join(RES, dossier, "strings.xml"))

    toutes_ref = set(ref_chaines) | set(ref_pluriels)
    toutes = set(chaines) | set(pluriels)

    # 1. CLES
    for c in sorted(toutes_ref - toutes):
        echec(lg, "cle MANQUANTE : %s" % c)
    for c in sorted(toutes - toutes_ref):
        echec(lg, "cle EN TROP (absente de l'anglais) : %s" % c)

    # 2. PARAMETRES DE FORMAT - le seul defaut qui fait planter l'application
    for c, attendu in ref_chaines.items():
        if c not in chaines:
            continue
        a, b = parametres(attendu), parametres(chaines[c])
        if a != b:
            echec(lg, "parametres de format divergents sur %s : anglais=%s traduction=%s"
                      % (c, joli(a), joli(b)))
    for c, items in ref_pluriels.items():
        if c not in pluriels:
            continue
        attendu = parametres(" ".join(items.values()))
        for q, t in sorted(pluriels[c].items()):
            invente = parametres(t) - attendu
            if invente:
                echec(lg, "parametre INVENTE dans le pluriel %s[%s] : %s" % (c, q, joli(invente)))

    # 3. PLURIELS - les categories CLDR de LA LANGUE, pas un plancher anglais.
    #
    # v1.28.12 — ce controle n'exigeait que les quantites presentes en ANGLAIS. Or
    # l'anglais n'a que `one` et `other` : un `values-fr` ampute de `many` passait le
    # gate sans un mot, alors que le francais, l'italien et l'espagnol en ont besoin
    # pour les millions exacts. Le seul garde-fou etait le lint `MissingQuantity`, qui
    # ne tourne pas dans ce script. Un controle qui ne verifie que le plus petit
    # denominateur commun ne verifie pas la langue qu'il croit verifier.
    for c, items in ref_pluriels.items():
        if c not in pluriels:
            continue
        for q in sorted(set(items) - set(pluriels[c])):
            echec(lg, "quantite MANQUANTE dans le pluriel %s : %s" % (c, q))
    requises = QUANTITES_CLDR.get(lg)
    if requises is not None:
        for c in sorted(pluriels):
            manquantes = requises - set(pluriels[c])
            if manquantes:
                echec(lg, "pluriel %s : categorie CLDR MANQUANTE pour cette langue : %s"
                          % (c, ", ".join(sorted(manquantes))))
            superflues = set(pluriels[c]) - requises
            if superflues:
                echec(lg, "pluriel %s : categorie CLDR INUTILE en %s (jamais rendue) : %s"
                          % (c, lg, ", ".join(sorted(superflues))))

    # 4. LES QUATRE GESTES
    if lg not in gradle:
        echec(lg, "absente de localeFilters (app/build.gradle.kts) : AGP RETIRERAIT ces "
                  "ressources de l'APK et l'application resterait en anglais")
    if lg not in config:
        echec(lg, "absente de res/xml/locales_config.xml : pas de selecteur de langue par "
                  "application sur Android 13+")
    jumeau = os.path.join(RES_DEBUG, dossier, "strings.xml")
    if not os.path.exists(jumeau):
        echec(lg, "jumeau debug absent (%s) : la build de debug porterait le MEME nom que la "
                  "release sur un appareil dans cette langue"
                  % os.path.relpath(jumeau, RACINE).replace(os.sep, "/"))
    else:
        with open(jumeau, encoding="utf-8") as f:
            if "app_name" not in f.read():
                echec(lg, "le jumeau debug existe mais ne redefinit pas app_name")

    etat = "parite complete, quatre gestes faits" if len(erreurs) == avant \
        else "%d ecart(s)" % (len(erreurs) - avant)
    print("  %-8s %4d cles - %s" % (lg, len(toutes), etat))


def main():
    source = os.path.join(RES, "values", "strings.xml")
    if not os.path.exists(source):
        print("ERREUR : source anglaise introuvable (%s)" % source, file=sys.stderr)
        return 2

    ref_chaines, ref_pluriels = lire(source)
    total_ref = len(ref_chaines) + len(ref_pluriels)
    print("Source anglaise : %d cles (%d chaines + %d pluriels)"
          % (total_ref, len(ref_chaines), len(ref_pluriels)))

    dossiers = sorted(d for d in os.listdir(RES)
                      if LANGUE.match(d) and os.path.exists(os.path.join(RES, d, "strings.xml")))
    gradle = langues_declarees_gradle()
    config = langues_declarees_locales_config()

    for dossier in dossiers:
        verifier_langue(dossier, ref_chaines, ref_pluriels, gradle, config)

    # LA RECIPROQUE : une langue annoncee sans traduction derriere.
    traduites = {etiquette(d) for d in dossiers} | {"en"}
    verifier_test_des_sms(traduites)
    for lg in sorted(gradle - traduites):
        echec(lg, "annoncee dans localeFilters mais aucun values-%s/strings.xml : "
                  "l'application offrirait une langue vide" % lg)
    for lg in sorted(config - traduites):
        echec(lg, "annoncee dans locales_config.xml mais aucun values-%s/strings.xml : "
                  "le selecteur proposerait une langue vide" % lg)

    verifier_fins_de_ligne(dossiers)
    verifier_fastlane()

    print()
    if erreurs:
        print("PARITE i18n : %d ECART(S)" % len(erreurs))
        courante = None
        for lg, message in erreurs:
            if lg != courante:
                print("\n  [%s]" % lg)
                courante = lg
            print("    - %s" % message)
        print("\nLa build s'arrete ici. Voir TRANSLATING.md pour la marche a suivre.")
        return 1

    print("PARITE i18n : %d langue(s) alignee(s) sur les %d cles anglaises."
          % (len(dossiers), total_ref))
    return 0


if __name__ == "__main__":
    sys.exit(main())
