package com.example.carbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** Uses fixes already requested by another app; never starts a separate GPS request. Main thread only. */
internal object HuNavigationMotion {
    private val main = Handler(Looper.getMainLooper())
    private val observers = linkedSetOf<() -> Unit>()
    private var app: Context? = null
    private var manager: LocationManager? = null
    private var allowed = false
    private var listening = false
    private var fixAt = 0L
    private var speed = 0f
    private var moving = false
    private var movingSamples = 0
    private var stoppedSamples = 0
    private var stationarySamples = 0
    fun isStopped(): Boolean = allowed && listening && stationarySamples>=2 &&
        SystemClock.elapsedRealtime()-fixAt in 0L..4000L
    fun enabled(context: Context) = context.getSharedPreferences("navigation_motion",0).getBoolean("enabled",false)
    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("navigation_motion",0).edit().putBoolean("enabled",value).apply()
    }
    fun permitted(context: Context) = ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun setSessionAllowed(context: Context, value: Boolean) {
        app=context.applicationContext; allowed=value; sync()
    }
    fun observe(context: Context, observer: () -> Unit) {
        app=context.applicationContext; observers.add(observer); sync()
    }
    fun remove(observer: () -> Unit) { observers.remove(observer); sync() }
    fun metersPerSecond(): Float {
        if (!allowed || !listening || SystemClock.elapsedRealtime()-fixAt !in 0L..4000L) return 0f
        return speed
    }
    private fun notifyObservers() { observers.toList().forEach { it() } }
    private fun clear() {
        fixAt=0; speed=0f; moving=false; movingSamples=0; stoppedSamples=0; stationarySamples=0
    }
    private val watchdog=object: Runnable {
        override fun run() {
            if (!listening) return
            val context=app ?: return
            if (!permitted(context) || !enabled(context)) { sync(); return }
            if (fixAt>0L && SystemClock.elapsedRealtime()-fixAt>4000L) {
                clear(); notifyObservers()
            }
            main.postDelayed(this,1000L)
        }
    }
    private val listener=object: LocationListener {
        override fun onLocationChanged(location: Location) {
            val age=(SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos)/1_000_000L
            val valid=age in 0L..4000L && location.hasSpeed() && location.speed.isFinite() &&
                location.speed in 0f..100f && location.hasAccuracy() && location.accuracy<=35f &&
                (!location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond<=1.5f)
            if (!valid) { clear(); notifyObservers(); return }
            val sampleAt=location.elapsedRealtimeNanos/1_000_000L
            if (sampleAt<=fixAt) return
            if (sampleAt-fixAt>4000L) clear()
            fixAt=sampleAt
            val value=location.speed
            stationarySamples=if(value<=0.7f) (stationarySamples+1).coerceAtMost(2) else 0
            if (!moving) {
                movingSamples=if (value>=1.4f) movingSamples+1 else 0
                if (movingSamples>=2) { moving=true; stoppedSamples=0 }
            } else {
                stoppedSamples=if (value<=0.7f) stoppedSamples+1 else 0
                if (stoppedSamples>=2) { moving=false; movingSamples=0 }
            }
            speed=if (moving) value else 0f
            notifyObservers()
        }
        override fun onProviderDisabled(provider: String) { clear(); notifyObservers() }
        override fun onProviderEnabled(provider: String) = Unit
    }
    private fun sync() {
        val context=app ?: return
        val wanted=allowed && observers.isNotEmpty() && enabled(context) && permitted(context)
        if (wanted && !listening) {
            val service=context.getSystemService(LocationManager::class.java)
            runCatching {
                service.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,1000L,0f,listener,Looper.getMainLooper())
            }.onSuccess {
                manager=service; listening=true; main.postDelayed(watchdog,1000L)
                SessionDiagnostics.event("Navigation road speed: passive location observer active; coordinates not recorded")
            }.onFailure { SessionDiagnostics.event("Navigation road speed unavailable: ${it.javaClass.simpleName}") }
        } else if (!wanted && listening) {
            runCatching { manager?.removeUpdates(listener) }
            manager=null; listening=false; main.removeCallbacks(watchdog); clear()
        }
        if (!wanted) clear()
        notifyObservers()
    }
}
