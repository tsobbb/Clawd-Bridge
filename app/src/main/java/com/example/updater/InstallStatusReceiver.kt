package com.example.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import java.util.Collections

/**
 * Receives callbacks from PackageInstaller.Session.commit(), launching the system install confirmation
 * sheet strictly once per sessionId on STATUS_PENDING_USER_ACTION.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        Log.i(TAG, "InstallStatusReceiver sessionId=$sessionId status=$status msg=$statusMessage")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (!markConfirmationHandledIfNew(sessionId)) {
                    Log.d(TAG, "Ignoring duplicate STATUS_PENDING_USER_ACTION for sessionId=$sessionId")
                    return
                }
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    BridgeAppUpdater.onAwaitingUserConfirmation(sessionId)
                    runCatching {
                        context.startActivity(confirmIntent)
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
                BridgeAppUpdater.onInstallAborted()
            }
            else -> {
                val readable = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Конфликт подписи APK"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "APK несовместим с устройством"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Недостаточно памяти для установки"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "Повреждённый APK-файл"
                    else -> statusMessage.ifBlank { "Ошибка установки (код $status)" }
                }
                runCatching {
                    Toast.makeText(context.applicationContext, readable, Toast.LENGTH_LONG).show()
                }
                BridgeAppUpdater.onInstallFailed(readable)
            }
        }
    }

    companion object {
        private const val TAG = "InstallStatusReceiver"
        private val handledSessionIds: MutableSet<Int> = Collections.synchronizedSet(HashSet())

        internal fun markConfirmationHandledIfNew(sessionId: Int): Boolean {
            if (sessionId < 0) return true
            if (handledSessionIds.size > 64) {
                handledSessionIds.clear()
            }
            return handledSessionIds.add(sessionId)
        }

        internal fun clearHandledSessionsForTest() {
            handledSessionIds.clear()
        }
    }
}
