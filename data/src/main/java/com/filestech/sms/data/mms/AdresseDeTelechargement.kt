package com.filestech.sms.data.mms

/**
 * 2026-11-06 (courriel d'un utilisateur Visible/Verizon, Galaxy A54) — **l'adresse de téléchargement d'un MMS
 * reçu, complétée comme l'opérateur l'attend.**
 *
 * Constaté : chaque MMS reçu échouait au téléchargement (« could not be downloaded »), en Wi-Fi comme en données
 * mobiles, alors que l'envoi marchait et que l'application Samsung recevait tout. Cause, établie dans les sources :
 *
 * - le MMSC de Verizon annonce une adresse INCOMPLÈTE, `http://<IPv4>/servlets/mms?message-id=`, et attend que le
 *   client y ajoute l'`X-Mms-Transaction-Id` de la notification. Configuration Verizon d'AOSP
 *   (`carrier_config_carrierid_1839_Verizon-Wireless.xml`) : `enabledTransID` = `true` ;
 * - le service MMS d'Android télécharge l'adresse TELLE QUELLE (`DownloadRequest.doHttp`) : ajouter l'identifiant est
 *   le travail de l'application, d'après `SmsManager.MMS_CONFIG_APPEND_TRANSACTION_ID` — AOSP Messaging
 *   (`MmsUtils`), la bibliothèque `android-smsmms` (QUIK, Fossify Messages), mmsd ;
 * - sur Visible, cet indicateur vaut `false` ([FamilleVerizon]). Sans identifiant, le MMSC répond 404 (journaux
 *   publics de Signal #11831, Visible + Samsung, et de ClearSMS #135, Verizon).
 *
 * La règle est celle de ces implémentations : on n'ajoute que si l'opérateur le demande (configuration Android OU
 * [FamilleVerizon]) ET que l'adresse finit par `=`. Elle ne touche aucune autre adresse : un opérateur français,
 * dont l'adresse est complète, n'est pas concerné.
 */
internal object AdresseDeTelechargement {

    /**
     * [adresse] complétée de [transactionId] si [ajouter] et si elle finit par `=` ; sinon telle quelle. Une adresse
     * qui finit déjà par l'identifiant n'est pas complétée deux fois.
     */
    fun completer(adresse: String, transactionId: String?, ajouter: Boolean): String = when {
        !ajouter || transactionId.isNullOrEmpty() -> adresse
        !adresse.endsWith('=') -> adresse
        adresse.endsWith(transactionId) -> adresse
        else -> adresse + transactionId
    }
}
