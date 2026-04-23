package com.znliang.committee.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        EventEntity::class,
        MeetingSessionEntity::class,
        SpeechEntity::class,
        AgentChatMessageEntity::class,
        // v5: 自我进化相关表
        AgentEvolutionEntity::class,
        AgentSkillEntity::class,
        PromptChangelogEntity::class,
        MeetingOutcomeEntity::class,
        // v6: 动态 skill 定义
        SkillDefinitionEntity::class,
        // v7: app 设置
        AppConfigEntity::class,
        // v8: 多模态会议材料
        MeetingMaterialEntity::class,
        // v9: 决策执行追踪
        DecisionActionEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class CommitteeDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun sessionDao(): MeetingSessionDao
    abstract fun speechDao(): SpeechDao
    abstract fun agentChatDao(): AgentChatDao

    // v5: 进化相关 DAO
    abstract fun evolutionDao(): AgentEvolutionDao
    abstract fun skillDao(): AgentSkillDao
    abstract fun changelogDao(): PromptChangelogDao
    abstract fun outcomeDao(): MeetingOutcomeDao

    // v6: 动态 skill DAO
    abstract fun skillDefinitionDao(): SkillDefinitionDao

    // v7: App设置 Dao
    abstract fun appConfigDao(): AppConfigDao

    // v8: 多模态材料 Dao
    abstract fun materialDao(): MeetingMaterialDao

    // v9: 决策执行追踪 Dao
    abstract fun decisionActionDao(): DecisionActionDao
}
