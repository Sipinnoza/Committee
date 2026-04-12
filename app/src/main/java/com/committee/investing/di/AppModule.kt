package com.committee.investing.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.committee.investing.data.db.CommitteeDatabase
import com.committee.investing.data.db.EventDao
import com.committee.investing.data.db.MeetingSessionDao
import com.committee.investing.data.db.SpeechDao
import com.committee.investing.data.db.AgentChatDao
import com.committee.investing.data.remote.AnthropicApiService
import com.committee.investing.data.remote.OpenAiApiService
import com.committee.investing.engine.ApiKeyProvider
import com.google.gson.Gson
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "committee_prefs")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): CommitteeDatabase =
        Room.databaseBuilder(ctx, CommitteeDatabase::class.java, "committee.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideEventDao(db: CommitteeDatabase): EventDao = db.eventDao()
    @Provides fun provideSessionDao(db: CommitteeDatabase): MeetingSessionDao = db.sessionDao()
    @Provides fun provideSpeechDao(db: CommitteeDatabase): SpeechDao = db.speechDao()
    @Provides fun provideAgentChatDao(db: CommitteeDatabase): AgentChatDao = db.agentChatDao()
    @Provides @Singleton fun provideGson(): Gson = Gson()

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Anthropic ──────────────────────────────────────────────────────
    @Provides @Singleton @Named("anthropic")
    fun provideAnthropicRetrofit(okHttp: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides @Singleton
    fun provideAnthropicApi(@Named("anthropic") retrofit: Retrofit): AnthropicApiService =
        retrofit.create(AnthropicApiService::class.java)

    // ── DeepSeek (OpenAI-compat) ───────────────────────────────────────
    @Provides @Singleton @Named("deepseek")
    fun provideDeepSeekRetrofit(okHttp: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.deepseek.com/")
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides @Singleton @Named("deepseek")
    fun provideDeepSeekApi(@Named("deepseek") retrofit: Retrofit): OpenAiApiService =
        retrofit.create(OpenAiApiService::class.java)

    // ── Kimi / Moonshot (OpenAI-compat) ────────────────────────────────
    // NOTE: base URL does NOT include /v1 because @POST path already has "v1/chat/completions"
    @Provides @Singleton @Named("kimi")
    fun provideKimiRetrofit(okHttp: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.moonshot.cn/")
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides @Singleton @Named("kimi")
    fun provideKimiApi(@Named("kimi") retrofit: Retrofit): OpenAiApiService =
        retrofit.create(OpenAiApiService::class.java)

    @Provides @Singleton
    fun provideDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
        ctx.dataStore
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds @Singleton
    abstract fun bindApiKeyProvider(impl: DataStoreApiKeyProvider): ApiKeyProvider
}
