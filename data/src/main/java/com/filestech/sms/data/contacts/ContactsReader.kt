package com.filestech.sms.data.contacts

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.LruCache
import com.filestech.sms.core.ext.normalizePhone
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.ContactPhone
import com.filestech.sms.domain.model.PhoneAddress
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight cache around the system contact provider.
 *
 * We resolve `phoneNumber → displayName` lazily; the cache is invalidated whenever
 * the provider notifies a change (handled in the repository via a ContentObserver).
 *
 * **Thread-safety.** This is a Hilt `@Singleton` consumed concurrently from receiver threads
 * (SmsDeliverReceiver, MmsDownloadedReceiver), Worker threads (TelephonySyncWorker), and
 * viewModelScopes (ConversationsViewModel.refreshContactNames).
 *
 * **v1.6.1 (audit PERF-07).** Le précédent `ConcurrentHashMap` non-borné laissait le
 * cache grossir indéfiniment : chaque numéro inconnu rencontré (SMS spam, codes 2FA,
 * livraisons) s'accumulait pour la durée de vie du processus. Sur deux ans d'usage =
 * potentiellement 10 000+ entrées ≈ 3 Mo heap permanent. Remplacé par [LruCache] (qui
 * est thread-safe et déjà disponible via `android.util` — pas de dep additionnelle).
 * Bornes choisies pour rester sous ~500 KB heap dans le pire cas : 500 entrées
 * positives × ~250 B = 125 KB ; 1000 entrées négatives × 50 B = 50 KB.
 */
@Singleton
class ContactsReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val resolver: ContentResolver get() = context.contentResolver
    private val cache = LruCache<String, Contact>(CONTACT_CACHE_MAX)
    private val negativeCache = LruCache<String, Boolean>(NEGATIVE_CACHE_MAX)

    fun lookupByPhone(rawPhone: String): Contact? {
        val key = rawPhone.normalizePhone()
        cache.get(key)?.let { return it }
        if (negativeCache.get(key) != null) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(rawPhone))
        val proj = arrayOf(
            ContactsContract.PhoneLookup._ID,
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
        )
        var contact: Contact? = null
        resolver.query(uri, proj, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                contact = Contact(
                    id = c.getLong(0),
                    displayName = c.getString(1),
                    phones = listOf(ContactPhone(PhoneAddress.of(rawPhone))),
                    photoUri = c.getString(2),
                )
            }
        }
        // v1.6.1 (audit QUAL-05) — smart-cast post if(contact != null) suffit, pas
        // besoin du !! qui trompait sur l'intention.
        val resolved = contact
        if (resolved != null) cache.put(key, resolved) else negativeCache.put(key, true)
        return resolved
    }

    /**
     * Read all visible contacts that have at least one phone number. Used by the new-message picker.
     *
     * 2026-11-27 (issue #109) — chaque numéro, avec son type, le numéro par défaut en tête et sans doublon : cf.
     * [regrouperParContact], où vit la règle (testable sans fournisseur).
     */
    fun listAll(): List<Contact> {
        val proj = arrayOf(
            Phone.CONTACT_ID,
            Phone.DISPLAY_NAME,
            Phone.NUMBER,
            Phone.PHOTO_THUMBNAIL_URI,
            Phone.NORMALIZED_NUMBER,
            Phone.TYPE,
            Phone.LABEL,
            Phone.IS_SUPER_PRIMARY,
        )
        val lignes = ArrayList<LigneDeNumero>()
        resolver.query(Phone.CONTENT_URI, proj, null, null, Phone.DISPLAY_NAME + " ASC")?.use { c ->
            while (c.moveToNext()) {
                val numero = c.getString(2) ?: continue
                lignes += LigneDeNumero(
                    contactId = c.getLong(0),
                    nom = c.getString(1),
                    numero = numero,
                    photo = c.getString(3),
                    e164 = c.getString(4),
                    type = if (c.isNull(5)) ContactPhone.TYPE_LIBRE else c.getInt(5),
                    libelle = c.getString(6),
                    parDefaut = !c.isNull(7) && c.getInt(7) != 0,
                )
            }
        }
        return regrouperParContact(lignes)
    }

    fun invalidate() {
        cache.evictAll()
        negativeCache.evictAll()
    }

    private companion object {
        /** Max positive entries kept in RAM ; ~500 × 250 B = 125 KB ceiling. */
        const val CONTACT_CACHE_MAX = 500

        /** Max "no contact" entries kept in RAM ; bornes le pire cas SMS spam / 2FA. */
        const val NEGATIVE_CACHE_MAX = 1000
    }
}
