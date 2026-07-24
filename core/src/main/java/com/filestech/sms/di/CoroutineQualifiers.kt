package com.filestech.sms.di

import javax.inject.Qualifier

/**
 * Qualifiers Hilt des dispatchers / scope, dans le module `:core` pour être visibles par TOUS les
 * modules (`:domain`, `:data`, `:app`) qui injectent `@IoDispatcher CoroutineDispatcher` etc. Le
 * `@Module` qui les *fournit* ([com.filestech.sms.di.CoroutineModule]) reste dans `:app` (où Hilt
 * agrège le graphe). Ce sont de pures annotations — aucune dépendance au-delà de `javax.inject`.
 */

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
