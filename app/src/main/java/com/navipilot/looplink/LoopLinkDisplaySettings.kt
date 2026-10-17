package com.navipilot.looplink

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig

enum class LoopLinkDisplayProfile(val label: String, val width: Int, val height: Int) {
    TESLA("Tesla 5:4", 1120, 896),
    WIDE("16:9", 1280, 720),
    SQUARE("1:1", 1024, 1024);

    fun display() = AirPlayDisplayConfig(widthPixels = width, heightPixels = height, fps = 30)
}

object LoopLinkDisplaySettings {
    fun load(context: Context): LoopLinkDisplayProfile {
        val value = context.getSharedPreferences("usb_browser", Context.MODE_PRIVATE)
            .getString("display_profile", LoopLinkDisplayProfile.TESLA.name)
        return LoopLinkDisplayProfile.entries.firstOrNull { it.name == value }
            ?: LoopLinkDisplayProfile.TESLA.also {
                Log.w("LoopLink", "Invalid saved USB display profile; restoring Tesla profile")
                save(context, it)
            }
    }

    fun save(context: Context, profile: LoopLinkDisplayProfile) {
        context.getSharedPreferences("usb_browser", Context.MODE_PRIVATE)
            .edit().putString("display_profile", profile.name).apply()
    }
}
