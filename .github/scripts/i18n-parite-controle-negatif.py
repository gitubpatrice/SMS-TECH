#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Controle NEGATIF de i18n-parite.py - la preuve que l'instrument sait rougir.

Pourquoi ce fichier existe
--------------------------
Ce projet a deja paye le prix d'un test vert sur le defaut qu'il visait : une assertion de
schema qui passait sur une base effacee, quatre tests instrumentes comptes OK sans rien avoir
execute. Un controle de parite qui rendrait 0 quoi qu'il arrive serait pire qu'absent - il
donnerait une garantie fausse a chaque release.

Alors chaque mode de defaillance que i18n-parite.py pretend attraper est ici REPRODUIT dans un
arbre jetable, et deux choses sont verifiees :
  - le controle rend bien 1, et non 0 ;
  - son message nomme la BONNE cause. Echouer pour une autre raison est un faux vert : le jour
    ou la vraie cause disparait, le controle continue de rougir et plus personne ne le croit.

Un TEMOIN POSITIF ouvre la serie : un arbre parfaitement sain doit rendre 0. Sans lui, un
i18n-parite.py casse au point de rendre 1 sur tout ferait passer la serie entiere pour un succes.

Il n'a besoin d'aucune dependance et ne touche jamais au depot : tout se passe dans un dossier
temporaire, supprime a la fin.

    python3 .github/scripts/i18n-parite-controle-negatif.py
"""
import os
import shutil
import subprocess
import sys
import tempfile

ICI = os.path.dirname(os.path.abspath(__file__))
CONTROLE = os.path.join(ICI, "i18n-parite.py")

EN = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">SMS Tech</string>
    <string name="salut">Hello</string>
    <string name="promesse">100% private — zero tracking</string>
    <string name="envoye">Sent to %1$s at %2$s</string>
    <plurals name="messages">
        <item quantity="one">%1$d message</item>
        <item quantity="other">%1$d messages</item>
    </plurals>
</resources>
"""

# Noter le REORDONNANCEMENT de %1$s et %2$s : il est LEGITIME (c'est a cela que servent les
# 1$ / 2$) et le temoin positif ci-dessous exige donc qu'il passe. Un controle qui refuserait
# un ordre different interdirait de traduire vers la moitie des langues.
DE_SAIN = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">SMS Tech</string>
    <string name="salut">Hallo</string>
    <string name="promesse">100 % datenschutzfreundlich — null Tracking</string>
    <string name="envoye">Am %2$s an %1$s gesendet</string>
    <plurals name="messages">
        <item quantity="one">%1$d Nachricht</item>
        <item quantity="other">%1$d Nachrichten</item>
    </plurals>
</resources>
"""

GRADLE = """android {
    androidResources {
        localeFilters += listOf("en", "fr", "de")
    }
}
"""

LOCALES_CONFIG = """<?xml version="1.0" encoding="utf-8"?>
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="en" />
    <locale android:name="fr" />
    <locale android:name="de" />
</locale-config>
"""

DEBUG = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">SMS Tech Debug</string>
</resources>
"""

# Le test qui verifie les corps de SMS de securite, langue par langue. Seule sa liste de langues
# interesse le controle de parite : elle doit couvrir toutes les langues livrees.
TEST_SMS = """package com.filestech.sms.system.safety

class SafetyMessageTextsTest {
    private companion object {
        val LANGUES = listOf("en", "fr", "de")
    }
}
"""

base = None


def ecrire(chemin, contenu):
    os.makedirs(os.path.dirname(chemin), exist_ok=True)
    with open(chemin, "w", encoding="utf-8") as f:
        f.write(contenu)


def chemin(*morceaux):
    return os.path.join(base, *morceaux)


def res():
    return chemin("app", "src", "main", "res")


def res_debug():
    return chemin("app", "src", "debug", "res")


