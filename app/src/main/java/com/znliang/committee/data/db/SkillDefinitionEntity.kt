package com.znliang.committee.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── 动态 Skill 定义 ──────────────────────────────────────────────

/**
 * 用户在 APP 内创建的 tool/skill 定义。
 *
 * executionType:
 *   "http"  — HTTP 请求模板，executionConfig 里放 url/method/headers/bodyTemplate
 *   "llm"   — 用 LLM 处理，executionConfig 里放 systemPromptTemplate
 *
 * parameters: JSON Schema 格式，如：
 *   {"type":"object","properties":{"query":{"type":"string","description":"搜索关键词"}},"required":["query"]}
 *
 * executionConfig 示例（HTTP）：
 *   {"url":"https://api.tavily.com/search","method":"POST",
 *    "headers":{"Authorization":"Bearer {{TAVILY_API_KEY}}"},
 *    "bodyTemplate":"{\"query\":\"{{query}}\",\"max_results\":5}"}
 *
 * executionConfig 示例（LLM）：
 *   {"systemPromptTemplate":"根据用户提供的股票代码 {{code}} 分析..."}
 */
@Entity(
    tableName = "skill_definitions",
    indices = [Index("name", unique = true)],
)
data class SkillDefinitionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,                  // tool name: "web_search"
    val description: String,           // LLM 看到的描述
    val parameters: String,            // JSON Schema 格式
    val executionType: String,         // "http" | "llm"
    val executionConfig: String,       // JSON: 执行配置（URL模板/header/body模板/prompt模板等）
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface SkillDefinitionDao {
    @Query("SELECT * FROM skill_definitions WHERE enabled = 1 ORDER BY name")
    fun getAllEnabled(): Flow<List<SkillDefinitionEntity>>

    @Query("SELECT * FROM skill_definitions ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<SkillDefinitionEntity>>

    @Query("SELECT * FROM skill_definitions WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SkillDefinitionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SkillDefinitionEntity): Long

    @Query("DELETE FROM skill_definitions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE skill_definitions SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}
