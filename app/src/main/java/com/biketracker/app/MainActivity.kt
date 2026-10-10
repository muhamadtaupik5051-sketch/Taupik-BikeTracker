
package com.biketracker.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    window.statusBarColor = Color.rgb(7, 28, 46)
    window.navigationBarColor = Color.rgb(7, 28, 46)

    val layout = LinearLayout(this)
    layout.orientation = LinearLayout.VERTICAL
    layout.gravity = Gravity.CENTER
    layout.setPadding(32, 32, 32, 32)
    layout.setBackgroundColor(Color.rgb(7, 28, 46))

    val title = TextView(this)
    title.text = "RideTrack Pro V2"
    title.textSize = 28f
    title.setTextColor(Color.WHITE)
    title.gravity = Gravity.CENTER

    val subtitle = TextView(this)
    subtitle.text = "Bike Tracker"
    subtitle.textSize = 18f
    subtitle.setTextColor(Color.LTGRAY)
    subtitle.gravity = Gravity.CENTER

    layout.addView(title)
    layout.addView(subtitle)

    setContentView(layout)
}

}