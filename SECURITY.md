# SMS Tech — Security model

Current release : **v1.10.0** (2026-05-21)

This document describes the threat model SMS Tech protects against, the cryptographic
primitives it uses, the architectural choices that make those primitives meaningful, and the
known limits beyond which the app cannot defend (because no app can).

If you find a vulnerability, please disclose it to **contact@files-tech.com** with the subject
`SMS Tech security report`. We respond within 5 working days.

---

## Threat model

| Adversary | What we protect against | How |
|---|---|---|
| Device-level adb pull / forensic image | Plaintext SMS / MMS body, vault content, draft messages, recordings | Whole Room DB encrypted at rest by SQLCipher; key derived from a passphrase wrapped in the Android Keystore. Transient caches (PDU staging, voice drafts, exports) wiped on auto-lock + on panic. |
| Lost / stolen phone (no PIN known) | Read access to the conversation list and the vault | App lock (PIN / passphrase / biometric) with exponential backoff. `FLAG_SECURE` on every sensitive surface so the lock screen + recent-apps preview never leaks content. |
| Coerced unlock ("show me your phone") | Disclosure of the hidden vault under duress | Panic-code unlock (`PanicDecoy` state) exposes the standard conversation list while gating every vault entry point — UI hides the vault icon, navigation refuses the route, the data layer returns empty lists. |
| Replay of a successful biometric scan | Bypass of the lock state via a stolen "auth event" | Single-use challenge token issued by `AppLockManager.beginBiometricChallenge()`, consumed atomically (`AtomicReference.getAndSet(null)`) by `markBiometricUnlocked(token)`. A second call with a stale token is a no-op. |
| Brute-force PIN | Online guessing of a short PIN | PBKDF2-HMAC-SHA512 with calibrated iterations (>= 210 k) + salted hash. Exponential lockout (5 s, 10 s, 30 s, 1 min, 2 min, 5 min) starting at 5 failures. `setLockoutUntil` clamped to a 24 h forward horizon so a tainted backup restore cannot brick the app. |
| Malicious component on the same device sending an Intent | Forge a `MmsSent` / `SmsSent` broadcast to manipulate row status | Every result-callback `PendingIntent` uses **explicit** `Intent.setClass(context, ReceiverClass)` rather than implicit `setPackage`. Receivers stay `exported = false`. |
| Carrier-side / MITM | Intercept message bytes in flight | **Not in scope.** SMS / MMS is unencrypted by protocol; we cannot fix that. Users who need transport encryption should use Signal or similar. |
| Unwanted automatic Safety call SMS during coercion (v1.9.0) | The deadman feature firing while the victim is forced into the `PanicDecoy` session — which would reveal her emergency contacts to the attacker | `SafetyCallTriggerService` and `SafetyCallWorker` both check `AppLockManager.LockState.PanicDecoy` and short-circuit before any send. Worker tick retries automatically once decoy state is left. |
| Spoofed Safety call reset intent (v1.9.0) | A third-party app on the device crafting `ACTION_SAFETY_CALL_RESET` (the activity is `exported=true` because of the SMS role) to neutralise the deadman remotely | `SafetyCallIntentToken` rotates a `SecureRandom` 63-bit nonce on every warning notification; `MainActivity` validates the extra against the in-process token and burns it on consume. A forged intent with no/wrong token is logged and ignored. |
| Coerced reset of Safety call timer via app opening (v1.9.0) | An attacker who knows the app icon could open SMS Tech repeatedly (without PIN) to neutralise the deadman | `MainActivity.onResume` only resets `lastActivityAt` when `AppLockManager.LockState` is `Unlocked` or `Disabled`. `Locked` / `PanicDecoy` sessions never reset the timer. |
| User-triggered emergency SMS during coercion (v1.10.0) | The new Emergency mode (hold-3s button) could reveal the victim's emergency contacts if the attacker forces an unlock to `PanicDecoy` and sees the URGENCE button | Three layers of defence : (a) `AppRoot` navigation guard pops both `Emergency` and `EmergencySetup` routes the moment `PanicDecoy` becomes active, (b) `SettingsScreen` hides both "Mode urgence" and "Safety call" sections when `isPanicDecoy = true` (the attacker doesn't learn the feature exists), (c) `TriggerEmergencyUseCase` checks the same lock state and short-circuits before any SMS is sent. |
| Wall-clock manipulation to bypass anti-spam cooldown (v1.10.0) | A rooted attacker advances `Settings.Global.AUTO_TIME=0; date <future>` to skip the 60 s emergency-mode cooldown and re-trigger the SMS to harass the contacts | `EmergencyConfig.isInAntiSpamWindow()` checks both wall-clock AND `SystemClock.elapsedRealtime()`. Cooldown is active if EITHER clock is still in window. A negative monotonic delta (post-reboot before drift recovery) is also treated as "still in cooldown" — fail-safe against root + reboot + clock-forward. Same defence on `SafetyCallConfig.isExpired()` (SEC-11) — required both clocks to expire before the deadman fires. |
| Wall-clock manipulation to fire Safety call early (v1.10.0 SEC-11) | A rooted attacker advances the wall-clock to make `lastActivityAt + timeoutMs < now()` evaluate `true` immediately and trigger the SMS to expose the support network | `SafetyCallConfig.isExpired()` now requires BOTH wall-clock AND monotonic clock to have crossed `timeoutMs`. `SystemClock.elapsedRealtime()` is not manipulable via Settings → Date/time. The drift between the two clocks defeats the attack. Drift recovery in `MainApplication.onCreate` realigns monotonic post-reboot if the stored value exceeds current uptime. |
| Double-trigger of emergency SMS via UI race (v1.10.0) | A panicked user holds the URGENCE button, releases, and immediately holds again before the DataStore write completes; without protection a second SMS could be sent within ~50–300 ms | `EmergencyViewModel.trigger()` uses `AtomicBoolean compareAndSet(false, true)` as an in-flight guard. A second `trigger()` call while the first is still running returns immediately. The flag is reset in `finally` so any exception in the UseCase still releases it. |
| Listener leak in `LocationResolver` (v1.10.0) | Theoretical leak of GPS listener if `SecurityException` thrown on NETWORK provider after GPS listener was registered | `awaitFirstFix` uses `AtomicBoolean resumed` to enforce single-resume on `suspendCancellableCoroutine`, and `cleanup()` is always called in the `catch (SecurityException)` block regardless of `resumed` state. Same `cleanup()` is wired in `invokeOnCancellation`. |

---

## Cryptographic primitives

| Concern | Primitive | Key source | Notes |
|---|---|---|---|
| Room database at rest | SQLCipher v4 | 32-byte random passphrase wrapped by Keystore alias `db_master` | The unwrapped passphrase is wiped from the JVM heap immediately after Room consumes it (`SecretBytes.wipe()`). |
| Vault wrap key (reserved) | AES-256-GCM | Keystore alias `vault_kek` (Android 9+) | Currently used as a structural anchor; the actual vault gating is enforced at the data layer via `in_vault = 1` + session checks. v1.3.x is planned to add a separate envelope. |
| App-lock PIN / passphrase | PBKDF2-HMAC-SHA512 | User secret + 16-byte random salt | Iterations calibrated on the device (>= 210 k); stored in DataStore alongside the salt. We never store the secret itself. |
| Panic code | PBKDF2-HMAC-SHA512 | Same parameters as the PIN | Comparison is constant-time. A panic match resets the fail counter to 0 to avoid leaving fingerprint of attempts in the persisted state. |
| Settings AEAD blobs | AES-256-GCM | Keystore alias `settings_aead` | Used for low-volume sensitive prefs (cached display name, future heir-recovery hooks). IV is 12-byte random per encryption. |
| Biometric challenge | 32 bytes from `SecureRandom` | — | Single-use, single-thread atomic consume (`AtomicReference.getAndSet(null)`). Comparison uses `MessageDigest.isEqual` to avoid timing side-channels. |

All Keystore keys are non-exportable, hardware-backed when available, and pinned to the
biometric authenticator on the BiometricPrompt path (`setUserAuthenticationRequired = true`
will be wired in v1.3 once the strong-bio gating decision is finalised — v1.2.0 ships with
the BIOMETRIC_WEAK class for fingerprint **OR** face).

---

## Audit history

### v1.10.0 (this release) — Emergency mode + clock-monotonic hardening + refactors

