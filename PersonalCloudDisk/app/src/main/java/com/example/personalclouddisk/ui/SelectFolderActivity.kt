package com.example.personalclouddisk.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.personalclouddisk.databinding.ActivitySelectFolderBinding
import com.example.personalclouddisk.network.ApiClient
import com.example.personalclouddisk.network.FolderItem
import com.example.personalclouddisk.ui.adapter.FileAdapter
import com.example.personalclouddisk.ui.adapter.ListItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SelectFolderActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySelectFolderBinding
    private lateinit var apiClient: ApiClient
    private lateinit var fileAdapter: FileAdapter
    
    private var currentFolderId: Int? = null
    private val folderPathStack = mutableListOf<Pair<Int?, String>>()
    private var selectedFolderId: Int? = null

    companion object {
        const val EXTRA_FILE_ID = "file_id"
        const val RESULT_FOLDER_ID = "folder_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelectFolderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiClient = ApiClient.getInstance(this)

        setupUI()
        loadFolders(null)
    }

    private fun setupUI() {
        // 设置工具栏
        binding.toolbar.setNavigationOnClickListener {
            if (folderPathStack.isNotEmpty()) {
                goBackToParentFolder()
            } else {
                setResult(RESULT_CANCELED)
                finish()
            }
        }

        // 设置RecyclerView
        fileAdapter = FileAdapter(
            mutableListOf(),
            onItemClickListener = { item ->
                when (item) {
                    is ListItem.Folder -> {
                        enterFolder(item.folder)
                    }
                    is ListItem.File -> {
                        // 文件不能作为目标文件夹
                    }
                }
            },
            onDownloadClickListener = {},
            onDeleteClickListener = {},
            onFolderClickListener = { folder ->
                enterFolder(folder)
            },
            onDeleteFolderClickListener = {},
            onShareFileClickListener = {},
            onShareFolderClickListener = {},
            onMoveFileClickListener = {},
            onRenameFileClickListener = {},
            hideActionButtons = true  // 隐藏所有操作按钮
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@SelectFolderActivity)
            adapter = fileAdapter
        }

        // 确认按钮
        binding.btnConfirm.setOnClickListener {
            val resultIntent = Intent().apply {
                val fileId = intent.getIntExtra(EXTRA_FILE_ID, -1)
                putExtra(EXTRA_FILE_ID, fileId)
                // 如果 selectedFolderId 为 null，表示选择根目录，使用特殊值
                putExtra(RESULT_FOLDER_ID, selectedFolderId ?: Int.MIN_VALUE)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        updateToolbar()
    }

    private fun loadFolders(parentFolderId: Int?) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val foldersResult = withContext(Dispatchers.IO) {
                    apiClient.getFolders(parentFolderId = parentFolderId)
                }

                val folders = if (foldersResult.isSuccess) {
                    foldersResult.getOrNull() ?: emptyList()
                } else {
                    emptyList()
                }

                // 只显示文件夹，不显示文件
                updateFolderList(folders)
                updatePath()
            } catch (e: Exception) {
                Toast.makeText(
                    this@SelectFolderActivity,
                    "加载文件夹失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateFolderList(folders: List<FolderItem>) {
        val sortedFolders = folders.sortedBy { it.folder_name.lowercase() }
        
        // 如果在根目录，添加"根目录"选项
        val foldersToShow = if (currentFolderId == null) {
            val rootFolder = FolderItem(
                id = Int.MIN_VALUE,
                folder_name = "根目录",
                created_date = null,
                owner_id = 0,
                parent_folder_id = null
            )
            listOf(rootFolder) + sortedFolders
        } else {
            sortedFolders
        }
        
        fileAdapter.updateItems(foldersToShow, emptyList())
        
        if (foldersToShow.isEmpty()) {
            binding.layoutEmpty.visibility = android.view.View.VISIBLE
            binding.recyclerView.visibility = android.view.View.GONE
        } else {
            binding.layoutEmpty.visibility = android.view.View.GONE
            binding.recyclerView.visibility = android.view.View.VISIBLE
        }
    }

    private fun enterFolder(folder: FolderItem) {
        // 如果点击的是"根目录"选项，直接选择根目录
        if (folder.id == Int.MIN_VALUE) {
            selectedFolderId = null
            val resultIntent = Intent().apply {
                val fileId = intent.getIntExtra(EXTRA_FILE_ID, -1)
                putExtra(EXTRA_FILE_ID, fileId)
                putExtra(RESULT_FOLDER_ID, Int.MIN_VALUE)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
            return
        }
        
        folderPathStack.add(Pair(currentFolderId, currentFolderId?.let { "文件夹" } ?: "我的文件"))
        currentFolderId = folder.id
        selectedFolderId = folder.id
        updateToolbar()
        loadFolders(folder.id)
    }

    private fun goBackToParentFolder() {
        if (folderPathStack.isNotEmpty()) {
            val (parentId, _) = folderPathStack.removeAt(folderPathStack.size - 1)
            currentFolderId = parentId
            selectedFolderId = parentId
            updateToolbar()
            loadFolders(parentId)
        } else {
            currentFolderId = null
            selectedFolderId = null
            updateToolbar()
            loadFolders(null)
        }
    }

    private fun updateToolbar() {
        binding.toolbar.title = "选择目标文件夹"
        // 始终显示返回按钮
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun updatePath() {
        val pathParts = mutableListOf<String>()
        pathParts.add("我的文件")
        
        // 构建路径（简化版，只显示当前文件夹）
        if (currentFolderId != null) {
            pathParts.add("...")
        }
        
        binding.tvPath.text = pathParts.joinToString(" / ")
    }
}

