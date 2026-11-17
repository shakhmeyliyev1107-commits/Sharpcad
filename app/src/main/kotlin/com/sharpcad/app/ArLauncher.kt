package com.sharpcad.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/** Opens a GLB model in an AR or 3D viewer installed on the device. */
internal object ArLauncher {
    private const val AR_PACKAGE = "com.google.ar.core"

    /**
     * Tries Google's AR viewer first, then any app that views `model/gltf-binary`.
     * Returns false when nothing can show the model.
     */
    fun launch(activity: Activity, model: Uri, title: String): Boolean {
        val sceneViewer = Uri.parse("https://arvr.google.com/scene-viewer/1.0").buildUpon()
            .appendQueryParameter("file", model.toString())
            .appendQueryParameter("mode", "ar_preferred")
            .appendQueryParameter("title", title)
            .build()
        val ar = Intent(Intent.ACTION_VIEW, sceneViewer).setPackage(AR_PACKAGE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.grantUriPermission(AR_PACKAGE, model, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (tryStart(activity, ar)) return true

        val anyViewer = Intent(Intent.ACTION_VIEW).setDataAndType(model, "model/gltf-binary")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser itself always starts, so check first that some app can take the model.
        if (anyViewer.resolveActivity(activity.packageManager) == null) return false
        return tryStart(activity, Intent.createChooser(anyViewer, "View in 3D / AR"))
    }

    private fun tryStart(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
