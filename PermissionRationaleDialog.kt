package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.example.core.i18n.LocalStrings

@Composable
fun PermissionRationaleDialog(
    rationaleMessage: String,
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Security,
                contentDescription = strings.permissionTitle,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(text = strings.permissionTitle, style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Text(
                text = if (permanentlyDenied) {
                    "$rationaleMessage\n\n${strings.permissionDeniedHelp}"
                } else {
                    rationaleMessage
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            if (permanentlyDenied) {
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("open_app_settings_button"),
                ) {
                    Text(strings.permissionOpenSettings)
                }
            } else {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.testTag("grant_permission_button"),
                ) {
                    Text(strings.permissionGrant)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_permission_button"),
            ) {
                Text(strings.permissionCancel)
            }
        },
    )
}
