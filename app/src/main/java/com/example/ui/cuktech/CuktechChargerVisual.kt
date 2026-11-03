package com.example.ui.cuktech

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cuktech.protocol.PortTelemetry

/**
 * Authentic visual illustration of the physical CUKTECH 10 Ultra GaN Charger front panel,
 * patterned after the official Xiaomi Mi Home design.
 * Features glowing active port slots, dynamic laser power beams, and bottom LED accent bar.
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
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val hasActiveCharging = portsMap.values.any { it.powerW > 0.2 }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Floating Scenario Mode Pill on top (Mi Home Style)
        Surface(
            color = Color(0xFF13151A),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
            modifier = Modifier.padding(bottom = 14.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
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

        // 2. Main Hardware Body & Power Callout Area
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Glow / AC Power Indicator
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (hasActiveCharging) {
                            Brush.radialGradient(listOf(Color(0xFF00E5FF).copy(alpha = 0.35f * pulseAlpha), Color.Transparent))
                        } else {
                            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent))
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = if (hasActiveCharging) Color(0xFF00E5FF).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = if (hasActiveCharging) Color(0xFF00E5FF) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Physical Charger Front Faceplate (True to CUKTECH 10 Ultra hardware)
            Box(
                modifier = Modifier
                    .width(135.dp)
                    .height(235.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF16181D))
                    .border(2.dp, Brush.verticalGradient(listOf(Color(0xFF4A5260), Color(0xFF282C34))), RoundedCornerShape(18.dp))
            ) {
                // Background Grooved Lines Canvas
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val grooveColor = Color(0xFF1B1F26)
                    val grooveStep = 7.dp.toPx()
                    var y = 20.dp.toPx()
                    while (y < size.height - 40.dp.toPx()) {
                        drawLine(
                            color = grooveColor,
                            start = Offset(12.dp.toPx(), y),
                            end = Offset(size.width - 12.dp.toPx(), y),
                            strokeWidth = 2.dp.toPx()
                        )
                        y += grooveStep
                    }
                }

                // Four Physical Ports (C1, C2, C3, A)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    for (portId in 1..4) {
                        val port = portsMap[portId] ?: PortTelemetry(portId, "Port $portId")
                        val isC = portId in 1..3
                        val isCharging = port.powerW > 0.2
                        val label = if (isC) "C$portId" else "A"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .clickable { onPortClick?.invoke(portId) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (isCharging) Color(0xFF00E5FF) else Color(0xFF8E99A8),
                                modifier = Modifier.width(22.dp)
                            )

                            // Port Slot Geometry
                            if (isC) {
                                // Type-C Oval Pill Slot
                                Box(
                                    modifier = Modifier
                                        .width(42.dp)
                                        .height(11.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (isCharging) Color(0xFF00E5FF).copy(alpha = 0.25f * pulseAlpha) else Color(0xFF0D0F12)
                                        )
                                        .border(
                                            width = if (isCharging) 1.5.dp else 1.dp,
                                            color = if (isCharging) Color(0xFF00E5FF).copy(alpha = pulseAlpha) else Color(0xFF2C323D),
                                            shape = RoundedCornerShape(6.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(24.dp)
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(if (isCharging) Color(0xFF00E5FF) else Color(0xFF1E222A))
                                    )
                                }
                            } else {
                                // USB-A Rectangular Slot
                                Box(
                                    modifier = Modifier
                                        .width(48.dp)
                                        .height(17.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            if (isCharging) Color(0xFF00E5FF).copy(alpha = 0.25f * pulseAlpha) else Color(0xFF0D0F12)
                                        )
                                        .border(
                                            width = if (isCharging) 1.5.dp else 1.dp,
                                            color = if (isCharging) Color(0xFF00E5FF).copy(alpha = pulseAlpha) else Color(0xFF2C323D),
                                            shape = RoundedCornerShape(3.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Internal Blue Tongue
                                    Box(
                                        modifier = Modifier
                                            .width(28.dp)
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(1.dp))
                                            .background(if (isCharging) Color(0xFF00E5FF) else Color(0xFF1E5BB5))
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom Section: Signature Glowing Cyan LED Bar & CUKTECH Brand Chin
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    // Glowing LED Bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.5.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color(0xFF00E5FF).copy(alpha = 0.2f),
                                        Color(0xFF00E5FF).copy(alpha = if (hasActiveCharging) 0.95f * pulseAlpha else 0.4f),
                                        Color(0xFF00E5FF).copy(alpha = 0.2f)
                                    )
                                )
                            )
                    )

                    // Silver Embossed Chin
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF2E333D), Color(0xFF1E2229))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "CUKTECH",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                            color = Color(0xFFA6B0C0)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Right Power Callout Beams (Watts per Port)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(235.dp),
                verticalArrangement = Arrangement.SpaceAround
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
                        // Glowing Beam Trace
                        Box(
                            modifier = Modifier
                                .width(20.dp)
                                .height(if (isCharging) 3.dp else 1.5.dp)
                                .background(
                                    if (isCharging) {
                                        Brush.horizontalGradient(
                                            listOf(
                                                Color(0xFF00E5FF).copy(alpha = pulseAlpha),
                                                Color(0xFF00E5FF).copy(alpha = 0.2f)
                                            )
                                        )
                                    } else {
                                        Brush.horizontalGradient(
                                            listOf(
                                                Color(0xFF38404E).copy(alpha = 0.4f),
                                                Color.Transparent
                                            )
                                        )
                                    }
                                )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        if (isCharging) {
                            Column {
                                Text(
                                    text = "%.1f W".format(port.powerW),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF00E5FF)
                                )
                                Text(
                                    text = "$portName · ${"%.1fV".format(port.voltageV)} · ${"%.2fA".format(port.currentA)}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        } else {
                            Column {
                                Text(
                                    text = "-- W",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Normal,
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
