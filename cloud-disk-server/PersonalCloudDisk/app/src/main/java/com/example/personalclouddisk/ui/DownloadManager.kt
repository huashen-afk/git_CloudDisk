package com.example.personalclouddisk.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.personalclouddisk.network.ApiClient
import com.example.personalclouddisk.network.FileItem
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class DownloadManager(private val apiClient: ApiClient, private val context: Context) {
    private val downloads = ConcurrentHashMap<Int, DownloadTask>()
    private val listeners = mutableListOf<DownloadListener>()

    interface DownloadListener {
        fun onDownloadAdded(item: DownloadItem)
        fun onDownloadUpdated(item: DownloadItem)
        fun onDownloadRemoved(fileId: Int)
    }

    fun addListener(listener: DownloadListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: DownloadListener) {
        listeners.remove(listener)
    }

    fun downloadFile(file: FileItem, downloadPath: String? = null, downloadUri: String? = null) {
        // 检查是否已经在下载列表中
        if (downloads.containsKey(file.id)) {
            val existingTask = downloads[file.id]
            val status = existingTask?.downloadItem?.status
            if (status == DownloadItem.DownloadStatus.DOWNLOADING || 
                status == DownloadItem.DownloadStatus.PAUSED) {
                Log.d("DownloadManager", "文件已在下载列表中: ${file.filename}, 状态: $status")
                return
            }
            // 如果已完成或失败，先移除旧记录
            downloads.remove(file.id)
            notifyDownloadRemoved(file.id)
        }

        val downloadItem = DownloadItem(
            fileId = file.id,
            fileName = file.filename,
            status = DownloadItem.DownloadStatus.DOWNLOADING
        )

        val task = DownloadTask(downloadItem, file, apiClient, context, downloadPath, downloadUri) { item ->
            notifyDownloadUpdated(item)
        }

        downloads[file.id] = task
        notifyDownloadAdded(downloadItem)
        task.start()
    }

    fun toggleDownload(fileId: Int) {
        val task = downloads[fileId]
        when (task?.downloadItem?.status) {
            DownloadItem.DownloadStatus.DOWNLOADING -> {
                task.pause()
            }
            DownloadItem.DownloadStatus.PAUSED -> {
                task.resume()
            }
            DownloadItem.DownloadStatus.FAILED -> {
                task.retry()
            }
            else -> {}
        }
    }

    fun removeDownload(fileId: Int) {
        val task = downloads.remove(fileId)
        task?.cancel()
        notifyDownloadRemoved(fileId)
    }

    fun removeDownloads(fileIds: List<Int>) {
        fileIds.forEach { fileId ->
            val task = downloads.remove(fileId)
            task?.cancel()
            notifyDownloadRemoved(fileId)
        }
    }

    fun getAllDownloads(): List<DownloadItem> {
        return downloads.values.map { it.downloadItem }
    }

    fun getDownload(fileId: Int): DownloadItem? {
        return downloads[fileId]?.downloadItem
    }

    private fun notifyDownloadAdded(item: DownloadItem) {
        listeners.forEach { it.onDownloadAdded(item) }
    }

    private fun notifyDownloadUpdated(item: DownloadItem) {
        listeners.forEach { it.onDownloadUpdated(item) }
    }

    private fun notifyDownloadRemoved(fileId: Int) {
        listeners.forEach { it.onDownloadRemoved(fileId) }
    }

    private class DownloadTask(
        val downloadItem: DownloadItem,
        private val file: FileItem,
        private val apiClient: ApiClient,
        private val context: Context,
        private val downloadPath: String?,
        private val downloadUri: String?,
        private val onUpdate: (DownloadItem) -> Unit
    ) {
        private var job: Job? = null
        private var isPaused = false
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        fun start() {
            if (job?.isActive == true) return

            job = scope.launch {
                try {
                    downloadItem.status = DownloadItem.DownloadStatus.DOWNLOADING
                    // 切换到主线程更新UI
                    mainScope.launch {
                        onUpdate(downloadItem)
                    }

                    var destinationFile: File? = null
                    var destinationUri: Uri? = null
                    var useDocumentFile = false

                    // 优先使用 URI（Android 10+ 通过 ACTION_OPEN_DOCUMENT_TREE 选择的目录）
                    if (downloadUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        try {
                            val treeUri = Uri.parse(downloadUri)
                            val treeDocumentFile = DocumentFile.fromTreeUri(context, treeUri)
                            if (treeDocumentFile != null && treeDocumentFile.canWrite()) {
                                // 检查文件是否已存在，如果存在则删除
                                val existingFile = treeDocumentFile.findFile(file.filename)
                                existingFile?.delete()
                                
                                // 创建新文件
                                val documentFile = treeDocumentFile.createFile("*/*", file.filename)
                                if (documentFile != null) {
                                    destinationUri = documentFile.uri
                                    useDocumentFile = true
                                    Log.d("DownloadManager", "使用 DocumentFile，URI: $destinationUri")
                                } else {
                                    Log.e("DownloadManager", "无法在 DocumentFile 中创建文件")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("DownloadManager", "使用 DocumentFile 失败: ${e.message}", e)
                        }
                    }

                    // 如果 DocumentFile 不可用，回退到文件路径
                    if (!useDocumentFile) {
                        val downloadDir = if (downloadPath != null && downloadPath.isNotEmpty()) {
                            File(downloadPath)
                        } else {
                            File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                "PersonalCloudDisk"
                            )
                        }
                        if (!downloadDir.exists()) {
                            val created = downloadDir.mkdirs()
                            Log.d("DownloadManager", "创建下载目录: ${downloadDir.absolutePath}, 成功: $created")
                        }
                        Log.d("DownloadManager", "下载目录: ${downloadDir.absolutePath}, 存在: ${downloadDir.exists()}")

                        destinationFile = File(downloadDir, file.filename)
                        Log.d("DownloadManager", "目标文件路径: ${destinationFile.absolutePath}")
                    }

                    var lastUpdateTime = System.currentTimeMillis()
                    var lastBytes = 0L

                    if (useDocumentFile && destinationUri != null) {
                        // 使用 DocumentFile 下载
                        val result = downloadFileToUri(file.id, destinationUri) { current, total ->
                            if (isPaused) return@downloadFileToUri

                            val currentTime = System.currentTimeMillis()
                            val timeDiff = (currentTime - lastUpdateTime) / 1000.0
                            
                            if (timeDiff >= 1.0) {
                                val bytesDiff = current - lastBytes
                                downloadItem.speed = (bytesDiff / timeDiff).toLong()
                                lastUpdateTime = currentTime
                                lastBytes = current
                            }

                            downloadItem.currentBytes = current
                            downloadItem.totalBytes = total
                            downloadItem.progress = if (total > 0) {
                                ((current.toDouble() / total) * 100).toInt()
                            } else {
                                0
                            }

                            mainScope.launch {
                                onUpdate(downloadItem)
                            }
                        }

                        if (result.isSuccess && !isPaused) {
                            // DocumentFile 下载成功
                            downloadItem.status = DownloadItem.DownloadStatus.COMPLETED
                            downloadItem.progress = 100
                            downloadItem.speed = 0
                            Log.d("DownloadManager", "下载完成（DocumentFile）: $destinationUri")
                        } else if (isPaused) {
                            downloadItem.status = DownloadItem.DownloadStatus.PAUSED
                        } else {
                            val error = result.exceptionOrNull()
                            Log.e("DownloadManager", "下载失败: ${error?.message}", error)
                            downloadItem.status = DownloadItem.DownloadStatus.FAILED
                        }
                    } else if (destinationFile != null) {
                        // 使用文件路径下载
                        val result = apiClient.downloadFile(file.id, destinationFile) { current, total ->
                            if (isPaused) return@downloadFile

                            val currentTime = System.currentTimeMillis()
                            val timeDiff = (currentTime - lastUpdateTime) / 1000.0
                            
                            if (timeDiff >= 1.0) {
                                val bytesDiff = current - lastBytes
                                downloadItem.speed = (bytesDiff / timeDiff).toLong()
                                lastUpdateTime = currentTime
                                lastBytes = current
                            }

                            downloadItem.currentBytes = current
                            downloadItem.totalBytes = total
                            downloadItem.progress = if (total > 0) {
                                ((current.toDouble() / total) * 100).toInt()
                            } else {
                                0
                            }

                            mainScope.launch {
                                onUpdate(downloadItem)
                            }
                        }

                        if (result.isSuccess && !isPaused) {
                            // 文件路径下载成功
                            val downloadedFile = result.getOrNull()
                            if (downloadedFile != null && downloadedFile.exists()) {
                                downloadItem.status = DownloadItem.DownloadStatus.COMPLETED
                                downloadItem.progress = 100
                                downloadItem.speed = 0
                                Log.d("DownloadManager", "下载完成: ${downloadedFile.absolutePath}, 大小: ${downloadedFile.length()} bytes")
                            } else {
                                Log.e("DownloadManager", "下载失败: 文件不存在或无法访问")
                                downloadItem.status = DownloadItem.DownloadStatus.FAILED
                            }
                        } else if (isPaused) {
                            downloadItem.status = DownloadItem.DownloadStatus.PAUSED
                        } else {
                            val error = result.exceptionOrNull()
                            Log.e("DownloadManager", "下载失败: ${error?.message}", error)
                            downloadItem.status = DownloadItem.DownloadStatus.FAILED
                        }
                    } else {
                        throw Exception("无法确定下载目标位置")
                    }
                } catch (e: Exception) {
                    Log.e("DownloadManager", "下载异常: ${e.message}", e)
                    downloadItem.status = DownloadItem.DownloadStatus.FAILED
                } finally {
                    // 切换到主线程更新UI
                    mainScope.launch {
                        onUpdate(downloadItem)
                    }
                }
            }
        }

        fun pause() {
            isPaused = true
            downloadItem.status = DownloadItem.DownloadStatus.PAUSED
            onUpdate(downloadItem)
        }

        fun resume() {
            isPaused = false
            downloadItem.status = DownloadItem.DownloadStatus.DOWNLOADING
            onUpdate(downloadItem)
            start()
        }

        fun retry() {
            isPaused = false
            downloadItem.status = DownloadItem.DownloadStatus.DOWNLOADING
            downloadItem.progress = 0
            downloadItem.currentBytes = 0
            onUpdate(downloadItem)
            start()
        }

        fun cancel() {
            job?.cancel()
            isPaused = true
        }

        /**
         * 使用 DocumentFile/ContentResolver 下载文件到 URI
         */
        private suspend fun downloadFileToUri(
            fileId: Int,
            destinationUri: Uri,
            onProgress: (currentBytes: Long, totalBytes: Long) -> Unit
        ): Result<Uri> = withContext(Dispatchers.IO) {
            return@withContext try {
                // 创建带认证头的请求
                val url = "${apiClient.getServerUrl()}/files/$fileId/download"
                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .get()
                
                // 添加认证头
                val authToken = apiClient.getAuthToken()
                if (authToken.isNotEmpty()) {
                    requestBuilder.header("Authorization", "Bearer $authToken")
                }
                
                // 添加 ngrok 跳过警告头
                if (url.contains("ngrok-free.dev") || url.contains("ngrok.io")) {
                    requestBuilder.header("ngrok-skip-browser-warning", "true")
                }
                
                val request = requestBuilder.build()
                val response = apiClient.getClient().newCall(request).execute()

                if (response.isSuccessful) {
                    response.body?.let { body ->
                        val contentLength = body.contentLength()
                        Log.d("DownloadManager", "Download content length: $contentLength")

                        context.contentResolver.openOutputStream(destinationUri, "w")?.use { outputStream ->
                            val source = body.source()
                            var totalBytesRead = 0L
                            var bytesRead: Long
                            val buffer = okio.Buffer()

                            while (source.read(buffer, 8192).also { bytesRead = it } != -1L) {
                                val bytes = ByteArray(bytesRead.toInt())
                                buffer.readFully(bytes)
                                outputStream.write(bytes)
                                totalBytesRead += bytesRead
                                onProgress(totalBytesRead, contentLength)
                            }

                            source.close()
                            Log.d("DownloadManager", "Download completed: $totalBytesRead bytes")
                            Result.success(destinationUri)
                        } ?: run {
                            Log.e("DownloadManager", "无法打开输出流")
                            Result.failure(Exception("无法打开输出流"))
                        }
                    } ?: run {
                        Log.e("DownloadManager", "Download response body is null")
                        Result.failure(Exception("下载失败: 响应体为空"))
                    }
                } else {
                    val responseBody = response.body?.string() ?: ""
                    Log.e("DownloadManager", "Download HTTP error: ${response.code}, Body: $responseBody")
                    Result.failure(Exception("下载失败: HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Log.e("DownloadManager", "Download exception: ${e.message}", e)
                Result.failure(e)
            }
        }
    }
}

