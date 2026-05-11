package com.rite.pillcounting.core.utils.logger.di

import android.content.Context
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
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
}

