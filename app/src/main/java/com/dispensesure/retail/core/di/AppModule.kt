package com.dispensesure.retail.core.di

import android.content.Context
import com.dispensesure.retail.core.utils.common.LocationProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

// Marks the Default (CPU) dispatcher; the unqualified CoroutineDispatcher binding is IO.
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides the I/O dispatcher for background operations.
     * Hilt will use this to inject a [CoroutineDispatcher] where requested.
     */
    @Provides
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun provideLocationProvider(@ApplicationContext context: Context): LocationProvider =
        LocationProvider(context)

}
