package com.example.personalclouddisk.ui

import android.app.AlertDialog
import android.app.ProgressDialog
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.personalclouddisk.databinding.ActivityShareBinding
import com.example.personalclouddisk.network.ApiClient
import com.example.personalclouddisk.network.FileItem
import com.example.personalclouddisk.network.FolderItem
import com.example.personalclouddisk.ui.adapter.FileAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ShareActivity : AppCompatActivity() {
    private lateinit var binding: ActivityShareBinding
    private lateinit var apiClient: ApiClient
    private lateinit var fileAdapter: FileAdapter
    private var shareToken: String = ""
    private var currentFolderId: Int? = null
    private var currentFolderName: String? = null
    private val folderPathStack = mutableListOf<Pair<Int?, String?>>()  // 文件夹路径栈
    private var sharedContentType: String = "file"  // 分享类型："file" 或 "folder"
    private var rootSharedContent: com.example.personalclouddisk.network.SharedContent? = null  // 根分享内容
    private val selectedFiles = mutableSetOf<Int>()  // 选中的文件ID集合
    private val selectedFolders = mutableSetOf<Int>()  // 选中的文件夹ID集合

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        shareToken = intent.getStringExtra("share_token") ?: ""
        if (shareToken.isEmpty()) {
            Toast.makeText(this, "分享密钥无效", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        apiClient = ApiClient.getInstance(this)

        setupUI()
        loadSharedContent()
    }

    private fun setupUI() {
        // 设置返回按钮
        binding.toolbar.setNavigationOnClickListener {
            if (currentFolderId != null) {
                // 如果在子文件夹中，返回上一级
                goBackToParentFolder()
            } else {
                // 如果在根目录，关闭页面
                finish()
            }
        }

        // 设置适配器（分享页面支持选择和文件夹导航）
        fileAdapter = FileAdapter(
            onItemClickListener = { item ->
                when (item) {
                    is com.example.personalclouddisk.ui.adapter.ListItem.File -> {
                        // 文件点击：切换选择状态
                        toggleFileSelection(item.file.id)
                    }
                    is com.example.personalclouddisk.ui.adapter.ListItem.Folder -> {
                        // 文件夹点击：进入文件夹
                        enterFolder(item.folder)
                    }
                }
            },
            onDownloadClickListener = { file ->
                // 分享页面不支持下载
            },
            onDeleteClickListener = { file ->
                // 分享页面不支持删除
            },
            onFolderClickListener = { folder ->
                // 文件夹点击：进入文件夹
                enterFolder(folder)
            },
            onDeleteFolderClickListener = { folder ->
                // 分享页面不支持删除
            },
            onShareFileClickListener = { file ->
                // 分享页面不支持再次分享
            },
            onShareFolderClickListener = { folder ->
                // 分享页面不支持再次分享
            },
            hideActionButtons = true,  // 隐藏所有操作按钮
            isSelectionMode = true,  // 启用选择模式
            selectedFileIds = selectedFiles,  // 选中的文件ID集合
            selectedFolderIds = selectedFolders,  // 选中的文件夹ID集合
            onSelectionChanged = { fileId, isSelected ->
                if (isSelected) {
                    selectedFiles.add(fileId)
                } else {
                    selectedFiles.remove(fileId)
                }
                updateSaveButtonText()
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@ShareActivity)
            adapter = fileAdapter
        }

        // 保存按钮
        binding.btnSave.setOnClickListener {
            showSaveDialog()
        }
    }

    private fun loadSharedContent() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE

                val result = apiClient.getSharedContent(shareToken)

                binding.progressBar.visibility = View.GONE

                if (result.isSuccess) {
                    val sharedContent = result.getOrThrow()
                    Log.d("ShareActivity", "Loaded shared content: type=${sharedContent.type}, files=${sharedContent.files.size}, folders=${sharedContent.folders.size}")

                    // 保存根分享内容
                    rootSharedContent = sharedContent
                    sharedContentType = sharedContent.type

                    // 如果是文件夹类型，显示文件夹和文件；如果是文件类型，只显示文件
                    if (sharedContent.type == "folder") {
                        // 文件夹分享：显示文件夹和文件，支持导航
                        // 根文件夹是folders列表的第一个（后端保证）
                        val rootFolder = sharedContent.folders.firstOrNull()

                        val rootFolderId = rootFolder?.id

                        // 只显示根文件夹的直接子文件和子文件夹（排除根文件夹本身）
                        val rootFiles = if (rootFolderId != null) {
                            sharedContent.files.filter { it.folder_id == rootFolderId }
                        } else {
                            // 如果没有找到根文件夹，显示所有没有folder_id的文件（根目录文件）
                            sharedContent.files.filter { it.folder_id == null }
                        }

                        val rootFolders = if (rootFolderId != null) {
                            sharedContent.folders.filter {
                                it.parent_folder_id == rootFolderId && it.id != rootFolderId
                            }
                        } else {
                            // 如果没有找到根文件夹，显示所有没有parent_folder_id的文件夹（排除根文件夹本身）
                            sharedContent.folders.filter {
                                it.parent_folder_id == null && it.id != rootFolderId
                            }
                        }

                        // 保存根文件夹ID和名称（用于后续导航）
                        if (rootFolder != null) {
                            currentFolderId = rootFolder.id
                            currentFolderName = rootFolder.folder_name
                        } else {
                            currentFolderId = null
                            currentFolderName = null
                        }

                        updateFileList(rootFolders, rootFiles)
                        // 显示分享者信息
                        val ownerInfo = if (sharedContent.owner != null && sharedContent.owner.isNotEmpty()) {
                            "来自${sharedContent.owner}的文件夹"
                        } else {
                            "分享文件夹: ${sharedContent.folder_name ?: "未知"}"
                        }
                        binding.toolbar.title = ownerInfo
                    } else {
                        // 文件分享：只显示文件
                        updateFileList(emptyList(), sharedContent.files)
                        // 显示分享者信息
                        val ownerInfo = if (sharedContent.owner != null && sharedContent.owner.isNotEmpty()) {
                            "来自${sharedContent.owner}的文件"
                        } else {
                            "分享文件: ${sharedContent.filename ?: "未知"}"
                        }
                        binding.toolbar.title = ownerInfo
                    }

                    binding.recyclerView.visibility = View.VISIBLE
                    updateSaveButtonText()
                    updateToolbar()
                } else {
                    val error = result.exceptionOrNull()
                    Toast.makeText(
                        this@ShareActivity,
                        "加载失败: ${error?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.e("ShareActivity", "Load shared content failed: ${error?.message}")
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(
                    this@ShareActivity,
                    "加载异常: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                Log.e("ShareActivity", "Load shared content exception: ${e.message}", e)
            }
        }
    }

    private fun updateFileList(folders: List<FolderItem>, files: List<FileItem>) {
        fileAdapter.updateItems(folders, files)

        if (folders.isEmpty() && files.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.layoutEmpty.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    private fun enterFolder(folder: FolderItem) {
        Log.d("ShareActivity", "进入文件夹: ${folder.folder_name} (ID: ${folder.id})")

        // 将当前文件夹添加到路径栈
        if (currentFolderId != null && currentFolderName != null) {
            folderPathStack.add(Pair(currentFolderId, currentFolderName))
        }

        // 更新当前文件夹ID和名称
        currentFolderId = folder.id
        currentFolderName = folder.folder_name

        // 加载该文件夹内的内容
        loadFolderContent(folder.id)
    }

    private fun goBackToParentFolder() {
        if (folderPathStack.isNotEmpty()) {
            val (parentId, parentName) = folderPathStack.removeAt(folderPathStack.size - 1)
            currentFolderId = parentId
            currentFolderName = parentName
            if (parentId != null) {
                loadFolderContent(parentId)
            } else {
                // 返回根目录
                rootSharedContent?.let {
                    if (it.type == "folder") {
                        // 根文件夹是folders列表的第一个
                        val rootFolder = it.folders.firstOrNull()
                        val rootFolderId = rootFolder?.id
                        val rootFiles = if (rootFolderId != null) {
                            it.files.filter { file -> file.folder_id == rootFolderId }
                        } else {
                            it.files.filter { file -> file.folder_id == null }
                        }
                        val rootFolders = if (rootFolderId != null) {
                            it.folders.filter { folder ->
                                folder.parent_folder_id == rootFolderId && folder.id != rootFolderId
                            }
                        } else {
                            it.folders.filter { folder ->
                                folder.parent_folder_id == null && folder.id != rootFolderId
                            }
                        }

                        if (rootFolder != null) {
                            currentFolderId = rootFolder.id
                            currentFolderName = rootFolder.folder_name
                        }

                        updateFileList(rootFolders, rootFiles)
                    } else {
                        updateFileList(emptyList(), it.files)
                    }
                }
            }
        } else {
            // 返回根目录
            rootSharedContent?.let {
                if (it.type == "folder") {
                    // 根文件夹是folders列表的第一个
                    val rootFolder = it.folders.firstOrNull()
                    val rootFolderId = rootFolder?.id
                    val rootFiles = if (rootFolderId != null) {
                        it.files.filter { file -> file.folder_id == rootFolderId }
                    } else {
                        it.files.filter { file -> file.folder_id == null }
                    }
                    val rootFolders = if (rootFolderId != null) {
                        it.folders.filter { folder ->
                            folder.parent_folder_id == rootFolderId && folder.id != rootFolderId
                        }
                    } else {
                        it.folders.filter { folder ->
                            folder.parent_folder_id == null && folder.id != rootFolderId
                        }
                    }

                    if (rootFolder != null) {
                        currentFolderId = rootFolder.id
                        currentFolderName = rootFolder.folder_name
                    } else {
                        currentFolderId = null
                        currentFolderName = null
                    }

                    updateFileList(rootFolders, rootFiles)
                } else {
                    currentFolderId = null
                    currentFolderName = null
                    updateFileList(emptyList(), it.files)
                }
            }
        }
        updateToolbar()
    }

    private fun loadFolderContent(folderId: Int) {
        // 使用已保存的 rootSharedContent，避免重复请求
        rootSharedContent?.let { sharedContent ->
            if (sharedContent.type == "folder") {
                // 查找指定文件夹内的文件和子文件夹
                // 注意：由于后端现在返回所有递归的文件和文件夹，我们需要过滤出当前文件夹的直接子项
                val filesInFolder = sharedContent.files.filter { it.folder_id == folderId }
                val foldersInFolder = sharedContent.folders.filter { it.parent_folder_id == folderId }

                Log.d("ShareActivity", "加载文件夹内容: folderId=$folderId, 找到 ${foldersInFolder.size} 个文件夹, ${filesInFolder.size} 个文件")

                updateFileList(foldersInFolder, filesInFolder)
                updateToolbar()
                updateSharePath()
            }
        } ?: run {
            // 如果没有保存的共享内容，重新加载
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE

                    val result = apiClient.getSharedContent(shareToken)
                    binding.progressBar.visibility = View.GONE

                    if (result.isSuccess) {
                        val sharedContent = result.getOrThrow()
                        rootSharedContent = sharedContent

                        if (sharedContent.type == "folder") {
                            val filesInFolder = sharedContent.files.filter { it.folder_id == folderId }
                            val foldersInFolder = sharedContent.folders.filter { it.parent_folder_id == folderId }

                            updateFileList(foldersInFolder, filesInFolder)
                            updateToolbar()
                            updateSharePath()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ShareActivity", "加载文件夹内容失败: ${e.message}", e)
                    Toast.makeText(this@ShareActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateToolbar() {
        // 更新工具栏标题和返回按钮
        val title = if (currentFolderName != null) {
            currentFolderName
        } else {
            rootSharedContent?.let {
                if (it.type == "folder") {
                    // 显示分享者信息
                    if (it.owner != null && it.owner.isNotEmpty()) {
                        "来自${it.owner}的文件夹"
                    } else {
                        "分享文件夹: ${it.folder_name ?: "未知"}"
                    }
                } else {
                    // 显示分享者信息
                    if (it.owner != null && it.owner.isNotEmpty()) {
                        "来自${it.owner}的文件"
                    } else {
                        "分享文件: ${it.filename ?: "未知"}"
                    }
                }
            } ?: "分享内容"
        }
        binding.toolbar.title = title

        // 更新路径显示
        updateSharePath()
    }

    private fun updateSharePath() {
        // 只在分享文件夹时显示路径
        if (rootSharedContent?.type != "folder") {
            binding.layoutPath.visibility = View.GONE
            return
        }

        // 构建当前路径字符串
        val path = buildString {
            // 根文件夹名称
            rootSharedContent?.folder_name?.let {
                append("/").append(it)
            } ?: append("/")

            // 添加路径栈中的文件夹
            folderPathStack.forEach { (_, name) ->
                name?.let {
                    append("/").append(it)
                }
            }

            // 添加当前文件夹
            currentFolderName?.let {
                append("/").append(it)
            }
        }

        // 更新路径TextView
        binding.tvSharePath.text = path
        binding.layoutPath.visibility = View.VISIBLE
    }

    private fun toggleFileSelection(fileId: Int) {
        if (selectedFiles.contains(fileId)) {
            selectedFiles.remove(fileId)
        } else {
            selectedFiles.add(fileId)
        }
        fileAdapter.notifyDataSetChanged()
        updateSaveButtonText()
    }

    private fun updateSaveButtonText() {
        val selectedCount = selectedFiles.size + selectedFolders.size
        if (selectedCount > 0) {
            binding.btnSave.text = "保存选中项 ($selectedCount)"
        } else {
            binding.btnSave.text = "保存到我的云盘"
        }
    }

    private fun showSaveDialog() {
        // 检查是否有选中的文件
        if (selectedFiles.isEmpty() && selectedFolders.isEmpty()) {
            Toast.makeText(this, "请先选择要保存的文件", Toast.LENGTH_SHORT).show()
            return
        }

        // 获取用户的所有文件夹用于选择保存位置
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val foldersResult = apiClient.getFolders()
                val folders = if (foldersResult.isSuccess) {
                    foldersResult.getOrNull() ?: emptyList()
                } else {
                    emptyList()
                }

                val folderNames = mutableListOf("根目录")
                val folderIds = mutableListOf<Int?>(null)
                folders.forEach { folder ->
                    folderNames.add(folder.folder_name)
                    folderIds.add(folder.id)
                }

                val items = folderNames.toTypedArray()
                var selectedIndex = 0

                AlertDialog.Builder(this@ShareActivity)
                    .setTitle("选择保存位置")
                    .setSingleChoiceItems(items, 0) { _, which ->
                        selectedIndex = which
                    }
                    .setPositiveButton("保存") { _, _ ->
                        val targetFolderId = folderIds[selectedIndex]
                        saveSelectedContent(targetFolderId)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ShareActivity,
                    "获取文件夹列表失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun saveSelectedContent(targetFolderId: Int?) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val progressDialog = ProgressDialog(this@ShareActivity).apply {
                    setMessage("正在保存选中项...")
                    setCancelable(false)
                }
                progressDialog.show()

                // 收集所有选中的文件ID
                val selectedFileIds = selectedFiles.toList()
                val selectedFolderIds = selectedFolders.toList()

                if (selectedFileIds.isEmpty() && selectedFolderIds.isEmpty()) {
                    progressDialog.dismiss()
                    Toast.makeText(this@ShareActivity, "请先选择要保存的文件", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 调用API保存选中的文件
                val result = apiClient.saveSelectedSharedContent(
                    shareToken = shareToken,
                    fileIds = selectedFileIds,
                    folderIds = selectedFolderIds,
                    targetFolderId = targetFolderId
                )
                progressDialog.dismiss()

                if (result.isSuccess) {
                    val savedCount = result.getOrNull() ?: 0
                    val location = if (targetFolderId == null) "根目录" else "指定文件夹"
                    Toast.makeText(
                        this@ShareActivity,
                        "保存成功！已保存 $savedCount 项到$location",
                        Toast.LENGTH_LONG
                    ).show()

                    // 通知MainActivity刷新文件列表
                    setResult(RESULT_OK)

                    // 关闭页面
                    finish()
                } else {
                    val error = result.exceptionOrNull()
                    Toast.makeText(
                        this@ShareActivity,
                        "保存失败: ${error?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@ShareActivity,
                    "保存异常: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

