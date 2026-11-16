package com.sharpcad.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sharpcad.kernel.Kernel
import com.sharpcad.kernel.KernelException
import com.sharpcad.viewport.Viewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Phase0Screen()
            }
        }
    }
}

/** Phase 0 proof: Filament triangle plus a bounding box computed by OCCT. */
@Composable
private fun Phase0Screen() {
    val kernelReport by produceState(initialValue = "OCCT: …") {
        value = withContext(Dispatchers.Default) { describeKernelProof() }
    }
    var viewportError by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        Viewport(Modifier.fillMaxSize(), onError = { viewportError = "Viewport error: $it" })
        Text(
            text = listOfNotNull(kernelReport, viewportError).joinToString("\n\n"),
            color = Color(0xFFE6EAF0),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(16.dp)
                .background(Color(0xCC1B1F26), RoundedCornerShape(8.dp))
                .padding(12.dp),
        )
    }
}

private fun describeKernelProof(): String = try {
    Kernel.makeBox(10.0, 20.0, 30.0).use { box ->
        val b = box.boundingBox()
        "OCCT ${Kernel.occtVersion()}\n" +
            "box 10 × 20 × 30 mm\n" +
            "min (%.3f, %.3f, %.3f)\nmax (%.3f, %.3f, %.3f)"
                .format(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)
    }
} catch (e: KernelException) {
    "Kernel error ${e.code}: ${e.message}"
} catch (e: LinkageError) {
    "Native library failed to load: ${e.message}"
}
