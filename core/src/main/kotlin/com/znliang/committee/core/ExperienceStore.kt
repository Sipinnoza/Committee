package com.znliang.committee.core

/**
 * Abstraction for experience/evolution persistence.
 * Room implementation lives in :data module.
 */
interface ExperienceStore {
    suspend fun loadExperiences(roleId: String, limit: Int = 10): List<Experience>
    suspend fun saveExperience(experience: Experience)
}

data class Experience(
    val roleId: String,
    val subject: String,
    val lesson: String,
    val priority: Int,
    val timestamp: Long = System.currentTimeMillis(),
)
