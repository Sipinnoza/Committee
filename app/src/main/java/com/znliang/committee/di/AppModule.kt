package com.znliang.committee.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.znliang.committee.data.db.AgentChatDao
import com.znliang.committee.data.db.AgentEvolutionDao
import com.znliang.committee.data.db.AgentSkillDao
import com.znliang.committee.data.db.AppConfigDao
import com.znliang.committee.data.db.CommitteeDatabase
import com.znliang.committee.data.db.DecisionActionDao
import com.znliang.committee.data.db.EventDao
import com.znliang.committee.data.db.MeetingMaterialDao
import com.znliang.committee.data.db.MeetingOutcomeDao
import com.znliang.committee.data.db.MeetingSessionDao
import com.znliang.committee.data.db.PromptChangelogDao
import com.znliang.committee.data.db.SkillDefinitionDao
import com.znliang.committee.data.db.SpeechDao
import com.znliang.committee.data.repository.AppConfigRepository
import com.znliang.committee.data.repository.EventRepository
import com.znliang.committee.data.repository.EvolutionRepository
import com.znliang.committee.engine.AgentPool
import com.znliang.committee.domain.model.MeetingPreset
import com.znliang.committee.domain.model.MeetingPresetConfig
import com.znliang.committee.engine.runtime.AgentRuntime
import com.znliang.committee.engine.runtime.DynamicToolRegistry
import com.znliang.committee.engine.runtime.GenericAgent
import com.znliang.committee.engine.runtime.GenericSupervisor
import com.znliang.committee.engine.runtime.WebSearchService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
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

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS app_config (
                id INTEGER PRIMARY KEY NOT NULL DEFAULT 1,
                selectedLanguage TEXT NOT NULL DEFAULT 'auto'
            )
        """.trimIndent())
    }
}

// ── Room 迁移：v7 → v8（多模态会议材料）─────────────────────────

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS meeting_materials (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                meetingTraceId TEXT NOT NULL,
                fileName TEXT NOT NULL,
                mimeType TEXT NOT NULL,
                localPath TEXT NOT NULL,
                base64Cache TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '',
                addedAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_meeting_materials_meetingTraceId ON meeting_materials(meetingTraceId)")
    }
}

// ── Room 迁移：v8 → v9（决策执行追踪）─────────────────────────

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS decision_actions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                traceId TEXT NOT NULL,
                subject TEXT NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                assignee TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'pending',
                createdAt INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL DEFAULT 0,
                dueDate INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_decision_actions_traceId ON decision_actions(traceId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_decision_actions_status ON decision_actions(status)")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): CommitteeDatabase =
        Room.databaseBuilder(ctx, CommitteeDatabase::class.java, "committee.db")
            .fallbackToDestructiveMigrationFrom(1, 2, 3)
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
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
    @Provides fun provideAppConfigDao(db: CommitteeDatabase): AppConfigDao = db.appConfigDao()
    @Provides fun provideMaterialDao(db: CommitteeDatabase): MeetingMaterialDao = db.materialDao()
    @Provides fun provideDecisionActionDao(db: CommitteeDatabase): DecisionActionDao = db.decisionActionDao()
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

    // ── MeetingPresetConfig ──────────────────────────────────────

    @Provides @Singleton
    fun provideMeetingPresetConfig(dataStore: DataStore<Preferences>): MeetingPresetConfig =
        MeetingPresetConfig(dataStore)

    // ── Agent Runtime（v8 — preset-driven dynamic agents） ────────

    @Provides @Singleton
    fun provideAgentRuntime(
        agentPool: AgentPool,
        apiKeyProvider: DataStoreApiKeyProvider,
        repository: EventRepository,
        evolutionRepo: EvolutionRepository,
        toolRegistry: DynamicToolRegistry,
        presetConfig: MeetingPresetConfig,
        @ApplicationContext appContext: Context,
    ): AgentRuntime {
        val preset = presetConfig.getActivePreset()

        // Find supervisor role (last role or role with id containing "supervisor"/"coordinator"/"judge"/"chair")
        val supervisorRoleIds = setOf("supervisor", "coordinator", "judge", "area_chair")
        val supervisorPresetRole = preset.roles.find { it.id in supervisorRoleIds }
            ?: preset.roles.last()

        val supervisor = GenericSupervisor(
            presetRole = supervisorPresetRole,
            ratingScale = preset.ratingScale,
            committeeLabel = preset.committeeLabel,
        )

        // Build agent list from non-supervisor roles
        val agents = preset.roles
            .filter { it.id != supervisorPresetRole.id }
            .map { role ->
                GenericAgent(
                    presetRole = role,
                    systemPrompt = "", // Will be built dynamically by buildUnifiedPrompt
                    canUseTools = role.canUseTools,
                )
            }

        return AgentRuntime(
            agentPool = agentPool,
            supervisor = supervisor,
            agents = agents,
            configProvider = { apiKeyProvider.getConfig() },
            repository = repository,
            evolutionRepo = evolutionRepo,
            toolRegistry = toolRegistry,
            appContext = appContext,
            preset = preset,
        )
    }

    private val httpLogLevel: HttpLoggingInterceptor.Level
        get() = if (com.znliang.committee.BuildConfig.DEBUG)
            HttpLoggingInterceptor.Level.BODY
        else HttpLoggingInterceptor.Level.BASIC

    @Provides @Singleton @Named("normal")
    fun provideNormalClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor { msg ->
                android.util.Log.d("OkHttp", msg)
            }.apply {
                level = httpLogLevel
            })
            .build()

    @Provides @Singleton @Named("streaming")
    fun provideStreamingClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor { msg ->
                android.util.Log.d("OkHttp", msg)
            }.apply {
                level = httpLogLevel
            })
            .build()

    @Provides @Singleton
    fun provideDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
        ctx.dataStore

    @Provides @Singleton
    fun provideAppConfigRepository(dao: AppConfigDao): AppConfigRepository =
        AppConfigRepository(dao)
}
