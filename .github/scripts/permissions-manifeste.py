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

Usage
-----
    python3 .github/scripts/permissions-manifeste.py [manifeste]
    python3 .github/scripts/permissions-manifeste.py --controle-negatif

Code 0 si le manifeste correspond exactement a la liste revue, 1 sinon. Le contrôle negatif
fabrique des manifestes fautifs et exige que chacun soit REFUSE : un controle qui ne peut pas
echouer ne protege rien.
"""

from __future__ import annotations

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
    "android.permission.BROADCAST_WAP_PUSH",
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
    if echecs:
        erreur([f"le controle des permissions ne refuse pas ce qu'il doit refuser ({echecs} cas)"])
        return 1
    print("Controle negatif : chaque manifeste fautif est refuse, le conforme passe.")
    return 0


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--controle-negatif":
        sys.exit(controle_negatif())
    sys.exit(verifier(sys.argv[1] if len(sys.argv) > 1 else MANIFESTE_PAR_DEFAUT))
