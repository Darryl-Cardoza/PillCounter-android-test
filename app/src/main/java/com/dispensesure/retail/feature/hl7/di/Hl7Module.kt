package com.dispensesure.retail.feature.hl7.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.rite.hl7.HL7
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object Hl7Module {

    @Provides
    @Singleton
    fun provideHl7(): HL7 = HL7()
}
