package com.filestech.sms.system.notifications

import com.filestech.sms.data.local.datastore.SecurityStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.26.1 (audit H2) — authentifie les intents internes routés par `MainActivity`.
 *
 * # Pourquoi
 *
 * `MainActivity` est `exported="true"` — obligatoire pour le rôle d'application SMS par défaut.
 * N'importe quelle application tierce peut donc l'atteindre par un Intent **explicite** portant
 * n'importe quelle action, indépendamment des `<intent-filter>` déclarés. Or
 * [IncomingMessageNotifier.ACTION_OPEN_CONVERSATION] et son extra `conversationId` sont des
 * constantes publiques, et les identifiants Room sont des entiers séquentiels : leur énumération
 * est triviale. Sans authentification, un tiers pouvait forcer l'affichage d'une conversation
 * arbitraire.
 *
 * L'équipe avait déjà construit la bonne défense — [SafetyCallIntentToken] — mais ne l'avait
 * appliquée qu'à UNE des actions internes. C'est l'asymétrie que cet audit cherchait.
 *
 * # Pourquoi un modèle différent de [SafetyCallIntentToken]
 *
 * Ce jeton-ci n'est **ni mono-usage, ni roté à chaque notification** :
 *
 *  - **Pas mono-usage** : plusieurs notifications de conversations différentes coexistent dans le
 *    volet. Consommer le jeton au premier tap invaliderait tous les autres.
 *  - **Persistant, pas en mémoire** : une notification survit largement à la mort du processus.
 *    Un secret régénéré au démarrage aurait fait échouer le tap sur toute notification postée
 *    avant — une régression sur le chemin le plus courant de l'application.
 *
 * La protection ne vient donc pas de la fraîcheur du secret mais de son **entropie** : 64 bits
 * tirés de `SecureRandom`, illisibles pour une application tierce, qui ne peut ni lire notre
 * magasin privé ni inspecter le contenu d'un `PendingIntent` qui ne lui appartient pas.
 *
 * # Où la vérification a lieu
 *
 * # Coût ponctuel à la mise à jour
 *
 * Une notification affichée par une version antérieure à la v1.26.1, encore présente dans le
 * volet après mise à jour, ne porte pas l'extra : son tap sera refusé et ne fera rien de
 * visible. Le cas est ponctuel et se résout de lui-même à la notification suivante. Compromis
 * assumé, documenté ici comme [SafetyCallIntentToken] documente le sien.
 *
 * # Où la vérification a lieu — bis
 *
 * **Pas à la réception de l'intent**, mais au moment où la navigation est consommée
 * (`AppRoot`) : la lecture du secret est suspendue, et `MainActivity.onCreate` ne peut pas
 * l'attendre sans bloquer le démarrage à froid — précisément le cas d'un tap sur notification
 * quand le processus est mort.
 */
@Singleton
class NotificationIntentToken @Inject constructor(
    private val securityStore: SecurityStore,
) {

    @Volatile
    private var cached: Long = 0L

    /** Le secret courant, créé au premier appel. Mémoïsé : une seule lecture par processus. */
    suspend fun current(): Long {
        cached.takeIf { it != 0L }?.let { return it }
        val loaded = securityStore.notificationTokenOrCreate()
        cached = loaded
        return loaded
    }

    /**
     * Vrai si [token] est le secret courant. `0` — l'absence d'extra, donc un intent forgé par
     * quelqu'un qui ignore jusqu'à l'existence du champ — est toujours refusé.
     */
    suspend fun matches(token: Long): Boolean = token != 0L && token == current()

    companion object {
        /**
         * Extra porte par TOUS les intents internes que `MainActivity` route et qui declenchent
         * une navigation. Partage entre les notificateurs pour qu'aucun n'invente le sien.
         */
        const val EXTRA_NAV_TOKEN = "navToken"
    }
}
