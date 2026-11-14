package com.example.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Receives callbacks from PackageInstaller.Session.commit(), launching the system install confirmation
 * sheet on STATUS_PENDING_USER_ACTION with FLAG_ACTIVITY_NEW_TASK.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        Log.i(TAG, "InstallStatusReceiver status=$status msg=$statusMessage")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching {
                        context.startActivity(confirmIntent)
                        BridgeAppUpdater.onAwaitingUserConfirmation()
                    }.onFailure { err ->
                        Log.e(TAG, "Failed to launch system installer UI: ${err.message}", err)
                        BridgeAppUpdater.onInstallFailed("Не удалось открыть системный диалог установки: ${err.message}")
                    }
                } else {
                    BridgeAppUpdater.onInstallFailed("Системный установщик не передал Intent подтверждения")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                BridgeAppUpdater.onInstallSucceeded(context)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                BridgeAppUpdater.onInstallFailed("Установка отменена пользователем")
            }
            else -> {
                val readable = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Конфликт подписи APK"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "APK несовместим с устройством"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Недостаточно памяти для установки"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "Повреждённый APK-файл"
                    else -> statusMessage.ifBlank { "Ошибка установки (код $status)" }
                }
                BridgeAppUpdater.onInstallFailed(readable)
            }
        }
    }

    companion object {
        private const val TAG = "InstallStatusReceiver"
    }
}
