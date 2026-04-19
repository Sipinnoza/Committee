package com.znliang.committee.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.znliang.committee.domain.model.AppLanguage


/**
 * @description:
 * @author xiebin04
 * @date 2026/04/19
 * @version
 */

@Entity(tableName = "app_config")
data class AppConfigEntity(
    @PrimaryKey val id: Int = 1,
    val selectedLanguage: String = AppLanguage.SYSTEM.code,
)

@Dao
interface AppConfigDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(config: AppConfigEntity)

    @Query("SELECT * FROM app_config WHERE id = 1")
    suspend fun get(): AppConfigEntity?
}