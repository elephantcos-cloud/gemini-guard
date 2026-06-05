package com.shohan.geminiguard

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shohan.geminiguard.ui.theme.*

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GeminiGuardTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                MinimalScreen(
                    state       = state,
                    onPowerTap  = {
                        when {
                            !state.hasOverlayPermission -> openOverlay()
                            state.isServiceRunning      -> stopGuard()
                            else                        -> startGuard()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() { super.onResume(); vm.refresh() }

    private fun openOverlay() {
        overlayLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                   Uri.parse("package:$packageName"))
        )
    }

    private fun startGuard() {
        val i = Intent(this, FloatingOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
        vm.refresh()
    }

    private fun stopGuard() {
        startService(Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_STOP
        })
        vm.refresh()
    }
}

@Composable
fun MinimalScreen(state: AppState, onPowerTap: () -> Unit) {
    Box(
        modifier          = Modifier
            .fillMaxSize()
            .background(DarkBg),
        contentAlignment  = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier            = Modifier.fillMaxSize()
        ) {

            Spacer(Modifier.weight(1f))

            // ── App logo — calligraphy শ ──────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFF3A2280), Color(0xFF0C0C1E))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter           = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint              = Color.Unspecified,
                    modifier          = Modifier.size(118.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Status dot ────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            state.isGuardActive   -> GuardGreen
                            state.isServiceRunning -> GuardPurple
                            else                   -> Color(0xFF3A3A55)
                        }
                    )
            )

            Spacer(Modifier.height(28.dp))

            // ── Power / start button ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(82.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !state.hasOverlayPermission -> GuardOrange.copy(alpha = 0.85f)
                            state.isServiceRunning      -> GuardRed
                            else                        -> GuardPurple
                        }
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication        = null
                    ) { onPowerTap() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter           = painterResource(
                        if (!state.hasOverlayPermission) R.drawable.ic_alert
                        else R.drawable.ic_power
                    ),
                    contentDescription = null,
                    tint              = Color.White,
                    modifier          = Modifier.size(36.dp)
                )
            }

            Spacer(Modifier.height(56.dp))
        }
    }
}
