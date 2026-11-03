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
import androidx.compose.ui.graphics.StrokeCap
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
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.6f)),
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
                    color = Color(0xFF00B0FF)
                )
            }
        }

        // 2. Hardware Faceplate & Aligned Laser Callouts Area
        // Symmetrical 100.dp - 135.dp - 100.dp layout guarantees exact mathematical center alignment
        val rowHeight = 34.dp
        val rowSpacing = 12.dp
        val topPadding = 18.dp

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Top
        ) {
            // --- LEFT: Power Drop-Line & Lightning AC Badge (Mi Home replica) ---
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(235.dp)
            ) {
                if (hasActiveCharging) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 1.4.dp.toPx()
                        val wireGray = Color(0xFF475569) // Thin dark slate gray wire matching Mi Home
                        val startY = (topPadding + rowHeight / 2).toPx() // Exactly aligned with C1 port
                        val cornerX = size.width - 26.dp.toPx()
                        val endY = 138.dp.toPx()

                        // Horizontal line from charger faceplate left edge towards corner
                        drawLine(
                            color = wireGray,
                            start = Offset(size.width, startY),
                            end = Offset(cornerX, startY),
                            strokeWidth = stroke
                        )
                        // Vertical line dropping down to the lightning icon
                        drawLine(
                            color = wireGray,
                            start = Offset(cornerX, startY),
                            end = Offset(cornerX, endY),
                            strokeWidth = stroke
                        )
                    }

                    // Glowing Vertical Line Capsule Pulse (Mi Home replica)
                    Box(
                        modifier = Modifier
                            .offset(x = 72.dp, y = 78.dp)
                            .width(3.dp)
                            .height(24.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFF00E5FF).copy(alpha = 0.95f * pulseAlpha),
                                        Color(0xFF00E5FF).copy(alpha = 0.25f)
                                    )
                                )
                            )
                    )

                    // Pure Floating Glowing Neon Lightning Bolt (Mi Home replica - no circle badge)
                    Box(
                        modifier = Modifier
                            .offset(x = 56.dp, y = 120.dp)
                            .size(36.dp)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        Color(0xFF00E5FF).copy(alpha = 0.50f * pulseAlpha),
                                        Color(0xFF00E5FF).copy(alpha = 0.15f * pulseAlpha),
                                        Color.Transparent
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "AC Power Connected",
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

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

                // Chrome Metallic Edge Pillars (Mi Home hardware realism)
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF71717A), Color(0xFFD4D4D8), Color(0xFF3F3F46))
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .fillMaxHeight()
                        .align(Alignment.CenterEnd)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF3F3F46), Color(0xFFD4D4D8), Color(0xFF71717A))
                            )
                        )
                )

                // Four Physical Ports (C1, C2, C3, A)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = topPadding),
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
                            // Left Wing: Silkscreen Port Label (Right-aligned next to centered slot)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isCharging) Color(0xFF00E5FF) else Color(0xFF8E99A8)
                                )
                            }

                            // Centered Port Slot Geometry
                            if (isC) {
                                // Type-C Oval Pill Slot (Precisely Centered)
                                Box(
                                    modifier = Modifier
                                        .width(44.dp)
                                        .height(12.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (isCharging) Color(0xFF00E5FF).copy(alpha = 0.35f * pulseAlpha) else Color(0xFF0B0D11)
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
                                            .width(26.dp)
                                            .height(3.5.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(if (isCharging) Color(0xFF00E5FF) else Color(0xFF1E222A))
                                    )
                                }
                            } else {
                                // USB-A Rectangular Slot (Precisely Centered)
                                Box(
                                    modifier = Modifier
                                        .width(48.dp)
                                        .height(16.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            if (isCharging) Color(0xFF00E5FF).copy(alpha = 0.35f * pulseAlpha) else Color(0xFF0B0D11)
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
                                            .height(5.5.dp)
                                            .clip(RoundedCornerShape(1.dp))
                                            .background(if (isCharging) Color(0xFF00E5FF) else Color(0xFF1E5BB5))
                                    )
                                }
                            }

                            // Right Wing: Internal Laser Beam extending from slot to charger edge
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(if (isC) 12.dp else 16.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (isCharging) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        drawRect(
                                            brush = Brush.horizontalGradient(
                                                listOf(
                                                    Color(0xFF00E5FF).copy(alpha = 0.20f * pulseAlpha),
                                                    Color(0xFF00E5FF).copy(alpha = 0.38f * pulseAlpha)
                                                )
                                            )
                                        )
                                    }
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

            // --- RIGHT: Laser Power Beams & Wattage Callouts (Strictly Row-Aligned) ---
            Column(
                modifier = Modifier
                    .width(100.dp)
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
                            // Soft, translucent glowing light beam continuing seamlessly from edge to wattage callout (Mi Home replica)
                            Canvas(
                                modifier = Modifier
                                    .width(32.dp)
                                    .height(14.dp)
                            ) {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        listOf(
                                            Color(0xFF00E5FF).copy(alpha = 0.28f * pulseAlpha),
                                            Color(0xFF00E5FF).copy(alpha = 0.08f * pulseAlpha),
                                            Color.Transparent
                                        )
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "%.1f".format(port.powerW),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "W",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