def batir():
    """Un arbre SAIN : allemand complet, quatre gestes faits, francais present."""
    if os.path.exists(base):
        shutil.rmtree(base)
    os.makedirs(chemin(".github", "scripts"))
    shutil.copy(CONTROLE, chemin(".github", "scripts", "i18n-parite.py"))
    ecrire(chemin("app", "build.gradle.kts"), GRADLE)
    ecrire(os.path.join(res(), "values", "strings.xml"), EN)
    ecrire(os.path.join(res(), "xml", "locales_config.xml"), LOCALES_CONFIG)
    for langue in ("de", "fr"):
        # Le francais est present pour que la RECIPROQUE (langue annoncee sans traduction) ne
        # se declenche pas toute seule et ne masque pas le defaut que chaque cas vise.
        ecrire(os.path.join(res(), "values-" + langue, "strings.xml"), DE_SAIN)
        ecrire(os.path.join(res_debug(), "values-" + langue, "strings.xml"), DEBUG)
    ecrire(chemin("app", "src", "test", "java", "com", "filestech", "sms", "system", "safety",
                  "SafetyMessageTextsTest.kt"), TEST_SMS)


def de(contenu):
    ecrire(os.path.join(res(), "values-de", "strings.xml"), contenu)


# --- Une mutation par mode de defaillance revendique -------------------------------------

def cle_manquante():
    de(DE_SAIN.replace('    <string name="salut">Hallo</string>\n', ""))
    return "cle MANQUANTE : salut"


def cle_en_trop():
    de(DE_SAIN.replace("</resources>", '    <string name="inconnue">x</string>\n</resources>'))
    return "cle EN TROP"


def cle_en_double():
    de(DE_SAIN.replace('    <string name="salut">Hallo</string>\n',
                       '    <string name="salut">Hallo</string>\n'
                       '    <string name="salut">Servus</string>\n'))
    return "cle EN DOUBLE : salut"


def parametre_perdu():
    de(DE_SAIN.replace("Am %2$s an %1$s gesendet", "An %1$s gesendet"))
    return "parametres de format divergents sur envoye"


def parametre_invente():
    de(DE_SAIN.replace("Am %2$s an %1$s gesendet", "Am %2$s an %1$s via %3$s gesendet"))
    return "parametres de format divergents sur envoye"


def parametre_malforme():
    """Un %1$p n'est pas une conversion Java : le parametre est PERDU, pas traduit.

    Ce cas garde le resserrement du motif : en n'acceptant plus n'importe quelle lettre,
    le controle doit continuer de voir la DISPARITION d'un vrai parametre.
    """
    de(DE_SAIN.replace("Am %2$s an %1$s gesendet", "Am %2$s an %1$p gesendet"))
    return "parametres de format divergents sur envoye"


def parametre_invente_dans_pluriel():
    de(DE_SAIN.replace('<item quantity="other">%1$d Nachrichten</item>',
                       '<item quantity="other">%1$d von %2$d Nachrichten</item>'))
    return "parametre INVENTE dans le pluriel messages[other]"


def quantite_manquante():
    de(DE_SAIN.replace('        <item quantity="one">%1$d Nachricht</item>\n', ""))
    return "quantite MANQUANTE dans le pluriel messages"


def absente_de_localefilters():
    ecrire(chemin("app", "build.gradle.kts"), GRADLE.replace('"en", "fr", "de"', '"en", "fr"'))
    return "absente de localeFilters"


def absente_de_locales_config():
    ecrire(os.path.join(res(), "xml", "locales_config.xml"),
           LOCALES_CONFIG.replace('    <locale android:name="de" />\n', ""))
    return "absente de res/xml/locales_config.xml"


def jumeau_debug_absent():
    os.remove(os.path.join(res_debug(), "values-de", "strings.xml"))
    return "jumeau debug absent"


def jumeau_debug_sans_app_name():
    ecrire(os.path.join(res_debug(), "values-de", "strings.xml"),
           '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
           '    <string name="autre">x</string>\n</resources>\n')
    return "ne redefinit pas app_name"


def langue_annoncee_sans_traduction():
    shutil.rmtree(os.path.join(res(), "values-de"))
    shutil.rmtree(os.path.join(res_debug(), "values-de"))
    return "offrirait une langue vide"


