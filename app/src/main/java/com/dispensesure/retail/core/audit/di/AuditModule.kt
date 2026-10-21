package com.dispensesure.retail.core.audit.di

import android.content.Context
import com.dispensesure.retail.core.audit.AuditLogConfig
import com.dispensesure.retail.core.audit.crypto.AuditRecordCipher
import com.dispensesure.retail.core.audit.crypto.DekAuditKeySource
import com.dispensesure.retail.core.audit.store.AuditLogFileStore
import com.dispensesure.retail.core.audit.store.AuditSequenceCounter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuditModule {

    @Provides
    @Singleton
    fun provideAuditRecordCipher(@ApplicationContext context: Context): AuditRecordCipher =
        AuditRecordCipher(DekAuditKeySource(context))

    @Provides
    @Singleton
    fun provideAuditLogFileStore(@ApplicationContext context: Context): AuditLogFileStore =
        AuditLogFileStore(File(context.filesDir, AuditLogConfig.DIRECTORY_NAME))

    @Provides
    @Singleton
    fun provideAuditSequenceCounter(@ApplicationContext context: Context): AuditSequenceCounter =
        AuditSequenceCounter(context)
}
