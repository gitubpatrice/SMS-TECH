#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Controle des permissions du manifeste FUSIONNE release - il ECHOUE, il n'avertit pas.

Ce que ce controle protege
--------------------------
v1.28.13 (MR F-Droid !38458, relecture de mezinster) - SMS Tech ne detient plus la permission
INTERNET. Elle etait declaree « pour le transport MMS », alors que l'application n'ouvre aucune
connexion : le service MMS d'Android, dans son propre processus, parle au MMSC de l'operateur.
Sans elle, le processus de l'application n'est pas dans le groupe noyau `inet` et ne peut creer
aucun socket : « aucun acces reseau » devient VERIFIABLE. Une promesse qu'aucune build ne verifie
n'est qu'une intention - modele : tools/check-manifest-permissions.py d'Agenda Tech.

Deux pieges que ce script evite
-------------------------------
1. Le manifeste SOURCE ne prouve rien : une dependance peut ajouter une permission a la fusion
   (androidx.work apporte ACCESS_NETWORK_STATE, WAKE_LOCK, RECEIVE_BOOT_COMPLETED). Seul le manifeste
   fusionne de la variante RELEASE dit ce que porte l'APK publie.
2. `<uses-permission>` peut s'ecrire sur plusieurs lignes : un grep ligne a ligne ne le voit pas.
   On lit le XML.

Liste revue, pas seulement liste noire : une permission qui arrive par une dependance doit etre un
acte delibere - l'ajouter a AUTORISEES ET a PERMISSIONS.md, ou retirer la dependance.

Les exceptions au lint ProtectedPermissions, dans les manifestes SOURCE
-----------------------------------------------------------------------
Relecture de mezinster sur la 1.28.13 : le manifeste demandait BROADCAST_WAP_PUSH, une permission
`signature` qu'aucune application installee par l'utilisateur n'obtient. Mesure sur le S9 le
2026-10-04 : demandee, jamais accordee (`dumpsys package`). Le lint le signalait depuis la v1.2.0 ;
un `tools:ignore="ProtectedPermissions"` le faisait taire, et PERMISSIONS.md la justifiait par un
« appariement requis » qui n'existe pas - c'est l'attribut `android:permission` du RECEPTEUR qui
compte, et il reste. Une exception a ce lint doit donc etre revue, comme une permission : elle figure
dans PROTEGEES_REVUES avec sa mesure, ou la build echoue. Le manifeste fusionne ne porte plus les
attributs `tools:`, d'ou la lecture des manifestes source.

Usage
-----
    python3 .github/scripts/permissions-manifeste.py [manifeste]
    python3 .github/scripts/permissions-manifeste.py --controle-negatif

