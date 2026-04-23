package com.znliang.committee.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 决策执行追踪 — Action Item 实体
 *
 * 每次会议结束后可生成若干 action items，追踪决策的执行落地情况。
 */
@Entity(
    tableName = "decision_actions",
    indices = [
        Index(value = ["traceId"]),
        Index(value = ["status"]),
    ]
)
data class DecisionActionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val traceId: String,           // 关联的会议 traceId
    val subject: String,           // 会议主题
    val title: String,             // action item 标题
    val description: String = "",  // 详细描述
    val assignee: String = "",     // 负责人/负责角色
    val status: String = "pending", // pending | in_progress | done | overdue
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val dueDate: Long? = null,     // 截止日期（可选）
)

@Dao
interface DecisionActionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(action: DecisionActionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(actions: List<DecisionActionEntity>)

    @Update
    suspend fun update(action: DecisionActionEntity)

    @Query("SELECT * FROM decision_actions WHERE traceId = :traceId ORDER BY createdAt")
    suspend fun getByTrace(traceId: String): List<DecisionActionEntity>

    @Query("SELECT * FROM decision_actions WHERE traceId = :traceId ORDER BY createdAt")
    fun observeByTrace(traceId: String): Flow<List<DecisionActionEntity>>

    @Query("SELECT * FROM decision_actions WHERE status IN ('pending', 'in_progress') ORDER BY createdAt DESC")
    fun observePending(): Flow<List<DecisionActionEntity>>

    @Query("SELECT * FROM decision_actions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DecisionActionEntity>>

    @Query("SELECT COUNT(*) FROM decision_actions WHERE status IN ('pending', 'in_progress')")
    fun countPending(): Flow<Int>

    @Query("UPDATE decision_actions SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM decision_actions WHERE id = :id")
    suspend fun delete(id: Long)
}
