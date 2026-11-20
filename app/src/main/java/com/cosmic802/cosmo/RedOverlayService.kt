package com.cosmic802.cosmo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager

class RedOverlayService : Service() {

    companion object {
        private const val PREFS = "cosmo_overlay"
        private const val K_INTENSITY = "intensity"
        private const val DEFAULT_INTENSITY = 0.45f
        private const val MAX_ALPHA = 0.85f
        private const val CHANNEL_ID = "cosmo_overlay"
        private const val NOTIF_ID = 802
        private const val ACTION_STOP = "com.cosmic802.cosmo.OVERLAY_STOP"

        @Volatile
        private var instance: RedOverlayService? = null

        fun isRunning(): Boolean = instance != null

        fun getIntensity(context: Context): Float =
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(K_INTENSITY, DEFAULT_INTENSITY)

        fun setIntensity(context: Context, value: Float) {
            val v = value.coerceIn(0f, 1f)
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putFloat(K_INTENSITY, v)
                .apply()
            val svc = instance ?: return
            Handler(Looper.getMainLooper()).post { svc.applyIntensity(v) }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (overlayView == null) {
            addOverlay()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cosmo Red Filter", NotificationManager.IMPORTANCE_LOW)
        )

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, RedOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Cosmo")
            .setContentText("Red filter active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, "Stop", stopIntent).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun addOverlay() {
        val view = View(this)
        view.setBackgroundColor(colorFor(getIntensity(this)))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        try {
            windowManager?.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun colorFor(intensity: Float): Int {
        val alpha = (intensity.coerceIn(0f, 1f) * MAX_ALPHA * 255f).toInt()
        return Color.argb(alpha, 210, 0, 0)
    }

    private fun applyIntensity(value: Float) {
        overlayView?.setBackgroundColor(colorFor(value))
    }

    override fun onDestroy() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        instance = null
        super.onDestroy()
    }
}
