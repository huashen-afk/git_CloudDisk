package com.example.personalclouddisk.ui

data class DownloadItem(
    val fileId: Int,
    val fileName: String,
    var progress: Int = 0,
    var speed: Long = 0, // 字节/秒
    var status: DownloadStatus = DownloadStatus.DOWNLOADING,
    var totalBytes: Long = 0,
    var currentBytes: Long = 0
) {
    enum class DownloadStatus {
        DOWNLOADING,
        PAUSED,
        COMPLETED,
        FAILED
    }
}

