package com.filestech.sms.data.sync

import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.sms.TelephonyReader
import com.filestech.sms.domain.mms.DestinatairesMms
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-11-06 (issue #76) — **rend leurs destinataires aux MMS importés avant la v15.**
 *
 * L'import des MMS du fournisseur système ne tourne qu'une fois (`mmsImportCompleted`) : sans ce rattrapage,
 * l'historique d'un utilisateur existant n'aurait jamais sa liste. Chaque MMS qui garde une copie dans
 * `content://mms` est relu dans `addr` ; les MMS reçus en direct n'y sont pas (leur PDU, effacé, était seul à
 * porter les destinataires) : rien à rattraper pour eux.
 *
 * Une ligne regardée reçoit sa liste, ou [DestinatairesMms.VERIFIE_SANS_GROUPE] : elle ne sera plus relue. Une
 * lecture en échec (fournisseur indisponible, permission retirée) laisse la ligne à `NULL` et arrête la passe :
 * la suivante reprendra. Par tranches de [TRANCHE] lignes, une tranche par synchronisation.
 */
@Singleton
class RattrapageDestinatairesMms @Inject constructor(
    private val messageDao: MessageDao,
    private val telephonyReader: TelephonyReader,
) {

    /**
     * @param cle la clé d'identité des numéros (`PhoneIdentity.Snapshot.key`).
     * @return le nombre de lignes regardées.
     */
    suspend fun rattraper(cle: (String) -> String): Int {
        val lignes = messageDao.mmsSansDestinatairesVerifies(TRANCHE)
        var regardees = 0
        for (ligne in lignes) {
            val idSysteme = idDansLeFournisseur(ligne.telephonyUri)
            val valeur = if (idSysteme == null) {
                // Une URI qu'on ne sait pas relire ne le saura jamais mieux : regardée, sans groupe.
                DestinatairesMms.VERIFIE_SANS_GROUPE
            } else {
                val adresses = telephonyReader.lireAdressesMms(idSysteme, ligne.direction, cle)
                if (adresses == null) {
                    Timber.w("Rattrapage des destinataires MMS : lecture en echec, reprise au prochain passage")
                    break
                }
                DestinatairesMms.encoder(adresses.destinataires) ?: DestinatairesMms.VERIFIE_SANS_GROUPE
            }
            messageDao.setMmsRecipients(ligne.id, valeur)
            regardees++
        }
        return regardees
    }

    internal companion object {
        const val TRANCHE = 500

        /** `content://mms/123` → 123 ; toute autre forme → `null`. */
        fun idDansLeFournisseur(telephonyUri: String): Long? =
            telephonyUri.removePrefix("content://mms/").takeIf { it != telephonyUri }?.toLongOrNull()
    }
}
