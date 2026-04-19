package com.znliang.committee.data.repository

import com.znliang.committee.data.db.AppConfigDao
import com.znliang.committee.data.db.AppConfigEntity
import com.znliang.committee.domain.model.AppConfig
import com.znliang.committee.domain.model.AppLanguage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @description:
 * @author xiebin04
 * @date 2026/04/19
 * @version
 */
@Singleton
class AppConfigRepository @Inject constructor(
    private val dao: AppConfigDao
) {
    suspend fun getConfig(): AppConfig {
        val entity = dao.get()
        val language = entity?.selectedLanguage
            ?.let { code -> AppLanguage.entries.find { it.code == code } }
            ?: AppLanguage.SYSTEM
        return AppConfig(selectedLanguage = language)
    }

    suspend fun saveLanguage(language: AppLanguage) {
        dao.save(AppConfigEntity(selectedLanguage = language.code))
    }
}