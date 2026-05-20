package com.filestech.sms.data.ml

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device translation service — **temporarily disabled in v1.7.0**.
 *
 * **Why the stub.** v1.6.x relied on Google ML Kit (`com.google.mlkit:translate`
 * + `com.google.mlkit:language-id`) for on-device translation and language
 * identification. ML Kit is Google proprietary, which made the app
 * incompatible with F-Droid's FLOSS policy (flagged `AntiFeatures: [NonFreeDep]`
 * on MR !38458). v1.7.0 removes both dependencies entirely so the app builds
 * cleanly without any non-free code.
 *
 * **Roadmap.** Full FLOSS replacement is planned for v1.8.x :
 *   - Language identification : fastText `lid.176.ftz` (Meta, MIT, ~125 KB)
 *     bundled in assets, wrapped via ONNX Runtime.
 *   - Translation : Marian MT models (Helsinki-NLP, MIT) imported by the user
 *     via Storage Access Framework (same pattern as the AI Tech / Notes Tech
 *     Gemma model imports). The pair FR↔EN alone is ~300 MB so bundling
 *     was rejected.
 *
 * **API contract preserved.** The public surface (`translate(text, targetTag)
 * -> Outcome<TranslationResult>`) is intentionally unchanged so that
 * `ThreadViewModel` and any future tests keep compiling. Calls return
 * [Outcome.Failure] with [AppError.Validation] code `translation_unavailable_v17`
 * which the UI maps to a localized banner explaining the temporary removal.
 */
@Singleton
class TranslationService @Inject constructor() {

    data class TranslationResult(
        /** Detected source language as a BCP-47 tag (e.g. `en`, `es`, `de`). */
        val sourceLanguage: String,
        /** The translated string. Same as input when [sourceLanguage] == target. */
        val translated: String,
    )

    /**
     * Stub : always returns `Outcome.Failure(Validation("translation_unavailable_v17"))`
     * while the FLOSS replacement is being implemented. UI is expected to
     * suppress the "Translate" action (greyed out + tooltip) — calling here
     * directly is harmless but yields no work.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun translate(text: String, targetTag: String): Outcome<TranslationResult> {
        return Outcome.Failure(AppError.Validation("translation_unavailable_v17"))
    }
}
