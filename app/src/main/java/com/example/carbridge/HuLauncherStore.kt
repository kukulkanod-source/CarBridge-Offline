package com.example.carbridge

import android.content.Context

/** History of successful HU launches, not a claim that a process is still running. */
object HuLauncherStore {
    fun recent(context: Context): List<String> = context.getSharedPreferences("hu_launcher", 0)
        .getString("recent", "").orEmpty().split('|').filter { it.isNotBlank() }.take(8)

    fun record(context: Context, packageName: String) {
        val packages = (listOf(packageName) + recent(context)).distinct().take(8)
        context.getSharedPreferences("hu_launcher", 0).edit()
            .putString("recent", packages.joinToString("|")).apply()
    }

    fun remove(context: Context, packageName: String) {
        context.getSharedPreferences("hu_launcher", 0).edit()
            .putString("recent", recent(context).filter { it != packageName }.joinToString("|")).apply()
    }
}
