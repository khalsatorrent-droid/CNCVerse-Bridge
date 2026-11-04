package com.cncverse.stremiobridge.state

import android.content.Intent
import androidx.core.content.FileProvider
import com.cncverse.stremiobridge.repo.AndroidContextHolder
import java.io.File

actual fun shareLogFile(path: String): Boolean = runCatching {
    val context = AndroidContextHolder.appContext
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Bridge log")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share the log file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    true
}.getOrDefault(false)
