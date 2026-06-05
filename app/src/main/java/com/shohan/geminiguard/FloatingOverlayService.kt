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
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class FloatingOverlayService : Service() {

    companion object {
        const val NOTIF_ID      = 1001
        const val CHANNEL_ID    = "guard_ch"
        const val ACTION_STOP   = "com.shohan.geminiguard.STOP"
        const val ACTION_TOGGLE = "com.shohan.geminiguard.TOGGLE"

        @Volatile var isRunning     = false
        @Volatile var isGuardActive = false
    }

    private lateinit var wm: WindowManager
    private var floatingView: ImageView? = null
    private var blackView: View?         = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var btnLp: WindowManager.LayoutParams

    private var startX = 0;  private var startY = 0
    private var touchX = 0f; private var touchY = 0f
    private var dragging = false
    private var lastTapTime = 0L

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        btnLp = WindowManager.LayoutParams(
            dp(62), dp(62),
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
        // একবারই button add হবে — multiple icon সমস্যা ঠিক
        if (!isRunning) {
            startForeground(NOTIF_ID, buildNotif())
            addButton()
            isRunning = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cleanup()
        isRunning = false
        isGuardActive = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addButton() {
        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_shield)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = circleBg(Color.parseColor("#6C63FF"))
            elevation  = dp(8).toFloat()
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
                if (!dragging && (abs(dx) > 10 || abs(dy) > 10)) dragging = true
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
                    if (!isGuardActive) {
                        activate()
                    } else {
                        // Double-tap লাগবে deactivate করতে
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 500L) {
                            deactivate()
                            lastTapTime = 0L
                        } else {
                            lastTapTime = now
                        }
                    }
                }
                true
            }
            else -> false
        }
    }

    private fun activate() {
        isGuardActive = true

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GeminiGuard:WakeLock")
        wakeLock?.acquire(2 * 60 * 60 * 1000L)

        floatingView?.let { if (it.isAttachedToWindow) wm.removeView(it) }

        // সম্পূর্ণ স্ক্রিন কালো — status bar এবং nav bar সহ
        val statusBarH = getStatusBarHeight()
        val navBarH    = getNavBarHeight()
        val dm         = resources.displayMetrics

        val bv = View(this).apply {
            setBackgroundColor(Color.BLACK)
            // সব touch consume করবে — কিছুই pass through হবে না
            setOnTouchListener { _, _ -> true }
        }
        blackView = bv

        wm.addView(bv, WindowManager.LayoutParams(
            dm.widthPixels,
            dm.heightPixels + statusBarH + navBarH + dp(20),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = -statusBarH
        })

        // Button overlay-র উপরে — লাল রং
        floatingView?.background = circleBg(Color.parseColor("#E53935"))
        floatingView?.let { wm.addView(it, btnLp) }

        updateNotif()
    }

    private fun deactivate() {
        isGuardActive = false
        lastTapTime = 0L
        wakeLock?.release(); wakeLock = null

        blackView?.let { if (it.isAttachedToWindow) wm.removeView(it) }
        blackView = null

        floatingView?.background = circleBg(Color.parseColor("#6C63FF"))
        updateNotif()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "GeminiGuard", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
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
                if (isGuardActive) "স্ক্রিন কালো · লাল বাটনে Double-tap করুন"
                else "শিল্ড বাটনে ট্যাপ করুন"
            )
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_shield,
                if (isGuardActive) "বন্ধ করুন" else "গার্ড চালু", togglePi)
            .addAction(R.drawable.ic_shield, "সার্ভিস বন্ধ", stopPi)
            .build()
    }

    private fun updateNotif() { nm().notify(NOTIF_ID, buildNotif()) }

    private fun cleanup() {
        wakeLock?.release(); wakeLock = null
        floatingView?.let { if (it.isAttachedToWindow) wm.removeView(it) }
        blackView?.let    { if (it.isAttachedToWindow) wm.removeView(it) }
    }

    private fun getStatusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun getNavBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(48)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun circleBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
}
