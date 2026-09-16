# Translating SMS Tech

SMS Tech ships in **English** (source) and **French**. This page is for anyone adding or
correcting a language — no Android knowledge required beyond editing an XML file.

> **Honesty first.** The German, Italian and Spanish translations were produced by the maintainer
> with machine assistance and **have not been reviewed by a native speaker**. They are offered
> because a rough translation in your language beats no translation at all — not because they are
> professional work. If something reads wrong, **please open an issue or a pull request**. You do
> not need to justify a correction: a native ear is the authority here, not the person who wrote it.

## Where the strings live

| Path | Role |
|---|---|
| `app/src/main/res/values/strings.xml` | **English — the source of truth.** Every key is defined here first. |
| `app/src/main/res/values-fr/strings.xml` | French |
| `app/src/main/res/values-<lang>/strings.xml` | your language |
| `app/src/debug/res/values-<lang>/strings.xml` | debug-build app name only (see below) |
| `fastlane/metadata/android/<locale>/` | the F-Droid / store listing |

There are **783 keys**: 773 `<string>` and 10 `<plurals>`. No string is marked
`translatable="false"`, and the app has no hardcoded text — everything you see on screen comes
from these files.

## Adding a language: the four gestures

They are **solidary** — doing three of the four ships a language that does not work. A CI check
enforces all four (see *Continuous integration* below), so a partial change cannot be merged.

1. **Translate** `app/src/main/res/values-<lang>/strings.xml`, starting from the English file.
2. **Declare the locale** in `app/build.gradle.kts` → `androidResources { localeFilters }`.
   Without this line the Android Gradle Plugin **strips your resources out of the APK** and the
   app silently falls back to English.
3. **Add the locale** to `app/src/main/res/xml/locales_config.xml`. This is what puts your
   language in *Settings → Apps → SMS Tech → Language* on Android 13 and later.
4. **Create** `app/src/debug/res/values-<lang>/strings.xml` with just `app_name`, set to
   `SMS Tech Debug`. Without it, a debug build installed on a device in your language is named
   exactly like the release build, and the two become impossible to tell apart.

## Rules that are not style preferences

**Format placeholders — 80 strings carry them.** `%1$s`, `%2$d` and friends must all survive,
with their numbers intact. You may **reorder** them to fit your grammar — that is what the `1$`
and `2$` are for — but dropping one, or inventing one, **crashes the app at runtime**. It is not
a cosmetic defect.

```xml
<!-- English -->  <string name="x">Sent to %1$s at %2$s</string>
<!-- valid    -->  <string name="x">%2$s : envoyé à %1$s</string>
<!-- CRASH    -->  <string name="x">Envoyé à %1$s</string>
```

**Apostrophes must be escaped** as `\'`, always. An unescaped `'` is an Android build error.
Same for a leading/trailing space (` `) and for `"`, `\` and `@`.

**Plurals are not a free choice.** Each language has a fixed set of CLDR quantity categories, and
Android refuses the build if one is missing. English and German use `one` / `other`; French,
Italian and Spanish also use `many`. If you are unsure, add the category the build asks for —
`./gradlew :app:lintDebug` names it explicitly (`MissingQuantity`).

**Emoji, and the `&#8230;` / `&amp;` entities, are content.** Keep them as they are.

**Do not translate these**, they are names: `SMS Tech`, `Safety Call`, and protocol words that are
the same everywhere in a phone's UI (`SMS`, `MMS`, `PIN`, `GPS`, `PDF`).

## Glossary — terms that must not drift

A handful of words carry the app's meaning and appear in dozens of strings. Pick **one** word per
concept in your language and use it everywhere. The two worst offenders are *vault* (69 strings)
and *emergency* (45): a synonym used halfway through makes users think there are two features.

| English | Occurrences | French | German |
|---|---|---|---|
| vault | 69 | Coffre-fort | Tresor |
| emergency (mode) | 45 | mode urgence | Notfallmodus |
| **EMERGENCY** (the big button) | — | URGENCE | NOTRUF |
| Safety Call | 22 | *Safety call* (kept) | *Safety Call* (kept) |
| app lock | 23 | verrouillage | App-Sperre |
| decoy mode | 6 | mode leurre | Tarnmodus |
| panic code | 8 | code panique | Panikcode |
| passphrase | 14 | phrase de passe | Passphrase |
| conversation | 47 | conversation | Unterhaltung |
| attachment | — | pièce jointe | Anhang |
| backup | 14 | sauvegarde | Sicherung |

**Register**: French uses the formal *vous*, German the formal *Sie*. The app talks about
emergencies, coercion and legal terms, and a casual register reads wrong there. Match the formality
of your language's serious-software convention rather than copying English, which has no choice to
make.

**Strings that do not forgive an approximation.** Translate these slowly, and prefer a plain,
unambiguous wording over an elegant one — someone may read them in a situation that matters:
the **vault**, the **decoy / panic** session, the **emergency** alert and its SMS templates, and
the legal notices. If a nuance does not exist in your language, say what the feature *does*
rather than inventing a term.

## Screen space

German runs about 30 % longer than English, and the app has **159 labels of ten characters or
less** — tabs, buttons, chips — where that margin does not exist. `Settings` becomes
*Einstellungen* (+62 %). Where a short label will not fit, a shorter accurate synonym is better
than a truncated correct one. Please check your language on a device or emulator before opening
the pull request; the screens most at risk are the conversation list, the settings sections and
the emergency setup.

## Store metadata (optional but welcome)

Copy `fastlane/metadata/android/en-US/` to your locale (`de-DE`, `it-IT`, `es-ES`, …) and
translate. Two hard caps, counted in **bytes**, not characters — an accented letter costs two:

| File | Cap |
|---|---|
| `short_description.txt` | **80** |
| `full_description.txt` | 4000 |
| `changelogs/<versionCode>.txt` | **500** |

## Continuous integration

`.github/scripts/i18n-parite.sh` runs on every build. It **fails** — it does not warn — when a
language drifts from the English source: a missing or extra key, a missing plural category, a
format placeholder that changed, or one of the four gestures above left undone.

This is deliberate. The app changes with every release, and a translation that can silently fall
behind is worse than no translation, because it looks current. You can run it yourself before
pushing:

```sh
./.github/scripts/i18n-parite.sh
```

## Thank you

Translating 783 strings is real work, and it is the difference between an app someone can use and
one they close. It is appreciated.
