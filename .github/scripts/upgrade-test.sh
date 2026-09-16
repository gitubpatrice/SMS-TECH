#!/usr/bin/env bash
#
# Contrôle de mise à jour EN PLACE : la version précédente écrit un jeu d'essai dans sa base
# chiffrée, la version courante est installée PAR-DESSUS sans effacement, et doit tout relire.
#
# Ce que ce contrôle protège
# --------------------------
# La v1.28.10 a retiré les onze règles `ignore` de Dependabot qui gelaient SQLCipher et Room —
# les deux bibliothèques qui touchent aux données stockées. Leurs montées arrivent désormais
# toutes seules, en PR, avec une CI verte qui ne vérifiait pas qu'une base chiffrée EXISTANTE
# s'ouvre encore. Le mode de défaillance visé n'est pas un plantage : c'est une perte de données
# chez quelqu'un qui a seulement accepté une mise à jour.
#
# Pourquoi ce script, et pas une tâche Gradle
# -------------------------------------------
# `connectedDebugAndroidTest` DÉSINSTALLE le paquet en fin de course (mesuré le 2026-09-14) : elle
# effacerait le jeu d'essai entre le semis et sa relecture. Le pilotage passe donc par
# `adb install -r` et `am instrument`, qui laissent les données en place.
#
# Pourquoi compter les codes de statut
# ------------------------------------
# `am instrument` rend TOUJOURS 0, y compris quand tous les tests tombent. Sous AGP 9, un test
# ignoré est de surcroît écrit `FAILED` dans les rapports et le listener peut perdre toute une
# suite. Le seul juge est donc le compte des `INSTRUMENTATION_STATUS_CODE` du flux :
#   0 = réussi   -1 = erreur   -2 = échec   -3/-4 = ignoré
# (à ne pas confondre avec l'`INSTRUMENTATION_CODE: -1` qui clôt CHAQUE exécution, réussie ou non).
#
# Utilisable en local : APK_ANCIEN=… APK_ANCIEN_TEST=… APK_NOUVEAU=… APK_NOUVEAU_TEST=… \
#                       SERIE=emulator-5554 .github/scripts/upgrade-test.sh

set -euo pipefail

PAQUET="${PAQUET:-com.filestech.sms.debug}"
PAQUET_TEST="${PAQUET_TEST:-com.filestech.sms.debug.test}"
INSTRUMENTATION="$PAQUET_TEST/com.filestech.sms.HiltTestRunner"
SEMIS="com.filestech.sms.upgrade.UpgradeSeedTest"
VERIFICATION="com.filestech.sms.upgrade.UpgradeVerifyTest"

# Nombre de cas attendus de chaque côté. En dur, et c'est voulu : ajouter un test à
# `UpgradeVerifyTest` doit obliger à toucher cette ligne. Un « au moins un test réussi » laisserait
# passer une suite à moitié perdue par le listener.
CAS_ATTENDUS_SEMIS="${CAS_ATTENDUS_SEMIS:-1}"
CAS_ATTENDUS_VERIFICATION="${CAS_ATTENDUS_VERIFICATION:-5}"

BASE_DANS_LE_PAQUET="databases/smstech.db"
CLE_DANS_LE_PAQUET="files/db/master.key"

ADB=(adb)
if [ -n "${SERIE:-}" ]; then ADB=(adb -s "$SERIE"); fi

TRAVAIL="${TRAVAIL:-$(mktemp -d)}"
mkdir -p "$TRAVAIL"

echo "::group::Paramètres"
echo "  paquet          : $PAQUET"
echo "  ancienne (app)  : ${APK_ANCIEN:?APK_ANCIEN manquant}"
echo "  ancienne (test) : ${APK_ANCIEN_TEST:?APK_ANCIEN_TEST manquant}"
echo "  courante (app)  : ${APK_NOUVEAU:?APK_NOUVEAU manquant}"
echo "  courante (test) : ${APK_NOUVEAU_TEST:?APK_NOUVEAU_TEST manquant}"
echo "  travail         : $TRAVAIL"
echo "::endgroup::"

