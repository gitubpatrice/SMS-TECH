package com.filestech.sms.data.blocking

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.BlockedNumberContract
import androidx.annotation.RequiresApi
import com.filestech.sms.core.ext.normalizePhone
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper over [BlockedNumberContract] (API 24+).
 *
 * The system contract is authoritative: when our app is the default SMS app we can write entries
 * which then propagate to Phone/Dialer as well. We mirror everything we write in our own
 * Room table to keep the UI fast and to survive a default-app change.
 */
@Singleton
class BlockedNumberSystem @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @RequiresApi(Build.VERSION_CODES.N)
    fun isSystemBlocked(rawNumber: String): Boolean {
        return runCatching { BlockedNumberContract.isBlocked(context, rawNumber) }
            .onFailure { Timber.w(it, "BlockedNumberContract.isBlocked failed") }
            .getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun block(rawNumber: String): String? {
        val cv = ContentValues().apply {
            put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, rawNumber)
            put(BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER, rawNumber.normalizePhone())
        }
        return try {
            context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, cv)?.toString()
        } catch (t: Throwable) {
            Timber.w(t, "BlockedNumberContract insert failed")
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun unblock(rawNumber: String): Boolean {
        return try {
            BlockedNumberContract.unblock(context, rawNumber) > 0
        } catch (t: Throwable) {
            Timber.w(t, "BlockedNumberContract.unblock failed")
            false
        }
    }

    /**
     * Returns every raw number currently registered in the system's
     * [BlockedNumberContract.BlockedNumbers] table. Used at app start to mirror the OS-wide
     * blocklist into our Room cache so the user does not have to re-block contacts they had
     * already blocked via Téléphone / Samsung Messages.
     *
     * **Read requires either the default-SMS-app role OR `READ_BLOCKED_NUMBERS`**. Outside both
     * cases the contract throws `SecurityException`; we swallow it and return an empty list so
     * the caller can degrade gracefully (nothing to import yet).
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun listSystemBlocked(): List<String> {
        val out = ArrayList<String>()
        runCatching {
            context.contentResolver.query(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
                null,
                null,
                null,
            )?.use { c ->
                val idx = c.getColumnIndex(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER)
                if (idx >= 0) {
                    while (c.moveToNext()) {
                        val raw = c.getString(idx)
                        if (!raw.isNullOrBlank()) out += raw
                    }
                }
            }
        }.onFailure { Timber.w(it, "BlockedNumberContract listSystemBlocked failed") }
        return out
    }
}