MINOR release introducing the **Emergency mode** feature (opt-in active hold-3s SMS button that sends a personalised template + GPS location URL to the user's safety-call contacts) plus a monotonic-clock complementary check (SEC-11) on the existing Safety call deadman.

A pre-release audit (3 axes + deep dive security + architecture coherence + i18n) surfaced 3 HIGH, 8 MEDIUM and 2 LOW findings, all fixed before tag :

- **HIGH SEC-1** — `AppRoot` navigation guard now pops both `Emergency` and `EmergencySetup` routes when `PanicDecoy` activates ; `SettingsScreen` hides both "Mode urgence" and "Safety call" sections when `isPanicDecoy = true`. Without this, an attacker in a forced-decoy session would see the URGENCE button and learn the feature exists, breaking the "ordinary SMS app" illusion.
- **HIGH SEC-2** — `EmergencyViewModel.trigger()` protected by `AtomicBoolean compareAndSet` in-flight guard. Without it, a panicked double-hold during the ~50–300 ms DataStore-write window could fire two SMS to every contact, confusing the recipients in a stressful moment.
- **HIGH P1** — `LocationResolver.awaitFirstFix` uses `AtomicBoolean resumed` to guarantee single-resume on `suspendCancellableCoroutine`. Without it, near-simultaneous GPS + NETWORK fixes could call `cont.resume` twice → `IllegalStateException: Already resumed` swallowed silently → SMS sent without coordinates despite a valid fix being available.
- **HIGH S1+U1+U2** — `EmergencySetupScreen` now uses `rememberPermissionState(ACCESS_FINE_LOCATION)`, prompts at toggle ON, and shows a persistent red warning if the permission is denied. `EmergencyScreen.MessagePreviewCard` reflects the REAL permission state (not just the user preference) so the previewed SMS body matches what will be sent. Without these, a user activating the switch without granting the permission was building false trust in a security feature.
- **MEDIUM SEC-4** — `EmergencyConfig.isInAntiSpamWindow()` treats a negative monotonic delta (post-reboot before async drift recovery in `MainApplication.onCreate` completes) as "still in cooldown" — fail-safe against a root + reboot + clock-forward attack.
- **MEDIUM SEC-5** — `EmergencyTemplate` body strings switched from `—` (U+2014, em dash) to `-` (ASCII hyphen) and removed `Ù` from `DISCREET`. All three templates now fit in a single GSM-7 segment with the URL Maps appended → no multi-segment risk in weak-radio emergency zones. Guarded by two unit tests in `AuditV1100Test`.
- **MEDIUM SEC-6** — `LocationResolver.awaitFirstFix`'s `SecurityException` catch always calls `cleanup()` before checking the `resumed` flag, ensuring the GPS listener is removed even if NETWORK provider registration failed after GPS already registered.
- **MEDIUM S3** — `EmergencyViewModel.save()` preserves the LIVE `lastTriggeredAt` and `monotonicLastTriggeredAt` from DataStore at the moment of save, not the stale value captured at setup-open. Without this, modifying any setup parameter after a recent trigger would clear the anti-spam cooldown.
- **MEDIUM C1** — `EmergencySetupScreen.SetupCard` aligned on `surfaceContainer` (was `surface`), matching the `SafetyCallSetupScreen.SectionCard` convention.
- **MEDIUM C2** — `EmergencySetupScreen` event collector uses exhaustive `when (event)` instead of `if (event is …)`, so a new `Event` case added later is signalled at compile time.
- **MEDIUM i18n** — 6 FR strings in the Emergency block converted from tutoiement to vouvoiement, aligning with the project-wide FR tone (the templates themselves keep the user's first-person voice).
- **LOW C5** — `K.safetyCallCustomMessage` declaration moved back into the `safetyCall*` block in `SettingsRepository` (was visually orphaned after the `emergency*` block).

#### SEC-11 — clock-monotonic complementary check on Safety call

Independent hardening of the v1.9.0 Safety call : `SafetyCallConfig.lastActivityAt` (wall-clock) is now complemented by `SafetyCallConfig.monotonicLastActivityAt` (snapshot of `SystemClock.elapsedRealtime()` at every reset). `isExpired()` and `isInWarningWindow()` require BOTH clocks to cross `timeoutMs` before triggering. A rooted attacker who advances `Settings.Global.AUTO_TIME=0; date <future>` to force the deadman to fire immediately is now defeated — the monotonic clock continues to track real elapsed time since boot, regardless of wall-clock manipulation.

Drift recovery: on every cold-start, `MainApplication.onCreate` detects if any stored monotonic value exceeds the current `SystemClock.elapsedRealtime()` (consequence of a reboot) and realigns it to the current monotonic. The deadman is effectively extended by the post-reboot uptime — acceptable trade-off given the alternative would be a permanently-locked deadman after every reboot.

Migration v1.9.0 → v1.10.0: configs persisted before this release have `monotonicLastActivityAt = 0L` ; `isExpired()` returns `false` in that case (safety net) until the first reset (`MainActivity.onResume`, "Je vais bien", warning-notif tap, setup save) populates the new field. The first app open after upgrade implicitly re-arms the deadman.

#### Refactors C1 + C4 (cosmetic, no behaviour change)

- C1 — `SafetyCallTriggerService` (data layer) → `TriggerSafetyCallUseCase` (domain/usecase) with `operator invoke()`, aligning with the project's dominant UseCase pattern. Callers updated: `SafetyCallWorker`, `AuditV190Test`.
- C4 — `SafetyCallContactJsonCodec` → `SafetyCallContactCodec` (the format was never JSON, was pipe-separated from day one). Renamed object + file ; DataStore key (`security.safetyCall.contactsJson`) kept unchanged for storage backward-compat.

#### Performance P2

`SettingsScreen.SafetyCallArmedRecap` no longer calls `System.currentTimeMillis()` at every recomposition ; `SettingsViewModel` exposes `safetyCallRemainingMs: StateFlow<Long>` recomputed every 60 s (or whenever `state` changes via `combine`). Granularity sufficient for an hour-level countdown displayed to the user.

Pre-release audit: 17 garde-régression tests in `AuditV1100Test` (clock-forward attack on safety call + emergency, post-reboot drift, v1.9.0 migration fallback, emergency anti-spam, GSM-7 single-segment guarantee, template defaults).

### v1.9.0 — Safety call + compact reaction format + audit hardening

MINOR release introducing the **Safety call** feature (opt-in automatic SMS to 1–4 emergency contacts after a user-configured inactivity timeout, 1 h to 30 days) and a fourth compact reaction format `EMOJI_WITH_QUOTE` (`❤️ «excerpt»`). Disabled by default.

A pre-release audit (3 axes in parallel + architecture coherence + i18n + deep dive security) surfaced 1 CRITICAL, 4 HIGH and 6 MEDIUM findings, all fixed before tag :

- **CRITICAL** — `SafetyCallTriggerService` + `SafetyCallWorker` now check `AppLockManager.LockState.PanicDecoy` and short-circuit before any send. Without that guard, the deadman would have fired SMS to the victim's emergency contacts under coercion, revealing her support network to the attacker. The worker tick (60 min) retries automatically once decoy state is left.
- **HIGH** — `BootReceiver` now reschedules `SafetyCallWorker.schedulePeriodic` on `BOOT_COMPLETED` so a force-stop OEM (Xiaomi / Huawei) doesn't lose the deadman. KEEP policy keeps the call idempotent.
- **HIGH** — `IncomingReactionDecoder.EMOJI_WITH_QUOTE_REGEX` reformulated with negative class `[^»"]{1,200}` (was `.+?` + DOT_MATCHES_ALL) — eliminates catastrophic backtracking on pathological input `❤️ «aaaa...` (no closing guillemet, capped at 400 chars).
- **HIGH** — Strict emoji guard `isLikelyEmojiChar(c)` requires high-surrogate OR `U+2300..U+27BF` OR ZWJ / VS-16 (was `code < 128` which silently swallowed FR messages starting with an accented word — `"été «aperçu»"` was being misinterpreted as a reaction).
- **MEDIUM** — Anti-spoofing nonce (`SafetyCallIntentToken`) on `ACTION_SAFETY_CALL_RESET`. The intent extra `EXTRA_RESET_TOKEN` is validated and consumed mono-shot by `MainActivity` ; a third-party app cannot neutralise the deadman by forging the action.
- **MEDIUM** — `MainActivity.onResume` reset gated on `LockState.Unlocked || Disabled`. `Locked` / `PanicDecoy` no longer reset the timer.
- **MEDIUM** — `SafetyCallTriggerService.disableSafetyCall()` is now called **before** the send loop (preemptive disable). A crash mid-loop no longer causes a double-trigger 60 min later.
- **MEDIUM** — `SafetyCallContactJsonCodec.decode()` filters via `SafetyCallContact.isValid()` (defense in depth against tampered DataStore restore). Encoding strips full C0/C1 range + `|` separator (was only `\n` + `|`).
- **MEDIUM** — Dedicated notification channel `CHANNEL_SAFETY_CALL_WARNING` (was sharing `CHANNEL_INCOMING` with regular SMS) so the user can tune sound / vibration independently.
- **MEDIUM** — `SafetyCallSetupViewModel` snapshot-once from DataStore (`first()` instead of `collect`) — fixes data loss when a concurrent write (`onResume` reset) overrode the in-progress draft.

Logs no longer leak `phoneNumber` to Timber (replaced by index-only identifiers). `SafetyCallTemplate.CUSTOM` re-caps at render to `MAX_CUSTOM_MESSAGE_LENGTH=140` to defend against tampered DataStore values that would otherwise produce a multi-segment surprise.

### v1.8.1 — Reaction wording hybrid (named / anonymous) + decoder dual

PATCH following v1.8.0 field testing — the FR readable reaction format `"J'ai réagi par ❤️ à : «…»"` was ambiguous when read out of context. v1.8.1 reformulates to a hybrid : with sender name → `"<Name> a réagi par ❤️ à votre message : «…»"`, anonymous → `"Réagi par ❤️ à votre message : «…»"`. Sender name resolved via 3-tier fallback : (1) Settings override (sanitized, cap 40c, anti-C0/C1/bidi/BOM), (2) `ContactsContract.Profile` auto-detection, (3) anonymous.

Decoder accepts 4 new regex (named/anonymous × with-preview/no-preview) + legacy v1.8.0 + legacy Tapback EN. `MAX_DECODE_INPUT_LENGTH = 400` neutralises ReDoS on the non-greedy quantifiers. No schema migration, DataStore-additive downgrade-safe.

### v1.8.0 — Conversation badge fixes + reaction format picker + tap-notif nav

12 fixes confirmed on Galaxy S9 Android 10 + Galaxy S24 Android 15. Notable security-adjacent : `markRead` now propagates to the system `content://sms` + `content://mms` providers so uninstall + reinstall preserves read state ; one-shot migration `unreadResetV180` resets historical incoming messages to read=1 (aligned Google Messages / Samsung Messages) ; `TelephonySyncManager.runSync` forces `read=true` on all historical messages at first sync.

### v1.7.1 — FLOSS translation via system delegation (ACTION_PROCESS_TEXT)

Restores translation by delegating to the user's installed translation app via `Intent.ACTION_PROCESS_TEXT` with `EXTRA_PROCESS_TEXT_READONLY=true` (anti-spoofing : the called app cannot modify the original). `<queries>` in `AndroidManifest` declares targeted package visibility (no `QUERY_ALL_PACKAGES`). System chooser obligatory. No bundled ML model.

### v1.7.0 — F-Droid FLOSS compliance (Google ML Kit removed)

Suppression of `com.google.mlkit:translate` + `com.google.mlkit:language-id` + `kotlinx-coroutines-play-services` (was a bridge `Task→suspend` for ML Kit). `TranslationService.kt` rewritten as a stub returning `Outcome.Failure(Validation("translation_unavailable_v17"))`. Cert SHA-256 stable. APK arm64 -2 MB.

### v1.6.2 — Critical settings regression fix + Tapback fold improvements

PATCH bundling 5 user-visible fixes uncovered during v1.6.1 in-field testing.

**B1 — CRITICAL : tous les réglages utilisateur ignorés par ThreadViewModel.** Ma
PERF-01 v1.6.1 a introduit un `cachedSettings: StateFlow<AppSettings>` initialisé
avec `stateIn(viewModelScope, WhileSubscribed(5_000), AppSettings())` — mais
AUCUN consommateur ne collectait jamais cette flow (lecture uniquement via `.value`
depuis 5 sites). Sans collecteur, le flux sous-jacent n'était jamais souscrit et
`.value` retournait toujours la **valeur initiale par défaut** `AppSettings()`. Tous
les réglages utilisateur étaient donc **silencieusement ignorés** dans
`ThreadViewModel` : `confirmBeforeBroadcast`, `reactionConfirmDismissed`,
`reactionEmojiOnly`, `sendReactionsToRecipient`. Le dialog de confirmation s'affichait
sans cesse même après coche "Ne plus demander", le mode emoji-only restait inaccessible,
etc. Fix : `cachedSettings` délègue désormais à `settings.state` (la StateFlow
`Eagerly` hydratée par `appScope` côté [SettingsRepository], qui elle EST toujours
collectée). Vérification ajoutée : `WhileSubscribed` n'est valide que pour des
StateFlow exposées et collectées par Compose ; les caches privés doivent utiliser
`Eagerly` ou un autre mécanisme actif.

**B2 — Tapback fold échouait sur les bodies multi-ligne.** L'encoder
[SendReactionUseCase.buildTapbackBody] normalise les whitespace (newlines, tabs →
espace simple) dans le preview avant émission, mais le matcher receiver
[ConversationMirror.applyIncomingReaction] utilisait `body LIKE 'prefix%'` côté
SQL — et SQLite LIKE ne fait pas d'équivalence whitespace. Un OUTGOING stocké
`"Hello\nworld"` ne matchait pas le prefix `"Hello world"`, donc la réaction
s'affichait comme bulle texte au lieu d'un badge. Fix : nouveau DAO
`findRecentOutgoingForConversation(convId, 50)` + fallback Kotlin qui normalise
les whitespace des 2 côtés (`collapseWhitespace()` extension privée). Path rapide
SQL LIKE conservé pour les cas mono-ligne (majorité).

**B3 — Ambiguïté de fold sur messages courts à préfixe partagé.** Quand
plusieurs OUTGOING courts partagent un préfixe ("Hello" vs "Hello world"),
l'ancien matcher prenait toujours le PLUS RÉCENT — donc une réaction à l'ancien
"Hello" était folded sur "Hello world" (faux message). Fix : nouveau champ
[DecodedReaction.wasTruncated] (true si le wire contenait `…`). Dans le matcher :
- `wasTruncated == false` (body court non tronqué, preview = body complet) →
  match **EXACT** après normalisation des whitespace. "Hello" matche uniquement
  "Hello", pas "Hello world".
- `wasTruncated == true` (body long, prefix seul connu) → fallback prefix
  match (avec l'ambiguïté inhérente au protocole SMS-based Tapback, sans solution
  sans casser la compat iMessage/Google Messages).

**B4 — Dialog confirm réaction réouvrait malgré "Ne plus demander".** Race
sub-100 ms entre `settings.update { reactionConfirmDismissed = true }` (write
DataStore async) et la prochaine lecture de `cachedSettings.value.sending`
(StateFlow Eagerly avec délai de propagation). Si l'utilisateur réagissait deux
fois en rapide succession, la 2e lecture trouvait encore l'ancienne valeur
`false`, ré-ouvrait le dialog. Fix : lecture **fraîche** via `settings.flow.first()`
UNIQUEMENT sur ce site (lecture après write potentiel). Les 4 autres sites
PERF-01 (envoi SMS/MMS hot path) restent en lecture `cachedSettings.value` car
ils n'ont pas de write précédent à attendre.

**B5 — Label "Format compact (emoji seul)" trompeur.** L'option contrôle en
réalité le format wire des réactions (Tapback verbeux qui permet le fold côté
destinataire, vs emoji nu qui force le destinataire à voir un SMS texte sans
contexte). Les utilisateurs activaient l'option pensant "compact = mieux", et se
retrouvaient avec les badges qui n'apparaissaient plus chez le destinataire.
Label renommé en **"Envoyer l'emoji nu (sans contexte)"** + description
réécrite pour expliciter le trade-off OFF (recommandé, badge sur message) vs
ON (SMS texte, perd la fusion).

Aucune surface sécurité changée. Le fold Tapback est strictement local au
receveur ; il ne crée pas de nouvelle entrée sensible. Le matcher exact
(B3) ne diminue pas la sécurité — il améliore juste la précision de
l'association message↔réaction.

### v1.6.1 — Reaction notif fix + deep audit hardening (30 fixes)

**1. Reaction notification regression fix** (root cause of this PATCH).
Since v1.4.1 (Tapback fold path), `SmsDeliverReceiver` correctly attached an incoming
reaction to the original outgoing message but did `return@launch` immediately after the
SEC-01 sentinel insert — skipping the entire `notifier.notifyIncoming(...)` branch. The
recipient saw the badge change silently, no system notification, breaking parity with
iMessage / Google Messages Tapback.
- `ConversationMirror.ReactionApplied` gains a `targetMessageId: Long` (stable notif id
  + deep-link to the precise message ; no collisions, no DB schema change).
- `SmsDeliverReceiver` posts a localized body (`reaction_notif_body_with_preview` /
  `_no_preview`) via `notifyIncoming(...)`, preserving `previewMode`, `enabled`,
  `POST_NOTIFICATIONS`, active-conv auto-dismiss and tag-based cleanup contracts.

**2. Post-release deep audit — 30 fixes landed across 3 axes** (score 84/83/88 → 96+).

*Security (7)*
- **SEC-01** : `MessagingStyle.Message(visiblePreview)` au lieu de `body` brut. Avant,
  certains OEMs (Xiaomi MIUI/HyperOS, Samsung One UI < 5) ignoraient
  `VISIBILITY_SECRET` pour `MessagingStyle` et fuitaient le contenu en lockscreen.
- **SEC-05** : `addrSuffixes` (PII suffixes téléphoniques 8 chiffres, quasi-identifiants
  RGPD) retirés des logs Timber dans `BlockedNumbersImporter`.
- **SEC-06** : URL MMSC complète (potentiels tokens session opérateur dans path/query)
  retirée du log debug dans `MmsWapPushReceiver`.
- **SEC-07** : `applied.targetBody` désormais passé par `stripInvisibleChars()` dans
  `SmsDeliverReceiver` avant injection dans la notif réaction (anti BiDi/RLO sur body
  OUTGOING qui n'était pas stripé à l'écriture).
- **SEC-08** : sender + caption + subject MMS passés par `stripInvisibleChars()` dans
  `MmsDownloadedReceiver` (parité avec le path SMS, defense in depth).
- **SEC-09** : `Attachment.toShareableUri` ajoute `canonicalFile` + whitelist
  `[filesDir, cacheDir]` avant FileProvider (defense in depth path traversal).
- **SEC-11** : `AndroidManifest.xml` clarifié sur la protection réelle des actions
  `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `USER_UNLOCKED` (protected broadcasts
  AOSP, pas la permission `RECEIVE_BOOT_COMPLETED` qui est "normal").

*Performance (8)*
- **PERF-01 (HIGH)** : `cachedSettings: StateFlow<AppSettings>` dans `ThreadViewModel`
  remplace 5 `settings.flow.first()` sur le chemin send (économie ~25-50 ms de
  latence cumulée sur clavier rapide). Idem **PERF-08** dans `TelephonySyncWorker`
  (snapshot unique) et **PERF-11** dans `IncomingMessageNotifier` (lecture
  synchrone via `SettingsRepository.state` hydraté Eagerly au boot).
- **PERF-02** : `distinctUntilChanged` sur le flux de lookup contact dans
  `ThreadViewModel` (la query ContentProvider ne se redéclenche plus sur chaque
  frappe clavier).
- **PERF-03** : `remember(conversation.lastMessageAt)` autour de `relativeRowLabel`
  dans `ConversationRow` (~100 allocations Calendar évitées par recomposition de
  liste).
- **PERF-04** : pré-calcul `daySeparatorLabels: List<String?>` dans `ThreadScreen`
  via `remember(state.messages, todayLabel, yesterdayLabel)` (était ~600 allocations
  Calendar par rendu initial sur thread de 200 msgs).
- **PERF-05** : `appLock.state` isolé du combine principal dans
  `ConversationRepositoryImpl.observeMessages` (un déverrouillage ne déclenche plus
  un rebuild attachments).
- **PERF-06** : `debounce(200 ms)` sur la query recherche dans `ConversationsViewModel`
  (une seule recomposition LazyColumn par stabilisation au lieu d'une par frappe).
- **PERF-07** : `ContactsReader` passe de `ConcurrentHashMap` non-borné à
  `LruCache(500)` + `LruCache(1000)` (anti leak mémoire progressif sur SMS spam
  / 2FA / livraisons).

*Quality (15)*
- **QUAL-01 + QUAL-16** : `SAFETY_NET_DAYS`, `MS_PER_DAY`, `purgeCutoffMs(days, now)`
  centralisés dans `domain/purge/PurgePolicy.kt` (source unique de vérité — les
  duplications dans `ConversationRepositoryImpl` et `TelephonySyncWorker` sont
  retirées).
- **QUAL-02** : `flowOn(io)` avant `stateIn` dans `ConversationsViewModel`
  (`defaultAppManager.isDefault()` IPC Binder synchrone ne bloque plus le Main thread).
- **QUAL-03** : `defaultAppManager` rendu `private` dans `ConversationsViewModel` —
  la screen passe désormais par `buildChangeDefaultIntent()` (encapsulation ViewModel
  respectée).
- **QUAL-04** : `openOutputStream(uri)!!` remplacé par `?: error("...")` dans
  `BackupService` (diagnostic explicite si URI révoqué / disque plein).
- **QUAL-05** : `!!` redondant post smart-cast retiré dans `ContactsReader`.
- **QUAL-06** : `conv!!.draft` remplacé par `conv?.draft.orEmpty()` dans
  `ThreadViewModel` (invariante non-captée par le compilateur, robuste à un futur
  refactor de `seededDraft`).
- **QUAL-07** : `"PDF export failed"` (anglais hardcodé) remplacé par
  `R.string.snack_pdf_export_failed` FR+EN (regression i18n corrigée).
- **QUAL-10** : `SmsDeliverReceiver` passe par `ConversationRepository.findMessageById`
  au lieu d'accéder directement à `MessageDao` (pattern Repository respecté).
- **QUAL-11** : `VoicePlaybackController` reçoit `@MainDispatcher` injecté au lieu
  du `Dispatchers.Main.immediate` hardcodé (testabilité).
- **QUAL-13** : `splitGraphemeClusters` déplacé de `ui.components` vers
  `core/ext/StringExt.kt` (extension String utilitaire, ne doit pas vivre dans un
  module UI).
- **QUAL-14** : `SortMode.DATE` ne trie plus par `pinned` en premier (était
  indistinguable de `SortMode.PINNED_FIRST`).
- **QUAL-15** : KDoc drift `v1.3.11` → `v1.4.0 (F5 forward feature)` dans
  `ThreadViewModel` et `AppRoot`.
- **QUAL-17** : `@androidx.compose.runtime.Stable` sur les 5 `UiState` data classes
  (Compose recomposition skipping).
- **QUAL-18** : `escapeFtsQuery` extrait en fonction top-level pure dans
  `data/repository/EscapeFtsQuery.kt` + nouveau fichier de tests
  `EscapeFtsQueryTest.kt` (15 cas : empty, whitespace, FTS reserved chars, BiDi,
  zero-width, BOM, control chars, Unicode letters).

**Reportés v1.6.2+** (changement de format / migration / infrastructure) : SEC-04
(PBKDF2 salt 16→32 B casse `.smsbk`), SEC-12 (hash PendingIntent), SEC-14 (whitelist
MMSC opérateurs), PERF-09 (Baseline Profile setup), PERF-10 (FTS4→FTS5),
PERF-12 (WAL + page_size SQLCipher), QUAL-08/09/12 (FQN imports / Dispatchers.Default
injectable VoiceRecorder).

**Tests** : 14 tests pré-existants `IncomingReactionDecoderTest` + 15 nouveaux
`EscapeFtsQueryTest` (29 tests JUnit5 sur les 2 fichiers les plus sensibles) + suite
complète verte. **Lint** : aucune nouvelle erreur (baseline régénéré pour 4 erreurs
+ 177 warnings pré-existants).

### v1.6.0 — Post-v1.5.0 audit hardening (security / perf / a11y)

Patch+minor follow-up to v1.5.0. The 3-axis audit run after publication surfaced 2 HIGH and 6 MEDIUM/LOW findings ; this release lands them all.

**HIGH (closed)**

- **S1 — ReDoS guard on `IncomingReactionDecoder`.** The Tapback regex
  `^Reacted\s+(.+?)\s+to\s+[«"](.+?)[»"]$` is non-greedy and accepts `DOT_MATCHES_ALL` ;
  a malicious multi-part SMS like `Reacted ❤️ to «aaa…` (no closing guillemet, 3 kB)
  would otherwise force catastrophic backtracking. We now reject any input
  `> MAX_DECODE_INPUT_LENGTH = 400` chars before the regex sees it. A real Tapback
  always fits in a single UCS-2 segment (≤ 70 chars). Three new JUnit5 tests lock the
  guard (oversize body rejected fast, body just above the cap rejected, realistic
  body close to the cap accepted).
- **U1 — Custom emoji dialog respects `MAX_REACTION_EMOJIS`.** The "Other emoji"
  shortcut routes through the system emoji keyboard, which could produce a string of
  arbitrarily many clusters. The dispatch site in `ThreadScreen` now splits the input
  via `splitGraphemeClusters().take(MAX_REACTION_EMOJIS).joinToString("")`, atomically
  preserving ZWJ family / skin-tone / VS-16 sequences.

**MEDIUM / LOW (closed)**

- **Q1** — `SendReactionUseCase` KDoc updated : the "Changed transitions stay
  network-silent" line was a v1.3.1 artifact ; since v1.4.1 the ViewModel dispatches
  Changed too so the recipient sees the new emoji. KDoc now reflects reality.
- **Q2** — Dead branch `Modifier.then(Modifier)` in `EmojiReactionPickerSheet`
  replaced by `Modifier.alpha(0.38f)` (Material 3 disabled state), so emojis blocked
  by the capacity cap are visually muted instead of just non-clickable.
- **Q3** — `when (result)` on the `SetReactionResult` sealed interface is now
  exhaustive with explicit `Removed` and `Noop` branches. A future variant of the
  sealed type will fail to compile here instead of being silently swallowed.
- **P2** — `atCapacity` in the picker wrapped in `derivedStateOf`, so the 22 emojis
  that did not change selection state skip a recomposition on every tap.
- **U2** — `EmojiReactionBadge` now carries `semantics { role = Role.Button ;
  contentDescription = "Réaction <emoji>" }` and `clickable(onClickLabel = "Retirer la
  réaction")`. TalkBack used to announce a mute "double-tap to activate" with no
  context ; it now describes both the badge state and the remove action.
- **S2** — `MessageDao.listAll()` (the backup snapshot read) excludes Tapback
  sentinels (`body = '' AND attachments_count = 0 AND reaction_emoji IS NULL`). These
  rows are internal artifacts of the anti-reimport mechanism ; including them in a
  backup would surface empty bubbles on restore.

**Not changed**

- The composite `(conversation_id, date)` index on `messages` was flagged as missing
  but is in fact already present since schema v2 (and the `date` standalone index since
  v4). No migration required.
- The "abandon multi-select on Other emoji" UX is intentional and documented in code
  ; we left it as-is for v1.6.0.

### v1.5.0 — Multi-emoji reactions + Tapback bidirectional fidelity

Minor SemVer bump. Two themes shipped together :

1. **Multi-emoji reactions** (v1.5.0 feature) :
   - `EmojiReactionPickerSheet` rewritten as a multi-select grid. Tap toggles an
     emoji in/out of the current selection (highlighted with a `cs.primary` ring),
     "Envoyer la réaction" commits the joined string. Cap at `MAX_REACTION_EMOJIS = 3`
     to keep the badge readable.
   - `EmojiReactionBadge` switched from fixed-size circle to an auto-growing pill
     (min 28 dp height, min 28 dp width, rounded-rect with 14 dp corner = visual
     circle for the single-emoji case ; widens horizontally for 2-3 emojis).
   - `splitGraphemeClusters()` helper handles the multi-codepoint emojis (ZWJ
     families, skin tones, variation selectors) so the picker's pre-selection
     state and the badge rendering keep clusters atomic.
   - The picker opens with the message's existing reaction pre-selected, allowing
     additive edits without re-typing the full combination.
   - The wire format (Tapback or emoji-only per `SendingSettings.reactionEmojiOnly`)
     ships the joined string transparently — the receiving decoder accepts arbitrary
     emoji sequences without code changes.

2. **Tapback bidirectional fidelity** (folded the v1.4.1 backlog) :
   - **Multi-react dispatch** : `ThreadViewModel.dispatchReactionSms` dedup map
     switched from `Map<Long, Long>` (messageId → timestamp) to `Map<Long,
     ReactionDispatch>` (messageId → emoji + timestamp). The 60 s dedup now only
     fires when the SAME emoji is sent twice ; legitimate changes (❤️ → 👍) bypass
     the window so the correspondent sees the update.
   - **Send-on-change** : `setReaction` dispatches an outgoing SMS on both `First`
     (null → emoji) AND `Changed` (A → B) transitions (was First-only since v1.3.1).
     `Removed` stays local-only — Tapback has no "remove reaction" wire format.
   - **Hide reactor's own outgoing Tapback bubble** : `upsertOutgoingSms` accepts a
     new `localMirrorBody: String?` parameter ; `SendReactionUseCase` passes `""`
     so the Tapback SMS still ships on-wire + lands in `content://sms` (legal duty
     as default SMS app) but the reactor's own thread no longer paints a redundant
     `"Reacted ❤️ to «…»"` text bubble. The empty Room row is filtered out by
     `MessageDao.observeForConversation` (`body='' AND attach=0 AND reaction IS NULL`
     regardless of direction).
   - `touchConversation` is skipped when `mirrorBody.isEmpty()` so the conversation
     list does not show a blank preview / wrong sort order from the sentinel row.

**Carryover from v1.4.0 / v1.4.1 backlog** (already in v1.4.0 but kept here for
trace) : MMS reception unblocked on Android 10+, multi-MIME attachment extraction,
caption ↔ previewLabel decoupling, KeepAliveService opt-in, AttachmentPicker `*/*`,
voice bubble waveform, file picker open to all MIME types.

**Defensive audit fixes shipped with this release**
- **SEC-01** : `upsertReactionSentinel` drops a poison-pill Room row carrying the
  same `telephonyUri` as the system inbox row after a Tapback fold, so
  `TelephonySyncManager` cannot re-import the body as a phantom text bubble. The
  sentinel is filtered out at the DAO level (body='' + 0 attach + 0 reaction).
- **SEC-02** : `stripInvisibleChars` widened to also strip U+00AD (soft hyphen),
  U+034F (combining grapheme joiner), U+061C, U+180E, U+2060–2064, U+FFFC, U+FFFD.
  Closes an attack path where a forged SMS like `­❤` could pass the
  pure-emoji heuristic and forcibly pin a reaction badge.
- **SEC-03** : `AppRoot` share-target route now clears `IncomingShareHolder.pending`
  when the user is already inside a Thread or Compose — prevents the pending
  payload from being silently stuffed into a different conversation later.
- **SEC-04** : phone number address removed from a `Timber.i` log line for
  consistency with the project-wide PII-out-of-logs policy.
- **KQ1** : FQN repetition in `ConversationMirror.applyIncomingReaction` replaced
  by a top-of-file import alias `Kind`.
- **KQ2** : `AppRoot` switched from `collectAsState()` to
  `collectAsStateWithLifecycle()` for the incoming-share flow.
- **KQ3** : `TAPBACK_WITH_PREVIEW_REGEX` KDoc fixed to describe the actual fallback
  ASCII quotes `"..."` (was misleadingly stating `<<>>`).
- **KQ4** : `EMOJI_ONLY_REACT_WINDOW_MS` visibility narrowed from `public` to
  `internal`.

Same cert SHA-256 `b09a9511…687d`. No new permission. No schema change. 17 JUnit
tests green (3 new for the grapheme cluster splitter, 14 existing for the reaction
decoder).

### v1.4.0 — Ergonomics pack + voice bubble waveform

Minor SemVer bump driven by 5 user-facing features. No new permission, no schema
change, no signing key change. 6 defensive fixes applied from a parallel 3-axis
audit (security / performance / UI-coherence) before tag.

**F1 — Keyboard retract after send** (`ThreadScreen.kt`)
- After every send (text, voice, or media MMS), the soft keyboard is hidden via
  `LocalSoftwareKeyboardController.hide()` and IME focus is dropped via
  `LocalFocusManager.clearFocus()`. Lets the sender see the freshly-sent message
  at the bottom of the thread without manually collapsing the keyboard.

**F2 — Instant-validation contact picker** (`ComposeViewModel.kt`, `ComposeScreen.kt`)
- New `pickRecipient(rawNumber: String): Boolean` checks if `recipients.isEmpty()`
  BEFORE the append; when true it appends + immediately calls `createConversation`
  (single-recipient flow, ~99 % of cases). When false (user is building a group)
  it only appends — the explicit "Continuer" button stays the validation step.
  The free-entry row supporting text adapts via a new `compose_use_this_number` /
  `compose_add_to_group` string pair to mirror the current state.

**F3 — Copy message** (`BubbleMenuTrigger.kt`, `MessageBubble.kt`)
- New `onCopy: (() -> Unit)?` parameter on `BubbleMenuTrigger` surfaces a
  Material 3 `DropdownMenuItem` "Copier" with `Icons.Outlined.ContentCopy`.
  `MessageBubble` wraps its body Box in `combinedClickable(onClick, onLongClick)`
  so a long-press triggers the same copy action without going through the 3-dots
  menu (iMessage convention). `MediaAttachmentBubble` exposes copy only when a
  caption is present (placeholder bodies are filtered out at the call site).
  `ThreadScreen` injects `LocalClipboardManager` and routes through a single
  `copyMessageBody(msg)` helper that emits a `LongPress` haptic + a "Message
  copié" snackbar for tactile + visual confirmation.

**F4 — Phone number actions** (`MessageTextWithLinks.kt`, `PhoneActionsDialog.kt`)
- `buildLinkifiedText` is extended to also run `Patterns.PHONE` over the body
  alongside `Patterns.WEB_URL`. Phone hits are filtered by a strict digit-count
  band `[PHONE_DIGITS_MIN=7, PHONE_DIGITS_MAX=15]` to reject promo codes (too
  short) and IBANs / credit-card numbers (too long). Overlapping hits prioritise
  URL over phone (priority `0 < 1` in `compareBy`) so an URL containing digits
  is never fragmented.
- Phone hits emit `LinkAnnotation.Clickable` (NOT `Url`) so the tap routes
  through a custom listener instead of an implicit Intent. The listener
  surfaces `PhoneActionsDialog` with 3 actions :
  - **Call** → `Intent.ACTION_DIAL` with `tel:$number` URI. Intentionally NOT
    `ACTION_CALL` which would require `CALL_PHONE` runtime permission.
  - **Copy** → push to clipboard + snackbar.
  - **Add to contacts** → `ContactsContract.Intents.Insert.ACTION` pre-filled
    with the number. No permission required.
- Each `startActivity` is wrapped in `runCatching` + fallback snackbar so a
  stripped ROM without a default dialer / contacts app cannot crash the thread.
- The WAP "any-charset" sentinel (MIBenum 0 → literal `*`) was already handled
  by `resolveCharset` in v1.3.10 — same fallback to UTF-8.

**F5 — Forward message** (`ForwardMessageSheet.kt`, `ForwardPickerViewModel.kt`,
`ThreadViewModel.stageForward`)
- New `Modal Bottom Sheet` lists recent conversations (with search) + a top
  "Nouveau destinataire" CTA. The source conversation is hidden from the list
  (impossible to forward to oneself) via a new
  `ForwardPickerViewModel.setExcludedConversation(id)` API.
- The forward payload reuses the existing share-target plumbing
  (`IncomingShareHolder.Pending`) :
  - text → `Pending.text` → consumer's draft
  - first attachment → `Pending.uris[0]` (wrapped via FileProvider for local
    files — see SEC-01 below), `Pending.mimeType` drives the `AttachmentKind`
    selection
- Destination ThreadViewModel picks up the payload via the existing
  `consumeIncomingShareIfAny()` path. No new ViewModel-to-ViewModel coupling.

**Voice bubble waveform** (`AudioMessageBubble.kt`)
- At rest (`!isPlaying`), the standard Material 3 inactive slider track is
  overlaid by a `Canvas` drawing `WAVE_BAR_COUNT = 28` vertical bars with
  pseudo-random heights seeded by `audio.id`. Same audio clip always renders
  the same silhouette across recompositions (deterministic `Random(seed)`).
  During playback the slider takes over for clean progress animation.
- Incoming voice bubbles now carry a 1-dp border at
  `lerp(bgColor, Color.Black, 0.18f)` (18 % darker than the fill) for better
  contrast against the thread surface, symmetric with the outgoing border-only
  design.

**Defensive audit fixes shipped with this release**
- **SEC-01** (`ThreadViewModel.stageForward`) : local file paths in the forward
  payload are wrapped through `FileProvider.getUriForFile(...)` instead of
  `Uri.fromFile(...)`. The latter was technically safe today (intra-process
  `openInputStream` consumer, no `Intent` crossing), but the `file://` pattern
  is a known `FileUriExposedException` landmine and the rest of the app already
  uses `FileProvider` throughout — alignment.
- **P1** (`ForwardPickerViewModel`) : the filtered conversation list is now
  cached in `UiState.filtered` and recomputed only on `setQuery`,
  `setExcludedConversation`, or `observeAll` emissions. The composable reads
  `state.filtered` instead of calling the filter inside the recomposition.
  Eliminates O(n·m) jank on Android Go appliances when typing into the picker
  search field with ~150 conversations loaded.
- **P3** (`MessageTextWithLinksTest.kt`) : 3 new JUnit 5 tests pin the
  `countDigits` helper and the `PHONE_DIGITS_MIN/MAX` band so a future widen
  cannot silently let through promo codes or IBANs.
- **U2** (`ForwardMessageSheet`) : the "Nouveau destinataire" `ListItem` carries
  `semantics { role = Role.Button }` so TalkBack announces "Bouton" in addition
  to the headline text.
- **U3** (`ForwardMessageSheet`) : the sheet's `dismissAndReset` lambda clears
  `viewModel.setQuery("")` before bubbling the dismiss up — prevents a stale
  query from reappearing when the user reopens the sheet.
- **Hook removed** : the `Annuler` button on `PhoneActionsDialog` was orphaned
  by Material 3's `dismissButton` slot (rendered below the 3-action stack).
  Removed — tap-outside and back-press already invoke `onDismissRequest`.

Same cert SHA-256 `b09a9511…687d`. ~12 files modified, 2 new files
(`PhoneActionsDialog.kt`, `ForwardMessageSheet.kt`, `ForwardPickerViewModel.kt`,
`OemKeepAliveOnboarding.kt` was v1.3.10 — no new manifest entry).

### v1.3.1 (this release) — Reaction-as-SMS feature

v1.3.1 adds an opt-in capability : when the user posts an emoji reaction on a
**received** message, an SMS containing only that emoji is sent to the
**single sender** of the message (never to other group participants, never
when the message is outgoing). The preference is **on by default** ; a one-shot
confirmation dialog (with "don't ask again" checkbox) protects the user from
silent billing surprises on the first send.

Audited along three axes ; two CRITICAL findings caught and fixed before tag :

- **F1 (CRITICAL)** : `setReaction` could trigger a SMS for outgoing messages
  (i.e. reacting to your own sent message). Now blocked at the use case AND
  hidden from the UI menu (`onReact` is `null` on outgoing bubbles).
- **F2 (CRITICAL)** : in a group conversation, the reaction SMS would be
  broadcast to *every* participant. Now targets *only* the sender of the
  reacted message (`message.address`).
- **F3 (HIGH)** : user signature would be appended to the reaction body
  (`❤️\n--\nPat` → multi-part SMS billed x2/x3 + sender thread pollution).
  `SendSmsUseCase` got an `appendSignature: Boolean = true` parameter ;
  `SendReactionUseCase` passes `false`.
- **F4 (HIGH)** : race between the confirm dialog and a subsequent reaction
  tap could dispatch a stale emoji. `Event.RequestReactionConfirm` now carries
  the `messageId` and `ThreadViewModel.confirmReactionSend` re-checks the
  current `reactionEmoji` before dispatching.
- **P1 (HIGH)** : a `DataStore.first()` failure in the post-First block of
  `ThreadViewModel.setReaction` would crash the ViewModel scope. Wrapped in
  `runCatching` with a Timber warning.
- **P5 (MEDIUM)** : empty recipient label in the confirm dialog if the
  conversation was not yet hydrated. Falls back to a localised "this contact"
  string.

The 24 quick-pick emojis are hard-coded standard codepoints (no ZWJ-only,
no BiDi controls). The "+ Other emoji" path runs `EmojiCustomDialog
.isLikelyEmoji()` which rejects `<>&"'\`, BiDi overrides, BOMs and ZWJ-only
fakes (see v1.3.0 audit Q4/F2).

A second-pass UI/branchements audit caught 6 additional issues, all fixed
before tag :

- **X1 (CRITICAL)** : refuse alphanumeric senders (`Free`, `INFO`, bank,
  delivery, 2FA) and short codes <4 digits. Without this guard, reacting to
  a bank SMS would attempt to send `❤️` to a non-dialable address or to a
  premium short code (1,50 €+/SMS in France). `SendReactionUseCase
  .isDialablePhoneNumber()` enforces `^[+0-9 .()-]+$` + ≥4 digits + no ASCII
  letter.
- **X2 (HIGH)** : RAM dedup window (60 s) on `messageId` to prevent billing
  spam when the user toggles `null → ❤️ → null → ❤️` quickly. Each cycle is
  legitimately a `SetReactionResult.First` but only the first one in the
  window sends a SMS.
- **X3 (HIGH)** : `reactionConfirmDismissed = true` is now persisted **only
  after** a successful `DispatchOutcome.Sent`. Previously, a permanently
  failing dispatch (NotDefaultSmsApp, blocklist) would still set the pref
  silently, leaving the user with no future confirmation despite no SMS ever
  having been sent.
- **X4 (MEDIUM)** : confirm dialog autofocuses the "Cancel" button (pattern
  used by all destructive dialogs : DestructiveConfirmDialog,
  PurgeNowConfirmDialog, nuke data).
- **X5 (MEDIUM)** : a second `RequestReactionConfirm` event arriving while
  the first dialog is still open is now silently dropped (no overwrite). The
  local reaction badge of the 2nd tap remains posted ; only the SMS dispatch
  for that second tap is skipped — consistent with "one confirm = one send".
- **X6 (MEDIUM)** : "Don't ask again" row uses `Modifier.toggleable(role =
  Role.Checkbox)` instead of `Row.clickable` + `Checkbox.onCheckedChange` to
  expose a single a11y node to TalkBack / Switch Access.

Tests `AppSettingsTest.v1_3_1_reaction_send_defaults_are_explicit` +
`SetReactionResultTest` lock the new defaults and sealed-class semantics so
any future refactor that drops `messageId` from `First` or flips the
default toggle to `false` fails CI.

### v1.3.2 (this release) — Apple/Google Tapback format + clickable URLs

Two UX refinements building on v1.3.1 :

- **Tapback format** : the reaction SMS body is now
  `"Reacted <emoji> to «<preview>»"` (e.g. `"Reacted ❤️ to «See you tomorrow?»"`).
  This **exact** ASCII wrapping is what iMessage (iPhone) and recent Google
  Messages parse to display a **native attached reaction bubble** on the
  original message — instead of a free-standing `❤️` text bubble. Other apps
  show the raw text, which remains contextually clear.
  - `SendReactionUseCase.buildTapbackBody` is extracted as a top-level
    `internal` function for direct JUnit testing without instantiating the
    use case.
  - Body sanitization : control characters (CR/LF/NUL/BEL, U+0000–U+001F,
    U+007F–U+009F) are replaced by a single space, then whitespace runs are
    collapsed. Prevents a malicious incoming SMS from injecting line breaks
    that would split our outgoing tapback into multiple PDUs or fake a
    different sender prefix on the receiver's parser.
  - Preview truncated at 50 chars + "…" to fit comfortably inside one UCS-2
    SMS segment (70 chars cap) with the wrapping + emoji ; avoids the silent
    billing surprise of a 2-segment SMS.
  - Empty body (MMS image-only) → `"Reacted <emoji>"` fallback (still parsed
    by Apple/Google).
- **Clickable URLs in message bubbles** : `MessageTextWithLinks` Composable
  uses Compose 1.7+ `LinkAnnotation.Url` with `Patterns.WEB_URL` (Android's
  battle-tested regex used by every system app). Detected URLs are styled
  underlined + medium weight, inherit the parent text color (legible in
  both light/dark themes), and open via the system `UriHandler` →
  `Intent.ACTION_VIEW`.
  - Scheme normalization : bare domains (`google.com`) are wrapped as
    `https://google.com` before opening. Existing `http(s)://` URLs are
    preserved as-is. **No other scheme is ever generated** (no `tel:`,
    `file:`, `content:`, etc.) — eliminates the entire class of "click a
    URL, open a weird intent" exploits.
  - Trailing punctuation strip (`.`, `,`, `;`, `:`, `!`, `?`, `)`, `]`, `}`,
    `»`, `"`, `'`) so `Hello google.com.` opens `https://google.com` and
    leaves the trailing period outside the link.
  - `remember(text)` caches the `AnnotatedString` so the WEB_URL regex
    doesn't re-run on every recomposition of the bubble list.

New test `SendReactionUseCaseTest` locks the exact Apple/Google Tapback
wording, the control-character sanitization, the truncation boundary, and
the empty-body fallback. Any future refactor that changes
`"Reacted X to «Y»"` wording would fail CI immediately.

A second-pass audit found 7 issues, all fixed before tag :

- **Y1 (HIGH)** : extend the body-sanitization regex to strip U+2028/U+2029
  (Line/Paragraph separators), U+200E/U+200F + U+202A–U+202E + U+2066–U+2069
  (Bidi controls — a `‮` RLO in a received malicious SMS would
  visually flip the rendered Tapback on the recipient's screen and break
  the iMessage parser), and U+FEFF (BOM).
- **Y2 (HIGH)** : `String.take(n)` operates on UTF-16 code units and can
  cut in the middle of a surrogate pair (emoji 4-byte) or a ZWJ cluster
  (family emoji). A new `safeTake()` walks back to a clean boundary so the
  outgoing SMS never carries an orphan surrogate / corrupted glyph.
- **Y3 (MEDIUM)** : preview budget is now computed dynamically from
  `SMS_UCS2_SEGMENT_CAP (70) - TAPBACK_WRAP_LENGTH (15) - emoji.length`,
  guaranteeing the whole tapback fits in **one** UCS-2 SMS segment even
  with a 4-UTF-16-unit flag emoji or an 11-UTF-16-unit family emoji.
- **Y4 (MEDIUM)** : URL whitelist via `toSafeHttpsTargetOrNull()`. Only
  `http://` and `https://` are turned into `LinkAnnotation.Url`. Any other
  scheme (`javascript:`, `data:`, `intent:`, `file:`, `content:`, `tel:`,
  custom app schemes) is rendered as plain text — the system `UriHandler`
  is never asked to resolve a hostile intent.
- **Y5 (MEDIUM)** : `buildLinkifiedText` short-circuits when the input
  exceeds `LINKIFY_INPUT_CAP = 2000` chars. Anti-ReDoS on the
  `Patterns.WEB_URL` regex (java.util.regex NFA can degrade on
  pathological inputs).
- **Y6 (MEDIUM)** : `MessageBubble` skips the linkified renderer on
  messages whose status is `FAILED` — the bubble's outer `clickable`
  retry-tap stays unambiguous, no gesture conflict with the link.
- **Y7 (MEDIUM)** : new `MessageTextWithLinksTest` (11 cases) locks the
  scheme whitelist behavior (case-insensitive http/https accepted, every
  other scheme rejected, bare domain normalized to `https://`, path-colon
  edge case correctly classified as non-scheme). Patterns-dependent cases
  deferred to v1.3.3 with Robolectric.

Tests added in v1.3.2 : `SendReactionUseCaseTest` (10 cases including the
new Y1/Y2/Y3 anti-regression guards), `MessageTextWithLinksTest` (11
cases). All pass.

### v1.3.3 (this release) — Critical bug fixes + share-target IPC surface

Triggered by user-reported regressions after v1.3.2. Seven fixes (5
critical, 2 high) plus a global audit that caught 2 additional issues
fixed before tag.

User-facing fixes :

- **Bug #5 (CRITICAL)** : duplicate empty conversation on SMS receive.
  `ConversationMirror.ensureConversation` now falls back to **8-digit
  suffix matching** for 1-to-1 conversations when the exact CSV match
  fails. Prevents a system-imported conv in international format
  (`+33612345678`) from spawning a duplicate when a broadcast SMS arrives
  in national format (`0612345678`).
- **Bug #6 (CRITICAL)** : notifications kept stacking. Notifier now posts
  with a **tag** (`com.filestech.sms.conv.<id>`) ; `markRead` calls
  `cancelAllForConversation` which iterates `activeNotifications` filtered
  by tag. Effective even when the app is opened directly (no longer
  requires tapping the notification).
- **Bug #1 (HIGH)** : voice send failed on SFR. `BITRATE_BPS` 24 → 16
  kbps + `MAX_SIZE_BYTES` 450 → 280 KB. 120 s now fits in one MMS segment
  for all French carriers.
- **Bug #4 (MEDIUM)** : SMS Tech absent from system Share chooser. Added
  `<intent-filter ACTION_SEND>` to MainActivity with **strict MIME
  whitelist** (`image/*`, `video/*`, `audio/*`, `text/plain`,
  `text/x-vcard`, `text/vcard`, `application/pdf`). New `IncomingShareHolder`
  singleton + parsing in MainActivity. `ThreadViewModel` consumes the holder
  after `state.conversation` hydrates (Z3 audit fix).
- **Bug #2 (MEDIUM)** : received images / files not openable. New
  `MediaAttachmentBubble` Composable with tap → `Intent.ACTION_VIEW` via
  scheme-aware URI resolution (Z1 audit fix : detects `content://mms/part/N`
  for system-imported MMS vs file paths for app-cache attachments —
  blanket `File(localUri)` would have crashed on legacy MMS).
- **Feature #3** : image thumbnail preview in the attachment confirmation
  dialog (Coil with same scheme-aware URI handling).
- **UI #7** : `senderLabel` ("You" / contact name) bold above the first
  bubble of each burst ; vertical spacing 3 dp → 8 dp at boundaries.
  Snackbar custom in `inverseSurface` (per user spec : red reserved for
  destructive actions only).

Audit Z (post-fix) — 7 findings, all fixed before tag :

- **Z1 (CRITICAL)** : `MediaAttachmentBubble` was opening every attachment
  with `File(localUri)` regardless of scheme. `content://mms/part/N`
  attachments (all system-imported MMS) would silently fail. Fixed with
  `toShareableUri(context)` helper that branches on scheme.
- **Z3 (HIGH)** : `consumeIncomingShareIfAny` was racing with the
  conversation hydration flow ; `onAttachmentPicked` returned early if
  `state.conversation == null` and the holder was already consumed —
  losing the share. Fixed by suspending on `_state.first { it.conversation
  != null }` with a 5 s timeout.
- **Z4 (HIGH)** : `setGroup(...)` without a posted `groupSummary` creates
  phantom group headers on OneUI/MIUI that are not cancelled by per-notif
  cancel. Switched to **tag-based** notify/cancel (`nm.notify(tag, id,
  notif)` / `nm.cancel(tag, id)`).
- **Z5 (MEDIUM)** : `senderLabel` color on outgoing AudioMessageBubble used
  `cs.onSurfaceVariant` over `cs.primary` background = ~2:1 contrast (WCAG
  AA fail). Switched to `cs.onPrimary` for outgoing.
- **Z6 (MEDIUM)** : intent-filter MIME whitelist tightened (removed
  `text/*` and `application/*` catch-alls).

Global audit — 2 high findings caught :

- **G1 (HIGH, privacy)** : after `purgeOlderThan`, conversations whose every
  message was deleted retained their original `last_message_preview` in
  clear on the list screen. New `refreshAllConversationPreviewsAfterPurge`
  DAO query recomputes preview + `last_message_at` from the surviving
  rows. Called by both `purgeHistoryNow` (manual) and `TelephonySyncWorker`
  (auto monthly).
- **G2 (HIGH, UX)** : an `IncomingShareHolder` left posted from an
  abandoned Share could attach the file to the wrong conversation when
  the user later opened a thread via notification tap.
  `IncomingShareHolder.Pending` now carries a `postedAt` timestamp ;
  `consume()` returns `null` if past `PENDING_TTL_MS = 60 s`. MainActivity
  also `clear()`s the holder on any non-SEND intent (launcher, deep-link,
  notification-open).

Other audit findings (G3–G10) deferred to v1.3.4 (dead code cleanup, doc
sync, settings polish — non-blocking).

### v1.3.4 (this release) — Multi-attach in composer

Refactor of the attachment staging UX following user feedback ("immediate
send is not practical, I want to stack several images / files in one
message"). Replaces the modal confirmation dialog (v1.2.1) by an
in-composer staging bar. Single MMS multipart dispatch for the whole
batch + optional text body.

Architecture :

- `UiState.pendingAttachment: PendingAttachment?` → `pendingAttachments:
  List<PendingAttachment>` ; cleanup of all staged files in `onCleared`.
- `onAttachmentPicked` **appends** to the list with a live cap check
  (`sum(file sizes) + draft.length > CARRIER_PAYLOAD_CAP_BYTES` =
  280 KB) — refuses the new pick + snack + deletes the tmp cache copy.
- `removePendingAttachment(fileAbsolutePath: String)` removes by stable
  path id (not by index, anti-race) + deletes cache file.
- `clearAllPendingAttachments()` flush + delete-all (used by future
  routes if needed).
- `dispatchPendingAttachments()` is the new MMS multipart path : single
  `SendMediaMmsUseCase.invoke(recipients, payloads, textBody)` call.
- `send()` routes : if `pendingAttachments.isNotEmpty()` →
  `dispatchPendingAttachments` ; else → text-only SMS via `doSend`.
- Modal `AlertDialog(pendingAttachment)` removed from `ThreadScreen`.
- New `PendingAttachmentsBar` Composable : `LazyRow` of 72 dp chips,
  thumbnail Coil for images / icon for video/file, small red X
  (16 dp circle + `cs.error` background + `cs.onError` cross) in top-right
  to remove (destructive color reserved per the v1.3.3 user rule).
- `ComposerBar` / `ComposingRow` get `hasPendingAttachments: Boolean`
  param that flips the mic button to the Send button when the user has
  staged attachments but no text yet (M1 audit fix — without this, the
  feature was unusable for "just send a photo" cases).

Audit M (post-fix) — 4 findings, all fixed before tag :

- **M1 (HIGH)** : Send button unreachable when draft empty + attachments
  staged. Fixed by propagating `hasPendingAttachments` flag.
- **M2 (MEDIUM)** : race between dispatch (1-5 s) and concurrent
  attachment add wipes the late add. Switched to snapshot/diff pattern
  (`val dispatched = pending` then `filterNot { it in dispatched }`).
- **M3 (MEDIUM)** : the cap check at `onAttachmentPicked` didn't cover
  the case where the user added text *after* staging — could submit a
  payload > 280 KB to MMSC. Added re-check at `dispatchPendingAttachments`
  entry.
- **M4 (MEDIUM)** : `LazyRow` key derived from `absolutePath.hashCode().toLong()`
  risks Int collision crash on edge cases, and `onRemove(index: Int)` was
  vulnerable to index shift on add/remove race. Switched to stable
  `String` id (= absolute path) + remove by id.

### v1.3.10 — MMS reception unblocked on Android 10+ OEM ROMs

Cross-device testing on Samsung Galaxy S9 (Android 10 One UI), Samsung S24 FE,
Redmi 9A (MIUI 12 Go), and Xiaomi Poco F5 (HyperOS 2024) exposed three independent
silent failures in the MMS reception pipeline. Each was a "no log, no crash, no
bubble, no notification" failure mode — and the fixes are layered:

**Fix 1 — Hidden API blacklist on Android 10+** (PDU package rename) :
- The embedded AOSP MMS PDU codec (`PduParser`, `PduComposer`, `PduBody`,
  `PduPart`, `EncodedStringValue`, `PduHeaders`, `CharacterSets`,
  `NotificationInd`, `SendReq`, `RetrieveConf`, `MultimediaMessagePdu`,
  `GenericPdu`, `PduContentTypes`, `MmsException`, `InvalidHeaderValueException`)
  previously lived under `com.google.android.mms.pdu.*`. Android 10+ enforces a
  hidden API blacklist on that namespace — the system ClassLoader prefers the
  framework-bundled (hidden) class over our embedded copy and denies access at
  link time ("Accessing hidden method PduParser.<init>([B)V (blacklist, linking,
  denied)", observed on S9 Android 10 logcat 2026-05-18).
- The whole codec was renamed to `com.filestech.sms.pdu.*` (15 .java files moved,
  5 .kt files updated, ProGuard `-keep` rule updated). The ClassLoader now always
  picks our embedded copy. Same pattern Signal / Google Messages use.

**Fix 2 — Silent Hilt-injection crash on Android 10 BroadcastReceivers** :
- `@AndroidEntryPoint` on `MmsWapPushReceiver` + `MmsDownloadedReceiver` triggered
  a silent crash in the Hilt-generated wrapper BEFORE `onReceive` was called when
  Android dispatched WAP_PUSH at cold-start (the normal state for an SMS app
  killed in the background by aggressive OEMs). Result: every incoming MMS was
  dropped without a single log line.
- Both receivers now expose a `@EntryPoint @InstallIn(SingletonComponent::class)`
  interface and resolve `MmsDownloader`, `ConversationMirror`, `MessageDao`,
  `IncomingMessageNotifier`, and the `@ApplicationScope CoroutineScope` on-demand
  inside `onReceive` via `EntryPointAccessors.fromApplication(...)`. The
  `Application` is guaranteed initialized before any broadcast dispatch, so the
  Singleton component is always ready, and any resolution failure now logs
  loudly instead of crashing silently.

**Fix 3 — `PduParser` Message-Class header devoured Content-Location** :
- Per OMA-WAP-MMS-ENC, the `Message-Class` header can be either a `class-identifier`
  octet (0x80–0x83 = Personal/Advertisement/Informational/Auto) OR a `text-string`.
  Our parser was forcing the `text-string` branch unconditionally; when the carrier
  sent a class-identifier (0x8A 0x80…), the parser consumed the 0x80 as the first
  byte of a "text-string" and looped until the next 0x00 — devouring `Message-Size`,
  `Expiry`, AND the trailing `Content-Location` URL. The receiver then bailed with
  "NotificationInd has no contentLocation", silently dropping the MMS.
- Added a dual-path decoder: high-bit-set first byte → class-identifier lookup
  (mapped to "personal" / "advertisement" / "informational" / "auto"); otherwise
  the existing `text-string` path.

**Reception attachment pipeline modernized** (`MmsDownloadedReceiver`,
`ConversationMirror.upsertIncomingMms`) :
- SMS Tech v1.3.9 only kept `audio/*` parts; every incoming image / video / file
  MMS landed as a `[MMS]` placeholder bubble with no attachment, the file silently
  discarded. The receiver now extracts the first non-text non-`application/smil`
  part of ANY MIME (image, video, audio, application) and persists it to
  `cache/mms_incoming/`.
- A separate `text/plain` extractor handles the user caption — but the
  `CharacterSets.getMimeName(0)` call returns the literal `*` (WAP "any-charset"
  MIBenum sentinel) which is not a valid JVM charset; `charset("*")` then threw
  and the caption was silently dropped. Now treated as UTF-8 fallback.
- Decoupled storage: `messages.body` stores the caption verbatim (empty when
  absent); the conversation-list preview label is computed separately and passed
  to `touchConversation`. Stops the placeholder emoji from rendering as a fake
  inline caption under the attachment bubble.

**MMS notifications wired** (`IncomingMessageNotifier`, `MmsDownloadedReceiver`) :
- Renamed `notifyIncomingSms` → `notifyIncoming` (the `MessagingStyle`
  notification is type-agnostic — same heads-up, same lockscreen redaction, same
  inline-reply action, same per-conversation tag cancellation). `MmsDownloadedReceiver`
  now calls it after `upsertIncomingMms` returns, using the preview label as the
  notification body. Before, every incoming MMS landed silently in the DB.

**Duplicate-conversation guard** (`ConversationMirror.ensureConversationByThread`) :
- `MmsDownloadedReceiver` creates the conversation with no system thread id
  (we don't query `content://mms-sms/threadID` from the receiver). The subsequent
  `TelephonySyncManager.bulkImportMmsFromTelephony` was importing the same MMS
  with its real system thread id and inserting a SECOND conversation row when the
  thread-id lookup missed. Added exact-CSV + suffix-8 fallback inside
  `ensureConversationByThread` symmetric with `ensureConversation`: a matching
  one-to-one conv now gets its `thread_id` UPDATED in place instead of being
  shadowed by a duplicate.

**File picker accepts all MIME types** (`AttachmentPickerSheet`) :
- Replaced `{pdf, image/*, audio/*, video/*, text/*}` whitelist by `arrayOf("*/*")`.
  Office formats (.docx, .xlsx, .pptx, .odt, .zip, .epub, .json…) no longer appear
  greyed out in the system file picker. The MMS size cap (~280 KB on most French
  MMSCs) still gates oversized files at send time.

**KeepAliveService — opt-in foreground service ("Mode résistant")** :
- For aggressive OEMs (Xiaomi/Redmi/Poco, Huawei/Honor, Oppo/Realme/OnePlus,
  Vivo/iQOO, Meizu, Asus — detected by `OemRomDetector` via `Build.MANUFACTURER`
  / `Build.BRAND`) that kill background SMS apps within minutes. Disabled by
  default; enabled via Settings → Avancé → "Mode résistant". `START_STICKY`,
  `stopWithTask="false"`, `foregroundServiceType="dataSync"`. Auto-restart at
  device boot via `BootReceiver` (reads the DataStore flag). Defensive try/catch
  covers POST_NOTIFICATIONS revocation (Android 13+) and
  `ForegroundServiceStartNotAllowedException` (Android 12+).

**Limites de compatibilité documentées (HyperOS récent)** :
- **Xiaomi Poco F5 + HyperOS 2024+** : HyperOS whitelists Google Messages + Mi
  Messages at the system level and demands a Mi Account login to disable MIUI
  Optimization — a step many users won't take. The "Mode résistant" foreground
  service mitigates background kills but does not bypass the system whitelist.
  Recommended fallback for these devices: use Google Messages. Documented in
  the [Compatibilité](https://files-tech.com/sms-tech.php) section of the
  product site.

No new dependency. No schema change. No signing-key change. Same cert SHA-256
`b09a9511…687d`. ~10 files modified, 15 .java files renamed.

### v1.3.9 — Foreground-active conversation : auto-dismiss notification

User-driven UX fix : when the user is **currently looking at conversation X** in the
foreground (Thread screen open) and a new SMS for X arrives, the notification was
persisting in the shade — forcing the user to manually swipe it away even though the
message had already appeared in real-time inside the open conversation.

**New behaviour** (aligns with Google Messages, iMessage, Mi Messages) :
- Conv X **open foreground** + SMS arrives on X → notif is **posted briefly** (sound
  + heads-up play normally, so the user "hears" the new message arrive) then **auto-
  cancelled by Android after 1500 ms** via `Notification.setTimeoutAfter(1500L)`
  (API 26+, in our `minSdk`). No persistence in the shade.
- Conv X open + SMS arrives on **a different conv Y** → notif on Y persists normally
  (the user has no visibility on Y).
- App in background → notifs persist normally (unchanged).

**Architecture** :
- New `ActiveConversationTracker` singleton (`@Singleton @Inject constructor()` Hilt,
  `AtomicLong` for the active id, sentinel `NONE = -1L`).
  - `setActive(conversationId)` : called from `ThreadViewModel.init` (before
    observers / `markRead`).
  - `clearActive(conversationId)` : called from `ThreadViewModel.onCleared` (before
    the rest of the cleanup). Uses `AtomicLong.compareAndSet(conversationId, NONE)`
    so a stale `onCleared` from a previous ViewModel cannot wipe the state set by
    a freshly-init'd new ViewModel during a configChange race.
  - `isActive(conversationId): Boolean` : lock-free read by the notifier.
- `IncomingMessageNotifier.notifyIncoming` reads `isActiveConversation` ONCE
  before building the notification, then conditionally applies
  `.setTimeoutAfter(ACTIVE_CONV_TIMEOUT_MS)` in the builder `.also { ... }` block.

**Why this design is robust** (pre-release audit mobile-quality-auditor, niveau 2
STRICT, verdict APPROVED) :
- `AtomicLong` lock-free read costs ~1 ns per SMS, negligible vs the already-present
  IO suspends (`settings.flow.first()`, `contacts.lookupByPhone`).
- `setTimeoutAfter` is Android's official API for time-bounded notifications — no
  custom coroutine timer, no Handler.postDelayed, no WorkManager. Zero overhead.
- The metadata held in memory (the active Room conversation id, a Long) carries no
  PII, no message content. Lock-screen redaction (`VISIBILITY_PRIVATE` / `_SECRET`)
  is unaffected.
- `cancelAllForConversation` (v1.3.3 Z4 audit) keeps working : it iterates
  `activeNotifications` filtered by tag, and a notif already auto-cancelled via
  `setTimeoutAfter` is simply absent from the iteration — no double-cancel.
- `markRead` flow stays intact : `init → setActive → observers launch → first batch
  → markRead → cancelAllForConversation`. Active-conv timeout and read-cancel are
  orthogonal.

**Limits accepted** :
- On aggressive OEM ROMs (some MIUI / ColorOS variants) `setTimeoutAfter` may be
  ignored at the system level — fallback behaviour is the pre-v1.3.9 persistence,
  no functional regression.
- If the process is hard-killed without `ThreadViewModel.onCleared` running (rare :
  OOM killer, force-stop), the AtomicLong stays "active" for the rest of the process
  session. Next SMS on that conv would then be auto-dismissed instead of persisted —
  cosmetic UX glitch, no security risk. Reset on next app cold start (AtomicLong
  has no persistence).

No new dependency. No format / schema / signing key / permission change. Same cert
SHA-256 `b09a9511…687d`. 3 files modified (1 new `ActiveConversationTracker.kt`, 2
edited `ThreadViewModel.kt` + `IncomingMessageNotifier.kt`).

### v1.3.8 — CRITICAL hotfix : R8 keep rule for embedded AOSP MMS PDU classes

**Severity** : CRITICAL. All users on v1.3.7 had their **MMS sending silently broken**
(voice messages, multi-attach, single-image MMS). Text SMS were unaffected — the bug was
specific to the MMS dispatch path through `MmsBuilder.attachRecipientsCompat` reflection.

**Symptom** :
- User taps Send on a voice clip → bubble appears with status "Sending" → instantly
  flips to FAILED with "Échec d'envoi" red snackbar.
- "Tap to retry" under the bubble does nothing (pre-existing limitation : `RetrySend
  UseCase` only knows how to re-dispatch text SMS, not MMS — to be addressed in a
  later release).
- Logcat shows `TP/MmsProvider: insert outbox row 10XX` followed 24 ms later by
  `TP/MmsProvider: delete row 10XX, caller: com.filestech.sms` — the system row is
  rolled back the moment `MmsBuilder.buildMultipartSendReq` returns null.

**Root cause** :
`app/src/main/java/com/google/android/mms/pdu/*.java` embeds the AOSP MMS PDU encoder
classes (`SendReq`, `PduComposer`, `PduBody`, `PduPart`, `EncodedStringValue`,
`PduHeaders`, `CharacterSets`, `PduParser`…). `MmsBuilder.attachRecipientsCompat` +
`MmsBuilder.appendPart` invoke methods on these classes **exclusively via reflection**
(`Class.getMethod("setTo", arr.javaClass)`, `Class.getMethod("addTo", …)`,
`Class.getMethod("addPart", …)`) to cross OEM signature divergences (Samsung One UI
removed `addTo`, certain AOSP versions don't expose `addPart(PduPart)`, etc.).

Because no Kotlin/Java source code calls these methods directly, R8's global static
call-graph analyzer concluded they were dead code and **stripped them from the release
APK** when minifying. Subsequent `Class.getMethod("setTo", …)` then throws
`NoSuchMethodException` → `attachRecipientsCompat` returns `false` →
`buildMultipartSendReq` returns `null` → `dispatchMms` enters the "BRANCH 2 :
encodePdu null" rollback path → user sees the failure.

The bug existed latently in every prior release ; v1.3.6 happened to compile with R8
keeping the methods (call-graph analysis decided to preserve them for unrelated
reasons), v1.3.7's adjacent changes (G4 migration / G5 string purge / F5 LruCache)
shifted the analysis output enough to trigger the strip.

**Fix** (`proguard-rules.pro`) :
```
-keep class com.filestech.sms.pdu.** { *; }
```
(Updated in v1.3.10 — the embedded PDU package was renamed from
`com.google.android.mms.*` to `com.filestech.sms.pdu.*` to bypass the Android 10+
Hidden API blacklist; the keep rule was bumped accordingly. Pre-v1.3.10 history:
the rule originally targeted `com.google.android.mms.**`.)

A single keep rule covering all members of the embedded PDU package. Verified
sufficient by user test 2026-05-17 (vocal sent successfully, received by recipient).
No security risk added — the PDU classes are read-only utility code (no credential,
no state, no network access, no persistence).

**Verification trail** :
1. `MMS_DEBUG` `android.util.Log.e` calls were temporarily added to all 5 failure
   branches of `MmsSender.dispatchMms` + every `return null` of
   `MmsBuilder.buildMultipartSendReq` to bypass the `-assumenosideeffects`
   Timber stripping in release. Logcat capture pinpointed
   "`attachRecipientsCompat returned false`" as the root path.
2. After applying the keep rule + cleaning up the debug logs, user retested ;
   voice MMS dispatched successfully and was confirmed received.
3. No diff vs v1.3.7 in `MmsSender.kt` / `MmsBuilder.kt` net of the `MMS_DEBUG`
   removals — only `proguard-rules.pro` changed functionally.

**Deferred to v1.3.9+** :
- `RetrySendUseCase` should learn to re-dispatch MMS (voice / multi-attach) via the
  appropriate use case, not always through `SmsSender.send()` (which silently
  ignores attachments). The "tap to retry" affordance is currently dead for MMS.
- Consider tightening the AOSP PDU keep rule from `** { *; }` to per-method `-keep
  class … { method-name; }` once we have an exhaustive list of reflected methods —
  saves ~20-50 KB in the APK but requires audit-trail discipline.

No new feature in this patch. No change to file formats, database schema, signing
key, permissions, or i18n. Same cert SHA-256 `b09a9511…687d`.

### v1.3.7 — First-launch splash + snackbar bi-color + audit backlog

User-driven feature release that also clears the entire audit backlog reported during
v1.3.5 and v1.3.6 pre-release audits.

**Feature 1 — First-launch splash** (`SplashScreen.kt` + `SplashViewModel.kt` + DataStore
`AdvancedSettings.splashShown`) :
- 100% Compose native animation (no Lottie dependency, no APK weight added).
- Animations : logo scale (0.5 → 1.0) + alpha (0 → 1) over 900 ms, ease-out cubic ;
  tagline fade-in after 700 ms ; skip hint fade-in after 1500 ms. Total ≈ 5.5 s,
  skippable via tap, back hardware, or auto-dismiss.
- Tagline : "L'appli qui ne lit pas vos messages et qui respecte votre vie privée."
  (FR) / "The app that doesn't read your messages and respects your privacy." (EN).
- Single-fire guard via `AtomicBoolean` shared across all dismiss paths (tap, back,
  auto-dismiss, cold-start "already seen" branch). `LaunchedEffect(Unit)` never
  re-fires → no double-navigation risk on `markShown()` flag flip.
- `StateFlow` with `SharingStarted.Eagerly` + initial value `true` → no flash splash
  on second-launch+ users ; redirect to home before any frame is drawn.
- Branched into `AppRoot` as `startDestination = Splash` with `popUpTo(Splash)
  { inclusive = true }` on completion → splash is unreachable via back stack.

**Feature 2 — Bi-color snackbar** (success vs error) :
- `ThreadViewModel.Event.ShowSnackbar` gains `isError: Boolean = false` flag.
- `SmsTechSnackbarVisuals` custom `SnackbarVisuals` carries the flag through the
  `SnackbarHostState` (official Material 3 pattern, no external state).
- `SnackbarHost { data -> ... }` branches `containerColor` / `contentColor` /
  `actionColor` on `(data.visuals as? SmsTechSnackbarVisuals)?.isError`.
- Success → slate-blue brand (`BrandBlue #2460AB`, `Color.kt:SnackbarBg`). Error →
  strong red (`BrandDanger #C62828`, identical to delete buttons + destructive
  dialogs). White text on both, WCAG AA contrast verified (5.8:1 / 5.5:1).
- All `_FAILED` snackbar emissions in `ThreadViewModel` pass `isError = true`.
- `SnackbarHostState.showError(message)` helper for direct-call sites
  (`onMicPermissionDenied`, etc.).

**Cleanup release — audit backlog cleared** :

- **G4 (MEDIUM)** : `MIGRATION_5_6 { DROP TABLE IF EXISTS conversation_overrides }` —
  table morte (entity + DAO existed but zero business consumer ; verified by
  transversal grep). `IF EXISTS` makes the migration idempotent. `ConversationOverride
  Entity` + DAO files deleted, removed from `AppDatabase.entities[]` + Hilt
  `DatabaseModule.@Provides`. Schema version bumped 5 → 6.
- **G5 (MEDIUM)** : 64 orphan i18n strings removed from both `values/strings.xml`
  (EN) and `values-fr/strings.xml` (FR). FR↔EN parity preserved at 291/291.
  ~10-15 KB APK reduction. 43 orphans evocative of planned features
  (`action_archive`, `action_pin`, `settings_signature`, etc.) kept as-is per
  v1.3.5 retention rule.
- **F5 (MEDIUM)** : `displayNameCache` migrated `ConcurrentHashMap` (unbounded) →
  `android.util.LruCache<String, String>(1000)`. Long-tenure accounts (50k+ SMS
  history with bank/delivery/2FA alphanumeric senders) no longer grow the cache
  unbounded across the singleton lifetime. LRU eviction drops oldest senders
  first ; active conversations stay hot. `LruCache` is thread-safe (synchronized
  internally) — same atomicity guarantee as the previous `ConcurrentHashMap`.
- **U1 (MEDIUM)** : `ToggleRow.clickable + Switch.onCheckedChange` → `Modifier
  .toggleable(role = Role.Switch) + Switch.onCheckedChange = null`. Single
  semantic node for TalkBack / Switch Access (previously announced two distinct
  interactive elements per toggle). Material 3 official recommendation for
  selectable list items.
- **P2 (MEDIUM)** : conditional "Compact reaction format" toggle wrapped in
  `AnimatedVisibility(fadeIn + expandVertically / fadeOut + shrinkVertically)`
  for smooth layout transition when the parent `sendReactionsToRecipient` toggle
  flips.

**Deferred to v1.3.8** :
- **U2** : `stateDescription` on parent toggles that gate a child toggle — TalkBack
  doesn't currently announce why the child appeared/disappeared. Low priority,
  affects only TalkBack power-users.
- 43 orphan i18n strings flagged as "evocative of planned features" — to be
  reassessed individually when the corresponding features land (or get cancelled).

Compile clean (`assembleRelease` green). All 60+ unit tests still pass. No new
dependency. APK size delta ≈ -10 KB (G5 strings purge minus a few bytes from the
splash classes). Cert SHA-256 `b09a9511…687d` unchanged. Aucun changement format
fichier / aucune destruction de données utilisateur (G4 drop empty table).

### v1.3.6 — Voice MMS universal codec + reaction format toggle

Two user-driven fixes after field reports on Xiaomi Redmi 9A (Android 10 Go
+ Orange/SFR). No new feature surface ; minimal touch, audited delta.

**Fix 1 — Voice MMS codec switched to AMR-NB / 3GP** (`VoiceRecorder.kt`
only) :
- `audio/mp4` (AAC encoder, MP4 container) → `audio/3gpp` (AMR-NB encoder,
  3GP container). AAC was being silently rejected by the carrier MMSC on
  certain ROM × carrier combinations (Redmi 9A Android 10 Go + Orange and
  SFR observed 2026-05-16) — the local bubble appeared but the PDU upload
  came back `RESULT_ERROR_GENERIC_FAILURE` and the message stayed at "send
  failed". AMR-NB is the historical universal MMS audio codec (RFC 3267,
  OMA-MMS since 2002) accepted without exception by every MMSC and every
  Android ROM. It is the format used by Mi Messages, legacy Google
  Messages, Samsung Messages. Slight bitrate reduction (12.2 kbps fixed,
  mono 8 kHz) compensated by universal compatibility ; voice intelligibility
  remains identical to the GSM-FR 2G phone codec.
- Renamed constant `MIME_AUDIO_M4A` → `MIME_AUDIO_3GPP`.
- File extension `.m4a` → `.3gp`. `MmsDownloadedReceiver.mimeExtension`
  already handled `audio/3gpp` for the receive path, no consumer-side
  change needed.
- `MAX_SIZE_BYTES` cap (280 KB) kept unchanged : AMR-NB at 12.2 kbps for
  120 s = ~183 KB, comfortable margin for future unknown MMSCs.

**Fix 2 — User-facing toggle for reaction SMS format** (5 files +
i18n FR/EN) :
- New `SendingSettings.reactionEmojiOnly: Boolean = false`. When `true`,
  the reaction SMS contains just the bare emoji (e.g. `"❤️"`). When `false`
  (default), the Apple/Google Tapback wrapping introduced in v1.3.2 stays
  in effect (`"Reacted ❤️ to «preview»"`).
- Rationale : on legacy SMS apps (Mi Messages, older Samsung) that do not
  parse Tapback, the wrapping shows as raw text — visually noisy. The new
  option lets users targeting legacy recipients send the cleaner bare
  emoji. Default is left at `false` to preserve the native reaction-bubble
  rendering on iPhone iMessage and recent Google Messages, where the
  Tapback parsing is what produces the merged bubble under the original
  message.
- UI : new `ToggleRow` in `SettingsScreen` → Sending section, shown only
  when `sendReactionsToRecipient` is enabled (consistency : you can only
  set the format if you are actually sending).
- Use-case path : `SendReactionUseCase.invoke()` gains an `emojiOnly:
  Boolean = false` parameter (defaulted for backward compat with existing
  callers / tests). The guards F1/F2/F3/X1 remain unchanged and execute
  before the branching `body = if (emojiOnly) emoji else buildTapbackBody
  (...)`.
- DataStore key : `send.reactions.emojiOnly` (boolean). No migration, no
  schema bump — first read returns `false` on installs that pre-date the
  release.

**Pre-tag audit found 1 HIGH + 5 MEDIUM ; HIGH + 1 MEDIUM (P1) fixed
inline, 4 MEDIUM deferred to v1.3.7 (TalkBack semantic merges that affect
all existing `ToggleRow`s, not just this delta) :**

- **S1 (HIGH)** : `dispatchReactionSms` originally added a 2nd
  `settings.flow.first()` read on every reaction send (in addition to the
  one already in `setReaction`). Refactored to pass `emojiOnly: Boolean`
  as a parameter from the caller's existing snapshot — zero extra
  DataStore reads, no suspend-point introduced where the in-flight emoji
  value could drift. Eliminates the original concern (no-timeout flow
  collection on a critical send path) entirely.
- **S2 (MEDIUM)** : `versionName` bumped `"1.3.5"` → `"1.3.6"` in
  `app/build.gradle.kts` (line 38) plus the related comment on line 71.
  Required for manifest / F-Droid yml coherence.
- **P1 (MEDIUM)** : same as S1 — refactored `dispatchReactionSms(messageId,
  emoji, emojiOnly)` and updated both call sites (`setReaction` post-
  observer path + `confirmReactionSend` post-dialog path) to read the
  `sending` snapshot once and pass `emojiOnly` down.

Deferred to v1.3.7 (not specific to this delta, applies to **all**
existing `ToggleRow`s) :
- **P2** : wrap conditional `ToggleRow`s in `AnimatedVisibility` for
  smoother layout transitions.
- **U1** : `Modifier.semantics(mergeDescendants = true) {}` on `ToggleRow`
  Row + `onCheckedChange = null` on the inner `Switch` to fuse TalkBack
  into a single a11y node. Affects every toggle in `SettingsScreen`, not
  just the new one.
- **U2** : add a `stateDescription` to parent toggles whose value gates
  the visibility of a child toggle, so TalkBack users know the child is
  hidden because the parent is OFF (rather than navigation skipping
  silently).

No regression in the existing 60+ unit tests (compileRelease + tests
green). No file format change. Voice clips recorded by v1.3.5 (AAC `.m4a`)
remain readable — `MmsDownloadedReceiver.mimeExtension` handles both
`audio/mp4` (legacy received) and `audio/3gpp` (new sent + received).

### v1.3.5 — Architecture cleanup + UI polish

Polish release closing 5 findings from the v1.3.3 global audit (G3, G6,
G7, G8, G9) plus a user UI tweak. No new feature. Aims to keep the
codebase tidy heading into v1.4.x.

User-facing :
- X attachment-remove button : 20 → 22 dp (slightly bigger, easier tap).

Cleanup :
- **G3 (MEDIUM)** : dropped 3 orphan permissions from manifest
  (`SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `CHANGE_NETWORK_STATE`) —
  unused by the code, generated Play Console warnings + could be revoked
  on Android 14+. Scheduled message dispatch goes via `WorkManager
  .enqueueUniqueWork`, no need for exact alarms ; MMS transport uses
  `INTERNET + ACCESS_NETWORK_STATE`, no `CHANGE_NETWORK_STATE` calls.
- **G6 (MEDIUM)** : removed phantom field `BlockingSettings
  .blockShortCodes` — never read anywhere, never exposed in UI. If
  short-code filtering is ever re-requested, re-implement via a
  `BlockedNumberEntity.scope` column rather than a global boolean.
- **G7 (MEDIUM)** : `ThreadViewModel.recentlySentReactionFor` now purges
  expired entries (> 60 s window) at each access. Was a slow growth
  during ViewModel lifetime (negligible but unnecessary).
- **G8 (MEDIUM)** : `HeadlessSmsSendService` dropped `Intent.ACTION_SEND`
  from its action whitelist — the body extractor relied on
  `intent.data.schemeSpecificPart` which is empty for true `ACTION_SEND`
  intents (`EXTRA_TEXT`-based). Dead branch + needlessly opened IPC
  surface.
- **G9 (MEDIUM)** : `AppLockManager.disableBiometric` switched from
  read-then-update to atomic `settings.update { transform }`. DataStore
  guarantees atomicity ; the previous pattern was anti-idiomatic and
  bordered on race-conditional under concurrent callers.

Deferred to v1.3.6+ (need more careful work) :
- G4 : `ConversationOverrideDao` + entity + table : entirely dead. To be
  dropped via a `MIGRATION_5_6 { DROP TABLE conversation_overrides }`.
- G5 : ~103 i18n strings unused. To be cleaned via the new
  `android-i18n-strings-cleaner` agent.

### v1.2.0 — 3-axis audit

Three independent agents reviewed the v1.1.x → v1.2.0 delta along three axes :

- **Security** : scored 78/100. Two P0 fixed before release :
  - **P0-1** Vault bypass via `ToggleConversationStateUseCase` short-circuiting the
    `VaultManager` guard, combined with `VaultManager.markUnlocked()` never being called.
  - **P0-2** PendingIntent implicit form silently dropped on Android 14+, leading to the MMS
    PDU file (raw audio + sender headers) being left in clear in `cache/mms_incoming/`
    forever — receiver never fired.
  - P1 fixes : lockout horizon clamp, biometric challenge atomicity, recursive cache purge,
    explicit `setClass` on every internal receiver target.
- **Code quality / performance** : scored 84/100. Dead code removed
  (`ThreadViewModel.replyToMessage`, `archiveThisConversation`, `ConversationsViewModel.pin /
  archive / mute`, `TelephonySyncManager.messageDao` injection), `MessageBubble.time` cached
  via `remember`, duplicate `rememberChatFormatters()` in `ThreadScreen` removed.
- **Duplications** : scored 72/100. Dead strings removed
  (`lock_biometric_prompt_title`, `lock_biometric_use_pin` — never referenced). `BrandDanger`
  centralised in `ui/theme/Color.kt`. The `AsyncCoroutineReceiver` factoring is **deferred to
  v1.3** as it touches every receiver and we wanted v1.2.0 to ship without that risk.

Full audit reports archived as comments inside the code (search "Audit P0-1", "Audit P1-5"
etc. for the inline justification of each fix).

### v1.1.x — Vagues 1–3 (audit interne)

Internal pre-release audit applied 23 corrections (F1–F14 sec, P1–P5 perf, U1–U11 UX).
See [`CHANGELOG.md`](CHANGELOG.md#1-1-0--2026-05-14) for the exhaustive list.

---

## Out of scope

- **Transport encryption.** SMS and MMS are clear by protocol. Use Signal / WhatsApp / Matrix
  for content that must not be carrier-visible.
- **Rooted devices.** If `/data/data/com.filestech.sms.debug/` is readable as root, the
  SQLCipher key is still wrapped in the Keystore, but a sufficiently privileged attacker can
  hijack the same `KeyStore.getInstance("AndroidKeyStore")` API the app uses. We do not
  detect or refuse to run on root.
- **Side-channel attacks on the Keystore hardware** (TEE / StrongBox firmware bugs). Out of
  our hands.
- **Carrier-level MMS gateway compromise.** A malicious MMSC could deliver an arbitrary PDU
  and we'd display it — we do not sign / verify content. Mitigation : caps on attachment
  sizes, MIME whitelist for incoming parts, SMIL XML escape on filenames.

---

## Permissions inventory

See [`PERMISSIONS.md`](PERMISSIONS.md) for the full table. Every permission is justified there
with a one-sentence rationale.

---

## Reporting

`contact@files-tech.com` — subject `SMS Tech security report`. PGP key available on request.
Please give us 90 days before public disclosure for any unpatched issue.
