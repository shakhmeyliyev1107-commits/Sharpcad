package com.sharpcad.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.sharpcad.ui.designsystem.SharpCadTheme
import com.sharpcad.ui.workspace.Workspace
import com.sharpcad.ui.workspace.WorkspaceActions
import com.sharpcad.viewport.Viewport
import com.sharpcad.viewport.ViewportController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SharpCadTheme {
                WorkspaceScreen()
            }
        }
    }
}

@Composable
private fun WorkspaceScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { WorkspaceState(context, scope, ViewportController()) }

    // STEP files have no reliable MIME type on Android, so the picker shows all files.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.importStep(uri)
    }
    val actions = remember(state, picker) {
        WorkspaceActions(
            onImportStep = { picker.launch(arrayOf("*/*")) },
            onOpenSample = state::openSample,
            onFitView = state::fitView,
        )
    }

    // Start with something to look at.
    LaunchedEffect(state) { state.openSample() }

    Workspace(
        projectName = state.projectName,
        actions = actions,
        status = state.status,
        busy = state.busy,
    ) {
        Viewport(state.viewport, Modifier.fillMaxSize(), onError = state::onViewportError)
    }
}
