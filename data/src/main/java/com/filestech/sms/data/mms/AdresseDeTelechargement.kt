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
 * - la configuration de Visible (`carrier_config_carrierid_2146_Visible.xml`) ne porte AUCUNE clé MMS, et la plus
 *   précise l'emporte sans fusion : sur Visible, l'indicateur vaut `false`. Les bibliothèques libres s'en passent :
 *   elles embarquent ce réglage par MCC/MNC de la SIM ([OPERATEURS_A_IDENTIFIANT]). Sans identifiant, le MMSC répond
 *   404 (journaux publics de Signal #11831, Visible + Samsung, et de ClearSMS #135, Verizon).
 *
 * La règle est celle de ces implémentations : on n'ajoute que si l'opérateur le demande (configuration Android OU
 * famille Verizon / US Cellular) ET que l'adresse finit par `=`. Elle ne touche aucune autre adresse : un opérateur
 * français, dont l'adresse est complète, n'est pas concerné.
 */
internal object AdresseDeTelechargement {

    /**
     * MCC+MNC dont le MMSC attend l'identifiant de transaction : ceux pour lesquels `android-smsmms` embarque
     * `enabledTransID` = `true` (`res/xml-mcc…-mnc…/mms_config.xml`) — Verizon (310 004, 310 005, 311 012,
     * 311 480, dont Visible) et US Cellular (311 220 à 229, 311 580 à 589).
     */
    private val OPERATEURS_A_IDENTIFIANT: Set<String> = buildSet {
        addAll(listOf("310004", "310005", "311012", "311480"))
        for (mnc in 220..229) add("311$mnc")
        for (mnc in 580..589) add("311$mnc")
    }

    /** Le MMSC de cet opérateur (MCC+MNC de la SIM, `TelephonyManager.simOperator`) attend-il l'identifiant ? */
    fun operateurAttendLIdentifiant(operateurSim: String?): Boolean = operateurSim in OPERATEURS_A_IDENTIFIANT

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
