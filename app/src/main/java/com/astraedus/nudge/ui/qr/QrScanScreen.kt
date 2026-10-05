package com.astraedus.nudge.ui.qr

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Viewfinder side as a fraction of screen width, capped so it never crowds the header. */
private const val VIEWFINDER_WIDTH_FRACTION = 0.72f
private const val VIEWFINDER_MAX_HEIGHT_FRACTION = 0.42f
/** Vertical centre of the viewfinder, a little above the middle where a hand-held code sits. */
private const val VIEWFINDER_CENTER_Y_FRACTION = 0.46f
private val VIEWFINDER_CORNER = 28.dp
private val SCRIM = Color.Black.copy(alpha = 0.62f)
private val ON_CAMERA = Color.White
private val ON_CAMERA_MUTED = Color.White.copy(alpha = 0.78f)

/**
 * The scanner's UI. Stateless: [QrScanActivity] owns the state and the camera; this draws the
 * preview (once [state] is SCANNING), the viewfinder, and the controls for every other state.
 */
@Composable
internal fun QrScanScreen(
    state: ScanScreenState,
    title: String,
    subtitle: String?,
    torchAvailable: Boolean,
    torchOn: Boolean,
    onPreviewCreated: (PreviewView) -> Unit,
    onToggleTorch: () -> Unit,
    onAllowCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val side = minOf(maxWidth * VIEWFINDER_WIDTH_FRACTION, maxHeight * VIEWFINDER_MAX_HEIGHT_FRACTION)
        val windowTop = maxHeight * VIEWFINDER_CENTER_Y_FRACTION - side / 2

        if (state == ScanScreenState.SCANNING) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PreviewView(context).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }.also(onPreviewCreated)
                }
            )
            Viewfinder(side = side, top = windowTop)
            Text(
                text = "Hold the code inside the frame. It scans by itself.",
                style = MaterialTheme.typography.bodyMedium,
                color = ON_CAMERA_MUTED,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = windowTop + side + 20.dp)
                    .padding(horizontal = 32.dp)
            )
        } else {
            PermissionPanel(
                state = state,
                onAllowCamera = onAllowCamera,
                onOpenSettings = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp)
            )
        }

        Header(
            title = title,
            subtitle = subtitle,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 24.dp)
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = ON_CAMERA, style = MaterialTheme.typography.titleMedium)
            }
            if (state == ScanScreenState.SCANNING && torchAvailable) {
                TorchButton(torchOn = torchOn, onToggle = onToggleTorch)
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = ON_CAMERA,
            textAlign = TextAlign.Center
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = ON_CAMERA_MUTED,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun TorchButton(torchOn: Boolean, onToggle: () -> Unit) {
    val label = if (torchOn) "Turn flashlight off" else "Turn flashlight on"
    FilledTonalIconButton(
        onClick = onToggle,
        modifier = Modifier
            .size(56.dp)
            .semantics { contentDescription = label },
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (torchOn) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.16f),
            contentColor = if (torchOn) MaterialTheme.colorScheme.onPrimary else ON_CAMERA
        )
    ) {
        Icon(
            imageVector = if (torchOn) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
            contentDescription = null
        )
    }
}

/**
 * Scrim with a rounded window cut out of it, corner brackets, and a slow sweep line: the frame
 * tells the user where to hold the code, the sweep tells them it is live. The decode itself reads
 * the WHOLE frame, so a code half outside the window still scans.
 */
@Composable
private fun Viewfinder(side: Dp, top: Dp) {
    val accent = MaterialTheme.colorScheme.primary
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "sweepY"
    )
    val density = LocalDensity.current
    Canvas(modifier = Modifier.fillMaxSize()) {
        val sidePx = with(density) { side.toPx() }
        val window = Rect(
            offset = Offset((size.width - sidePx) / 2f, with(density) { top.toPx() }),
            size = Size(sidePx, sidePx)
        )
        val corner = with(density) { VIEWFINDER_CORNER.toPx() }

        val scrim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(RoundRect(window, CornerRadius(corner)))
        }
        drawPath(scrim, SCRIM)

        val stroke = with(density) { 4.dp.toPx() }
        val arm = sidePx * 0.16f
        val bracket = Path().apply {
            // Each corner: down the side, round the corner, along the edge.
            moveTo(window.left, window.top + arm)
            lineTo(window.left, window.top + corner)
            quadraticTo(window.left, window.top, window.left + corner, window.top)
            lineTo(window.left + arm, window.top)

            moveTo(window.right - arm, window.top)
            lineTo(window.right - corner, window.top)
            quadraticTo(window.right, window.top, window.right, window.top + corner)
            lineTo(window.right, window.top + arm)

            moveTo(window.right, window.bottom - arm)
            lineTo(window.right, window.bottom - corner)
            quadraticTo(window.right, window.bottom, window.right - corner, window.bottom)
            lineTo(window.right - arm, window.bottom)

            moveTo(window.left + arm, window.bottom)
            lineTo(window.left + corner, window.bottom)
            quadraticTo(window.left, window.bottom, window.left, window.bottom - corner)
            lineTo(window.left, window.bottom - arm)
        }
        drawPath(bracket, accent, style = Stroke(width = stroke, cap = StrokeCap.Round))

        val lineY = window.top + window.height * sweep
        val inset = corner * 0.6f
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, accent.copy(alpha = 0.9f), Color.Transparent),
                startX = window.left + inset,
                endX = window.right - inset
            ),
            start = Offset(window.left + inset, lineY),
            end = Offset(window.right - inset, lineY),
            strokeWidth = with(density) { 2.dp.toPx() },
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun PermissionPanel(
    state: ScanScreenState,
    onAllowCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val blocked = state == ScanScreenState.BLOCKED
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PanelIcon(if (blocked) Icons.Outlined.NoPhotography else Icons.Outlined.PhotoCamera)
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (blocked) "Camera access is off" else "HikaruFocus needs the camera to scan",
            style = MaterialTheme.typography.titleLarge,
            color = ON_CAMERA,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (blocked) {
                "Android won't ask again. Allow Camera for HikaruFocus in Settings, then come back here."
            } else {
                "It's used only to read the code. Nothing is photographed, saved or sent anywhere."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = ON_CAMERA_MUTED,
            textAlign = TextAlign.Center
        )
        when (state) {
            ScanScreenState.RATIONALE -> {
                Spacer(Modifier.height(24.dp))
                Button(onClick = onAllowCamera) { Text("Allow camera") }
            }
            ScanScreenState.BLOCKED -> {
                Spacer(Modifier.height(24.dp))
                Button(onClick = onOpenSettings) { Text("Open settings") }
            }
            else -> Unit
        }
    }
}

@Composable
private fun PanelIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = ON_CAMERA, modifier = Modifier.size(32.dp))
    }
}
