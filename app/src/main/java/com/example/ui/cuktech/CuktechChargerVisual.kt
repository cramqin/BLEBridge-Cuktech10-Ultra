package com.example.ui.cuktech

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.cuktech.protocol.PortTelemetry

/**
 * Authentic replica of the Xiaomi Mi Home CUKTECH 10 Ultra GaN Charger visual.
 * Features official 3D hardware assets, real glowing port beams, accurate
 * port alignments, and clean typography.
 */
@Composable
fun CuktechChargerVisual(
    portsMap: Map<Int, PortTelemetry>,
    modeLabel: String = "AI Mode",
    onPortClick: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "charger_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val hasActiveCharging = portsMap.values.any { it.powerW > 0.2 }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── 1. Top Scenario Pill Badge (Mi Home Style) ──
        Surface(
            color = Color(0xFF13151A),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.7f)),
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AI",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF00E5FF),
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    text = modeLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF00E5FF)
                )
            }
        }

        // ── 2. Charger Body & Power Readout Grid ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            contentAlignment = Alignment.Center
        ) {
            // Subtle Radial Glow behind active charger
            if (hasActiveCharging) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .alpha(0.35f * pulseAlpha)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0xFF00E5FF).copy(alpha = 0.25f), Color.Transparent)
                            ),
                            shape = CircleShape
                        )
                )
            }

            // Left Power Drop Indicator (Drop wire + Lightning Badge)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .width(50.dp)
                    .height(280.dp)
            ) {
                if (hasActiveCharging) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 1.5.dp.toPx()
                        val glowCyan = Color(0xFF00E5FF).copy(alpha = 0.7f * pulseAlpha)
                        // Drop wire from top down to the power badge
                        drawLine(
                            color = glowCyan,
                            start = Offset(size.width - 4.dp.toPx(), 45.dp.toPx()),
                            end = Offset(size.width / 2, 45.dp.toPx()),
                            strokeWidth = stroke
                        )
                        drawLine(
                            color = glowCyan,
                            start = Offset(size.width / 2, 45.dp.toPx()),
                            end = Offset(size.width / 2, 130.dp.toPx()),
                            strokeWidth = stroke
                        )
                    }

                    // Neon Lightning Icon
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0xFF00E5FF).copy(alpha = 0.4f), Color(0xFF0080FF).copy(alpha = 0.1f))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.main_charger_dark_power_on),
                            contentDescription = "Active Charging",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Center 3D Charger Asset
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(160.dp)
                    .height(290.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.main_charger_dark_ad1204_all),
                    contentDescription = "CUKTECH 10 Ultra GaN Charger",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )

                // Glowing Port Halos overlaid precisely on the charger front face
                // Vertical positions relative to the 290dp charger height
                val portYFractions = listOf(
                    1 to 0.28f, // C1
                    2 to 0.38f, // C2
                    3 to 0.48f, // C3
                    4 to 0.60f  // A
                )

                portYFractions.forEach { (portId, fraction) ->
                    val port = portsMap[portId]
                    val isCharging = (port?.powerW ?: 0.0) > 0.2
                    if (isCharging) {
                        val glowDrawable = when (portId) {
                            1 -> R.drawable.main_card_port_c1_on
                            2 -> R.drawable.main_card_port_c2_on
                            3 -> R.drawable.main_card_port_c3_on
                            else -> R.drawable.main_card_port_a_on
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(fraction)
                                .align(Alignment.TopCenter),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Image(
                                painter = painterResource(id = glowDrawable),
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(start = if (portId <= 3) 22.dp else 20.dp)
                                    .size(width = if (portId <= 3) 34.dp else 38.dp, height = 16.dp)
                                    .alpha(pulseAlpha)
                            )
                        }
                    }
                }
            }

            // Right Power Readout Column (Wattage & Soft Laser Beam)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(135.dp)
                    .height(280.dp),
                verticalArrangement = Arrangement.SpaceAround
            ) {
                for (portId in 1..4) {
                    val port = portsMap[portId] ?: PortTelemetry(portId, "Port $portId")
                    val isCharging = port.powerW > 0.2
                    val portName = if (portId in 1..3) "C$portId" else "A"

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPortClick?.invoke(portId) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Soft Horizontal Laser Light Ray
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(if (isCharging) 12.dp else 2.dp)
                                .background(
                                    if (isCharging) {
                                        Brush.horizontalGradient(
                                            listOf(
                                                Color(0xFF00E5FF).copy(alpha = 0.85f * pulseAlpha),
                                                Color(0xFF00E5FF).copy(alpha = 0.15f)
                                            )
                                        )
                                    } else {
                                        Brush.horizontalGradient(
                                            listOf(
                                                Color(0xFF2A303C).copy(alpha = 0.4f),
                                                Color.Transparent
                                            )
                                        )
                                    }
                                )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Wattage Readout
                        Column {
                            if (isCharging) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = "%.1f".format(port.powerW),
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "W",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    )
                                }
                                Text(
                                    text = "$portName · ${"%.1fV".format(port.voltageV)}",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontFamily = FontFamily.Monospace
                                )
                            } else {
                                Text(
                                    text = "-- W",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White.copy(alpha = 0.25f)
                                )
                                Text(
                                    text = portName,
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.22f),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
