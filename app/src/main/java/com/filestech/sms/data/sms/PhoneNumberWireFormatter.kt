package com.filestech.sms.data.sms

import android.content.Context
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.filestech.sms.core.ext.WireAddress
import com.filestech.sms.data.local.datastore.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single choke point that turns a recipient's stored address into the **E.164 wire form** just
 * before it is handed to the telephony stack ([SmsSender], [com.filestech.sms.data.mms.MmsSender]).
 *
 * Why it exists: an SMS/MMS sent from a **foreign SIM** with a destination in national form (a
 * Luxembourg SIM texting a French `06…` number) is not routable — the network needs the
 * international `+33…` form, otherwise the recipient never gets the message. Reference SMS apps
 * (Google Messages, Signal) all normalise the destination to E.164 before sending.
 *
 * Scope is intentionally minimal: **only the wire address changes**. The Room mirror and the
 * `content://sms` / `content://mms` provider rows keep the user's original raw string, so
 * conversation threading, contact display and history are untouched — no regression on the
 * common domestic case (a French SIM sending `06…` still works, and is now additionally
 * hardened to `+33…`).
 *
 * The region defaults to the **SIM country** of the target subscription (falling back to the
 * network country). When the region can't be determined, or the number is a short code /
 * alphanumeric sender / not valid for that region, the raw string is returned unchanged — the
 * pure decision logic lives in [WireAddress].
 */
@Singleton
class PhoneNumberWireFormatter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {

    /**
     * @param raw the recipient address as stored/displayed (national or international).
     * @param subId the subscription the message is dispatched on (multi-SIM aware); null uses the
     *   device default subscription.
     * @return the E.164 form when it can be derived, otherwise [raw] verbatim.
     */
    fun toWireFormat(raw: String, subId: Int?): String =
        WireAddress.toE164OrRaw(raw, resolveRegion(subId)) { number, region ->
            PhoneNumberUtils.formatNumberToE164(number, region)
        }

    /**
     * The default region for national numbers: the user's explicit override (Settings → Envoi →
     * "Indicatif pays par défaut") when set, otherwise the SIM country. The override is what lets
     * someone on a foreign SIM keep texting national numbers of another country (e.g. a Luxembourg
     * SIM writing French `06…` with the override set to `FR`). `state.value` is the eagerly-hydrated
     * hot snapshot — zero-I/O on the send path.
     */
    private fun resolveRegion(subId: Int?): String? =
        settings.state.value.sending.defaultRegionIso?.takeIf { it.isNotBlank() }
            ?: simRegionIso(subId)

    /**
     * ISO country of the SIM for [subId] (uppercased downstream). Falls back to the network
     * country when the SIM ISO is blank (no SIM record yet, some MVNOs). Every access is wrapped
     * so a telephony read that throws (rare OEM quirk, airplane mode transitions) can never break
     * an outgoing send.
     */
    private fun simRegionIso(subId: Int?): String? = runCatching {
        val base = context.getSystemService(TelephonyManager::class.java) ?: return null
        val tm = if (
            subId != null &&
            subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        ) {
            runCatching { base.createForSubscriptionId(subId) }.getOrNull() ?: base
        } else {
            base
        }
        tm.simCountryIso?.takeIf { it.isNotBlank() }
            ?: tm.networkCountryIso?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
