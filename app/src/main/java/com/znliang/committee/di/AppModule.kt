package com.znliang.committee.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.znliang.committee.data.db.*
import com.znliang.committee.data.repository.EventRepository
import com.znliang.committee.data.repository.EvolutionRepository
import com.znliang.committee.engine.AgentPool
import com.znliang.committee.engine.runtime.*
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "committee_prefs")

// ── Room 迁移：v4 → v5（新增4张进化表）─────────────────────────

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Agent 经验记忆
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS agent_evolution (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                agentRole TEXT NOT NULL,
                meetingTraceId TEXT NOT NULL,
                category TEXT NOT NULL,
                content TEXT NOT NULL,
                outcome TEXT NOT NULL,
                priority TEXT NOT NULL,
                appliedToPrompt INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_evolution_agentRole ON agent_evolution(agentRole)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_evolution_meetingTraceId ON agent_evolution(meetingTraceId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_evolution_category ON agent_evolution(category)")

        // Agent 技能库
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS agent_skills (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                agentRole TEXT NOT NULL,
                skillName TEXT NOT NULL,
                triggerCondition TEXT NOT NULL,
                strategyContent TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 0.0,
                usageCount INTEGER NOT NULL DEFAULT 0,
                lastUsed INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_skills_agentRole ON agent_skills(agentRole)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_skills_skillName ON agent_skills(skillName)")

        // Prompt 变更历史
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS prompt_changelog (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                agentRole TEXT NOT NULL,
                changeType TEXT NOT NULL,
                beforePrompt TEXT NOT NULL,
                afterPrompt TEXT NOT NULL,
                reason TEXT NOT NULL,
                sourceMeetingTraceId TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL DEFAULT 0,
                isRolledBack INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_prompt_changelog_agentRole ON prompt_changelog(agentRole)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_prompt_changelog_createdAt ON prompt_changelog(createdAt)")

        // 会议结果追踪
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS meeting_outcomes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                meetingTraceId TEXT NOT NULL,
                agentRole TEXT NOT NULL,
                subject TEXT NOT NULL,
                finalRating TEXT,
                agentVote TEXT,
                voteCorrect INTEGER,
                selfScore REAL NOT NULL DEFAULT 0.0,
                lessonsLearned TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_meeting_outcomes_meetingTraceId ON meeting_outcomes(meetingTraceId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_meeting_outcomes_agentRole ON meeting_outcomes(agentRole)")
    }
}

// ── Room 迁移：v5 → v6（新增 skill_definitions 表）─────────────────

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS skill_definitions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                parameters TEXT NOT NULL,
                executionType TEXT NOT NULL,
                executionConfig TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_skill_definitions_name ON skill_definitions(name)")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): CommitteeDatabase =
        Room.databaseBuilder(ctx, CommitteeDatabase::class.java, "committee.db")
            .fallbackToDestructiveMigrationFrom(1, 2, 3)
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
            .build()

    @Provides fun provideEventDao(db: CommitteeDatabase): EventDao = db.eventDao()
    @Provides fun provideSessionDao(db: CommitteeDatabase): MeetingSessionDao = db.sessionDao()
    @Provides fun provideSpeechDao(db: CommitteeDatabase): SpeechDao = db.speechDao()
    @Provides fun provideAgentChatDao(db: CommitteeDatabase): AgentChatDao = db.agentChatDao()
    @Provides fun provideEvolutionDao(db: CommitteeDatabase): AgentEvolutionDao = db.evolutionDao()
    @Provides fun provideSkillDao(db: CommitteeDatabase): AgentSkillDao = db.skillDao()
    @Provides fun provideChangelogDao(db: CommitteeDatabase): PromptChangelogDao = db.changelogDao()
    @Provides fun provideOutcomeDao(db: CommitteeDatabase): MeetingOutcomeDao = db.outcomeDao()
    @Provides fun provideSkillDefinitionDao(db: CommitteeDatabase): SkillDefinitionDao = db.skillDefinitionDao()
    @Provides @Singleton fun provideGson(): Gson = Gson()

    @Provides @Singleton
    fun provideEvolutionRepository(
        evolutionDao: AgentEvolutionDao,
        skillDao: AgentSkillDao,
        changelogDao: PromptChangelogDao,
        outcomeDao: MeetingOutcomeDao,
    ): EvolutionRepository = EvolutionRepository(evolutionDao, skillDao, changelogDao, outcomeDao)

    @Provides @Singleton
    fun provideWebSearchService(
        @Named("normal") normalClient: OkHttpClient,
        gson: Gson,
        apiKeyProvider: DataStoreApiKeyProvider,
    ): WebSearchService {
        return WebSearchService(
            okHttp = normalClient,
            gson = gson,
            tavilyKeyProvider = { apiKeyProvider.getTavilyKeyCached() },
        )
    }

    @Provides @Singleton
    fun provideDynamicToolRegistry(
        skillDefinitionDao: SkillDefinitionDao,
        gson: Gson,
        @Named("normal") normalClient: OkHttpClient,
        apiKeyProvider: DataStoreApiKeyProvider,
        webSearchService: WebSearchService,
    ): DynamicToolRegistry {
        return DynamicToolRegistry(
            skillDao = skillDefinitionDao,
            gson = gson,
            okHttp = normalClient,
            configProvider = { apiKeyProvider.getConfig() },
            webSearchService = webSearchService,
        )
    }

    @Provides @Singleton
    fun provideAgentPool(
        apiKeyProvider: DataStoreApiKeyProvider,
        gson: Gson,
        @Named("streaming") streamingClient: OkHttpClient,
        @ApplicationContext appContext: Context,
        toolRegistry: DynamicToolRegistry,
    ): AgentPool = AgentPool(apiKeyProvider, gson, streamingClient, appContext, toolRegistry)

    // ── Agent Runtime（v7 — 自我进化） ─────────────────────────────

    @Provides @Singleton
    fun provideAgentRuntime(
        agentPool: AgentPool,
        apiKeyProvider: DataStoreApiKeyProvider,
        repository: EventRepository,
        evolutionRepo: EvolutionRepository,
        toolRegistry: DynamicToolRegistry,
        @ApplicationContext appContext: Context,
    ): AgentRuntime {
        val supervisor = SupervisorAgent()
        val agents = listOf(
            AnalystAgent(),
            RiskAgent(),
            StrategistAgent(),
            IntelAgent(),
            ExecutorAgent(),
        )
        return AgentRuntime(
            agentPool = agentPool,
            supervisor = supervisor,
            agents = agents,
            configProvider = { apiKeyProvider.getConfig() },
            repository = repository,
            evolutionRepo = evolutionRepo,
            toolRegistry = toolRegistry,
            appContext = appContext,
        )
    }

    @Provides @Singleton @Named("normal")
    fun provideNormalClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor { msg ->
                android.util.Log.d("OkHttp", msg)
            }.apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()

    @Provides @Singleton @Named("streaming")
    fun provideStreamingClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor { msg ->
                android.util.Log.d("OkHttp", msg)
            }.apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()

    @Provides @Singleton
    fun provideDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
        ctx.dataStore
}
