package com.biketracker.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager

class AppTheme(val name: String, val dark: Boolean, c: List<String>) {
    val bg = Color.parseColor(c[0])
    val surface = Color.parseColor(c[1])
    val surface2 = Color.parseColor(c[2])
    val accent = Color.parseColor(c[3])
    val text = Color.parseColor(c[4])
    val muted = Color.parseColor(c[5])
    val good = Color.parseColor(c[6])
    val bad = Color.parseColor(c[7])
}

fun onColor(c: Int): Int {
    val l = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255
    return if (l > 0.6) Color.rgb(16, 24, 40) else Color.WHITE
}

object Themes {
    val all = listOf(
        AppTheme("Neon Gelap", true, listOf("#050F22", "#0C1E3A", "#112A4D", "#00B8FF", "#F5F9FF", "#97AECA", "#27D282", "#F44C58")),
        AppTheme("Terang Bersih", false, listOf("#F3F6FB", "#FFFFFF", "#E3EAF4", "#0B7BEA", "#101828", "#667085", "#12A15F", "#E5384A")),
        AppTheme("Sporty Oranye", true, listOf("#120B08", "#221510", "#33201A", "#FF6B1A", "#FFF4EC", "#C9A58F", "#2ED47A", "#FF3D3D")),
        AppTheme("Hutan Hijau", true, listOf("#07140E", "#0F2419", "#173A28", "#34D399", "#ECFDF5", "#8DB8A4", "#A3E635", "#F87171")),
        AppTheme("AMOLED Ungu", true, listOf("#000000", "#121212", "#1F1F1F", "#A78BFA", "#FFFFFF", "#A1A1AA", "#34D399", "#FB7185")),
        AppTheme("Pink Pastel", false, listOf("#FFF5F8", "#FFFFFF", "#FCE4EC", "#E91E63", "#2B1B22", "#8A6F7A", "#16A34A", "#DC2626"))
    )

    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun index(c: Context) = p(c).getInt("theme", 0).coerceIn(0, all.size - 1)
    fun current(c: Context) = all[index(c)]
    fun save(c: Context, i: Int) = p(c).edit().putInt("theme", i).apply()
    fun keepOn(c: Context) = p(c).getBoolean("keep_on", true)
    fun setKeepOn(c: Context, v: Boolean) = p(c).edit().putBoolean("keep_on", v).apply()

    @Suppress("DEPRECATION")
    fun styleWindow(a: Activity, t: AppTheme) {
        a.window.statusBarColor = t.bg
        a.window.navigationBarColor = t.bg
        var f = 0
        if (!t.dark) {
            f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        a.window.decorView.systemUiVisibility = f
        if (keepOn(a)) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
