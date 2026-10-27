package com.hereliesaz.cleanunderwear.di

import android.content.Context
import com.hereliesaz.cleanunderwear.BuildConfig
import com.hereliesaz.cleanunderwear.data.CleanUnderwearDatabase
import com.hereliesaz.cleanunderwear.data.CommsHistory
import com.hereliesaz.cleanunderwear.data.NoCommsHistory
import com.hereliesaz.cleanunderwear.data.DeviceMessagingPresence
import com.hereliesaz.cleanunderwear.data.MessagingPresenceSource
import com.hereliesaz.cleanunderwear.data.MergeLedger
import com.hereliesaz.cleanunderwear.data.MergeLedgerDao
import com.hereliesaz.cleanunderwear.data.RoomMergeLedger
import com.hereliesaz.cleanunderwear.data.OfflineTargetRepository
import com.hereliesaz.cleanunderwear.data.TargetDao
import com.hereliesaz.cleanunderwear.data.TargetRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CleanUnderwearDatabase {
        return CleanUnderwearDatabase.getDatabase(context)
    }

    @Provides
    fun provideTargetDao(database: CleanUnderwearDatabase): TargetDao {
        return database.targetDao()
    }

    @Provides
    fun provideMergeLedgerDao(database: CleanUnderwearDatabase): MergeLedgerDao {
        return database.mergeLedgerDao()
    }

    @Provides
    @Singleton
    fun provideMergeLedger(dao: MergeLedgerDao): MergeLedger = RoomMergeLedger(dao)

    @Provides
    @Singleton
    fun provideCommsHistory(history: NoCommsHistory): CommsHistory = history

    @Provides
    @Singleton
    fun provideMessagingPresence(presence: DeviceMessagingPresence): MessagingPresenceSource = presence

    @Provides
    @Singleton
    fun provideTargetRepository(targetDao: TargetDao, database: CleanUnderwearDatabase): TargetRepository {
        return OfflineTargetRepository(targetDao, database)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Bounds a whole search, redirects and token page included, so one slow site
            // can't hold a scrape slot indefinitely.
            .callTimeout(60, TimeUnit.SECONDS)
            .apply {
                // Request lines carry contacts' names in the query; never log them in release.
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
                }
            }
            .build()

}
