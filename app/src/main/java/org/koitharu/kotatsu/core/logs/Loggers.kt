package org.koitharu.kotatsu.core.logs

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.SOURCE)
annotation class TrackerLogger

@Qualifier
@Retention(AnnotationRetention.SOURCE)
annotation class SyncLogger
