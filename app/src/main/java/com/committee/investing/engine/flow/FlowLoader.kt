package com.committee.investing.engine.flow

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Flow 配置加载器
 *
 * 加载优先级（与 prompt 一致）：
 *   1) filesDir/flows/{name}.json — 用户自定义，可随时替换
 *   2) assets/flows/{name}.json  — 随 APK 分发
 *   3) 内置 default_flow.json
 */
object FlowLoader {

    private const val TAG = "FlowLoader"
    private const val DIR_NAME = "flows"

    /** 加载指定名字的 flow，null 返回 default */
    fun load(context: Context, name: String = "default_flow"): FlowDefinition {
        val json = loadJson(context, name)
        val flow = FlowParser.parse(json)

        val errors = FlowParser.validate(flow)
        if (errors.isNotEmpty()) {
            Log.e(TAG, "[Flow] 验证失败: $errors")
            throw IllegalArgumentException("Flow 配置验证失败: $errors")
        }

        Log.e(TAG, "[Flow] 加载成功: ${flow.name} (${flow.states.size} states, ${flow.transitions.size} transitions, ${flow.phases.size} phases)")
        return flow
    }

    private fun loadJson(context: Context, name: String): String {
        // 1) filesDir — 用户自定义
        val localFile = File(context.filesDir, "$DIR_NAME/${removeExt(name)}.json")
        if (localFile.exists()) {
            val text = localFile.readText()
            if (text.isNotBlank()) {
                Log.e(TAG, "[Flow] 从本地文件加载: ${localFile.absolutePath}")
                return text
            }
        }

        // 2) assets
        val assetPath = "$DIR_NAME/${removeExt(name)}.json"
        try {
            val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            if (text.isNotBlank()) {
                Log.e(TAG, "[Flow] 从 assets/$assetPath 加载")
                return text
            }
        } catch (_: Exception) {}

        throw IllegalStateException("找不到 flow 配置: $name")
    }

    /** 列出所有可用的 flow 配置名 */
    fun listAvailable(context: Context): List<String> {
        val names = mutableSetOf<String>()

        // assets
        try {
            val assets = context.assets.list(DIR_NAME) ?: emptyArray()
            assets.mapTo(names) { it.removeSuffix(".json") }
        } catch (_: Exception) {}

        // filesDir
        val dir = File(context.filesDir, DIR_NAME)
        if (dir.exists()) {
            dir.listFiles()?.mapTo(names) { it.name.removeSuffix(".json") }
        }

        return names.sorted()
    }

    /** 保存用户自定义 flow */
    fun save(context: Context, name: String, json: String) {
        val dir = File(context.filesDir, DIR_NAME)
        dir.mkdirs()
        File(dir, "${removeExt(name)}.json").writeText(json)
    }

    /** 删除用户自定义 flow，回退到 assets */
    fun delete(context: Context, name: String) {
        val file = File(context.filesDir, "$DIR_NAME/${removeExt(name)}.json")
        if (file.exists()) file.delete()
    }

    private fun removeExt(name: String): String = name.removeSuffix(".json")
}
