package com.filestech.sms.data.contacts

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.filestech.sms.core.ext.normalizePhone
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.PhoneAddress
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
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
 * viewModelScopes (ConversationsViewModel.refreshContactNames). The historical
 * `mutableMapOf<...>()` (a non-synchronized `LinkedHashMap`) is **unsafe** under concurrent
 * writes on Android — a long-standing JVM/ART quirk where concurrent `put` calls can rehash
 * the table into a self-referential bucket linked list and pin the thread at 100 % CPU
 * forever. The symptom is a sporadic, non-reproducible ANR. Switching to
 * [ConcurrentHashMap] keeps the lookup O(1) and eliminates the race.
 *
 * `ConcurrentHashMap` does not accept null **values**, so we encode "no contact found" via
 * the [NEGATIVE] sentinel object rather than dropping the negative cache (which would
 * re-trigger a content-provider query on every miss).
 */
@Singleton
class ContactsReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val resolver: ContentResolver get() = context.contentResolver
    private val cache = ConcurrentHashMap<String, Contact>()
    private val negativeCache = ConcurrentHashMap.newKeySet<String>()

    fun lookupByPhone(rawPhone: String): Contact? {
        val key = rawPhone.normalizePhone()
        cache[key]?.let { return it }
        if (key in negativeCache) return null
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
                    phones = listOf(PhoneAddress.of(rawPhone)),
                    photoUri = c.getString(2),
                )
            }
        }
        if (contact != null) cache[key] = contact!! else negativeCache.add(key)
        return contact
    }

    /** Read all visible contacts that have at least one phone number. Used by the new-message picker. */
    fun listAll(): List<Contact> {
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
        )
        val rows = mutableMapOf<Long, Contact>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            proj,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1)
                val number = c.getString(2) ?: continue
                val photo = c.getString(3)
                val existing = rows[id]
                if (existing == null) {
                    rows[id] = Contact(id, name, listOf(PhoneAddress.of(number)), photo)
                } else {
                    rows[id] = existing.copy(phones = existing.phones + PhoneAddress.of(number))
                }
            }
        }
        return rows.values.toList()
    }

    fun invalidate() {
        cache.clear()
        negativeCache.clear()
    }
}
