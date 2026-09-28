package org.polycare.app.di

import android.content.Context
import android.provider.Settings
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.polycare.app.log.FileEventLog
import org.polycare.common.EventLog
import org.polycare.common.HlcClock
import org.polycare.common.UuidV7
import org.polycare.governor.DeviceProbe
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun deviceProbe(@ApplicationContext context: Context): DeviceProbe = DeviceProbe(context)

    /** Device-scoped HLC node id. Falls back to a fixed id if Settings.Secure is unreadable. */
    @Provides
    @Singleton
    fun hlcClock(@ApplicationContext context: Context): HlcClock {
        val deviceId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        return HlcClock(node = deviceId ?: "unknown-device", physicalClock = System::currentTimeMillis)
    }

    @Provides
    @Singleton
    fun uuidV7(): UuidV7 = UuidV7(unixMillis = System::currentTimeMillis)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LogModule {
    @Binds
    abstract fun eventLog(impl: FileEventLog): EventLog
}
