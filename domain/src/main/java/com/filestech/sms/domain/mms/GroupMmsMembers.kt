package com.filestech.sms.domain.mms

import com.filestech.sms.domain.model.PhoneAddress

/**
 * v1.28.4 — **les membres d'un groupe, reconstitués depuis un MMS reçu.**
 *
 * Un MMS de groupe est un seul PDU adressé à plusieurs : son en-tête porte l'expéditeur (`From`)
 * et tous les destinataires (`To`, `Cc`), **nous compris**. Le groupe, c'est tout ce monde-là
 * moins nous. Si nous ne savons pas qui nous sommes — « Mon numéro » non renseigné — on ne
 * reconstitue rien : mieux vaut une conversation individuelle qu'un groupe dont l'utilisateur
 * serait membre en double.
 *
 * Les adresses sont comparées par leur **clé d'identité** ([identityKey], la même règle de
 * rapprochement que partout ailleurs dans l'application — E.164 quand c'est possible) : un PDU
 * écrit `+33612345678` là où le contact est enregistré `06 12 34 56 78`, et les deux sont la
 * même personne. La fonction est injectée : `domain` ne connaît pas la région du téléphone.
 *
 * @return la liste des membres (au moins deux, l'expéditeur en tête), ou `null` quand ce n'est
 *   pas un groupe pour nous : nous-mêmes inconnus, ou un seul autre participant.
 */
object GroupMmsMembers {

    fun of(
        from: String,
        to: List<String>,
        cc: List<String>,
        self: String?,
        identityKey: (String) -> String,
    ): List<PhoneAddress>? {
        val cleMoi = self?.let(identityKey)?.takeIf { it.isNotBlank() } ?: return null
        val membres = LinkedHashMap<String, PhoneAddress>()
        for (brut in listOf(from) + to + cc) {
            val cle = identityKey(brut)
            if (cle.isBlank() || cle == cleMoi) continue
            membres.putIfAbsent(cle, PhoneAddress.of(brut))
        }
        return membres.values.toList().takeIf { it.size >= 2 }
    }
}
