package com.committee.investing.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.committee.investing.data.db.CommitteeDatabase
import com.committee.investing.data.db.EventDao
import com.committee.investing.data.db.MeetingSessionDao
import com.committee.investing.data.db.SpeechDao
import com.committee.investing.data.db.AgentChatDao
import com.committee.investing.data.remote.AnthropicApiService
import com.committee.investing.data.remote.OpenAiApiService
import com.committee.investing.engine.ApiKeyProvider
import com.committee.investing.engine.StateEngine
import com.committee.investing.engine.Scheduler
import com.committee.investing.engine.flow.FlowLoader
import com.committee.investing.engine.flow.StateMachine
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

// ── Room 迁移脚本 ─────────────────────────────────────────────────────────────
//
// 修复：原代码使用 fallbackToDestructiveMigration()，每次数据库版本升级时
//        无声地清除用户全部数据。对于投资决策记录而言完全不可接受。
//
// 迁移规则：
//   - 每次修改 @Entity 或新增表时，必须：
//     1. 将 CommitteeDatabase.version 加 1
//     2. 在此处添加对应的 MIGRATION_X_Y 对象
//     3. 在 provideDatabase() 的 addMigrations() 中注册
//
// 示例（version 3 → 4，如果未来需要给 speeches 表加列）：
//
//   val MIGRATION_3_4 = object : Migration(3, 4) {
//       override fun migrate(db: SupportSQLiteDatabase) {
//           db.execSQL("ALTER TABLE speeches ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
//       }
//   }
//
// 注意：如需从更早版本迁移（1→3 / 2→3），由于原代码一直使用 destructive 策略，
//        无法安全地编写 Schema 迁移脚本。建议在首次发布正式版前通过
//        Room.databaseBuilder().fallbackToDestructiveMigrationFrom(1, 2) 处理历史债，
//        之后所有新版本必须提供显式 Migration。

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): CommitteeDatabase =
        Room.databaseBuilder(ctx, CommitteeDatabase::class.java, "committee.db")
            // 修复：移除 fallbackToDestructiveMigration()
            // 若仍需兼容版本 1/2 的老数据库（这些版本无从得知 Schema），
            // 可改为 .fallbackToDestructiveMigrationFrom(1, 2)，仅对这两个老版本执行清除，
            // 版本 3 以后必须通过 addMigrations() 显式迁移。
            .fallbackToDestructiveMigrationFrom(1, 2)
            // 未来有 Schema 变更时在此注册：.addMigrations(MIGRATION_3_4)
            .build()

    @Provides fun provideEventDao(db: CommitteeDatabase): EventDao         = db.eventDao()
    @Provides fun provideSessionDao(db: CommitteeDatabase): MeetingSessionDao = db.sessionDao()
    @Provides fun provideSpeechDao(db: CommitteeDatabase): SpeechDao       = db.speechDao()
    @Provides fun provideAgentChatDao(db: CommitteeDatabase): AgentChatDao = db.agentChatDao()
    @Provides @Singleton fun provideGson(): Gson = Gson()

    // ── Flow DSL / State Machine ─────────────────────────────────────

    @Provides @Singleton
    fun provideStateMachine(@ApplicationContext ctx: Context): StateMachine {
        val flow = FlowLoader.load(ctx, "default_flow")
        return StateMachine(flow, "IDLE")
    }

    @Provides @Singleton
    fun provideStateEngine(sm: StateMachine): StateEngine = StateEngine(sm)

    @Provides @Singleton
    fun provideScheduler(sm: StateMachine): Scheduler = Scheduler(sm)

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

    // ── DeepSeek ───────────────────────────────────────────────────────

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

    // ── Kimi / Moonshot ────────────────────────────────────────────────

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