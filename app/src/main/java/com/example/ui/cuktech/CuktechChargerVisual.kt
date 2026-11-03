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
 * Photorealistic 3D visualization of the physical CUKTECH 10 Ultra GaN Charger,
 * faithfully adopting the official Xiaomi Mi Home visual language with realistic
 * hardware textures, cyan glow rings, laser power beams, and status indicator.
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
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val hasActiveCharging = portsMap.values.any { it.powerW > 0.2 }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Floating Scenario Mode Pill on top
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = modeLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )
            }
        }

        // Main Hardware Graphic & Power Callout Area
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Glowing Power Icon and Vertical Drop Trace (as seen in Mi Home)
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(240.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val lineColor = if (hasActiveCharging) {
                        Color(0xFF00E5FF).copy(alpha = 0.7f * pulseAlpha)
                    } else {
                        Color(0xFF38404E).copy(alpha = 0.4f)
                    }
                    // Connecting wire dropped from top of charger
                    drawLine(
                        color = lineColor,
                        start = Offset(size.width - 2.dp.toPx(), 40.dp.toPx()),
                        end = Offset(size.width / 2, 40.dp.toPx()),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawLine(
                        color = lineColor,
                        start = Offset(size.width / 2, 40.dp.toPx()),
                        end = Offset(size.width / 2, 110.dp.toPx()),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }

                // Power Indicator Badge
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (hasActiveCharging) {
                        Image(
                            painter = painterResource(id = R.drawable.main_charger_dark_power_on),
                            contentDescription = "Active Charging",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "⚡",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Center: 3D Rendered CUKTECH 10 Ultra Body
            Box(
                modifier = Modifier
                    .width(136.dp)
                    .height(246.dp)
            ) {
                // Official 3D Hardware Image Asset
                Image(
                    painter = painterResource(id = R.drawable.main_charger_dark_ad1204_all),
                    contentDescription = "CUKTECH 10 Ultra GaN Charger",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )

                // Overlay Glowing Port LEDs
                val portYOffsets = listOf(
                    1 to 0.27f, // C1
                    2 to 0.38f, // C2
                    3 to 0.49f, // C3
                    4 to 0.62f  // A
                )

                portYOffsets.forEach { (portId, yFraction) ->
                    val port = portsMap[portId]
                    val isCharging = (port?.powerW ?: 0.0) > 0.2
                    val isC = portId in 1..3

                    if (isCharging) {
                        val glowRes = when (portId) {
                            1 -> R.drawable.main_card_port_c1_on
                            2 -> R.drawable.main_card_port_c2_on
                            3 -> R.drawable.main_card_port_c3_on
                            else -> R.drawable.main_card_port_a_on
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(yFraction)
                                .align(Alignment.TopCenter),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Image(
                                painter = painterResource(id = glowRes),
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(start = if (isC) 26.dp else 24.dp)
                                    .size(width = if (isC) 36.dp else 42.dp, height = if (isC) 16.dp else 20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Right: Glowing Laser Beams & Real-Time Wattage Callouts
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(246.dp),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                for (portId in 1..4) {
                    val port = portsMap[portId] ?: PortTelemetry(portId, "Port $portId")
                    val isCharging = port.powerW > 0.2
                    val portName = if (portId in 1..3) "C$portId" else "A"

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPortClick?.invoke(portId) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Glowing Laser Beam Trace
                        Box(
                            modifier = Modifier
                                .width(28.dp)
                                .height(if (isCharging) 8.dp else 2.dp)
                                .background(
                                    if (isCharging) {
                                        Brush.horizontalGradient(
                                            listOf(
                                                Color(0xFF00E5FF).copy(alpha = 0.8f * pulseAlpha),
                                                Color(0xFF00E5FF).copy(alpha = 0.2f)
                                            )
                                        )
                                    } else {
                                        Brush.horizontalGradient(
                                            listOf(
                                                Color(0xFF38404E).copy(alpha = 0.3f),
                                                Color.Transparent
                                            )
                                        )
                                    }
                                )
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        // Wattage and Port Specs
                        Column {
                            if (isCharging) {
                                Text(
                                    text = "%.1f W".format(port.powerW),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFF00E5FF),
                                    lineHeight = 18.sp
                                )
                                Text(
                                    text = "$portName · ${"%.1fV".format(port.voltageV)}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else {
                                Text(
                                    text = "-- W",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                )
                                Text(
                                    text = portName,
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
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
