#!/bin/bash
# ==============================================================================
# NEUTRALISE LE 2026-09-02 — NE PAS EXECUTER. Rien n'a ete supprime en dessous.
#
# Ce script a ete ecrit pour la PREMIERE soumission (v1.2.5, versionCode 13).
# Cette soumission a eu lieu : c'est la MR !38458, ouverte et en cours de revue.
# Le relancer aujourd'hui detruirait cette MR, en deux gestes precis :
#
#   cp "$YAML_SRC" metadata/com.filestech.sms.yml
#       -> ecrase la recette vivante (1.27.9 / 285) par la copie locale de ce
#          dossier, restee en 1.18.2 / 105 : sans Binaries:, sans
#          AllowedAPKSigningKeys, avec les prebuild: sed retires en aout 2026,
#          un AutoUpdateMode: Version sans le motif v%v que les tags exigent,
#          et un UpdateCheckData dont les deux regex sont INVERSEES (le
#          versionName occupe la place du versionCode).
#
#   git push -u origin add-sms-tech --force
#       -> reecrit l'historique d'une branche sous revue depuis des mois.
#
# La recette qui fait foi est : j:/applications/fdroiddata/metadata/com.filestech.sms.yml
# Le clone fdroiddata qui fait foi est : j:/applications/fdroiddata
# Pour publier une nouvelle version, editer cette recette-la, puis commit + push
# NORMAL (jamais --force) sur la branche add-sms-tech.
#
# Le corps d'origine est conserve tel quel ci-dessous, comme trace de la
# soumission initiale. Pour le relire sans risque : sed -n "/^set -e/,$p" submit.sh
# ==============================================================================
echo "submit.sh est neutralise : la soumission a deja eu lieu (MR !38458)." >&2
echo "Le relancer ecraserait la recette 1.27.9 par une copie 1.18.2 et force-pusherait." >&2
echo "Recette vivante : j:/applications/fdroiddata/metadata/com.filestech.sms.yml" >&2
exit 1

# F-Droid submission script for SMS Tech v1.2.5
# Run from j:/applications/sms_tech/fdroid-submission/
# Prerequisites: glab installed + authenticated as gitubpatrice (already done)
#
# What it does:
#   1. Clones the gitubpatrice/fdroiddata fork (shallow, ~150 MB)
#   2. Pulls latest fdroid/fdroiddata master so the branch is up to date
#   3. Creates branch add-sms-tech
#   4. Copies the metadata YAML
#   5. Commits + pushes to your fork
#   6. Opens the MR to fdroid/fdroiddata via glab

set -e

CLONE_DIR="j:/applications/fdroiddata"
YAML_SRC="j:/applications/sms_tech/fdroid-submission/com.filestech.sms.yml"

if [ -d "$CLONE_DIR" ]; then
  echo "==> Fork already cloned, updating"
  cd "$CLONE_DIR"
  git remote get-url upstream >/dev/null 2>&1 || \
    git remote add upstream https://gitlab.com/fdroid/fdroiddata.git
  git fetch upstream master
  git checkout master
  git merge upstream/master --ff-only
  git push origin master
else
  echo "==> Cloning fork (depth 50, ~150 MB, give it 3-5 min)"
  cd j:/applications
  git clone --depth 50 https://gitlab.com/gitubpatrice/fdroiddata.git
  cd "$CLONE_DIR"
  git remote add upstream https://gitlab.com/fdroid/fdroiddata.git
  git fetch upstream master
  git checkout master
  git merge upstream/master --ff-only
  git push origin master
fi

echo "==> Creating branch add-sms-tech"
git checkout -b add-sms-tech 2>/dev/null || git checkout add-sms-tech

echo "==> Copying metadata YAML"
cp "$YAML_SRC" metadata/com.filestech.sms.yml

echo "==> Commit + push"
git add metadata/com.filestech.sms.yml
git commit -m "New app: SMS Tech (com.filestech.sms)"
git push -u origin add-sms-tech --force

echo "==> Opening MR via glab"
glab mr create \
  --repo gitubpatrice/fdroiddata \
  --target-repo fdroid/fdroiddata \
  --target-branch master \
  --source-branch add-sms-tech \
  --title "New app: SMS Tech (com.filestech.sms)" \
  --description "$(cat <<'EOF'
## New app submission: SMS Tech

**Package**: com.filestech.sms
**Version**: 1.2.5 (versionCode 13)
**Repo**: https://github.com/gitubpatrice/SMS-TECH
**Tag**: v1.2.5
**License**: Apache-2.0
**Website**: https://files-tech.com/sms-tech.php

### About

SMS Tech is an ad-free, tracker-free Android SMS / MMS app. It replaces the stock messaging
app while keeping every message on-device — no cloud, no analytics, no third-party SDK
shipping data anywhere. Features include voice MMS, an encrypted vault (SQLCipher +
Keystore), a panic-code decoy session, biometric unlock with PIN fallback, on-device
translation (ML Kit), retroactive purge of system-blocked conversations.

### Build notes

- Build artefact: `app/build/outputs/apk/release/app-arm64-v8a-release.apk` (and the 3 other
  ABI splits)
- The `prebuild` directives strip the keystore-loading block from `app/build.gradle.kts`
  so the F-Droid signing key applies, not the developer one
- Schema migrations live under `app/schemas/` (committed)
- 5 ABI-split build entries for versionCodes 7 (v1.2.1), 10 (v1.2.2), 11 (v1.2.3),
  12 (v1.2.4) and 13 (v1.2.5)

### Security audit

A 5-axis expert audit (security / code quality / performance / duplication / UI) was applied
between v1.2.2 and v1.2.4. Notes inline in the source via `audit F1-F13`, `audit P1-P4`,
`audit U1-U22` comments. Full notes at
https://github.com/gitubpatrice/SMS-TECH/blob/main/SECURITY.md.
EOF
)" \
  --remove-source-branch \
  --yes

echo "==> Done! MR should be visible at https://gitlab.com/fdroid/fdroiddata/-/merge_requests"
