package com.example.personalclouddisk.ui

import android.Manifest
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.navigation.NavigationView
import com.example.personalclouddisk.R
import com.example.personalclouddisk.databinding.ActivityMainBinding
import com.example.personalclouddisk.network.FileItem
import com.example.personalclouddisk.network.FolderItem
import com.example.personalclouddisk.model.User
import com.example.personalclouddisk.network.ApiClient
import com.example.personalclouddisk.ui.adapter.FileAdapter
import com.example.personalclouddisk.utils.SharedPrefManager
import com.example.personalclouddisk.ui.UploadProgressCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPrefManager: SharedPrefManager
    private lateinit var apiClient: ApiClient
    private lateinit var fileAdapter: FileAdapter
    private val fileList = mutableListOf<FileItem>()

    // 文件夹导航相关
    private var currentFolderId: Int? = null  // 当前文件夹ID，null表示根目录
    private var currentFolderName: String? = null  // 当前文件夹名称
    private val folderPathStack = mutableListOf<Pair<Int?, String>>()  // 路径栈：(folderId, folderName)
    
    // 文件夹内容缓存（最多缓存10个文件夹）
    private val folderCache = mutableMapOf<Int?, Pair<List<FolderItem>, List<FileItem>>>()
    private val maxCacheSize = 10

    // 刷新防抖
    private var lastRefreshTime = 0L
    private val REFRESH_THROTTLE_MS = 1000L

    private companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    // 注册文件选择器结果回调
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "文件选择器返回，resultCode: ${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                Log.d(TAG, "选择的文件URI: $uri")
                handleSelectedFile(uri)
            } ?: run {
                Log.w(TAG, "文件选择器返回成功，但URI为空")
                Toast.makeText(this, "未选择文件", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.d(TAG, "用户取消了文件选择")
        }
    }

    // 注册ShareActivity结果回调
    private val shareActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "ShareActivity返回，resultCode: ${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            // 从ShareActivity返回时，刷新文件列表和用户信息
            Log.d(TAG, "从ShareActivity返回，刷新文件列表")
            loadUserProfile()
            syncAndLoadFiles()
        }
    }

    // 注册权限请求回调
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, "需要存储权限才能选择文件", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d(TAG, "Activity created")

        // 检查登录状态
        sharedPrefManager = SharedPrefManager.getInstance(this)
        if (!sharedPrefManager.isLoggedIn()) {
            navigateToLogin()
            return
        }

        // 初始化API客户端
        apiClient = ApiClient.getInstance(this)
        val accessToken = sharedPrefManager.getAccessToken()
        Log.d(TAG, "从SharedPreferences获取的Token: ${if (accessToken.isNotEmpty()) "${accessToken.take(20)}..." else "空"}")
        if (accessToken.isNotEmpty()) {
            apiClient.setAuthToken(accessToken)
            Log.d(TAG, "已设置AuthToken")
        } else {
            Log.e(TAG, "AccessToken为空，无法设置")
            Toast.makeText(this, "登录状态异常，请重新登录", Toast.LENGTH_SHORT).show()
            navigateToLogin()
            return
        }

        // 初始化下载管理器
        val downloadManager = DownloadManager(apiClient, this)
        DownloadActivity.setDownloadManager(downloadManager)

        setupUI()
        loadUserProfile()
        syncAndLoadFiles()  // 首次加载时先同步文件
    }

    private fun setupUI() {
        // 设置工具栏标题
        binding.toolbar.title = "我的网盘"

        // 隐藏默认的导航图标，使用自定义按钮
        binding.toolbar.navigationIcon = null

        // 设置工具栏菜单按钮（打开侧边栏）
        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        // 设置侧边栏
        setupNavigationView()

        // 初始化路径显示
        updateToolbar()

        // 设置RecyclerView
        fileAdapter = FileAdapter(
            mutableListOf(),
            onItemClickListener = { item ->
                when (item) {
                    is com.example.personalclouddisk.ui.adapter.ListItem.File -> {
                        showFileDetailsDialog(item.file)
                    }
                    is com.example.personalclouddisk.ui.adapter.ListItem.Folder -> {
                        // 文件夹点击事件（可以后续实现进入文件夹功能）
                        Toast.makeText(this, "文件夹: ${item.folder.folder_name}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDownloadClickListener = { file ->
                downloadFile(file)
            },
            onDeleteClickListener = { file ->
                deleteFile(file)
            },
            onFolderClickListener = { folder ->
                // 文件夹点击事件：进入文件夹
                enterFolder(folder)
            },
            onDeleteFolderClickListener = { folder ->
                // 文件夹删除事件
                deleteFolder(folder)
            },
            onShareFileClickListener = { file ->
                // 文件分享事件
                shareFile(file)
            },
            onShareFolderClickListener = { folder ->
                // 文件夹分享事件
                shareFolder(folder)
            },
            onMoveFileClickListener = { file ->
                // 文件移动事件
                showMoveFileDialog(file)
            },
            onRenameFileClickListener = { file ->
                // 文件重命名事件
                showRenameFileDialog(file)
            },
            onRenameFolderClickListener = { folder ->
                // 文件夹重命名事件
                showRenameFolderDialog(folder)
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = fileAdapter
            // 确保RecyclerView可以滚动
            isNestedScrollingEnabled = true
            // 设置固定大小以提高性能
            setHasFixedSize(false)
            // 确保可见
            visibility = View.VISIBLE
        }
        Log.d(TAG, "RecyclerView初始化完成，适配器已设置，可见性: ${binding.recyclerView.visibility}")

        // 设置下拉刷新
        binding.swipeRefreshLayout.setOnRefreshListener {
            syncAndLoadFiles()
        }

        // 设置按钮点击事件
        binding.btnUpload.setOnClickListener {
            Log.d(TAG, "上传按钮被点击")
            requestStoragePermission()
        }

        binding.btnRefresh.setOnClickListener {
            syncAndLoadFiles()
        }

        binding.btnNewFolder.setOnClickListener {
            showCreateFolderDialog()
        }

        binding.btnShareInput.setOnClickListener {
            showShareInputDialog()
        }

        binding.btnLogout.setOnClickListener {
            showLogoutConfirmation()
        }

        binding.btnDownload.setOnClickListener {
            val intent = Intent(this, DownloadActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupNavigationView() {
        // 设置版本号
        val headerView = binding.navigationView.getHeaderView(0)
        val tvVersion = headerView.findViewById<android.widget.TextView>(R.id.tvVersion)
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName
            tvVersion?.text = "版本号: $versionName"
        } catch (e: Exception) {
            tvVersion?.text = "版本号: 1.0.0"
        }

        // 设置侧边栏菜单点击监听
        binding.navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_settings -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.nav_about -> {
                    showAboutDialog()
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                else -> {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
            }
        }

        // 设置返回按钮
        binding.btnBack.setOnClickListener {
            goBackToParentFolder()
        }
    }

    private fun showAboutDialog() {
        val intent = Intent(this, AboutActivity::class.java)
        startActivity(intent)
    }

    private fun requestStoragePermission() {
        Log.d(TAG, "请求存储权限，Android版本: ${Build.VERSION.SDK_INT}")
        // Android 11及以上版本，使用ACTION_GET_CONTENT不需要存储权限，直接打开文件选择器
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.d(TAG, "Android 11+，直接打开文件选择器")
            openFilePicker()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6.0到10版本，需要检查权限
            val hasPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            Log.d(TAG, "Android 6-10，权限状态: $hasPermission")
            if (hasPermission) {
                openFilePicker()
            } else {
                Log.d(TAG, "请求READ_EXTERNAL_STORAGE权限")
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        } else {
            // Android 6.0以下版本，直接打开
            Log.d(TAG, "Android 6以下，直接打开文件选择器")
            openFilePicker()
        }
    }

    private fun openFilePicker() {
        try {
            Log.d(TAG, "打开文件选择器")
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            }
            filePickerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开文件选择器失败: ${e.message}", e)
            Toast.makeText(this, "无法打开文件选择器: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSelectedFile(uri: Uri) {
        try {
            val contentResolver = contentResolver
            val inputStream = contentResolver.openInputStream(uri)
            inputStream?.use { stream ->
                // 获取文件名
                val fileName = getFileNameFromUri(uri)
                Log.d(TAG, "Selected file: $fileName")

                // 创建临时文件
                val tempDir = File(cacheDir, "uploads")
                tempDir.mkdirs()
                val tempFile = File(tempDir, fileName)

                // 复制文件到临时目录
                FileOutputStream(tempFile).use { outputStream ->
                    stream.copyTo(outputStream)
                }

                // 显示上传进度
                showUploadProgress(fileName)

                // 上传文件
                uploadFile(tempFile, fileName)
            } ?: run {
                Toast.makeText(this, "无法打开文件", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling selected file", e)
            Toast.makeText(this, "文件处理失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var fileName = "unknown_file"
        var foundName = false

        try {
            // 方法1: 使用OpenableColumns获取文件名（最可靠）
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrEmpty()) {
                            fileName = name
                            foundName = true
                            Log.d(TAG, "从OpenableColumns获取文件名: $fileName")
                            return@use
                        }
                    }

                    // 方法2: 尝试_display_name列
                    if (!foundName) {
                        val displayNameIndex = cursor.getColumnIndex("_display_name")
                        if (displayNameIndex >= 0) {
                            val name = cursor.getString(displayNameIndex)
                            if (!name.isNullOrEmpty()) {
                                fileName = name
                                foundName = true
                                Log.d(TAG, "从_display_name获取文件名: $fileName")
                                return@use
                            }
                        }
                    }
                }
            }

            // 方法3: 从URI路径获取文件名（只有在前面方法都失败时才使用）
            if (!foundName) {
                uri.path?.let { path ->
                    val segments = path.split("/")
                    if (segments.isNotEmpty()) {
                        val lastSegment = segments.last()
                        // 跳过明显不是文件名的路径段（如 "document", "document:xxx"）
                        if (lastSegment.isNotEmpty() &&
                            lastSegment != "document" &&
                            !lastSegment.startsWith("document:") &&
                            !lastSegment.startsWith("image:")) {
                            // URL解码文件名（处理编码的中文字符）
                            try {
                                fileName = java.net.URLDecoder.decode(lastSegment, "UTF-8")
                                Log.d(TAG, "从URI路径获取文件名: $fileName")
                            } catch (e: Exception) {
                                fileName = lastSegment
                                Log.d(TAG, "从URI路径获取文件名（未解码）: $fileName")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取文件名失败: ${e.message}", e)
        }

        Log.d(TAG, "最终文件名: $fileName")
        return fileName
    }

    private fun showUploadProgress(fileName: String) {
        // 不在主界面弹出上传进度，仅通知上传中心
        binding.btnUpload.isEnabled = false
        binding.btnRefresh.isEnabled = false
        UploadProgressCenter.start(fileName)
    }

    private fun hideUploadProgress() {
        binding.btnUpload.isEnabled = true
        binding.btnRefresh.isEnabled = true
        UploadProgressCenter.finish()
    }

    private fun updateUploadProgress(progress: Int) {
        UploadProgressCenter.updateProgress(progress)
    }

    private fun uploadFile(file: File, fileName: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d(TAG, "开始上传文件: $fileName, 大小: ${file.length()} bytes, 当前文件夹ID: $currentFolderId")

                // 上传文件时传递当前文件夹ID
                val result = apiClient.uploadFile(file, fileName, folderId = currentFolderId) { currentBytes, totalBytes ->
                    val progress = ((currentBytes.toDouble() / totalBytes) * 100).toInt()
                    runOnUiThread {
                        updateUploadProgress(progress)
                    }
                }

                if (result.isSuccess) {
                    val uploadedFile = result.getOrNull()
                    Log.d(TAG, "文件上传成功: ${uploadedFile?.filename}, folder_id: ${uploadedFile?.folder_id}")

                    Toast.makeText(
                        this@MainActivity,
                        "文件上传成功: ${uploadedFile?.filename}",
                        Toast.LENGTH_SHORT
                    ).show()

                    // 清除当前文件夹缓存
                    clearCacheForFolder(currentFolderId)

                    // 更新用户信息和文件统计（包括文件数量）
                    loadUserProfile()

                    // 刷新文件列表（从服务器获取最新数据，使用当前文件夹ID避免重复）
                    loadFiles(currentFolderId)

                } else {
                    val error = result.exceptionOrNull()
                    Log.e(TAG, "上传失败: ${error?.message}", error)
                    Toast.makeText(
                        this@MainActivity,
                        "上传失败: ${error?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "上传异常: ${e.message}", e)
                Toast.makeText(
                    this@MainActivity,
                    "上传异常: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                hideUploadProgress()
                // 删除临时文件
                try {
                    file.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "无法删除临时文件", e)
                }
            }
        }
    }

    private fun loadUserProfile() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                binding.progressBarLoading.visibility = View.VISIBLE

                val result = apiClient.getProfile()
                if (result.isSuccess) {
                    val profile = result.getOrNull()
                    profile?.user?.let { user ->
                        updateUserInfo(user)
                    }
                    profile?.stats?.let { stats ->
                        updateStorageInfo(stats)
                    }
                } else {
                    val error = result.exceptionOrNull()
                    Log.e(TAG, "获取用户信息失败: ${error?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取用户信息异常: ${e.message}", e)
            } finally {
                binding.progressBarLoading.visibility = View.GONE
            }
        }
    }

    private fun updateUserInfo(user: User) {
        // 更新侧边栏用户信息
        val headerView = binding.navigationView.getHeaderView(0)
        val tvUsername = headerView.findViewById<android.widget.TextView>(R.id.tvUsername)
        tvUsername?.text = user.username
        
        // 设置头像和用户名点击事件
        val ivAvatar = headerView.findViewById<android.widget.ImageView>(R.id.ivAvatar)
        ivAvatar?.setOnClickListener {
            openProfileActivity()
        }
        tvUsername?.setOnClickListener {
            openProfileActivity()
        }
        
        // 加载头像
        loadAvatarToSidebar(user.avatarUrl)
    }
    
    private fun loadAvatarToSidebar(avatarUrl: String?) {
        if (avatarUrl.isNullOrEmpty()) {
            return
        }
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    loadAvatarFromUrl(avatarUrl)
                }
                bitmap?.let {
                    val headerView = binding.navigationView.getHeaderView(0)
                    val ivAvatar = headerView.findViewById<android.widget.ImageView>(R.id.ivAvatar)
                    ivAvatar?.setImageBitmap(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载侧边栏头像失败: ${e.message}", e)
            }
        }
    }
    
    private fun loadAvatarFromUrl(avatarUrl: String): android.graphics.Bitmap? {
        return try {
            val baseUrl = apiClient.getServerUrl().replace("/api", "")
            val fullUrl = if (avatarUrl.startsWith("http")) {
                avatarUrl
            } else {
                "$baseUrl/api/profile/avatar/$avatarUrl"
            }
            
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder()
                .url(fullUrl)
                .header("Authorization", "Bearer ${sharedPrefManager.getAccessToken()}")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val inputStream = response.body?.byteStream()
                inputStream?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载头像失败: ${e.message}", e)
            null
        }
    }

    private fun updateStorageInfo(stats: com.example.personalclouddisk.network.UserStats) {
        binding.tvFileCount.text = stats.files_count.toString()
        binding.tvStorageUsed.text = String.format("%.1f MB", stats.total_size_mb)
        
        // 更新侧边栏剩余空间显示（1GB限制）
        val headerView = binding.navigationView.getHeaderView(0)
        val tvStorageRemaining = headerView.findViewById<android.widget.TextView>(R.id.tvStorageRemaining)
        // 总容量改为 10GB
        val totalSpaceMB = 10240.0 // 10GB = 10240MB
        val remainingMB = totalSpaceMB - stats.total_size_mb
        val remainingGB = remainingMB / 1024.0
        
        if (remainingMB >= 0) {
            if (remainingMB >= 1024) {
                tvStorageRemaining?.text = String.format("剩余空间: %.2f GB", remainingGB)
            } else {
                tvStorageRemaining?.text = String.format("剩余空间: %.1f MB", remainingMB)
            }
        } else {
            tvStorageRemaining?.text = "剩余空间: 0 MB"
            tvStorageRemaining?.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }

    private fun openProfileActivity() {
        val intent = Intent(this, ProfileActivity::class.java)
        startActivity(intent)
    }


    private fun syncAndLoadFiles() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastRefreshTime < REFRESH_THROTTLE_MS) {
            Log.d(TAG, "刷新请求过于频繁，已忽略")
            return
        }
        lastRefreshTime = currentTime

        CoroutineScope(Dispatchers.Main).launch {
            try {
                binding.swipeRefreshLayout.isRefreshing = true
                Log.d(TAG, "开始同步并加载文件列表")

                // 先同步文件系统中的文件到数据库
                val syncResult = apiClient.syncFiles()
                if (syncResult.isSuccess) {
                    val syncedCount = syncResult.getOrNull() ?: 0
                    if (syncedCount > 0) {
                        Log.d(TAG, "同步了 $syncedCount 个文件到数据库")
                        Toast.makeText(
                            this@MainActivity,
                            "同步了 $syncedCount 个文件",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Log.w(TAG, "文件同步失败，继续加载文件列表")
                }

                // 更新用户信息和文件统计（包括文件数量）
                loadUserProfile()

                // 然后加载文件列表（使用当前文件夹ID）
                loadFiles(currentFolderId)
            } catch (e: Exception) {
                Log.e(TAG, "同步文件异常: ${e.message}", e)
                // 即使同步失败，也尝试加载文件列表和更新统计（使用当前文件夹ID）
                loadUserProfile()
                loadFiles(currentFolderId)
            }
        }
    }

    private fun loadFiles() {
        loadFiles(currentFolderId)
    }

    private fun loadFiles(folderId: Int?) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 先检查缓存
                val cached = folderCache[folderId]
                if (cached != null) {
                    Log.d(TAG, "使用缓存数据，folderId: $folderId")
                    updateFileList(cached.first, cached.second)
                    // 后台刷新缓存
                    refreshCacheInBackground(folderId)
                    return@launch
                }

                binding.swipeRefreshLayout.isRefreshing = true
                Log.d(TAG, "开始加载文件夹和文件列表，folderId: $folderId")

                // 并行获取文件夹和文件（使用 async 并行执行）
                val foldersDeferred = async(Dispatchers.IO) {
                    apiClient.getFolders(parentFolderId = folderId)
                }
                val filesDeferred = async(Dispatchers.IO) {
                    apiClient.getFiles(folderId = folderId)
                }

                // 等待两个请求完成
                val foldersResult = foldersDeferred.await()
                val filesResult = filesDeferred.await()

                val folders = if (foldersResult.isSuccess) {
                    foldersResult.getOrNull() ?: emptyList()
                } else {
                    Log.w(TAG, "获取文件夹列表失败: ${foldersResult.exceptionOrNull()?.message}")
                    emptyList()
                }

                val files = if (filesResult.isSuccess) {
                    filesResult.getOrNull() ?: emptyList()
                } else {
                    Log.w(TAG, "获取文件列表失败: ${filesResult.exceptionOrNull()?.message}")
                    emptyList()
                }

                Log.d(TAG, "获取到 ${folders.size} 个文件夹, ${files.size} 个文件")

                // 更新缓存
                updateCache(folderId, folders, files)

                // 更新UI - 已经在主线程，直接更新
                Log.d(TAG, "在主线程更新UI，文件夹: ${folders.size}, 文件: ${files.size}")
                updateFileList(folders, files)
            } catch (e: Exception) {
                Log.e(TAG, "加载列表异常: ${e.message}", e)
                Toast.makeText(
                    this@MainActivity,
                    "网络异常: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }
    }
    
    /**
     * 更新缓存
     */
    private fun updateCache(folderId: Int?, folders: List<FolderItem>, files: List<FileItem>) {
        // 如果缓存已满，删除最旧的（简单策略：删除第一个）
        if (folderCache.size >= maxCacheSize && !folderCache.containsKey(folderId)) {
            val firstKey = folderCache.keys.firstOrNull()
            if (firstKey != null) {
                folderCache.remove(firstKey)
                Log.d(TAG, "缓存已满，删除最旧的缓存: $firstKey")
            }
        }
        folderCache[folderId] = Pair(folders, files)
        Log.d(TAG, "缓存已更新，folderId: $folderId, 缓存大小: ${folderCache.size}")
    }
    
    /**
     * 后台刷新缓存（不阻塞UI）
     */
    private fun refreshCacheInBackground(folderId: Int?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "后台刷新缓存，folderId: $folderId")
                val foldersDeferred = async {
                    apiClient.getFolders(parentFolderId = folderId)
                }
                val filesDeferred = async {
                    apiClient.getFiles(folderId = folderId)
                }
                
                val foldersResult = foldersDeferred.await()
                val filesResult = filesDeferred.await()
                
                val folders = if (foldersResult.isSuccess) {
                    foldersResult.getOrNull() ?: emptyList()
                } else {
                    emptyList()
                }
                
                val files = if (filesResult.isSuccess) {
                    filesResult.getOrNull() ?: emptyList()
                } else {
                    emptyList()
                }
                
                // 更新缓存
                withContext(Dispatchers.Main) {
                    updateCache(folderId, folders, files)
                    // 如果当前正在查看这个文件夹，更新UI
                    if (currentFolderId == folderId) {
                        updateFileList(folders, files)
                    }
                }
                Log.d(TAG, "后台缓存刷新完成，folderId: $folderId")
            } catch (e: Exception) {
                Log.e(TAG, "后台刷新缓存失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 清除指定文件夹的缓存
     */
    private fun clearCacheForFolder(folderId: Int?) {
        if (folderId != null && folderCache.containsKey(folderId)) {
            folderCache.remove(folderId)
            Log.d(TAG, "已清除文件夹缓存: $folderId")
        } else if (folderId == null && folderCache.containsKey(null)) {
            folderCache.remove(null)
            Log.d(TAG, "已清除根目录缓存")
        }
    }
    
    /**
     * 清除所有缓存（在文件/文件夹发生变化时调用）
     */
    private fun clearCache() {
        folderCache.clear()
        Log.d(TAG, "所有缓存已清除")
    }

    private fun updateFileList(folders: List<FolderItem>, files: List<FileItem>) {
        val totalCount = folders.size + files.size
        Log.d(TAG, "updateFileList: 文件夹数量: ${folders.size}, 文件数量: ${files.size}, 总计: $totalCount")

        // 更新适配器数据
        try {
            fileAdapter.updateItems(folders, files)
            Log.d(TAG, "适配器数据已更新")
        } catch (e: Exception) {
            Log.e(TAG, "更新适配器数据失败: ${e.message}", e)
        }

        // 更新UI可见性 - 只有当文件夹和文件都为空时才显示空状态
        if (folders.isEmpty() && files.isEmpty()) {
            Log.d(TAG, "列表为空，显示空状态")
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            Log.d(TAG, "列表有 $totalCount 个项目，显示列表")
            binding.layoutEmpty.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE

            // 确保RecyclerView可以滚动
            binding.recyclerView.isNestedScrollingEnabled = true
        }

        // 更新工具栏标题和返回按钮
        updateToolbar()
    }

    private fun enterFolder(folder: FolderItem) {
        Log.d(TAG, "进入文件夹: ${folder.folder_name} (ID: ${folder.id})")

        // 将当前文件夹添加到路径栈（如果不在根目录）
        if (currentFolderId != null && currentFolderName != null) {
            folderPathStack.add(Pair(currentFolderId, currentFolderName!!))
        }

        // 更新当前文件夹ID和名称
        currentFolderId = folder.id
        currentFolderName = folder.folder_name

        // 加载该文件夹内的文件和子文件夹
        loadFiles(currentFolderId)
    }

    private fun goBackToParentFolder() {
        if (folderPathStack.isNotEmpty()) {
            // 从路径栈弹出上一级文件夹
            val (parentFolderId, parentFolderName) = folderPathStack.removeAt(folderPathStack.size - 1)
            currentFolderId = parentFolderId
            currentFolderName = parentFolderName
        } else {
            // 返回根目录
            currentFolderId = null
            currentFolderName = null
            folderPathStack.clear()
        }

        // 更新工具栏和路径显示
        updateToolbar()

        // 加载文件列表
        loadFiles(currentFolderId)
    }

    private fun updateToolbar() {
        // 更新返回按钮和菜单按钮可见性
        val params = binding.tvWelcome.layoutParams as android.widget.RelativeLayout.LayoutParams
        if (currentFolderId != null) {
            binding.btnBack.visibility = View.VISIBLE
            binding.btnMenu.visibility = View.GONE
            // 当显示返回按钮时，更新文字位置约束，使其基于返回按钮
            params.removeRule(android.widget.RelativeLayout.END_OF)
            params.addRule(android.widget.RelativeLayout.END_OF, R.id.btnBack)
        } else {
            binding.btnBack.visibility = View.GONE
            binding.btnMenu.visibility = View.VISIBLE
            // 当显示菜单按钮时，更新文字位置约束，使其基于菜单按钮
            params.removeRule(android.widget.RelativeLayout.END_OF)
            params.addRule(android.widget.RelativeLayout.END_OF, R.id.btnMenu)
        }
        binding.tvWelcome.layoutParams = params

        // 更新页眉文字 - 显示当前文件夹名称
        if (currentFolderId == null) {
            binding.toolbar.title = "我的网盘"
            binding.tvWelcome.text = "我的网盘"
        } else {
            // 显示当前文件夹名
            val folderName = currentFolderName ?: "我的网盘"
            binding.toolbar.title = folderName
            binding.tvWelcome.text = folderName
        }

        // 更新文件路径显示
        updateFilePath()
    }

    private fun updateFilePath() {
        // 显示全部路径
        val path = buildString {
            if (currentFolderId == null) {
                // 根目录
                append("我的网盘")
            } else {
                // 显示完整路径
                append("我的网盘")
                folderPathStack.forEach { (_, name) ->
                    append(" > ").append(name)
                }
                if (currentFolderName != null) {
                    append(" > ").append(currentFolderName)
                }
            }
        }

        // 更新路径TextView
        binding.tvFilePath.text = path
        binding.tvFilePath.visibility = View.VISIBLE
    }

    private fun showFileDetailsDialog(file: FileItem) {
        AlertDialog.Builder(this)
            .setTitle(file.filename)
            .setMessage("""
                文件类型: ${file.file_type ?: "未知"}
                文件大小: ${file.getFormattedSize()}
                上传时间: ${formatDateTime(file.upload_date)}
                文件ID: ${file.id}
            """.trimIndent())
            .setPositiveButton("确定", null)
            .show()
    }

    private fun downloadFile(file: FileItem) {
        // 检查权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    PERMISSION_REQUEST_CODE
                )
                return
            }
        }

        // 使用下载管理器下载文件
        val downloadManager = DownloadActivity.getDownloadManager()
        if (downloadManager != null) {
            val downloadPath = sharedPrefManager.getDownloadPath()
            val downloadUri = sharedPrefManager.getDownloadUri()
            // 检查是否已在下载列表中
            val existingDownload = downloadManager.getDownload(file.id)
            if (existingDownload != null && 
                (existingDownload.status == DownloadItem.DownloadStatus.DOWNLOADING || 
                 existingDownload.status == DownloadItem.DownloadStatus.PAUSED)) {
                Toast.makeText(this, "文件已在下载列表中", Toast.LENGTH_SHORT).show()
            } else {
                downloadManager.downloadFile(file, downloadPath, downloadUri)
                Toast.makeText(this, "已添加到下载列表", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "下载管理器未初始化", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "需要存储权限才能下载文件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteFile(file: FileItem) {
        AlertDialog.Builder(this)
            .setTitle("删除文件")
            .setMessage("确定要删除文件: ${file.filename} 吗？")
            .setPositiveButton("删除") { _, _ ->
                performDeleteFile(file)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performDeleteFile(file: FileItem) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val progressDialog = ProgressDialog(this@MainActivity).apply {
                    setMessage("正在删除文件...")
                    setCancelable(false)
                }
                progressDialog.show()

                val result = apiClient.deleteFile(file.id)

                progressDialog.dismiss()

                if (result.isSuccess) {
                    Toast.makeText(
                        this@MainActivity,
                        "文件删除成功",
                        Toast.LENGTH_SHORT
                    ).show()

                    // 从列表中移除文件
                    fileAdapter.removeFile(file)

                    // 清除相关缓存
                    clearCacheForFolder(currentFolderId)
                    clearCacheForFolder(file.folder_id)

                    // 更新用户信息和文件统计（包括文件数量）
                    loadUserProfile()

                    // 刷新文件列表（使用当前文件夹ID）
                    loadFiles(currentFolderId)

                } else {
                    val error = result.exceptionOrNull()
                    Toast.makeText(
                        this@MainActivity,
                        "删除失败: ${error?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "删除异常: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun deleteFolder(folder: FolderItem) {
        AlertDialog.Builder(this)
            .setTitle("删除文件夹")
            .setMessage("确定要删除文件夹 \"${folder.folder_name}\" 吗？\n\n注意：此操作将删除文件夹内的所有文件和子文件夹，且无法恢复！")
            .setPositiveButton("删除") { _, _ ->
                performDeleteFolder(folder)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performDeleteFolder(folder: FolderItem) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val progressDialog = ProgressDialog(this@MainActivity).apply {
                    setMessage("正在删除文件夹...")
                    setCancelable(false)
                }
                progressDialog.show()

                val result = apiClient.deleteFolder(folder.id)

                progressDialog.dismiss()

                if (result.isSuccess) {
                    Toast.makeText(
                        this@MainActivity,
                        "文件夹删除成功",
                        Toast.LENGTH_SHORT
                    ).show()

                    // 清除相关缓存（包括父文件夹）
                    clearCacheForFolder(currentFolderId)
                    clearCacheForFolder(folder.parent_folder_id)

                    // 更新用户信息和文件统计（包括文件数量）
                    loadUserProfile()

                    // 如果当前在删除的文件夹内，返回上一级
                    if (currentFolderId == folder.id) {
                        goBackToParentFolder()
                    } else {
                        // 刷新文件列表（使用当前文件夹ID）
                        loadFiles(currentFolderId)
                    }
                } else {
                    val error = result.exceptionOrNull()
                    Toast.makeText(
                        this@MainActivity,
                        "删除失败: ${error?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "删除异常: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun formatDateTime(dateTime: String?): String {
        if (dateTime == null) return "未知时间"
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val date = parser.parse(dateTime)
            formatter.format(date ?: Date())
        } catch (e: Exception) {
            dateTime
        }
    }

    private fun showCreateFolderDialog() {
        val input = android.widget.EditText(this)
        input.hint = "请输入文件夹名称"

        AlertDialog.Builder(this)
            .setTitle("新建文件夹")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val folderName = input.text.toString().trim()
                if (folderName.isNotEmpty()) {
                    createFolder(folderName)
                } else {
                    Toast.makeText(this, "文件夹名称不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createFolder(folderName: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val progressDialog = ProgressDialog(this@MainActivity).apply {
                    setMessage("正在创建文件夹...")
                    setCancelable(false)
                }
                progressDialog.show()

                val result = apiClient.createFolder(folderName, currentFolderId)
                progressDialog.dismiss()

                if (result.isSuccess) {
                    val folder = result.getOrNull()
                    Log.d(TAG, "文件夹创建成功: ${folder?.folder_name}")
                    Toast.makeText(
                        this@MainActivity,
                        "文件夹创建成功: ${folder?.folder_name}",
                        Toast.LENGTH_SHORT
                    ).show()

                    // 清除当前文件夹缓存
                    clearCacheForFolder(currentFolderId)

                    // 刷新文件列表
                    loadFiles()
                } else {
                    val error = result.exceptionOrNull()
                    Log.e(TAG, "创建文件夹失败: ${error?.message}", error)
                    Toast.makeText(
                        this@MainActivity,
                        "创建文件夹失败: ${error?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "创建文件夹异常: ${e.message}", e)
                Toast.makeText(
                    this@MainActivity,
                    "创建文件夹异常: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("确定要退出登录吗？")
            .setPositiveButton("确定") { _, _ ->
                logout()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 分享文件
     */
    private fun shareFile(file: FileItem) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val progressDialog = ProgressDialog(this@MainActivity).apply {
                    setMessage("正在生成分享链接...")
                    setCancelable(false)
                }
                progressDialog.show()

                val result = apiClient.shareFile(file.id)
                progressDialog.dismiss()

                if (result.isSuccess) {
                    val shareResponse = result.getOrNull()
                    val shareToken = shareResponse?.share_token
                    if (shareToken != null) {
                        showShareTokenDialog(shareToken, file.filename)
                    } else {
                        Toast.makeText(this@MainActivity, "分享失败：未获取到分享密钥", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val error = result.exceptionOrNull()
                    Toast.makeText(
                        this@MainActivity,
                        "分享失败: ${error?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "分享异常: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * 分享文件夹
     */
    private fun shareFolder(folder: FolderItem) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val progressDialog = ProgressDialog(this@MainActivity).apply {
                    setMessage("正在生成分享链接...")
                    setCancelable(false)
                }
                progressDialog.show()

                val result = apiClient.shareFolder(folder.id)
                progressDialog.dismiss()

                if (result.isSuccess) {
                    val shareResponse = result.getOrNull()
                    val shareToken = shareResponse?.share_token
                    if (shareToken != null) {
                        showShareTokenDialog(shareToken, folder.folder_name)
                    } else {
                        Toast.makeText(this@MainActivity, "分享失败：未获取到分享密钥", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val error = result.exceptionOrNull()
                    Toast.makeText(
                        this@MainActivity,
                        "分享失败: ${error?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "分享异常: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * 显示分享密钥对话框
     */
    private fun showShareTokenDialog(shareToken: String, itemName: String) {
        val message = "分享密钥已生成！\n\n分享内容: $itemName\n\n分享密钥:\n$shareToken\n\n请复制此密钥分享给他人"

        AlertDialog.Builder(this)
            .setTitle("分享成功")
            .setMessage(message)
            .setPositiveButton("复制密钥") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("分享密钥", shareToken)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "分享密钥已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // 保存要移动的文件对象
    private var pendingMoveFile: FileItem? = null
    
    // 注册文件夹选择Activity结果回调
    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val folderId = result.data?.getIntExtra(SelectFolderActivity.RESULT_FOLDER_ID, Int.MIN_VALUE)
            
            // 使用保存的文件对象
            val file = pendingMoveFile
            if (file != null) {
                val targetFolderId = if (folderId == Int.MIN_VALUE) null else folderId
                moveFileToFolder(file, targetFolderId)
                pendingMoveFile = null  // 清除保存的文件
            } else {
                Toast.makeText(this@MainActivity, "找不到要移动的文件", Toast.LENGTH_SHORT).show()
            }
        } else {
            // 取消时清除保存的文件
            pendingMoveFile = null
        }
    }

    /**
     * 显示移动文件对话框（打开文件夹选择页面）
     */
    private fun showMoveFileDialog(file: FileItem) {
        // 保存要移动的文件对象
        pendingMoveFile = file
        
        val intent = Intent(this, SelectFolderActivity::class.java)
        intent.putExtra(SelectFolderActivity.EXTRA_FILE_ID, file.id)
        selectFolderLauncher.launch(intent)
    }
    
    /**
     * 移动文件到指定文件夹
     */
    private fun moveFileToFolder(file: FileItem, targetFolderId: Int?) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val progressDialog = ProgressDialog(this@MainActivity).apply {
                    setMessage("正在移动文件...")
                    setCancelable(false)
                }
                progressDialog.show()
                
                val result = apiClient.moveFile(file.id, targetFolderId)
                progressDialog.dismiss()
                
                if (result.isSuccess) {
                    Toast.makeText(this@MainActivity, "文件移动成功", Toast.LENGTH_SHORT).show()
                    // 清除相关缓存（源文件夹和目标文件夹）
                    clearCacheForFolder(currentFolderId)
                    clearCacheForFolder(file.folder_id)
                    clearCacheForFolder(targetFolderId)
                    // 刷新当前文件夹列表
                    loadFiles(currentFolderId)
                } else {
                    val error = result.exceptionOrNull()
                    Toast.makeText(
                        this@MainActivity,
                        "移动失败: ${error?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "移动异常: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    /**
     * 显示重命名文件对话框
     */
    private fun showRenameFileDialog(file: FileItem) {
        // 确保使用正确的文件名（filename字段实际上是original_filename）
        val currentFilename = file.filename
        if (currentFilename.isEmpty()) {
            Toast.makeText(this, "无法获取文件名", Toast.LENGTH_SHORT).show()
            return
        }
        
        val input = android.widget.EditText(this)
        input.setText(currentFilename)
        input.selectAll()
        
        AlertDialog.Builder(this)
            .setTitle("重命名文件")
            .setMessage("请输入新文件名")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newFilename = input.text.toString().trim()
                if (newFilename.isEmpty()) {
                    Toast.makeText(this, "文件名不能为空", Toast.LENGTH_SHORT).show()
                } else if (newFilename == currentFilename) {
                    Toast.makeText(this, "文件名未改变", Toast.LENGTH_SHORT).show()
                } else {
                    renameFile(file, newFilename)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 重命名文件
     */
    private fun renameFile(file: FileItem, newFilename: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val progressDialog = ProgressDialog(this@MainActivity).apply {
                    setMessage("正在重命名...")
                    setCancelable(false)
                }
                progressDialog.show()
                
                val result = apiClient.renameFile(file.id, newFilename)
                progressDialog.dismiss()
                
                if (result.isSuccess) {
                    Toast.makeText(this@MainActivity, "重命名成功", Toast.LENGTH_SHORT).show()
                    // 清除当前文件夹缓存
                    clearCacheForFolder(currentFolderId)
                    loadFiles(currentFolderId)
                } else {
                    val error = result.exceptionOrNull()
                    Toast.makeText(
                        this@MainActivity,
                        "重命名失败: ${error?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "重命名异常: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * 显示重命名文件夹对话框
     */
    private fun showRenameFolderDialog(folder: FolderItem) {
        val currentFolderName = folder.folder_name
        if (currentFolderName.isEmpty()) {
            Toast.makeText(this, "无法获取文件夹名", Toast.LENGTH_SHORT).show()
            return
        }
        
        val input = android.widget.EditText(this)
        input.setText(currentFolderName)
        input.selectAll()
        
        AlertDialog.Builder(this)
            .setTitle("重命名文件夹")
            .setMessage("请输入新文件夹名")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newFolderName = input.text.toString().trim()
                if (newFolderName.isEmpty()) {
                    Toast.makeText(this, "文件夹名不能为空", Toast.LENGTH_SHORT).show()
                } else if (newFolderName == currentFolderName) {
                    Toast.makeText(this, "文件夹名未改变", Toast.LENGTH_SHORT).show()
                } else {
                    renameFolder(folder, newFolderName)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 重命名文件夹
     */
    private fun renameFolder(folder: FolderItem, newFolderName: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val progressDialog = ProgressDialog(this@MainActivity).apply {
                    setMessage("正在重命名...")
                    setCancelable(false)
                }
                progressDialog.show()
                
                val result = apiClient.renameFolder(folder.id, newFolderName)
                progressDialog.dismiss()
                
                if (result.isSuccess) {
                    Toast.makeText(this@MainActivity, "重命名成功", Toast.LENGTH_SHORT).show()
                    // 清除当前文件夹缓存
                    clearCacheForFolder(currentFolderId)
                    loadFiles(currentFolderId)
                } else {
                    val error = result.exceptionOrNull()
                    Toast.makeText(
                        this@MainActivity,
                        "重命名失败: ${error?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "重命名异常: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * 显示输入分享密钥对话框
     */
    private fun showShareInputDialog() {
        val input = android.widget.EditText(this)
        input.hint = "请输入分享密钥"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT

        AlertDialog.Builder(this)
            .setTitle("输入分享密钥")
            .setMessage("请输入分享密钥以查看分享的内容")
            .setView(input)
            .setPositiveButton("查看") { _, _ ->
                val shareToken = input.text.toString().trim()
                if (shareToken.isNotEmpty()) {
                    openShareActivity(shareToken)
                } else {
                    Toast.makeText(this, "分享密钥不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 打开分享内容页面
     */
    private fun openShareActivity(shareToken: String) {
        val intent = Intent(this, ShareActivity::class.java)
        intent.putExtra("share_token", shareToken)
        shareActivityLauncher.launch(intent)
    }


    private fun logout() {
        sharedPrefManager.clear()
        navigateToLogin()
        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    override fun onResume() {
        super.onResume()
        // 当Activity重新显示时，检查登录状态
        if (!sharedPrefManager.isLoggedIn()) {
            navigateToLogin()
            return
        }

        // 确保令牌已设置
        val accessToken = sharedPrefManager.getAccessToken()
        if (accessToken.isNotEmpty()) {
            apiClient.setAuthToken(accessToken)
            Log.d(TAG, "onResume: 已重新设置AuthToken")
        }
    }
}