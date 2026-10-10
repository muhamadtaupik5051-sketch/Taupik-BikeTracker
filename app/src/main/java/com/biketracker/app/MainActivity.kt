
package com.biketracker.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val green = Color.rgb(38, 190, 125)
    private val white = Color.WHITE
    private val muted = Color.rgb(170, 180, 190)
    private val dark = Color.rgb(16, 24, 32)
    private val card = Color.rgb(31, 43, 54)

    private lateinit var locationManager: LocationManager
