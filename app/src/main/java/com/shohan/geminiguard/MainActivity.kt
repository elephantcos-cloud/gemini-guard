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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                MainScreen(
                    state          = state,
                    onGrantPerm    = ::openOverlaySettings,
                    onStartService = ::startGuard,
                    onStopService  = ::stopGuard
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    private fun openOverlaySettings() {
        overlayLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun startGuard() {
        val intent = Intent(this, FloatingOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        vm.refresh()
    }

    private fun stopGuard() {
        startService(Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_STOP
        })
        vm.refresh()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MainScreen(
    state: AppState,
    onGrantPerm: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(52.dp))

        // App icon
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(GuardPurple.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter            = painterResource(R.drawable.ic_shield),
                contentDescription = null,
                tint               = GuardPurple,
                modifier           = Modifier.size(54.dp)
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            "GeminiGuard",
            fontSize   = 30.sp,
            fontWeight = FontWeight.Bold,
            color      = Color.White
        )
        Text(
            "স্ক্রিন বন্ধেও Gemini Audio চলবে",
            fontSize = 14.sp,
            color    = Color(0xFF888888),
            modifier = Modifier.padding(top = 5.dp)
        )

        Spacer(Modifier.height(34.dp))

        // Permission card (only if not granted)
        if (!state.hasOverlayPermission) {
            PermissionCard(onGrantPerm)
            Spacer(Modifier.height(14.dp))
        }

        // Status card
        StatusCard(state)
        Spacer(Modifier.height(14.dp))

        // Main action button
        Button(
            onClick  = if (state.isServiceRunning) onStopService else onStartService,
            enabled  = state.hasOverlayPermission,
            colors   = ButtonDefaults.buttonColors(
                containerColor        = if (state.isServiceRunning) GuardRed else GuardPurple,
                disabledContainerColor = Color(0xFF333345)
            ),
            shape    = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Text(
                if (state.isServiceRunning) "সার্ভিস বন্ধ করুন" else "সার্ভিস চালু করুন",
                fontWeight = FontWeight.SemiBold,
                fontSize   = 16.sp
            )
        }

        Spacer(Modifier.height(22.dp))
        HowToCard()
        Spacer(Modifier.height(14.dp))
        RealmeTipCard()
        Spacer(Modifier.height(36.dp))
    }
}

@Composable
fun PermissionCard(onGrant: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("অনুমতি দরকার", fontWeight = FontWeight.Bold, color = GuardRed)
            Spacer(Modifier.height(6.dp))
            Text(
                "অ্যাপটিকে অন্য অ্যাপের উপর দেখানোর অনুমতি দিন",
                color = Color(0xFF999999), fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick  = onGrant,
                colors   = ButtonDefaults.buttonColors(containerColor = GuardPurple),
                shape    = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("অনুমতি দিন") }
        }
    }
}

@Composable
fun StatusCard(state: AppState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            state.isGuardActive   -> GuardGreen
                            state.isServiceRunning -> GuardPurple
                            else                   -> Color(0xFF555566)
                        }
                    )
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    when {
                        state.isGuardActive   -> "গার্ড সক্রিয়"
                        state.isServiceRunning -> "সার্ভিস চলছে"
                        else                   -> "সার্ভিস বন্ধ"
                    },
                    color      = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 15.sp
                )
                Text(
                    when {
                        state.isGuardActive   -> "স্ক্রিন কালো · Audio চলছে"
                        state.isServiceRunning -> "ভাসমান শিল্ড বাটন দেখুন"
                        else                   -> "উপরের বাটন দিয়ে শুরু করুন"
                    },
                    color    = Color(0xFF777788),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun HowToCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "কীভাবে ব্যবহার করবেন",
                fontWeight = FontWeight.Bold,
                color      = GuardPurple,
                modifier   = Modifier.padding(bottom = 12.dp)
            )
            val steps = listOf(
                "সার্ভিস চালু করুন",
                "Gemini খুলুন, Speaker বাটনে ট্যাপ করুন",
                "ভাসমান বেগুনি শিল্ড বাটনে ট্যাপ করুন",
                "স্ক্রিন কালো হবে কিন্তু Audio চলতে থাকবে!",
                "থামাতে: লাল বাটন বা Notification ট্যাপ করুন"
            )
            steps.forEachIndexed { i, step ->
                Row(
                    Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier            = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(GuardPurple.copy(alpha = 0.22f)),
                        contentAlignment    = Alignment.Center
                    ) {
                        Text(
                            "${i + 1}",
                            fontSize   = 10.sp,
                            color      = GuardPurple,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        step,
                        color    = Color(0xFFCCCCDD),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun RealmeTipCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1C1A2A))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Realme-তে বাড়তি সেটিং (গুরুত্বপূর্ণ)",
                fontWeight = FontWeight.Bold,
                color      = GuardOrange,
                fontSize   = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Settings > Battery > Battery Optimization > All apps > GeminiGuard > Don't optimize",
                color    = Color(0xFF999999),
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Settings > Battery > Battery Optimization > All apps > Gemini > Don't optimize",
                color    = Color(0xFF999999),
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}
