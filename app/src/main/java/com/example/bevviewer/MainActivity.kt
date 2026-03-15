package com.example.bevviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BevApp() }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BevApp() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var bevBitmap  by remember { mutableStateOf<Bitmap?>(null) }
    var gtBitmap   by remember { mutableStateOf<Bitmap?>(null) }
    var showingGt  by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Ready") }
    var timingText by remember { mutableStateOf("") }
    var isLoading  by remember { mutableStateOf(false) }

    fun loadImages(): Map<String, Bitmap> {
        val result = mutableMapOf<String, Bitmap>()
        for (cam in listOf("front", "left", "rear", "right")) {
            context.assets.open("bev_images/$cam/0.png").use { s ->
                result[cam] = BitmapFactory.decodeStream(s)
                    ?: throw Exception("Failed to decode $cam image")
            }
        }
        return result
    }

    fun cropGroundTruth(gt: Bitmap, zoomFactor: Float = 1.5f): Bitmap {
        val targetW = BevProcessor.BEV_W
        val targetH = BevProcessor.BEV_H

        val srcW = gt.width
        val srcH = gt.height

        val cropW = (targetW / zoomFactor).toInt().coerceAtMost(srcW)
        val cropH = (targetH / zoomFactor).toInt().coerceAtMost(srcH)
        val offsetX = -25
        val offsetY = -10
        val cropX = ((srcW - cropW) / 2 + offsetX).coerceIn(0, srcW - cropW)
        val cropY = ((srcH - cropH) / 2 + offsetY).coerceIn(0, srcH - cropH)

        val cropped = Bitmap.createBitmap(gt, cropX, cropY, cropW, cropH)
        return Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
    }

    fun loadGt(): Bitmap? = try {
        context.assets.open("bev_images/bev/0.png").use {
            val raw = BitmapFactory.decodeStream(it)
            raw?.let { cropGroundTruth(it, zoomFactor = 1.5f) }
        }
    } catch (_: Exception) { null }

    fun computeBev() {
        isLoading  = true
        showingGt  = false
        statusText = "Loading fisheye images..."
        timingText = ""
        scope.launch {
            try {
                val (bev, gt, dt) = withContext(Dispatchers.Default) {
                    val imgs = loadImages()
                    val t0   = System.currentTimeMillis()
                    val bev  = BevProcessor.buildBev(imgs)
                    Triple(bev, loadGt(), System.currentTimeMillis() - t0)
                }
                bevBitmap  = bev
                gtBitmap   = gt
                statusText = "✓  ${BevProcessor.BEV_W}×${BevProcessor.BEV_H} px  " +
                        "· ${(BevProcessor.SCALE * 100).toInt()} cm/px  " +
                        "· 16 m × 16 m coverage"
                timingText = "Computed in ${dt} ms"
            } catch (e: Exception) {
                statusText = "Error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // ───────────────────────────── SNAPSHOT FUNCTION ─────────────────────────────
    fun saveSnapshot(bitmap: Bitmap, filename: String) {
        try {
            val dir = File("/sdcard/Documents/BevSnapshots")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            statusText = "Saved snapshot: ${file.absolutePath}"
        } catch (e: Exception) {
            statusText = "Error saving snapshot: ${e.message}"
        }
    }

    LaunchedEffect(Unit) { computeBev() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111111))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text       = "Bird's Eye View — Appraid Project",
            color      = Color(0xFF00DCDC),
            fontSize   = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign  = TextAlign.Center,
            modifier   = Modifier.fillMaxWidth()
        )

        Text(
            text       = statusText,
            color      = Color(0xFFAAAAAA),
            fontSize   = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier   = Modifier.fillMaxWidth()
        )
        if (timingText.isNotEmpty()) {
            Text(
                text       = timingText,
                color      = Color(0xFF666666),
                fontSize   = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        if (isLoading) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color    = Color(0xFF00DC00)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val display = if (showingGt) gtBitmap else bevBitmap
            if (display != null) {
                Image(
                    bitmap             = display.asImageBitmap(),
                    contentDescription = "Bird's Eye View",
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier.fillMaxSize()
                )
            } else if (!isLoading) {
                Text("No image yet — press Compute BEV", color = Color(0xFF555555), fontSize = 16.sp)
            }
        }

        // ── Buttons ──────────────────────────────────────────────────
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BevButton(
                text     = if (isLoading) "Computing..." else "Compute BEV",
                enabled  = !isLoading,
                modifier = Modifier.weight(1f),
                onClick  = { computeBev() }
            )

            if (gtBitmap != null) {
                BevButton(
                    text     = if (showingGt) "Show Appraid BEV" else "Show Groundtruth",
                    enabled  = !isLoading,
                    modifier = Modifier.weight(1f),
                    onClick  = { showingGt = !showingGt }
                )
            }
        }

        // ── Snapshot Buttons ─────────────────────────────────────────
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BevButton(
                text = "Capture BEV",
                enabled = bevBitmap != null,
                modifier = Modifier.weight(1f),
                onClick = { bevBitmap?.let { saveSnapshot(it, "bev_output.png") } }
            )

            BevButton(
                text = "Capture GT",
                enabled = gtBitmap != null,
                modifier = Modifier.weight(1f),
                onClick = { gtBitmap?.let { saveSnapshot(it, "ground_truth.png") } }
            )

            BevButton(
                text = "Capture Fisheye",
                enabled = true,
                modifier = Modifier.weight(1f),
                onClick = {
                    val imgs = loadImages()
                    for ((cam, bmp) in imgs) {
                        saveSnapshot(bmp, "fisheye_${cam}.png")
                    }
                    statusText = "All fisheye images saved"
                }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BevButton(
    text:     String,
    enabled:  Boolean,
    modifier: Modifier = Modifier,
    onClick:  () -> Unit
) {
    Button(
        onClick  = onClick,
        enabled  = enabled,
        modifier = modifier,
        colors   = ButtonDefaults.colors(
            focusedContainerColor  = Color(0xFF00AA00),
            focusedContentColor    = Color.White,
            containerColor         = Color(0xFF2A2A2A),
            contentColor           = Color(0xFFCCCCCC),
            disabledContainerColor = Color(0xFF1A1A1A),
            disabledContentColor   = Color(0xFF555555)
        )
    ) {
        Text(text = text, fontSize = 14.sp)
    }
}