package com.shohan.geminiguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.KeyEvent
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
    private lateinit var btnLp: WindowManager.LayoutParams

    private var floatingView: ImageView? = null
    private var blackView: View?         = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    // Touch tracking
    private var startX = 0;  private var startY = 0
    private var touchX = 0f; private var touchY = 0f
    private var dragging = false

    // Triple-tap tracking
    private var tapCount    = 0
    private var firstTapTime = 0L

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        btnLp = WindowManager.LayoutParams(
            dp(64), dp(64),
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
            ACTION_STOP -> { cleanup(); stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE -> {
                if (isGuardActive) deactivate() else activate()
                return START_STICKY
            }
        }
        // Start once only — prevents duplicate floating buttons
        if (!isRunning) {
            startForeground(NOTIF_ID, buildNotif())
            addButton()
            isRunning = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cleanup()
        isRunning     = false
        isGuardActive = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Floating button
    // ─────────────────────────────────────────────────────────────────────────

    private fun addButton() {
        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_shohan)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = circleBg(Color.parseColor("#6C63FF"))
            elevation   = dp(8).toFloat()
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
                        // Single tap → activate (screen goes black)
                        activate()
                    } else {
                        // Triple-tap → stop service entirely
                        val now = System.currentTimeMillis()
                        if (now - firstTapTime > 600L) {
                            tapCount    = 1
                            firstTapTime = now
                        } else {
                            tapCount++
                        }
                        if (tapCount >= 3) {
                            tapCount = 0
                            deactivate()
                            handler.postDelayed({ cleanup(); stopSelf() }, 100)
                        }
                    }
                }
                true
            }
            else -> false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Guard ON — full black screen, block back button
    // ─────────────────────────────────────────────────────────────────────────

    private fun activate() {
        isGuardActive = true
        tapCount      = 0

        // Wake lock keeps CPU running
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nijhum:WL")
        wakeLock?.acquire(2 * 60 * 60 * 1000L)

        // Remove button temporarily
        floatingView?.let { if (it.isAttachedToWindow) wm.removeView(it) }

        val statusH = statusBarHeight()
        val dm      = resources.displayMetrics

        // Black overlay — focusable so it captures Back key, not touchable so
        // touches are still blocked via setOnTouchListener
        val bv = object : View(this@FloatingOverlayService) {
            override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = true
            override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean   = true
        }.apply {
            setBackgroundColor(Color.BLACK)
            isFocusable          = true
            isFocusableInTouchMode = true
            setOnTouchListener { _, _ -> true }   // Block every touch
        }
        blackView = bv

        wm.addView(bv, WindowManager.LayoutParams(
            dm.widthPixels,
            dm.heightPixels + statusH + dp(48),   // Extra height to fill nav bar
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = -statusH
        })

        // Focus request so Back key is intercepted
        bv.post { bv.requestFocus() }

        // Re-add button on top — red tint (triple-tap hint)
        floatingView?.background = circleBg(Color.parseColor("#C62828"))
        floatingView?.let { wm.addView(it, btnLp) }

        updateNotif()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Guard OFF
    // ─────────────────────────────────────────────────────────────────────────

    private fun deactivate() {
        isGuardActive = false
        tapCount      = 0
        wakeLock?.release(); wakeLock = null

        blackView?.let { if (it.isAttachedToWindow) wm.removeView(it) }
        blackView = null

        floatingView?.background = circleBg(Color.parseColor("#6C63FF"))
        updateNotif()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "নিঝুম", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm().createNotificationChannel(ch)
    }

    private fun buildNotif(): Notification {
        val togglePi = PendingIntent.getService(this, 1,
            Intent(this, FloatingOverlayService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 2,
            Intent(this, FloatingOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shohan)
            .setContentTitle(if (isGuardActive) "নিঝুম — সক্রিয়" else "নিঝুম — প্রস্তুত")
            .setContentText(
                if (isGuardActive) "৩ বার ট্যাপে বন্ধ করুন"
                else "শিল্ড বাটনে ট্যাপ করুন")
            .setOngoing(true).setSilent(true)
            .addAction(R.drawable.ic_shohan,
                if (isGuardActive) "বন্ধ" else "চালু", togglePi)
            .addAction(R.drawable.ic_shohan, "সার্ভিস বন্ধ", stopPi)
            .build()
    }

    private fun updateNotif() { nm().notify(NOTIF_ID, buildNotif()) }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun cleanup() {
        wakeLock?.release(); wakeLock = null
        floatingView?.let { if (it.isAttachedToWindow) wm.removeView(it) }
        blackView?.let    { if (it.isAttachedToWindow) wm.removeView(it) }
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(28)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun circleBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
}
