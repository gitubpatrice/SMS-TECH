package com.filestech.sms.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

// Les qualifiers @IoDispatcher / @DefaultDispatcher / @MainDispatcher / @ApplicationScope vivent
// désormais dans le module :core (com.filestech.sms.di.CoroutineQualifiers) pour être visibles de
// tous les modules ; ce @Module qui les fournit reste dans :app (agrégation Hilt).
@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {
    // Names cannot use Java keywords (`default`, `main` are valid Kotlin names but JavaPoet rejects
    // them when Dagger generates the Java proxy class — see audit fix for `IllegalArgumentException:
    // not a valid name: default`).
    @Provides @IoDispatcher fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
    @Provides @DefaultDispatcher fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
    @Provides @MainDispatcher fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    /**
     * v1.24.0 — un [kotlinx.coroutines.CoroutineExceptionHandler] est indispensable ici.
     *
     * `SupervisorJob` isole les enfants les uns des autres mais **n'attrape rien** : une exception
     * non rattrapée dans une coroutine racine de ce scope part au gestionnaire par défaut du thread
     * et tue le processus. Or ce scope porte les chemins les plus sensibles de l'app — réception de
     * SMS, raccourci d'urgence, migrations de démarrage. Un échec y devient un log, pas un crash.
     */
    @Provides @Singleton @ApplicationScope
    fun applicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(
            SupervisorJob() + dispatcher +
                kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
                    timber.log.Timber.e(t, "unhandled exception in the application scope")
                },
        )
}
