package com.hereliesaz.cleanunderwear.di

import android.content.Context
import com.hereliesaz.cleanunderwear.data.CleanUnderwearDatabase
import com.hereliesaz.cleanunderwear.data.OfflineTargetRepository
import com.hereliesaz.cleanunderwear.data.TargetDao
import com.hereliesaz.cleanunderwear.data.TargetRepository
import com.hereliesaz.cleanunderwear.network.WebViewIdentityInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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
    @Singleton
    fun provideTargetRepository(targetDao: TargetDao): TargetRepository {
        return OfflineTargetRepository(targetDao)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(identityBridge: WebViewIdentityInterceptor): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            // Identity-bridge runs first so the User-Agent / Cookie rewrite
            // is visible to the logging interceptor below it.
            .addInterceptor(identityBridge)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            // We use a dummy base URL since we construct full URLs dynamically for scraping
            .baseUrl("https://example.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

}
