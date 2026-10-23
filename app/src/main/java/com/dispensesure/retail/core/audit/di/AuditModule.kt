package com.dispensesure.retail.core.audit.di

import android.content.Context
import com.dispensesure.retail.core.audit.AuditLogConfig
import com.dispensesure.retail.core.audit.key.IDeviceKeysApi
import com.dispensesure.retail.core.audit.store.AuditLogFileStore
import com.dispensesure.retail.core.audit.store.AuditOutbox
import com.dispensesure.retail.core.audit.store.AuditSequenceCounter
import com.dispensesure.retail.core.audit.store.PrefsAuditSegmentSlot
import com.dispensesure.retail.core.audit.upload.IAuditLogApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuditModule {

    @Provides
    @Singleton
    fun provideAuditOutbox(@ApplicationContext context: Context): AuditOutbox = AuditOutbox(auditDirectory(context))

    @Provides
    @Singleton
    fun provideAuditLogFileStore(@ApplicationContext context: Context, outbox: AuditOutbox): AuditLogFileStore =
        AuditLogFileStore(auditDirectory(context), PrefsAuditSegmentSlot(context), outbox)

    @Provides
    @Singleton
    fun provideAuditSequenceCounter(@ApplicationContext context: Context): AuditSequenceCounter =
        AuditSequenceCounter(context)

    @Provides
    @Singleton
    fun provideAuditLogApi(retrofit: Retrofit): IAuditLogApi = retrofit.create(IAuditLogApi::class.java)

    @Provides
    @Singleton
    fun provideDeviceKeysApi(retrofit: Retrofit): IDeviceKeysApi = retrofit.create(IDeviceKeysApi::class.java)

    private fun auditDirectory(context: Context) = File(context.filesDir, AuditLogConfig.DIRECTORY_NAME)
}
