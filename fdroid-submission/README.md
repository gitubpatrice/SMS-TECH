# F-Droid submission instructions — SMS Tech

This folder contains the metadata required to submit SMS Tech to the official F-Droid
repository (https://gitlab.com/fdroid/fdroiddata). The submission is **manual** — F-Droid
does not accept anonymous additions. You need a GitLab account.

## Pre-requisites

- A GitLab account (free): https://gitlab.com/users/sign_up
- The GitLab CLI (`glab`) or just the web UI for forking + opening the MR
- Local clone of `fdroiddata` (~500 MB)

## Submission steps

### 1. Fork the official F-Droid data repo

Visit https://gitlab.com/fdroid/fdroiddata and click **Fork**.

### 2. Clone your fork locally

```bash
git clone https://gitlab.com/YOUR_USERNAME/fdroiddata.git
cd fdroiddata
```

### 3. Copy the metadata file

```bash
cp /j/applications/sms_tech/fdroid-submission/com.filestech.sms.yml metadata/
```

### 4. Test the build locally (optional but recommended)

```bash
fdroid build --verbose com.filestech.sms:13
```

This compiles SMS Tech inside the F-Droid reproducible build environment. It should produce
a release APK matching the one published on GitHub Releases.

### 5. Push + open the MR

```bash
git checkout -b add-sms-tech
git add metadata/com.filestech.sms.yml
git commit -m "Add SMS Tech (com.filestech.sms)"
git push origin add-sms-tech
```

Open the MR via the GitLab UI:

- Title: `New app: SMS Tech (com.filestech.sms)`
- Target: `fdroid/fdroiddata` `master`
- Description: paste from the body below

### MR description template

```
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
- Reproducible build verified locally: ✅
- The `prebuild` directives strip the keystore-loading block from `app/build.gradle.kts`
  so the F-Droid signing key applies, not the developer one
- Schema migrations live under `app/schemas/` (committed)

### Security audit

A 5-axis expert audit (security / code quality / performance / duplication / UI) was applied
between v1.2.2 and v1.2.4. Report inline in the source via `audit F1-F13`, `audit P1-P4`,
`audit U1-U22` comments. Full notes at
https://github.com/gitubpatrice/SMS-TECH/blob/main/SECURITY.md.
```

### 6. Wait for review

F-Droid maintainers usually triage new submissions within 1–3 weeks. They will leave inline
comments on the MR if anything needs adjustment (build script, license confirmation, etc.).

## Useful links

- F-Droid build server docs: https://f-droid.org/en/docs/Build_Metadata_Reference/
- F-Droid YAML syntax: https://gitlab.com/fdroid/fdroidserver/-/blob/master/docs/metadata.md
- IRC channel for help: #fdroid on libera.chat

## Notes on signing

F-Droid **re-signs every APK** with its own key — your `release.keystore` is **not** used
upstream. Users who install via F-Droid will see a different SHA-256 fingerprint than the
APKs you upload to GitHub Releases. That's normal and intentional: F-Droid users trust the
F-Droid signing chain, not the developer.

The keystore at `app/release.keystore` is for the GitHub Releases path only (direct download
+ side-load). Keep it backed up off-machine.
