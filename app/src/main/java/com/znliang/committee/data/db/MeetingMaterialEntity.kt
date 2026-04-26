package com.znliang.committee.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 会议附件材料（图片/文件），作为多模态证据注入Agent讨论。
 */
@Entity(
    tableName = "meeting_materials",
    indices = [Index("meetingTraceId")],
)
data class MeetingMaterialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,          // 自增主键
    val meetingTraceId: String,                                  // 关联的会议追踪ID
    val fileName: String,                                        // 文件名
    val mimeType: String,          // image/jpeg, image/png, application/pdf
    val localPath: String,         // 本地文件路径
    val base64Cache: String = "",  // 压缩后的base64（首次使用时lazy生成）
    val description: String = "",  // 用户描述 / OCR文字
    val addedAt: Long = System.currentTimeMillis(),             // 添加时间戳
)

@Dao
interface MeetingMaterialDao {
    @Query("SELECT * FROM meeting_materials WHERE meetingTraceId = :traceId ORDER BY addedAt")
    suspend fun getByTraceId(traceId: String): List<MeetingMaterialEntity>

    @Query("SELECT * FROM meeting_materials WHERE meetingTraceId = :traceId ORDER BY addedAt")
    fun observeByTraceId(traceId: String): Flow<List<MeetingMaterialEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MeetingMaterialEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<MeetingMaterialEntity>)

    @Query("UPDATE meeting_materials SET base64Cache = :base64 WHERE id = :id")
    suspend fun updateBase64Cache(id: Long, base64: String)

    @Query("DELETE FROM meeting_materials WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM meeting_materials WHERE meetingTraceId = :traceId")
    suspend fun deleteByTraceId(traceId: String)
}
