package com.example.twopchat.ui.main

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

enum class NetworkNodeState {
    OK, WARNING, ERROR, DISABLED
}

enum class RadarNode(val labelRu: String, val labelEn: String, val angleDegrees: Float) {
    SELF("Я", "Self", 0f),
    ROUTER("Роутер / UPnP", "Router / UPnP", 225f), // -135 degrees
    TRACKERS("Трекеры", "Trackers", 315f),       // -45 degrees
    YGGDRASIL("Yggdrasil", "Yggdrasil", 180f),
    PEERS("Активные Пиры", "Active Peers", 90f)
}

@Composable
fun NetworkRadarWidget(
    upnpStatus: NetworkNodeState,
    trackerStatus: NetworkNodeState,
    yggStatus: NetworkNodeState,
    peersCount: Int,
    onNodeClicked: (RadarNode) -> Unit,
    modifier: Modifier = Modifier
) {
    // Rotating scanning beam infinite animation
    val animationsEnabled = com.example.twopchat.LocalAppAnimationsEnabled.current
    val infiniteTransition = if (animationsEnabled) rememberInfiniteTransition(label = "RadarSweep") else null
    val sweepAngle = infiniteTransition?.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SweepAngle"
    )?.value ?: 0f

    // Pulsing halo animation for active nodes
    val pulseScale = infiniteTransition?.animateFloat(
        initialValue = 0f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )?.value ?: 0f

    // Running packet transmission animation
    val packetProgress = infiniteTransition?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PacketProgress"
    )?.value ?: 0f

    val primaryColor = MaterialTheme.colorScheme.primary
    val gridColor = primaryColor.copy(alpha = 0.15f)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // Keep track of coordinates mapping helper
    fun getNodeOffset(node: RadarNode, center: Offset, maxRadius: Float): Offset {
        return when (node) {
            RadarNode.SELF -> center
            RadarNode.ROUTER -> {
                val angleRad = Math.toRadians(RadarNode.ROUTER.angleDegrees.toDouble())
                val r = maxRadius * 0.42f
                Offset(center.x + (r * cos(angleRad)).toFloat(), center.y + (r * sin(angleRad)).toFloat())
            }
            RadarNode.YGGDRASIL -> {
                val angleRad = Math.toRadians(RadarNode.YGGDRASIL.angleDegrees.toDouble())
                val r = maxRadius * 0.70f
                Offset(center.x + (r * cos(angleRad)).toFloat(), center.y + (r * sin(angleRad)).toFloat())
            }
            RadarNode.TRACKERS -> {
                val angleRad = Math.toRadians(RadarNode.TRACKERS.angleDegrees.toDouble())
                val r = maxRadius * 0.70f
                Offset(center.x + (r * cos(angleRad)).toFloat(), center.y + (r * sin(angleRad)).toFloat())
            }
            RadarNode.PEERS -> {
                val angleRad = Math.toRadians(RadarNode.PEERS.angleDegrees.toDouble())
                val r = maxRadius * 0.82f
                Offset(center.x + (r * cos(angleRad)).toFloat(), center.y + (r * sin(angleRad)).toFloat())
            }
        }
    }

    Box(
        modifier = modifier
            .size(290.dp)
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { tapOffset ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val maxRadius = minOf(size.width, size.height) / 2f - with(density) { 22.dp.toPx() }
                        
                        var closestNode: RadarNode? = null
                        var minDistance = Float.MAX_VALUE
                        val clickThreshold = with(density) { 32.dp.toPx() } // Friendly hit target size

                        for (node in RadarNode.values()) {
                            val nodePos = getNodeOffset(node, center, maxRadius)
                            val distance = Math.hypot(
                                (tapOffset.x - nodePos.x).toDouble(),
                                (tapOffset.y - nodePos.y).toDouble()
                            ).toFloat()

                            if (distance < clickThreshold && distance < minDistance) {
                                closestNode = node
                                minDistance = distance
                            }
                        }

                        closestNode?.let {
                            onNodeClicked(it)
                        }
                    }
                }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            // Reserve room for node halos, labels and the outer stroke. The old
            // width-only radius made the scan arc an oval in a weighted layout
            // and clipped it against the Canvas bounds.
            val maxRadius = minOf(size.width, size.height) / 2f - 22.dp.toPx()
            val radarTopLeft = Offset(center.x - maxRadius, center.y - maxRadius)
            val radarSize = Size(maxRadius * 2f, maxRadius * 2f)

            // 1. Draw Concentric Grid Rings (Concentric circles with different styles)
            val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            
            // Inner solid ring
            drawCircle(color = gridColor, radius = maxRadius * 0.42f, style = Stroke(width = 1.dp.toPx()))
            
            // Middle dashed ring (adds hi-tech sci-fi texture)
            drawCircle(
                color = gridColor,
                radius = maxRadius * 0.70f,
                style = Stroke(width = 1.dp.toPx(), pathEffect = dashedEffect)
            )
            
            // Outer solid ring
            drawCircle(color = gridColor, radius = maxRadius, style = Stroke(width = 1.2f.dp.toPx()))

            // 2. Draw Ticks / Degree Markings on Outer Edge
            for (angle in 0 until 360 step 15) {
                val angleRad = Math.toRadians(angle.toDouble())
                val isMajor = angle % 90 == 0
                val tickLength = if (isMajor) 8.dp.toPx() else 4.dp.toPx()
                val alpha = if (isMajor) 0.35f else 0.15f
                
                val start = Offset(
                    center.x + ((maxRadius - tickLength) * cos(angleRad)).toFloat(),
                    center.y + ((maxRadius - tickLength) * sin(angleRad)).toFloat()
                )
                val end = Offset(
                    center.x + (maxRadius * cos(angleRad)).toFloat(),
                    center.y + (maxRadius * sin(angleRad)).toFloat()
                )
                drawLine(color = primaryColor.copy(alpha = alpha), start = start, end = end, strokeWidth = 1.dp.toPx())
            }

            // 3. Draw Axial lines with crosshair markings
            drawLine(color = gridColor, start = Offset(center.x, 0f), end = Offset(center.x, size.height), strokeWidth = 1.dp.toPx())
            drawLine(color = gridColor, start = Offset(0f, center.y), end = Offset(size.width, center.y), strokeWidth = 1.dp.toPx())

            // 4. Draw Relational connection lines (faint green lines connecting center to nodes)
            fun drawLinkLine(node: RadarNode, state: NetworkNodeState) {
                if (state == NetworkNodeState.DISABLED) return
                val nodeOffset = getNodeOffset(node, center, maxRadius)
                val color = when (state) {
                    NetworkNodeState.OK -> Color(0xFF00C853)
                    NetworkNodeState.WARNING -> Color(0xFFFFB300)
                    NetworkNodeState.ERROR -> Color(0xFFFF5252)
                    NetworkNodeState.DISABLED -> Color.Gray
                }
                
                // Draw connecting line
                drawLine(
                    color = color.copy(alpha = 0.08f),
                    start = center,
                    end = nodeOffset,
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = dashedEffect
                )

                // Draw moving transmission packet dot
                val px = center.x + (nodeOffset.x - center.x) * packetProgress
                val py = center.y + (nodeOffset.y - center.y) * packetProgress
                drawCircle(
                    color = color.copy(alpha = 0.65f),
                    radius = 2.5.dp.toPx(),
                    center = Offset(px, py)
                )
            }
            
            drawLinkLine(RadarNode.ROUTER, upnpStatus)
            drawLinkLine(RadarNode.TRACKERS, trackerStatus)
            drawLinkLine(RadarNode.YGGDRASIL, yggStatus)
            val peersState = if (peersCount > 0) NetworkNodeState.OK else NetworkNodeState.WARNING
            drawLinkLine(RadarNode.PEERS, peersState)

            // 5. Draw a trailing scan sector and its leading edge in one coordinate
            // system. Rotating a sweep-gradient brush and an arc together caused the
            // gradient and the line to drift visually on some GPU renderers.
            val trailDegrees = 70f
            val trailSegments = 28
            for (segment in 0 until trailSegments) {
                val progress = (segment + 1f) / trailSegments
                drawArc(
                    color = primaryColor.copy(alpha = 0.22f * progress),
                    startAngle = sweepAngle - trailDegrees + segment * (trailDegrees / trailSegments),
                    sweepAngle = trailDegrees / trailSegments + 0.35f,
                    useCenter = true,
                    topLeft = radarTopLeft,
                    size = radarSize
                )
            }
            val leadAngleRad = Math.toRadians(sweepAngle.toDouble())
            val leadEdgeEnd = Offset(
                center.x + (maxRadius * cos(leadAngleRad)).toFloat(),
                center.y + (maxRadius * sin(leadAngleRad)).toFloat()
            )
            drawLine(
                color = primaryColor.copy(alpha = 0.8f),
                start = center,
                end = leadEdgeEnd,
                strokeWidth = 2.dp.toPx()
            )

            // 6. Draw Radar Nodes & Ping Wave Ripple Effect
            fun drawNode(node: RadarNode, state: NetworkNodeState, codeLabel: String) {
                val nodeOffset = getNodeOffset(node, center, maxRadius)
                val color = when (state) {
                    NetworkNodeState.OK -> Color(0xFF00C853)      // Vivid Neon Green
                    NetworkNodeState.WARNING -> Color(0xFFFFB300) // Soft Vivid Amber
                    NetworkNodeState.ERROR -> Color(0xFFFF5252)   // Vibrant Red
                    NetworkNodeState.DISABLED -> Color(0xFF424242)
                }

                // Interactive Ping Wave Ripple triggered by sweep pass
                val diff = (sweepAngle - node.angleDegrees + 360f) % 360f
                if (state != NetworkNodeState.DISABLED && diff < 65f) {
                    val waveProgress = diff / 65f
                    drawCircle(
                        color = color.copy(alpha = 0.4f * (1f - waveProgress)),
                        radius = 8.dp.toPx() + (waveProgress * 28.dp.toPx()),
                        center = nodeOffset,
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                }

                // Outer pulsing shadow glow
                if (state != NetworkNodeState.DISABLED) {
                    drawCircle(
                        color = color.copy(alpha = 0.18f),
                        radius = 8.dp.toPx() + pulseScale,
                        center = nodeOffset
                    )
                }

                // Core node circle
                drawCircle(color = color, radius = 6.dp.toPx(), center = nodeOffset)
                
                // Core ring highlight
                drawCircle(
                    color = Color.White.copy(alpha = 0.8f),
                    radius = 8.dp.toPx(),
                    center = nodeOffset,
                    style = Stroke(width = 1.dp.toPx())
                )

                // Monospace HUD labels for nodes (gives tactical tech aesthetic)
                val textLayoutResult = textMeasurer.measure(
                    text = codeLabel,
                    style = TextStyle(
                        color = if (state == NetworkNodeState.DISABLED) Color.Gray else color.copy(alpha = 0.8f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
                val textWidth = textLayoutResult.size.width
                val textHeight = textLayoutResult.size.height
                
                // Position label slightly offset from the node
                val labelX = nodeOffset.x + 12.dp.toPx()
                val labelY = nodeOffset.y - (textHeight / 2)
                
                drawText(
                    textMeasurer = textMeasurer,
                    text = codeLabel,
                    topLeft = Offset(labelX, labelY),
                    style = TextStyle(
                        color = if (state == NetworkNodeState.DISABLED) Color.Gray else color.copy(alpha = 0.7f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }

            // Render Router node
            drawNode(RadarNode.ROUTER, upnpStatus, "RTR / UPnP")

            // Render Trackers node
            drawNode(RadarNode.TRACKERS, trackerStatus, "TRK")

            // Render Yggdrasil node
            drawNode(RadarNode.YGGDRASIL, yggStatus, "YGG")

            // Render Peers node
            drawNode(RadarNode.PEERS, peersState, "PRS: $peersCount")

            // 7. Draw Central Node (SELF) with complex multi-layered target sights
            drawCircle(color = Color.White, radius = 6.dp.toPx(), center = center)
            drawCircle(
                color = primaryColor.copy(alpha = 0.8f),
                radius = 11.dp.toPx(),
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
            // Outer dashed tactical circle
            drawCircle(
                color = primaryColor.copy(alpha = 0.4f),
                radius = 17.dp.toPx(),
                center = center,
                style = Stroke(width = 1.dp.toPx(), pathEffect = dashedEffect)
            )
        }
    }
}
