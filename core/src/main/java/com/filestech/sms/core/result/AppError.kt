package com.filestech.sms.core.result

/**
 * Typed application errors. Never throw raw exceptions to UI layers; map to one of these.
 */
sealed class AppError(open val cause: Throwable? = null) {

    data class Network(override val cause: Throwable? = null) : AppError(cause)
    data class Storage(override val cause: Throwable? = null) : AppError(cause)
    data class Permission(val permission: String) : AppError()
    data object NotDefaultSmsApp : AppError()
    data class Telephony(val reason: String, override val cause: Throwable? = null) : AppError(cause)
    data class MmsHttp(val statusCode: Int, override val cause: Throwable? = null) : AppError(cause)
    data class Crypto(val reason: String, override val cause: Throwable? = null) : AppError(cause)
    data class Database(override val cause: Throwable? = null) : AppError(cause)
    data class Validation(val message: String) : AppError()

    /**
     * v1.28.3 (F21) — l'envoi a été refusé parce que le destinataire figure dans la liste de
     * blocage de l'utilisateur.
     *
     * Typé, et non un [Validation] porteur d'un message en anglais, parce que l'interface doit
     * pouvoir le DIRE : ce n'est pas une panne, et l'utilisateur qui touche une bulle rouge doit
     * apprendre qu'il a lui-même bloqué ce numéro plutôt que de voir « Échec de l'envoi » et
     * réessayer indéfiniment. Le chemin de relance rendait jusqu'ici cette issue sans un mot.
     */
    data object RecipientBlocked : AppError()
    data class Locked(val unlockRequired: Boolean = true) : AppError()
    data class NotFound(val what: String) : AppError()
    data class Cancelled(val reason: String? = null) : AppError()
    data class Unknown(override val cause: Throwable? = null) : AppError(cause)
}
