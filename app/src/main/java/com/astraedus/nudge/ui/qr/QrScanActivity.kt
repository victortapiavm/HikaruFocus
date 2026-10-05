package com.astraedus.nudge.ui.qr

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.astraedus.nudge.ui.theme.NudgeTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Full-screen camera scanner behind [ScanQrContract]. Not exported: only Nudge launches it.
 *
 * Owns only the android half: the permission request, binding CameraX to this activity's
 * lifecycle, the torch, and returning the result. The permission decisions are
 * [CameraPermissionFlow], the decode is [BarcodeFrameDecoder], "deliver once / confirm 1D reads" is
 * [ScanConfirmer]; all three are JVM-tested.
 *
 * Back is left to the system default (finish, RESULT_CANCELED), which is exactly "cancel -> null".
 * Nothing is photographed or stored: frames are decoded in memory and dropped.
 */
class QrScanActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TITLE = "com.astraedus.nudge.qr.TITLE"
        const val EXTRA_SUBTITLE = "com.astraedus.nudge.qr.SUBTITLE"
        const val EXTRA_RESULT = "com.astraedus.nudge.qr.RESULT"
        private const val STATE_KEY = "scan_screen_state"

        /** Enough pixels for a small EAN-13 at arm's length, few enough to decode several a second. */
        private val ANALYSIS_TARGET = Size(1280, 720)
    }

    private var screenState by mutableStateOf(ScanScreenState.REQUESTING)
    private var torchAvailable by mutableStateOf(false)
    private var torchOn by mutableStateOf(false)

    private var camera: Camera? = null
    private var analysisExecutor: ExecutorService? = null
    private var finished = false

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            moveTo(CameraPermissionFlow.afterRequest(granted, shouldShowCameraRationale()))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Debug builds only: bench QA's stand-in for the camera. Always null in release.
        DebugScanOverride.read()?.let { fake ->
            finishWith(fake)
            return
        }

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)

        val restored = savedInstanceState?.getString(STATE_KEY)
            ?.let { name -> ScanScreenState.entries.firstOrNull { it.name == name } }
        if (restored != null) {
            // A permission request that was in flight is re-delivered by the result registry, so a
            // restored REQUESTING must not launch a second one.
            moveTo(restored)
        } else {
            moveTo(CameraPermissionFlow.initial(hasCameraPermission(), shouldShowCameraRationale()))
            if (screenState == ScanScreenState.REQUESTING) requestCamera()
        }

        setContent {
            NudgeTheme(darkTheme = true) {
                QrScanScreen(
                    state = screenState,
                    title = title,
                    subtitle = subtitle,
                    torchAvailable = torchAvailable,
                    torchOn = torchOn,
                    onPreviewCreated = ::bindCamera,
                    onToggleTorch = { camera?.cameraControl?.enableTorch(!torchOn) },
                    onAllowCamera = ::requestCamera,
                    onOpenSettings = ::openAppSettings,
                    onCancel = { finishWith(null) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Settings with the camera now allowed: start scanning without another tap.
        moveTo(CameraPermissionFlow.onResume(screenState, hasCameraPermission()))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_KEY, screenState.name)
    }

    override fun onDestroy() {
        // CameraX unbinds itself when this lifecycle is destroyed; the executor is ours to stop.
        analysisExecutor?.shutdown()
        analysisExecutor = null
        super.onDestroy()
    }

    private fun moveTo(next: ScanScreenState) {
        screenState = next
        if (next == ScanScreenState.CANCELLED) finishWith(null)
    }

    private fun requestCamera() {
        screenState = ScanScreenState.REQUESTING
        permissionRequest.launch(Manifest.permission.CAMERA)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun shouldShowCameraRationale(): Boolean =
        shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)

    /** Called once, when the SCANNING state first puts a [PreviewView] on screen. */
    private fun bindCamera(previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (finished || isDestroyed) return@addListener
            val provider = try {
                providerFuture.get()
            } catch (e: Exception) {
                cameraUnavailable()
                return@addListener
            }
            val selector = pickCamera(provider) ?: run {
                cameraUnavailable()
                return@addListener
            }

            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val executor = analysisExecutor ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                ANALYSIS_TARGET,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                // Decode the newest frame, never a queue of stale ones.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, QrFrameAnalyzer { text -> runOnUiThread { deliver(text) } })

            try {
                provider.unbindAll()
                val bound = provider.bindToLifecycle(this, selector, preview, analysis)
                camera = bound
                torchAvailable = bound.cameraInfo.hasFlashUnit()
                bound.cameraInfo.torchState.observe(this) { torchOn = it == TorchState.ON }
            } catch (e: RuntimeException) {
                // IllegalArgument / IllegalState: the camera exists but cannot take this use case
                // set right now (e.g. another app holds it).
                cameraUnavailable()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun pickCamera(provider: ProcessCameraProvider): CameraSelector? =
        listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
            .firstOrNull { selector -> runCatching { provider.hasCamera(selector) }.getOrDefault(false) }

    private fun cameraUnavailable() {
        Toast.makeText(this, "Couldn't open the camera", Toast.LENGTH_SHORT).show()
        finishWith(null)
    }

    private fun deliver(text: String) {
        if (finished) return
        window.decorView.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
        )
        finishWith(text)
    }

    private fun finishWith(result: String?) {
        if (finished) return
        finished = true
        if (result != null) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, result))
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Open Settings > Apps > HikaruFocus > Permissions", Toast.LENGTH_LONG).show()
        }
    }
}
