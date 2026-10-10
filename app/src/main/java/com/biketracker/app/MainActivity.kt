package com.biketracker.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val navy = Color.rgb(7, 28, 46)
    private val green = Color.rgb(0, 200, 120)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = navy
        window.navigationBarColor = navy

        showHome()
    }

    private fun showHome() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 32, 24, 32)
            setBackgroundColor(navy)
        }

        val title = TextView(this).apply {
            text = "Taupik_BikeTracker"
            textSize = 27f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "GPS BIKE TRACKER"
            textSize = 15f
            setTextColor(green)
            gravity = Gravity.CENTER
        }

        layout.addView(title)
        layout.addView(subtitle)

        addMenu(layout, "🚴  MULAI BERSEPEDA", "Sepeda")
        addMenu(layout, "🏃  MULAI BERLARI", "Lari")
        addMenu(layout, "🚶  MULAI BERJALAN", "Jalan")

        val note = TextView(this).apply {
            text = "GPS akan digunakan saat aktivitas dimulai"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(8, 24, 8, 8)
        }

        layout.addView(note)
        setContentView(layout)
    }

    private fun addMenu(
        layout: LinearLayout,
        label: String,
        activityName: String
    ) {
        val button = TextView(this).apply {
            text = label
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(12, 20, 12, 20)
            setBackgroundColor(Color.rgb(20, 70, 85))
            isClickable = true
            isFocusable = true

            setOnClickListener {
                showMessage(activityName)
            }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 14, 0, 0)
        layout.addView(button, params)
    }

    private fun showMessage(activityName: String) {
        android.widget.Toast.makeText(
            this,
            "$activityName dipilih. Fitur GPS belum diaktifkan.",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}