package com.filestech.sms.data.ml

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.di.IoDispatcher
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device translation service backed by Google ML Kit (#4).
 *
 * **Privacy posture.** ML Kit performs everything locally once the language model is on disk:
 * no network round-trip per request, no server-side logging, no telemetry. The first translation
 * for a given (source → target) pair triggers a one-time model download (~30 MB per pair),
 * cached by the system in `getSharedDataDir/com.google.mlkit.translate/`. We expose download
 * progress to the UI through the [Outcome] surface — a failure carrying [AppError.Network]
 * means the model is missing and the device has no connectivity.
 *
 * **Thread-safety.** [Translator] instances are not thread-safe across simultaneous translates
 * — we cache one per (src, dst) pair in a [ConcurrentHashMap] so threads share work but the
 * underlying TFLite calls happen on the IO dispatcher, single-flight per cache key by design
 * of ML Kit's internal queue.
 *
 * **Lifecycle.** Translators allocate native resources; closing them on dispose would be ideal
 * but Hilt singletons live with the process, so we accept a steady working set (~20–40 MB) in
 * exchange for snappy follow-up translates. A future memory-aware eviction is tracked under #4.5.
 */
@Singleton
class TranslationService @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val identifier: LanguageIdentifier = LanguageIdentification.getClient()
    private val translators = ConcurrentHashMap<TranslatorKey, Translator>()

    private data class TranslatorKey(val source: String, val target: String)

    data class TranslationResult(
        /** Detected source language as a BCP-47 tag (e.g. `en`, `es`, `de`). */
        val sourceLanguage: String,
        /** The translated string. Same as input when [sourceLanguage] == target. */
        val translated: String,
    )

    /**
     * Translates [text] into [targetTag] (BCP-47, e.g. `fr`, `en`). Detects the source
     * language internally. Returns:
     *  - [Outcome.Success] with the translated string,
     *  - [Outcome.Failure] / [AppError.Validation] when the source cannot be identified, the
     *    target is not supported by ML Kit, or the text is empty,
     *  - [Outcome.Failure] / [AppError.Network] when the model needs to be downloaded but the
     *    device is offline,
     *  - [Outcome.Failure] / [AppError.Unknown] for any other ML Kit failure.
     */
    suspend fun translate(text: String, targetTag: String): Outcome<TranslationResult> = withContext(io) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return@withContext Outcome.Failure(AppError.Validation("empty_text"))

        val sourceTag = try {
            identifier.identifyLanguage(cleaned).await()
        } catch (e: Exception) {
            Timber.w(e, "Language identification failed")
            return@withContext Outcome.Failure(AppError.Unknown(e))
        }
        if (sourceTag == "und") {
            return@withContext Outcome.Failure(AppError.Validation("undetermined_language"))
        }

        // ML Kit accepts both `fr` and BCP-47 like `fr-FR`; we normalize to the base subtag
        // it expects. `fromLanguageTag` returns null for unsupported targets (e.g. `eu`).
        val mlSource = TranslateLanguage.fromLanguageTag(sourceTag)
            ?: return@withContext Outcome.Failure(AppError.Validation("unsupported_source:$sourceTag"))
        val mlTarget = TranslateLanguage.fromLanguageTag(targetTag.substringBefore('-'))
            ?: return@withContext Outcome.Failure(AppError.Validation("unsupported_target:$targetTag"))

        // Trivial passthrough when the user asks to translate into the same language.
        if (mlSource == mlTarget) {
            return@withContext Outcome.Success(TranslationResult(sourceLanguage = sourceTag, translated = cleaned))
        }

        val key = TranslatorKey(mlSource, mlTarget)
        val translator = translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(mlSource)
                    .setTargetLanguage(mlTarget)
                    .build(),
            )
        }

        // Models are downloaded over any network by default. A future setting can restrict
        // this to Wi-Fi only via `requireWifi()` — keeping the unrestricted default for v1.2
        // matches Google Messages behaviour and avoids confusing failures on cellular-only users.
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        } catch (e: Exception) {
            Timber.w(e, "ML Kit model download failed (src=%s, tgt=%s)", mlSource, mlTarget)
            return@withContext Outcome.Failure(AppError.Network(e))
        }

        return@withContext try {
            val translated = translator.translate(cleaned).await()
            Outcome.Success(TranslationResult(sourceLanguage = sourceTag, translated = translated))
        } catch (e: Exception) {
            Timber.w(e, "ML Kit translate failed")
            Outcome.Failure(AppError.Unknown(e))
        }
    }
}
