# Translating SMS Tech

SMS Tech ships in **English** (the source), **French**, **German**, **Italian** and **Spanish**.
This page is for anyone adding or correcting a language — no Android knowledge required beyond
editing an XML file.

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

There are **797 keys**: 785 `<string>` and 12 `<plurals>`. No string is marked
`translatable="false"`, and the app has no hardcoded text — everything you see on screen, **and
every SMS it sends**, comes from these files.

## Adding a language: the six gestures

They are **solidary** — doing five of the six ships a language that does not work, or one that
works while quietly protecting its readers less. A CI check enforces the first five (see
*Continuous integration* below); the sixth is enforced by nothing, which is exactly why it is
written down here.

1. **Translate** `app/src/main/res/values-<lang>/strings.xml`, starting from the English file.
2. **Declare the locale** in `app/build.gradle.kts` → `androidResources { localeFilters }`.
   Without this line the Android Gradle Plugin **strips your resources out of the APK** and the
   app silently falls back to English.
3. **Add the locale** to `app/src/main/res/xml/locales_config.xml`. This is what puts your
   language in *Settings → Apps → SMS Tech → Language* on Android 13 and later.
4. **Create** `app/src/debug/res/values-<lang>/strings.xml` with just `app_name`, set to
   `SMS Tech (debug)` — the exact spelling the four existing twins use. Without it, a debug
   build installed on a device in your language is named exactly like the release build, and
   the two become impossible to tell apart.
5. **Add your language code** to `LANGUES` in
   `app/src/test/java/com/filestech/sms/system/safety/SafetyMessageTextsTest.kt`. That test walks
   the SMS the app actually sends, language by language, against the real resources. It carries
   its own list, so a language missing from it ships with **none** of those checks applied to it.
6. **Add your language's scam wording** to `URGENCY_KEYWORDS` and your country's official
   domains to `DOMAINES_OFFICIELS`, both in
   `domain/src/main/java/com/filestech/sms/domain/smishing/SmishingDetector.kt`. The scam
   detector matches literal words and real domain names, so it is blind in any language whose
   words it does not hold: a German scam does not write "urgent", and the app told German
   readers it caught `e1ster.de` while `elster.de` was in no list at all. Nothing fails when
   this is missing — the app simply protects your readers less than it claims to, in silence.
   That is why it belongs on this list.

*(A seventh place, `EmergencyNumbers.kt`, deliberately does **not** belong here. It is indexed
by the country the phone's network is in, never by language — a German speaker in Paris must be
shown 17, not 110. Adding a language must not add a country.)*

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

**Do not translate these**, they are names: `SMS Tech`, `Safety call`, and protocol words that are
the same everywhere in a phone's UI (`SMS`, `MMS`, `PIN`, `GPS`, `PDF`).
`Safety call` is spelled exactly that way in every language — one feature, one spelling.

## The strings that leave the phone

The keys starting with `safety_sms_`, plus `emergency_i_am_ok_body`, are **not screen labels**.
They are the SMS that go out when someone holds the emergency button for three seconds, or when
Safety Call decides the phone has not been touched for long enough. Two rules apply to them that
apply to nothing else:

- **Two SMS segments, maximum.** A segment holds 160 characters in the GSM-7 alphabet, but only
  **70** as soon as one single character falls outside it — and the emergency ones already spend a
  segment on the Maps URL. Every extra segment is another SMS billed, and another chance of
  arriving truncated in weak coverage, which is the exact situation these messages exist for.
  This is measured, not advised: `SafetyMessageTextsTest` fails the build past two.
- **Write your language correctly anyway.** Spanish `ubicación` and Polish `proszę` are outside
  GSM-7 and *should* be: dropping the accents to save a segment would be worse than the segment.
  The cap has room for that. What it does not have room for is a long sentence.

An em dash (`—`) costs the whole message its GSM-7 encoding for nothing. Use a plain hyphen.
The alert triangle `⚠️` at the start of the two urgent templates is deliberate and stays.

## Glossary — terms that must not drift

A handful of words carry the app's meaning and appear in dozens of strings. Pick **one** word per
concept in your language and use it everywhere. The two worst offenders are *vault* (69 strings)
and *emergency* (45): a synonym used halfway through makes users think there are two features.

| English | Occurrences | French | German | Italian | Spanish |
|---|---|---|---|---|---|
| vault | 69 | coffre | Tresor | Cassaforte | Caja fuerte |
| emergency (mode) | 45 | mode urgence | Notfallmodus | modalità emergenza | modo emergencia |
| **EMERGENCY** (the big button) | — | URGENCE | NOTFALL | EMERGENZA | EMERGENCIA |
| Safety call | 22 | *Safety call* (kept) | *Safety call* (kept) | *Safety call* (kept) | *Safety call* (kept) |
| app lock | 23 | verrouillage | App-Sperre | blocco dell'app | bloqueo de la app |
| decoy mode | 6 | mode leurre | Tarnmodus | modalità esca | modo señuelo |
| panic code | 8 | code panique | Panikcode | codice di panico | código de pánico |
| passphrase | 14 | phrase de passe | Passphrase | passphrase | frase de contraseña |
| conversation | 47 | conversation | Unterhaltung | conversazione | conversación |
| attachment | — | pièce jointe | Anhang | allegato | archivo adjunto |
| backup | 14 | sauvegarde | Sicherung | backup | copia de seguridad |

**Register: the interface is formal, the SMS are not.** French uses *vous*, German *Sie*, Italian
*Lei*, Spanish *usted*. The app talks about emergencies, coercion and legal terms, and a casual
register reads wrong there. Match the formality of your language's serious-software convention
rather than copying English, which has no choice to make.

The `safety_sms_*` bodies are the exception, and it is not an inconsistency: **the speaker there is
the user, writing to their own family**. All four languages use the familiar form in them — *tu*,
*du*, *tu*, *tú*. Keep that split.

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
translate. The caps below are counted in **bytes**, not characters — an accented letter costs
two, and that difference is not academic: the Spanish listing shipped 29 bytes over the cap while
a character count called it fine. The parity check now measures them, in bytes, on every build.

| File | Cap |
|---|---|
| `title.txt` | 50 |
| `short_description.txt` | **80** |
| `full_description.txt` | 4000 |

**Changelogs have no cap here.** The 500 characters you may have seen quoted for
`changelogs/<versionCode>.txt` are a *Google Play* rule: F-Droid validates nothing on that field
and its client displays the whole text. This app ships on F-Droid and GitHub, so write what the
release deserves — fifty of ours run well past 500 and always have.

Please end `full_description.txt` with the same short paragraph the German, Italian and
Spanish listings carry: who wrote the translation, that no native speaker reviewed it, and
where to report what reads wrong. A store page is read by people deciding whether to trust
the app; a translation that hides its own provenance is a bad way to start.

## Continuous integration

`.github/scripts/i18n-parite.sh` runs on every build. It **fails** — it does not warn — when a
language drifts from the English source: a missing or extra key, a missing plural category, a
format placeholder that changed, or one of the first five gestures above left undone.

This is deliberate. The app changes with every release, and a translation that can silently fall
behind is worse than no translation, because it looks current. You can run it yourself before
pushing:

```sh
./.github/scripts/i18n-parite.sh
```

## Thank you

Translating 797 strings is real work, and it is the difference between an app someone can use and
one they close. It is appreciated.
