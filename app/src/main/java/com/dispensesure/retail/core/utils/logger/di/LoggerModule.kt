package com.dispensesure.retail.core.utils.logger.di

import android.content.Context
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogDestination
import com.dispensesure.retail.core.utils.logger.PerformanceLogger
import com.dispensesure.retail.core.utils.logger.destination.FileLogDestination
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LoggerModule {

    @Provides
    @Singleton
    fun providePerformanceLogger(
        @ApplicationContext context: Context
    ): PerformanceLogger {
        return PerformanceLogger(context)
    }

    /**
     * Reuses the [FileLogDestination] that [AppLogger.init] already stood up in
     * [com.dispensesure.retail.PillCountingApplication.onCreate], so DI-managed classes and
     * `AppLogger` call sites write to the same log file instead of two separate ones.
     */
    @Provides
    @Singleton
    fun provideLogDestination(
        @ApplicationContext context: Context
    ): LogDestination {
        AppLogger.init(context)
        return AppLogger.currentDestination() ?: FileLogDestination(context)
    }
}

