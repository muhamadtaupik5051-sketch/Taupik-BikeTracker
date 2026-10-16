package com.biketracker.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Satu baris riwayat. Format: tanggal|mode|km|durasiMs|rata2|maks|target|rute|kkal|tanjakan|bergerakMs|detikPerContoh|contoh
 *  target: km (Sepeda/Lari) atau langkah (Jalan; data lama Jalan memakai km). */
internal class Rec(val f: List<String>) {
    val date get() = f[0]