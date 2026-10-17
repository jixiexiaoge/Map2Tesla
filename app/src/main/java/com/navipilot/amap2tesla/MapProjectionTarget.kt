package com.navipilot.amap2tesla

import android.content.Context

enum class MapProjectionTarget(
    val displayName: String,
    val packageName: String,
    val officialDownloadUrl: String,
) {
    AMAP_AUTO(
        "高德车机版",
        "com.autonavi.amapauto",
        "https://auto.amap.com/",
    ),
    TENCENT_AUTO(
        "腾讯车机版",
        "com.tencent.wecarnavi",
        "https://tai.qq.com/navi/pages/index/index.html",
    ),
    BAIDU_AUTO(
        "百度车机版",
        "com.baidu.naviauto",
        "https://mapauto.baidu.com/download",
    ),
    ;

    companion object {
        private const val PREFS = "map_projection_target"
        private const val KEY_TARGET = "target"

        fun load(context: Context): MapProjectionTarget {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TARGET, AMAP_AUTO.name)
            return entries.firstOrNull { it.name == saved } ?: AMAP_AUTO
        }

        fun save(context: Context, target: MapProjectionTarget) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TARGET, target.name)
                .apply()
        }
    }
}