Code 0 si le manifeste correspond exactement a la liste revue et si chaque exception au lint
ProtectedPermissions est revue, 1 sinon. Le contrôle negatif fabrique des manifestes fautifs et
exige que chacun soit REFUSE : un controle qui ne peut pas echouer ne protege rien.
"""

from __future__ import annotations

import contextlib
import glob
import io
import os
import sys
import tempfile
import xml.etree.ElementTree as ET

NS_ANDROID = "http://schemas.android.com/apk/res/android"

MANIFESTE_PAR_DEFAUT = os.path.join(
    "app", "build", "intermediates", "merged_manifest", "release",
    "processReleaseMainManifest", "AndroidManifest.xml",
)

# Ne doit JAMAIS atteindre l'APK, quoi que dise la liste revue.
INTERDITES = {
    "android.permission.INTERNET",
}

# Tout ce que l'APK revu porte. Chaque entree est documentee dans PERMISSIONS.md.
AUTORISEES = {
    # Declarees dans app/src/main/AndroidManifest.xml.
    "android.permission.SEND_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.READ_SMS",
    "android.permission.WRITE_SMS",
    "android.permission.RECEIVE_MMS",
    "android.permission.RECEIVE_WAP_PUSH",
    "android.permission.READ_CONTACTS",
    "android.permission.READ_PHONE_STATE",
    "android.permission.READ_PHONE_NUMBERS",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.VIBRATE",
    "android.permission.USE_BIOMETRIC",
    "android.permission.RECORD_AUDIO",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.CALL_PHONE",
    "android.permission.HIDE_OVERLAY_WINDOWS",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    # Transitives : androidx.biometric (Android 8) et androidx.work.
    "android.permission.USE_FINGERPRINT",
    "android.permission.WAKE_LOCK",
    # androidx.work : LIT l'etat de la connexion, n'en ouvre aucune.
    "android.permission.ACCESS_NETWORK_STATE",
    # Signature, generee par androidx.core pour ses recepteurs non exportes.
    "com.filestech.sms.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}

NS_TOOLS = "http://schemas.android.com/tools"

# Les seules permissions dont le manifeste SOURCE peut faire taire le lint ProtectedPermissions.
# Chaque entree porte sa mesure : une exception sans preuve est la faute meme que ce controle vise.
PROTEGEES_REVUES = {
    # Niveau `normal`, accordee a l'installation (S9 Android 10, 2026-10-04 : `pm list permissions -f`
    # et `dumpsys package`). Le lint la classe encore parmi les permissions reservees au systeme.
    "android.permission.WRITE_SMS",
}

EN_CI = os.environ.get("GITHUB_ACTIONS") == "true"


def erreur(lignes: list[str]) -> None:
    prefixe = "::error::" if EN_CI else "ERREUR: "
    for ligne in lignes:
        print(f"{prefixe}{ligne}")


def permissions_declarees(chemin: str) -> set[str]:
    racine = ET.parse(chemin).getroot()
    noms = set()
    for balise in ("uses-permission", "uses-permission-sdk-23"):
        for element in racine.iter(balise):
            nom = element.get(f"{{{NS_ANDROID}}}name")
            if nom:
                noms.add(nom)
    return noms


def verifier(chemin: str, bavard: bool = True) -> int:
    if not os.path.isfile(chemin):
        erreur([
            f"manifeste fusionne introuvable : {chemin}",
            "lancer d'abord : ./gradlew :app:processReleaseMainManifest",
        ])
        return 1
    try:
        trouvees = permissions_declarees(chemin)
    except ET.ParseError as e:
        erreur([f"manifeste fusionne illisible ({chemin}) : {e}"])
        return 1
    if not trouvees:
        # Un resultat vide est un controle casse, jamais un certificat de proprete.
        erreur([f"aucune permission lue dans {chemin} : c'est un echec du controle, pas une absence."])
        return 1

    if bavard:
        print(f"Permissions du manifeste fusionne ({len(trouvees)}) :")
        for nom in sorted(trouvees):
            print(f"  {nom}")

    problemes: list[str] = []
    interdites = sorted(trouvees & INTERDITES)
    if interdites:
        problemes.append("Permission RESEAU presente dans le manifeste fusionne release :")
        problemes += [f"  {nom}" for nom in interdites]
        problemes.append(
            "SMS Tech annonce ne detenir aucune permission INTERNET (PERMISSIONS.md, PRIVACY.md). "
            "Retirer la dependance qui l'apporte, ou la retirer par tools:node=\"remove\" en le justifiant."
        )
    inattendues = sorted(trouvees - AUTORISEES - INTERDITES)
    if inattendues:
        problemes.append("Permission(s) hors de la liste revue :")
        problemes += [f"  {nom}" for nom in inattendues]
        problemes.append("L'ajouter a AUTORISEES ET a PERMISSIONS.md, ou retirer la dependance.")
    absentes = sorted(AUTORISEES - trouvees)
    if absentes and bavard:
        print("\nNote - attendue(s) mais absente(s) ; retirer de AUTORISEES et de PERMISSIONS.md si voulu :")
        for nom in absentes:
            print(f"  {nom}")

    if problemes:
        if bavard:
            print()
        erreur(problemes)
        return 1
    if bavard:
        print("\nOK : pas d'INTERNET, et aucune permission hors de la liste revue.")
    return 0


def manifestes_source() -> list[str]:
    """Les manifestes de chaque module et de chaque jeu de sources (main, debug...)."""
    return sorted(glob.glob(os.path.join("*", "src", "*", "AndroidManifest.xml")))


def verifier_exceptions_lint(chemins: list[str], bavard: bool = True) -> int:
    """Chaque `tools:ignore` qui couvre ProtectedPermissions doit viser une permission revue.

    `all` fait taire tous les lints, ProtectedPermissions compris : il compte. Pose sur un autre
    element qu'un <uses-permission> (la racine <manifest>, par exemple), il couvrirait tout ce
    qui est dessous : refuse aussi.
    """
    if not chemins:
        erreur(["aucun manifeste source trouve (*/src/*/AndroidManifest.xml) : c'est un echec du "
                "controle, pas une absence. Lancer le script depuis la racine du depot."])
        return 1
    problemes: list[str] = []
    for chemin in chemins:
        try:
            racine = ET.parse(chemin).getroot()
        except ET.ParseError as e:
            problemes.append(f"manifeste source illisible ({chemin}) : {e}")
            continue
        for element in racine.iter():
            ignores = {v.strip() for v in element.get(f"{{{NS_TOOLS}}}ignore", "").split(",")}
            if not ignores & {"ProtectedPermissions", "all"}:
                continue
            nom = element.get(f"{{{NS_ANDROID}}}name", "")
            if element.tag == "uses-permission" and nom in PROTEGEES_REVUES:
                continue
            problemes.append(f"{chemin} : <{element.tag} {nom}> fait taire le lint ProtectedPermissions")
    if problemes:
        problemes.append(
            "Une permission reservee au systeme n'est jamais accordee a une application installee par "
            "l'utilisateur : la retirer. Si la mesure sur appareil prouve le contraire, l'ajouter a "
            "PROTEGEES_REVUES avec cette mesure.")
        erreur(problemes)
        return 1
    if bavard:
        print(f"OK : aucune exception non revue au lint ProtectedPermissions ({len(chemins)} manifestes source).")
    return 0


def controle_negatif() -> int:
    """Chaque manifeste fautif doit etre REFUSE ; le manifeste conforme doit passer."""

    def manifeste(permissions: list[str]) -> str:
        lignes = [f'<manifest xmlns:android="{NS_ANDROID}" package="com.filestech.sms">']
        for nom in permissions:
            # Une permission sur PLUSIEURS lignes : la forme qu'un grep ne voit pas.
            lignes.append(f'  <uses-permission\n      android:name="{nom}" />')
        lignes.append("</manifest>")
        fd, chemin = tempfile.mkstemp(suffix=".xml")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write("\n".join(lignes))
        return chemin

    conforme = sorted(AUTORISEES)
    cas = [
        ("conforme", conforme, 0),
        ("INTERNET ajoutee", conforme + ["android.permission.INTERNET"], 1),
        ("permission inconnue", conforme + ["android.permission.READ_CALL_LOG"], 1),
        ("manifeste vide", [], 1),
    ]
    echecs = 0
    for nom, permissions, attendu in cas:
        chemin = manifeste(permissions)
        try:
            obtenu = verifier(chemin, bavard=False)
        finally:
            os.unlink(chemin)
        ok = obtenu == attendu
        echecs += 0 if ok else 1
        print(f"  {'OK   ' if ok else 'RATE '} {nom} : attendu {attendu}, obtenu {obtenu}")

    def source(corps: str) -> str:
        fd, chemin = tempfile.mkstemp(suffix=".xml")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(f'<manifest xmlns:android="{NS_ANDROID}" xmlns:tools="{NS_TOOLS}"{corps}</manifest>')
        return chemin

    def exception(nom: str, valeur: str = "ProtectedPermissions") -> str:
        return f'  <uses-permission\n      android:name="{nom}"\n      tools:ignore="{valeur}" />\n'

    # (nom, corps du manifeste source ou None pour « aucun manifeste », code attendu, cause attendue)
    cas_lint = [
        ("exception revue (WRITE_SMS)", ">\n" + exception("android.permission.WRITE_SMS"), 0, ""),
        ("exception sur BROADCAST_WAP_PUSH (le defaut de la 1.28.13)",
         ">\n" + exception("android.permission.BROADCAST_WAP_PUSH"), 1, "BROADCAST_WAP_PUSH"),
        ("tools:ignore=\"all\"", ">\n" + exception("android.permission.READ_CONTACTS", "all"),
         1, "READ_CONTACTS"),
        ("exception parmi d'autres lints",
         ">\n" + exception("android.permission.CALL_PHONE", "MissingVersion, ProtectedPermissions"),
         1, "CALL_PHONE"),
        ("exception posee sur la racine <manifest>",
         ' tools:ignore="ProtectedPermissions">\n' + exception("android.permission.WRITE_SMS", "Autre"),
         1, "<manifest"),
        ("aucun manifeste source", None, 1, "aucun manifeste source"),
    ]
    for nom, corps, attendu, cause in cas_lint:
        chemins = [] if corps is None else [source(corps)]
        sortie = io.StringIO()
        try:
            with contextlib.redirect_stdout(sortie):
                obtenu = verifier_exceptions_lint(chemins, bavard=False)
        finally:
            for chemin in chemins:
                os.unlink(chemin)
        ok = obtenu == attendu and cause in sortie.getvalue()
        echecs += 0 if ok else 1
        print(f"  {'OK   ' if ok else 'RATE '} lint : {nom} : attendu {attendu}, obtenu {obtenu}")
        if obtenu == attendu and not ok:
            print(f"        rougit, mais sans nommer « {cause} » :\n        {sortie.getvalue().strip()}")

    if echecs:
        erreur([f"le controle des permissions ne refuse pas ce qu'il doit refuser ({echecs} cas)"])
        return 1
    print("Controle negatif : chaque manifeste fautif est refuse, et pour la bonne raison ; le conforme passe.")
    return 0


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--controle-negatif":
        sys.exit(controle_negatif())
    fusionne = verifier(sys.argv[1] if len(sys.argv) > 1 else MANIFESTE_PAR_DEFAUT)
    print()
    sources = verifier_exceptions_lint(manifestes_source())
    sys.exit(1 if fusionne or sources else 0)
