package com.filestech.sms.system.receiver

/**
 * v1.28.9 (relecture GPT 5.2 du code F17, constat 1) — **la marque d'un exemplaire de MMS, pour le doublon en
 * mémoire de [MmsDownloadedReceiver] : l'identifiant de transaction ET la SIM.**
 *
 * Un `transactionId` n'est unique que pour un MMSC, et un téléphone à deux SIM parle à deux MMSC. Marqué par
 * l'identifiant seul, un second MMS DISTINCT arrivé sur l'autre SIM dans les cinq minutes passait pour un
 * rejeu du premier ; le premier étant consigné, le PDU du second — sa seule copie — partait sans avoir été
 * lu. Sur une même SIM, l'identifiant suffit : c'est le rejeu de l'opérateur que ce garde existe pour
 * écarter, et il l'est toujours.
 *
 * @return `null` sans identifiant : rien ne permet alors de reconnaître un rejeu.
 */
internal fun marqueDeTransaction(transactionId: ByteArray?, subId: Int?): String? =
    transactionId?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }?.let { "$it|${subId ?: ""}" }
