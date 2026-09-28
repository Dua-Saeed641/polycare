package org.polycare.app.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.polycare.app.log.FileEventLog
import org.polycare.common.EventLog
import org.polycare.governor.DeviceProbe
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun deviceProbe(@ApplicationContext context: Context): DeviceProbe = DeviceProbe(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LogModule {
    @Binds
    abstract fun eventLog(impl: FileEventLog): EventLog
}
