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
4. LES QUATRE GESTES SOLIDAIRES - une traduction n'est livree que si les quatre sont faits :
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

# values-de, values-pt-rBR, values-b+sr+Latn - et surtout PAS values-night, values-v29,
# values-land, values-sw600dp... qui sont des qualificateurs de configuration, pas des langues.
LANGUE = re.compile(r"^values-(?:(b\+[A-Za-z+]+)|([a-z]{2,3})(?:-r([A-Z]{2}))?)$")
PARAMETRE = re.compile(r"%(\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])")

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

    # 3. PLURIELS - plancher : toute quantite de l'anglais doit exister
    for c, items in ref_pluriels.items():
        if c not in pluriels:
            continue
        for q in sorted(set(items) - set(pluriels[c])):
            echec(lg, "quantite MANQUANTE dans le pluriel %s : %s" % (c, q))

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
    for lg in sorted(gradle - traduites):
        echec(lg, "annoncee dans localeFilters mais aucun values-%s/strings.xml : "
                  "l'application offrirait une langue vide" % lg)
    for lg in sorted(config - traduites):
        echec(lg, "annoncee dans locales_config.xml mais aucun values-%s/strings.xml : "
                  "le selecteur proposerait une langue vide" % lg)

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
