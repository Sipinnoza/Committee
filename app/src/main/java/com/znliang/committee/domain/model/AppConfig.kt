package com.znliang.committee.domain.model

import com.znliang.committee.R

/**
 * @description:
 * @author xiebin04
 * @date 2026/04/19
 * @version
 */


enum class AppLanguage(val code: String, val displayNameRes: Int) {
    SYSTEM("auto", R.string.lang_system),
    CHINESE("zh", R.string.lang_chinese),
    ENGLISH("en", R.string.lang_english),
}

data class AppConfig(
    val selectedLanguage: AppLanguage = AppLanguage.SYSTEM, // 用户选择的语言
)
