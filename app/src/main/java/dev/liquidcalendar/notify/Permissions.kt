package dev.liquidcalendar.notify

import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Pide el permiso de notificaciones (Android 13+); en versiones anteriores no hace falta. */
@Composable
fun rememberNotificationPermission(onResult: (Boolean) -> Unit = {}): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult(it) }
    return {
        if (Build.VERSION.SDK_INT >= 33 && !Reminders.canNotify()) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) else onResult(true)
    }
}

/** Abre la página del sistema donde se permiten (o no) las alarmas exactas de esta app. */
@Composable
fun rememberExactAlarmSettings(): () -> Unit {
    val context = LocalContext.current
    return {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