# --------------------------------------------------------------------------------------------
# Outils
# --------------------------------------------------------------------------------------------

PASSED=0; ECHECS=0; ERREURS=0; IGNORES=0

lancer_les_tests() { # $1 = classe, $2 = fichier de sortie
  "${ADB[@]}" shell am instrument -w -r -e class "$1" "$INSTRUMENTATION" 2>&1 | tr -d '\r' > "$2" || true
  PASSED=$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' "$2" || true)
  ECHECS=$(grep -c '^INSTRUMENTATION_STATUS_CODE: -2$' "$2" || true)
  ERREURS=$(grep -c '^INSTRUMENTATION_STATUS_CODE: -1$' "$2" || true)
  IGNORES=$(grep -c '^INSTRUMENTATION_STATUS_CODE: -[34]$' "$2" || true)
  echo "  -> réussis=$PASSED échecs=$ECHECS erreurs=$ERREURS ignorés=$IGNORES  ($1)"
  if grep -q 'Process crashed\|Unable to find instrumentation' "$2"; then
    echo "  -> le processus de test ne s'est pas exécuté :"
    grep -m3 'Process crashed\|Unable to find instrumentation\|shortMsg' "$2" || true
  fi
}

echouer() {
  echo "::error::$*"
  echo
  echo "--- dernières lignes du flux d'instrumentation ---"
  tail -40 "$TRAVAIL"/*.txt 2>/dev/null || true
  exit 1
}

# Le fichier est lu par `exec-out` — flux binaire, sans passage par un pseudo-terminal, donc sans
# traduction de fins de ligne — et mesuré côté hôte : aucune dépendance aux outils de l'image
# Android, dont l'outillage varie d'un niveau d'API à l'autre.
copier_la_base() { # $1 = nom du relevé
  "${ADB[@]}" exec-out run-as "$PAQUET" cat "$BASE_DANS_LE_PAQUET" > "$TRAVAIL/$1.bin" || true
  # Un `run-as` refusé (paquet non débogable, paquet absent) écrit son refus À LA PLACE du fichier.
  # Le relevé n'est alors pas VIDE : ses seize premiers octets donnent trente-deux caractères
  # hexadécimaux parfaitement valides, et la comparaison des sels réussirait en ne comparant que
  # deux messages d'erreur identiques. La taille tranche sans ambiguïté — la moindre base SQLCipher
  # dépasse largement une page.
  local taille
  taille=$(wc -c < "$TRAVAIL/$1.bin")
  if [ "$taille" -lt 4096 ]; then
    echo "  contenu du relevé : $(head -c 200 "$TRAVAIL/$1.bin")"
    echouer "relevé « $1 » : $taille octets, ce n'est pas une base de données"
  fi
  # La clé enrobée est relevée avec la base : si un jour la relecture échoue sur « decrypts with
  # neither the Keystore passphrase nor the legacy zero key », la question est de savoir lequel des
  # deux a bougé. Sans ce relevé, il n'y a plus rien à examiner après coup.
  "${ADB[@]}" exec-out run-as "$PAQUET" cat "$CLE_DANS_LE_PAQUET" > "$TRAVAIL/$1.key" 2>/dev/null || true
}

sel_de() { # $1 = nom du relevé — les 16 octets de tête, que SQLCipher tire au hasard à la création
  head -c 16 "$TRAVAIL/$1.bin" | od -An -tx1 | tr -d ' \n'
}

empreinte_de() { md5sum "$TRAVAIL/$1.bin" | cut -d' ' -f1; }

# ⚠️ `dumpsys package` écrit BEAUCOUP, et un `grep -m1` en aval sort au premier résultat puis ferme
# le tuyau : `adb` reçoit alors SIGPIPE et meurt en 141, que `pipefail` transforme — à raison — en
# échec du script. Mesuré sur le runner GitHub le 2026-09-16 : les deux installations réussissent,
# puis « The process '/usr/bin/sh' failed with exit code 141 ». En local sous MSYS, le même
# enchaînement passait : c'est exactement la panne qui ne se voit que sur la vraie CI.
# Le relevé complet est donc écrit AVANT d'être filtré, et sert accessoirement de pièce à conviction.
version_installee() {
  "${ADB[@]}" shell dumpsys package "$PAQUET" | tr -d '\r' > "$TRAVAIL/dumpsys.txt"
  grep -m1 -o 'versionCode=[0-9]*' "$TRAVAIL/dumpsys.txt" || echo "versionCode=inconnu"
}

# --------------------------------------------------------------------------------------------
# 1. Table rase, puis la version PRÉCÉDENTE
# --------------------------------------------------------------------------------------------

echo "::group::1. Installation de la version précédente"
"${ADB[@]}" uninstall "$PAQUET_TEST" >/dev/null 2>&1 || true
"${ADB[@]}" uninstall "$PAQUET" >/dev/null 2>&1 || true
"${ADB[@]}" install -r "$APK_ANCIEN"
"${ADB[@]}" install -r "$APK_ANCIEN_TEST"
VERSION_ANCIENNE=$(version_installee)
echo "  installée : $VERSION_ANCIENNE"
echo "::endgroup::"

echo "::group::2. Semis du jeu d'essai par la version précédente"
lancer_les_tests "$SEMIS" "$TRAVAIL/1-semis.txt"
[ "$PASSED" = "$CAS_ATTENDUS_SEMIS" ] && [ "$ECHECS" = 0 ] && [ "$ERREURS" = 0 ] && [ "$IGNORES" = 0 ] \
  || echouer "le semis n'a pas abouti — rien ne sert de mesurer la suite"
copier_la_base avant
SEL_AVANT=$(sel_de avant); EMPREINTE_AVANT=$(empreinte_de avant)
[ ${#SEL_AVANT} -eq 32 ] || echouer "sel illisible ($SEL_AVANT) : la comparaison d'après serait vide de sens"
echo "  sel       : $SEL_AVANT"
echo "  empreinte : $EMPREINTE_AVANT"
echo "  clé       : $(md5sum "$TRAVAIL/avant.key" | cut -d' ' -f1)"
echo "::endgroup::"

# --------------------------------------------------------------------------------------------
# 3. La mise à jour elle-même
# --------------------------------------------------------------------------------------------

echo "::group::3. Mise à jour EN PLACE vers la révision courante"
"${ADB[@]}" install -r "$APK_NOUVEAU"
"${ADB[@]}" install -r "$APK_NOUVEAU_TEST"
VERSION_NOUVELLE=$(version_installee)
echo "  installée : $VERSION_NOUVELLE (était $VERSION_ANCIENNE)"

# ⚠️ Ce contrôle porte sur les FICHIERS, surtout pas sur les `versionCode`. Sur une branche ou une
# PR — le cas normal, et notamment celui d'une montée Dependabot — `version.properties` n'est pas
# bumpé : les deux côtés portent le même numéro tout en étant deux applications différentes. Ce
# qu'il faut écarter, c'est la mise à jour qui n'en est pas une, c'est-à-dire deux APK identiques.
[ "$(md5sum < "$APK_ANCIEN")" != "$(md5sum < "$APK_NOUVEAU")" ] \
  || echouer "les deux APK sont le MÊME fichier : il n'y a pas de mise à jour à mesurer"
echo "::endgroup::"

echo "::group::4. Relecture par la version courante"
lancer_les_tests "$VERIFICATION" "$TRAVAIL/2-verification.txt"

# Le relevé est pris AVANT le verdict, à dessein : sur un échec, c'est l'état de la base et de la
# clé qui dit pourquoi, et un `exit` posé plus haut emporterait la seule pièce à conviction.
copier_la_base apres
SEL_APRES=$(sel_de apres); EMPREINTE_APRES=$(empreinte_de apres)

[ "$PASSED" = "$CAS_ATTENDUS_VERIFICATION" ] && [ "$ECHECS" = 0 ] && [ "$ERREURS" = 0 ] && [ "$IGNORES" = 0 ] \
  || echouer "la version courante ne relit pas ce que la précédente avait écrit"
echo "  sel       : $SEL_APRES"
echo "  empreinte : $EMPREINTE_APRES"
echo "  clé       : $(md5sum "$TRAVAIL/apres.key" | cut -d' ' -f1)"

# Le sel est tiré au hasard à la CRÉATION du fichier et ne change plus, sauf re-chiffrement complet
# (`PRAGMA rekey`, conversion de format) qui réécrit tout. Son égalité est donc la preuve directe
# qu'aucune migration de chiffrement n'a eu lieu — et elle reste vraie même quand une migration
# Room légitime a réécrit des pages, ce que l'empreinte du fichier entier, elle, ne permettrait pas
# d'affirmer. C'est pour cela que seule celle-ci est bloquante.
[ "$SEL_AVANT" = "$SEL_APRES" ] \
  || echouer "le sel SQLCipher a changé : la base a été RE-CHIFFRÉE pendant la mise à jour"
if [ "$EMPREINTE_AVANT" = "$EMPREINTE_APRES" ]; then
  echo "  -> fichier inchangé au bit près : aucune réécriture"
else
  echo "  -> fichier modifié mais sel intact : réécriture de pages sans re-chiffrement"
  echo "     (attendu si cette version porte une migration Room)"
fi
echo "::endgroup::"

# --------------------------------------------------------------------------------------------
# 5. Les contrôles négatifs — sans eux, rien ne distingue une vérification qui passe
#    d'une vérification qui ne PEUT pas échouer.
# --------------------------------------------------------------------------------------------

echo "::group::5. Contrôle négatif nº1 — base corrompue"
"${ADB[@]}" shell "run-as $PAQUET sh -c 'dd if=/dev/urandom of=$BASE_DANS_LE_PAQUET bs=4096 count=1 conv=notrunc'" \
  > "$TRAVAIL/dd.txt" 2>&1 || true
grep -q 'records out' "$TRAVAIL/dd.txt" || echouer "la corruption n'a pas eu lieu : $(cat "$TRAVAIL/dd.txt")"
lancer_les_tests "$VERIFICATION" "$TRAVAIL/3-cn-corrompue.txt"
[ "$PASSED" = 0 ] || echouer "$PASSED test(s) réussissent sur une base CORROMPUE — la vérification est décorative"
echo "  -> la vérification tombe bien, comme exigé"
echo "::endgroup::"

echo "::group::6. Contrôle négatif nº2 — données effacées"
"${ADB[@]}" shell pm clear "$PAQUET" | tr -d '\r'
lancer_les_tests "$VERIFICATION" "$TRAVAIL/4-cn-effacee.txt"
[ "$PASSED" = 0 ] || echouer "$PASSED test(s) réussissent sur des données EFFACÉES — ce(s) test(s) ne peuvent pas échouer"
echo "  -> aucun des $CAS_ATTENDUS_VERIFICATION cas ne survit à une base vide"
echo "::endgroup::"

echo "::group::7. Témoin positif — le sel sait différer"
lancer_les_tests "$SEMIS" "$TRAVAIL/5-temoin.txt"
[ "$PASSED" = "$CAS_ATTENDUS_SEMIS" ] || echouer "le témoin n'a pas pu recréer une base"
copier_la_base temoin
SEL_TEMOIN=$(sel_de temoin)
echo "  sel d'origine : $SEL_AVANT"
echo "  sel recréé    : $SEL_TEMOIN"
[ "$SEL_AVANT" != "$SEL_TEMOIN" ] \
  || echouer "deux bases distinctes portent le même sel : la comparaison de l'étape 4 ne mesure rien"
echo "  -> la comparaison du sel a du sens"
echo "::endgroup::"

echo
echo "=============================================================================="
echo " Mise à jour $VERSION_ANCIENNE -> $VERSION_NOUVELLE : le jeu d'essai est intact."
echo " Sel SQLCipher inchangé : aucune re-encryption de la base."
echo " Deux contrôles négatifs et un témoin positif passés."
echo "=============================================================================="
