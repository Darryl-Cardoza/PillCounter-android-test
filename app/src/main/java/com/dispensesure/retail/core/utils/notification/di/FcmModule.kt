package com.dispensesure.retail.core.utils.notification.di

import android.content.Context
import com.dispensesure.retail.core.utils.notification.FCMService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FcmModule {

    @Provides
    @Singleton
    fun provideFcmService(@ApplicationContext context: Context): FCMService {
        return FCMService(context)
    }
}
