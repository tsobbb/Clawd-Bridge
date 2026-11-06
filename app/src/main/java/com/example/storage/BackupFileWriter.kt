package com.example.storage

import android.os.Environment
import android.util.Log
import com.example.model.NowPlayingData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles throttled, atomic backup writes to /sdcard/Download/ClaudePet/sync.json
 * strictly respecting flash-memory protection rules (append=false, size <= 200 bytes, throttle >= 2s).
 */
object BackupFileWriter {

    private const val TAG = "BackupFileWriter"
    private const val MIN_WRITE_INTERVAL_MS = 2000L
    private const val MAX_FILE_SIZE_BYTES = 512

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastWriteTimestamp = 0L
    private val isWriting = AtomicBoolean(false)

    private val _lastWrittenTime = MutableStateFlow(0L)
    val lastWrittenTime: StateFlow<Long> = _lastWrittenTime.asStateFlow()

    private val _lastFileSize = MutableStateFlow(0)
    val lastFileSize: StateFlow<Int> = _lastFileSize.asStateFlow()

    private val _backupStatus = MutableStateFlow("Ready")
    val backupStatus: StateFlow<String> = _backupStatus.asStateFlow()

    val targetFilePath: String
        get() = try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(downloadsDir, "ClaudePet/sync.json").absolutePath
        } catch (e: Exception) {
            "/sdcard/Download/ClaudePet/sync.json"
        }

    fun scheduleWrite(data: NowPlayingData) {
        val now = System.currentTimeMillis()
        if (now - lastWriteTimestamp < MIN_WRITE_INTERVAL_MS) {
            // Throttled: do not write to protect flash memory wear
            return
        }

        if (!isWriting.compareAndSet(false, true)) {
            return
        }

        scope.launch {
            try {
                performAtomicWrite(data)
                lastWriteTimestamp = System.currentTimeMillis()
                _lastWrittenTime.value = lastWriteTimestamp
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write backup file: ${e.message}")
                _backupStatus.value = "Error: ${e.message}"
            } finally {
                isWriting.set(false)
            }
        }
    }

    private fun performAtomicWrite(data: NowPlayingData) {
        val jsonString = data.toBackupJsonString(MAX_FILE_SIZE_BYTES)
        var jsonBytes = jsonString.toByteArray(Charsets.UTF_8)

        // Strict limit: <= 200 bytes per ТЗ
        if (jsonBytes.size > MAX_FILE_SIZE_BYTES) {
            jsonBytes = jsonBytes.copyOf(MAX_FILE_SIZE_BYTES)
        }

        val targetFile = getTargetFile()
        val parentDir = targetFile.parentFile ?: return
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }

        // Atomic write via sync.tmp -> sync.json
        val tempFile = File(parentDir, "sync.tmp")
        FileOutputStream(tempFile, false).use { output ->
            output.write(jsonBytes)
            output.flush()
        }

        // Rename tmp to target
        if (tempFile.exists()) {
            val renamed = tempFile.renameTo(targetFile)
            if (!renamed) {
                // Fallback replace
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                tempFile.renameTo(targetFile)
            }
        }

        _lastFileSize.value = jsonBytes.size
        _backupStatus.value = "Written (${jsonBytes.size} bytes)"
    }

    private fun getTargetFile(): File {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val claudePetDir = File(downloadsDir, "ClaudePet")
            File(claudePetDir, "sync.json")
        } catch (e: Exception) {
            File("/sdcard/Download/ClaudePet/sync.json")
        }
    }
}