def langue_absente_du_test_des_sms():
    # Une langue livree mais absente de la liste du test : ses corps de SMS d'urgence ne
    # seraient verifies par personne, et la suite resterait verte.
    ecrire(chemin("app", "src", "test", "java", "com", "filestech", "sms", "system", "safety",
                  "SafetyMessageTextsTest.kt"),
           TEST_SMS.replace('"en", "fr", "de"', '"en", "fr"'))
    return "langue de absente de LANGUES"


def langue_du_test_non_traduite():
    ecrire(chemin("app", "src", "test", "java", "com", "filestech", "sms", "system", "safety",
                  "SafetyMessageTextsTest.kt"),
           TEST_SMS.replace('"en", "fr", "de"', '"en", "fr", "de", "it"'))
    return "langue it listee dans LANGUES mais non traduite"


def test_des_sms_disparu():
    os.remove(chemin("app", "src", "test", "java", "com", "filestech", "sms", "system", "safety",
                     "SafetyMessageTextsTest.kt"))
    return "les corps de SMS ne sont plus verifies par langue"


CAS = [
    ("cle manquante", cle_manquante),
    ("cle en trop", cle_en_trop),
    ("cle en double", cle_en_double),
    ("parametre de format PERDU", parametre_perdu),
    ("parametre de format INVENTE", parametre_invente),
    ("parametre de format MALFORME (%1$p)", parametre_malforme),
    ("parametre invente dans un pluriel", parametre_invente_dans_pluriel),
    ("quantite de pluriel manquante", quantite_manquante),
    ("geste 2 : absente de localeFilters", absente_de_localefilters),
    ("geste 3 : absente de locales_config", absente_de_locales_config),
    ("geste 4 : jumeau debug absent", jumeau_debug_absent),
    ("geste 4 : jumeau debug sans app_name", jumeau_debug_sans_app_name),
    ("reciproque : langue annoncee sans traduction", langue_annoncee_sans_traduction),
    ("corps de SMS : langue livree hors du test", langue_absente_du_test_des_sms),
    ("corps de SMS : langue testee non traduite", langue_du_test_non_traduite),
    ("corps de SMS : le test lui-meme a disparu", test_des_sms_disparu),
]


def lancer():
    r = subprocess.run([sys.executable, chemin(".github", "scripts", "i18n-parite.py")],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def main():
    global base
    if not os.path.exists(CONTROLE):
        print("ERREUR : %s introuvable." % CONTROLE, file=sys.stderr)
        return 2

    racine_temporaire = tempfile.mkdtemp(prefix="i18n-parite-")
    base = os.path.join(racine_temporaire, "arbre")
    echecs = []
    try:
        batir()
        code, sortie = lancer()
        if code == 0:
            print("  OK    TEMOIN POSITIF : arbre sain (parametres reordonnes compris) -> 0")
        else:
            print("  RATE  TEMOIN POSITIF : arbre sain -> %d" % code)
            print("        " + sortie.strip().replace("\n", "\n        "))
            echecs.append("temoin positif")

        for nom, muter in CAS:
            batir()
            attendu = muter()
            code, sortie = lancer()
            if code != 1:
                print("  RATE  %-46s code=%d (attendu 1) NON DETECTE" % (nom, code))
                echecs.append(nom)
            elif attendu not in sortie:
                print("  RATE  %-46s rougit, mais pour une AUTRE raison" % nom)
                print("        attendu : %s" % attendu)
                print("        " + sortie.strip().replace("\n", "\n        "))
                echecs.append(nom)
            else:
                print("  OK    %-46s detecte, bonne cause" % nom)
    finally:
        shutil.rmtree(racine_temporaire, ignore_errors=True)

    total = len(CAS) + 1
    print()
    if echecs:
        print("CONTROLE NEGATIF : %d/%d -- RATES : %s"
              % (total - len(echecs), total, ", ".join(echecs)))
        return 1
    print("CONTROLE NEGATIF : %d/%d -- chaque defaut est detecte, et pour la bonne raison."
          % (total, total))
    return 0


if __name__ == "__main__":
    sys.exit(main())
