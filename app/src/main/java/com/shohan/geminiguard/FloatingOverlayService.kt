package com.shohan.geminiguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class FloatingOverlayService : Service() {

    companion object {
        const val NOTIF_ID       = 1001
        const val CHANNEL_ID     = "guard_ch"
        const val ACTION_STOP    = "com.shohan.geminiguard.STOP"
        const val ACTION_TOGGLE  = "com.shohan.geminiguard.TOGGLE"

        @Volatile var isRunning     = false
        @Volatile var isGuardActive = false
    }

    private lateinit var wm: WindowManager

    private var floatingView: ImageView? = null
    private var blackView: View?         = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Button layout params — stored as field so position is remembered across activate/deactivate
    private lateinit var btnLp: WindowManager.LayoutParams

    // Touch tracking
    private var startX = 0;  private var startY = 0
    private var touchX = 0f; private var touchY = 0f
    private var dragging = false

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        btnLp = WindowManager.LayoutParams(
            dp(58), dp(58),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(200)
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cleanup(); stopSelf(); return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                if (isGuardActive) deactivate() else activate()
                return START_STICKY
            }
        }
        // Normal start
        startForeground(NOTIF_ID, buildNotif())
        addButton()
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        cleanup()
        isRunning = false
        isGuardActive = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────────────────────────────────
    // Floating button
    // ──────────────────────────────────────────────────────────────────────────

    private fun addButton() {
        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_shield)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = circleBg(Color.parseColor("#6C63FF"))
            elevation  = dp(6).toFloat()
        }
        iv.setOnTouchListener { v, ev -> handleTouch(v, ev) }
        floatingView = iv
        wm.addView(iv, btnLp)
    }

    private fun handleTouch(v: View, ev: MotionEvent): Boolean {
        return when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = btnLp.x; startY = btnLp.y
                touchX = ev.rawX; touchY = ev.rawY
                dragging = false; true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - touchX).toInt()
                val dy = (ev.rawY - touchY).toInt()
                if (!dragging && (abs(dx) > 8 || abs(dy) > 8)) dragging = true
                if (dragging) {
                    btnLp.x = startX + dx
                    btnLp.y = startY + dy
                    if (floatingView?.isAttachedToWindow == true)
                        wm.updateViewLayout(floatingView, btnLp)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    if (isGuardActive) deactivate() else activate()
                }
                true
            }
            else -> false
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Guard ON
    // ──────────────────────────────────────────────────────────────────────────

    private fun activate() {
        isGuardActive = true

        // Keep CPU running while screen appears off
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GeminiGuard:WakeLock"
        )
        wakeLock?.acquire(2 * 60 * 60 * 1000L)   // 2 hours max

        // Remove button temporarily so overlay goes below it
        floatingView?.let { if (it.isAttachedToWindow) wm.removeView(it) }

        // Full-screen opaque black overlay — FLAG_KEEP_SCREEN_ON keeps screen
        // technically ON so Gemini's TTS continues; visually the screen looks OFF
        val bv = View(this).apply { setBackgroundColor(Color.BLACK) }
        blackView = bv
        wm.addView(
            bv,
            WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE
            )
        )

        // Re-add button on top of overlay with red tint (stop indicator)
        floatingView?.apply {
            background = circleBg(Color.parseColor("#E53935"))
        }
        floatingView?.let { wm.addView(it, btnLp) }

        updateNotif()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Guard OFF
    // ──────────────────────────────────────────────────────────────────────────

    private fun deactivate() {
        isGuardActive = false

        wakeLock?.release()
        wakeLock = null

        // Remove black overlay — screen visible again
        blackView?.let { if (it.isAttachedToWindow) wm.removeView(it) }
        blackView = null

        // Restore button to purple
        floatingView?.background = circleBg(Color.parseColor("#6C63FF"))

        updateNotif()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Notification
    // ──────────────────────────────────────────────────────────────────────────

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            "GeminiGuard",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        nm().createNotificationChannel(ch)
    }

    private fun buildNotif(): Notification {
        val togglePi = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingOverlayService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, FloatingOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(if (isGuardActive) "গার্ড সক্রিয়" else "GeminiGuard প্রস্তুত")
            .setContentText(
                if (isGuardActive)
                    "স্ক্রিন কালো · Gemini Audio চলছে"
                else
                    "শিল্ড বাটনে ট্যাপ করুন"
            )
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                R.drawable.ic_shield,
                if (isGuardActive) "বন্ধ করুন" else "গার্ড চালু",
                togglePi
            )
            .addAction(R.drawable.ic_shield, "সার্ভিস বন্ধ", stopPi)
            .build()
    }

    private fun updateNotif() {
        nm().notify(NOTIF_ID, buildNotif())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ──────────────────────────────────────────────────────────────────────────

    private fun cleanup() {
        wakeLock?.release(); wakeLock = null
        floatingView?.let { if (it.isAttachedToWindow) wm.removeView(it) }
        blackView?.let    { if (it.isAttachedToWindow) wm.removeView(it) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun circleBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun nm() =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
}
