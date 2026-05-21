package com.filestech.sms.domain.sender

import android.content.Context
import android.provider.ContactsContract
import com.filestech.sms.data.local.datastore.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.8.1 — résout le nom personnel à inclure dans les SMS de réaction sortants.
 *
 * Stratégie :
 *  1. **Override Settings** ([com.filestech.sms.data.local.datastore.SendingSettings
 *     .senderDisplayName]) — si l'utilisateur a saisi un nom explicite, on l'utilise
 *     tel quel (priorité absolue).
 *  2. **ContactsContract.Profile** — sinon, on tente de lire le profil "moi" du
 *     téléphone (l'utilisateur a renseigné son nom dans Contacts → "Moi"). Pratique
 *     sur Pixel et certains Samsung où l'utilisateur a configuré son profil au
 *     setup initial Android.
 *  3. **`null`** — si rien n'est trouvé, on retourne null. Le caller
 *     ([com.filestech.sms.domain.usecase.SendReactionUseCase]) bascule alors sur
 *     le format anonyme `"Réagi par ❤️ à votre message : «…»"`.
 *
 * **Lecture Profile** : depuis Android 4.0 (API 14), `ContactsContract.Profile.CONTENT_URI`
 * est lisible avec la permission `READ_CONTACTS` (que SMS Tech a déjà — utilisée pour
 * résoudre les noms de contacts dans la liste des conversations). Pas de permission
 * supplémentaire à demander.
 *
 * Sanitization défensive : le nom retourné est trimmé, dépouillé des caractères de
 * contrôle Unicode (cf. liste dans [SendReactionUseCase]) et cappé à 40 caractères
 * pour rester dans 1 segment UCS-2 SMS même avec emoji + preview.
 */
@Singleton
class SenderNameProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {

    /**
     * Retourne le nom à utiliser dans les SMS de réaction sortants, ou `null` si
     * aucune source n'a fourni de nom utilisable. Safe to call from any thread —
     * la lecture Profile est synchrone mais courte (< 5 ms typique).
     */
    fun resolveDisplayName(): String? {
        // 1) Override Settings — priorité.
        val override = settings.state.value.sending.senderDisplayName?.sanitizeForSms()
        if (!override.isNullOrBlank()) return override

        // 2) ContactsContract.Profile — lecture du "moi" Android.
        val profileName = runCatching { readProfileDisplayName() }
            .onFailure { Timber.w(it, "SenderNameProvider: Profile read failed (security ?)") }
            .getOrNull()
        return profileName?.sanitizeForSms()?.takeIf { it.isNotBlank() }
    }

    private fun readProfileDisplayName(): String? {
        context.contentResolver.query(
            ContactsContract.Profile.CONTENT_URI,
            arrayOf(ContactsContract.Profile.DISPLAY_NAME_PRIMARY),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(ContactsContract.Profile.DISPLAY_NAME_PRIMARY)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return null
    }

    /**
     * Cleans the candidate name : trim whitespace, replace any control / bidi /
     * BOM characters with a space (so they cannot inject SMS framing nor reverse
     * the visual rendering), collapse repeated whitespace, and cap to 40 UTF-16
     * units. The cap protects the SMS segment budget — with a 40-char name +
     * emoji + " a réagi par X à votre message : «...»" wrap, we still fit in 1
     * UCS-2 segment with ~10-15 chars of preview body.
     */
    private fun String.sanitizeForSms(): String {
        val cleaned = this
            .replace(FORBIDDEN_NAME_CHARS, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.length > MAX_NAME_LENGTH) {
            cleaned.take(MAX_NAME_LENGTH).trimEnd()
        } else cleaned
    }

    companion object {
        /** Hard cap sur la longueur du nom inclus dans le SMS sortant. */
        const val MAX_NAME_LENGTH: Int = 40

        /**
         * Caractères Unicode strippés du nom avant inclusion dans le SMS :
         * C0/C1 controls, line/paragraph separators, bidi controls, BOM.
         * Même liste que [com.filestech.sms.domain.usecase.FORBIDDEN_BODY_CHARS]
         * pour cohérence anti-injection.
         */
        private val FORBIDDEN_NAME_CHARS = Regex(
            "[\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]",
        )
    }
}
