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
 * patterned precisely after the official Xiaomi Mi Home design.
 * Features strict horizontal beam-to-port alignment, glowing laser rays, left AC power drop-line,
 * and clean watts-only callouts.
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
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (modeLabel.startsWith("AI", ignoreCase = true)) modeLabel else "AI $modeLabel",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )
            }
        }

        // 2. Hardware Faceplate & Aligned Laser Callouts Area
        // Fixed dimensions ensure 100% mathematical horizontal alignment
        val rowHeight = 34.dp
        val rowSpacing = 12.dp
        val topPadding = 18.dp

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Top
        ) {
            // --- LEFT: Power Drop-Line & Lightning AC Badge (Mi Home replica) ---
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(235.dp)
            ) {
                if (hasActiveCharging) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 1.8.dp.toPx()
                        val glowCyan = Color(0xFF00E5FF).copy(alpha = 0.85f * pulseAlpha)
                        val startY = (topPadding + rowHeight / 2).toPx() // Exactly aligned with C1 port
                        val cornerX = 18.dp.toPx()
                        val endY = 125.dp.toPx()

                        // Horizontal line from charger faceplate left edge towards corner
                        drawLine(
                            color = glowCyan,
                            start = Offset(size.width, startY),
                            end = Offset(cornerX, startY),
                            strokeWidth = stroke
                        )
                        // Vertical line dropping down to the lightning capsule
                        drawLine(
                            color = glowCyan,
                            start = Offset(cornerX, startY),
                            end = Offset(cornerX, endY),
                            strokeWidth = stroke
                        )
                    }

                    // Glowing Vertical Line Capsule
                    Box(
                        modifier = Modifier
                            .offset(x = 16.dp, y = 105.dp)
                            .width(4.dp)
                            .height(26.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF00E5FF), Color(0xFF00E5FF).copy(alpha = 0.3f))
                                )
                            )
                    )

                    // Neon Lightning Badge
                    Box(
                        modifier = Modifier
                            .offset(x = 2.dp, y = 128.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0xFF00E5FF).copy(alpha = 0.45f * pulseAlpha), Color.Transparent)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = Color(0xFF00E5FF).copy(alpha = 0.2f),
                            shape = CircleShape,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = "AC Power Connected",
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // --- CENTER: Physical CUKTECH 10 Ultra Hardware Front Body ---
            Box(
                modifier = Modifier
                    .width(135.dp)
                    .height(235.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF14171E))
                    .border(
                        width = 2.dp,
                        brush = Brush.verticalGradient(listOf(Color(0xFF4A5262), Color(0xFF262A34))),
                        shape = RoundedCornerShape(20.dp)
                    )
            ) {
                // Background Grooved Lines Canvas
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val grooveColor = Color(0xFF1B1F28)
                    val grooveStep = 7.dp.toPx()
                    var y = 16.dp.toPx()
                    while (y < size.height - 36.dp.toPx()) {
                        drawLine(
                            color = grooveColor,
                            start = Offset(10.dp.toPx(), y),
                            end = Offset(size.width - 10.dp.toPx(), y),
                            strokeWidth = 2.dp.toPx()
                        )
                        y += grooveStep
                    }
                }

                // Four Physical Ports (C1, C2, C3, A)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = topPadding, start = 12.dp, end = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(rowSpacing)
                ) {
                    for (portId in 1..4) {
                        val port = portsMap[portId] ?: PortTelemetry(portId, "Port $portId")
                        val isC = portId in 1..3
                        val isCharging = port.powerW > 0.2
                        val label = if (isC) "C$portId" else "A"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeight)
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
                                        .height(12.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (isCharging) Color(0xFF00E5FF).copy(alpha = 0.25f * pulseAlpha) else Color(0xFF0B0D11)
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
                                        .width(46.dp)
                                        .height(16.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            if (isCharging) Color(0xFF00E5FF).copy(alpha = 0.25f * pulseAlpha) else Color(0xFF0B0D11)
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
                                            .width(26.dp)
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(1.dp))
                                            .background(if (isCharging) Color(0xFF00E5FF) else Color(0xFF1E5BB5))
                                    )
                                }
                            }

                            if (isCharging) {
                                // Continuous glowing cyan laser beam shooting out of port slot to charger edge
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(if (isC) 12.dp else 16.dp)
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    Color(0xFF00E5FF).copy(alpha = 0.90f * pulseAlpha),
                                                    Color(0xFF00E5FF).copy(alpha = 0.75f * pulseAlpha)
                                                )
                                            )
                                        )
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
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

            // --- RIGHT: Laser Power Beams & Wattage Callouts (Strictly Row-Aligned) ---
            Column(
                modifier = Modifier
                    .width(115.dp)
                    .height(235.dp)
                    .padding(top = topPadding),
                verticalArrangement = Arrangement.spacedBy(rowSpacing)
            ) {
                for (portId in 1..4) {
                    val port = portsMap[portId] ?: PortTelemetry(portId, "Port $portId")
                    val isCharging = port.powerW > 0.2

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .clickable { onPortClick?.invoke(portId) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isCharging) {
                            // Soft, wide Glowing Laser Light Ray continuing from edge
                            Box(
                                modifier = Modifier
                                    .width(28.dp)
                                    .height(if (portId in 1..3) 12.dp else 16.dp)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                Color(0xFF00E5FF).copy(alpha = 0.75f * pulseAlpha),
                                                Color(0xFF00E5FF).copy(alpha = 0.08f)
                                            )
                                        )
                                    )
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "%.1f".format(port.powerW),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "W",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.padding(bottom = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
