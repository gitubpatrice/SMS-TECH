package com.filestech.sms.domain.sender

/**
 * Port domaine étroit : « SMS Tech est-il l'application SMS par défaut ? ».
 *
 * Les use-cases d'envoi ([com.filestech.sms.domain.usecase.SendSmsUseCase],
 * [com.filestech.sms.domain.usecase.SendMediaMmsUseCase],
 * [com.filestech.sms.domain.usecase.SendVoiceMmsUseCase]) refusent d'émettre si l'app n'est pas
 * l'app SMS par défaut ; ils ne dépendent que de cette capacité booléenne.
 *
 * La construction de l'`Intent` Android de demande de rôle
 * ([com.filestech.sms.data.sms.DefaultSmsAppManager.buildChangeDefaultIntent]) reste hors de ce
 * port : c'est un détail Android consommé uniquement par l'UI, qui garde une dépendance directe à
 * l'implémentation (ségrégation d'interface — `domain/` n'importe aucun type Android).
 */
interface DefaultSmsAppChecker {
    fun isDefault(): Boolean
}
