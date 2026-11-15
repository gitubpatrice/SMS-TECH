package com.filestech.sms.domain.sms

/**
 * 2026-11-11 (issue #89) — **les deux heures d'un SMS reçu** : [date], quand il est arrivé sur le téléphone, et
 * [dateSent], celle que le réseau a inscrite dans le message.
 *
 * Un SMS reçu était daté par l'horodatage du centre SMS de l'opérateur (TP-SCTS), et un message envoyé par
 * l'horloge du téléphone ; le fil, trié sur `date`, mêlait les deux horloges. Or le TP-SCTS porte une heure
 * LOCALE suivie d'un décalage horaire (3GPP TS 23.040, § 9.2.3.11) : un opérateur qui ne met pas ce décalage à
 * jour au changement d'heure place tous ses messages une heure à côté des réponses de l'utilisateur.
 *
 * C'est la règle d'AOSP (Messaging, `MmsUtils.parseReceivedSmsMessage` et `getMessageDate`, relus le
 * 2026-11-11) : `DATE` = horloge du téléphone, « to avoid confusion with clock drift between the handset and
 * the SMSC », `DATE_SENT` = horodatage du réseau. Repli, comme AOSP : une horloge antérieure à
 * [HORLOGE_REGLEE_DES_MS] n'est pas réglée, et l'heure du réseau vaut mieux.
 *
 * Les MMS ne sont pas concernés : leur en-tête `Date` compte des secondes depuis 1970 en temps universel, sans
 * décalage horaire à mal régler.
 *
 * @property date l'heure d'arrivée, en millisecondes : la colonne qui ordonne le fil.
 * @property dateSent l'heure du réseau, ou `null` quand le message n'en porte pas d'exploitable.
 */
data class HorodatageSmsRecu(val date: Long, val dateSent: Long?) {

    companion object {
        /**
         * 2026-11-01T00:00:00Z — antérieure à toute version de SMS Tech qui porte cette règle : une horloge en deçà
         * n'est pas réglée (AOSP prend la date de construction de son application pour le même usage).
         */
        const val HORLOGE_REGLEE_DES_MS: Long = 1_793_491_200_000L

        /**
         * @param horlogeTelephone `System.currentTimeMillis()` à l'arrivée.
         * @param horodatageReseau `SmsMessage.getTimestampMillis()` : `0` ou moins quand le message n'en porte pas.
         */
        fun de(horlogeTelephone: Long, horodatageReseau: Long): HorodatageSmsRecu {
            val reseau = horodatageReseau.takeIf { it > 0L }
            val date = if (horlogeTelephone < HORLOGE_REGLEE_DES_MS && reseau != null) reseau else horlogeTelephone
            return HorodatageSmsRecu(date = date, dateSent = reseau)
        }
    }
}
