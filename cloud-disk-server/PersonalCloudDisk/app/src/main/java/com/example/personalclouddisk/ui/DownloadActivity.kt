package com.example.personalclouddisk.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.personalclouddisk.databinding.ActivityDownloadBinding
import com.example.personalclouddisk.ui.adapter.DownloadAdapter
import com.example.personalclouddisk.ui.UploadProgressCenter

class DownloadActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDownloadBinding
    private lateinit var downloadAdapter: DownloadAdapter
    private val downloadList = mutableListOf<DownloadItem>()
    private val uploadListener = object : UploadProgressCenter.Listener {
        override fun onStateChanged(state: UploadProgressCenter.State) {
            runOnUiThread {
                if (state.uploading) {
                    binding.cardUploadProgress.visibility = View.VISIBLE
                    binding.tvUploadStatus.text = "正在上传: ${state.fileName ?: ""}"
                    binding.progressBarUpload.progress = state.progress
                    binding.tvUploadPercent.text = "${state.progress}%"
                } else {
                    binding.cardUploadProgress.visibility = View.GONE
                }
            }
        }
    }

    companion object {
        @Volatile
        private var downloadManager: DownloadManager? = null

        fun getDownloadManager(): DownloadManager? = downloadManager

        fun setDownloadManager(manager: DownloadManager) {
            downloadManager = manager
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置返回按钮
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "上传&下载"

        setupUI()
        updateUI()

        // 同步当前上传状态
        UploadProgressCenter.addListener(uploadListener)
    }

    private fun setupUI() {
        // 设置RecyclerView
        downloadAdapter = DownloadAdapter(
            downloadList,
            onPauseClickListener = { item ->
                // 暂停/继续/重试按钮点击
                getDownloadManager()?.toggleDownload(item.fileId)
            },
            onItemLongClickListener = { item ->
                // 长按进入选择模式
                if (!downloadAdapter.isSelectionMode) {
                    downloadAdapter.isSelectionMode = true
                    downloadAdapter.toggleSelection(item.fileId)
                    updateManageButtons()
                }
            }
        )

        binding.recyclerViewDownloads.apply {
            layoutManager = LinearLayoutManager(this@DownloadActivity)
            adapter = downloadAdapter
        }

        // 注册下载管理器监听器
        getDownloadManager()?.addListener(object : DownloadManager.DownloadListener {
            override fun onDownloadAdded(item: DownloadItem) {
                runOnUiThread {
                    // addItem 内部已经检查重复，这里直接调用即可
                    downloadAdapter.addItem(item)
                    updateUI()
                }
            }

            override fun onDownloadUpdated(item: DownloadItem) {
                runOnUiThread {
                    val index = downloadList.indexOfFirst { it.fileId == item.fileId }
                    if (index != -1) {
                        downloadList[index] = item
                    }
                    downloadAdapter.updateItem(item)
                }
            }

            override fun onDownloadRemoved(fileId: Int) {
                runOnUiThread {
                    // removeItem 内部已经处理了删除，这里只需要调用即可
                    downloadAdapter.removeItem(fileId)
                    updateUI()
                }
            }
        })

        // 加载现有下载任务
        getDownloadManager()?.getAllDownloads()?.forEach { item ->
            // addItem 内部已经检查重复，这里直接调用即可
            downloadAdapter.addItem(item)
        }
        updateUI()

        // 设置管理按钮
        binding.btnManage.setOnClickListener {
            downloadAdapter.isSelectionMode = true
            updateManageButtons()
        }

        binding.btnDeleteSelected.setOnClickListener {
            deleteSelectedDownloads()
        }

        binding.btnCancelSelection.setOnClickListener {
            downloadAdapter.isSelectionMode = false
            downloadAdapter.clearSelection()
            updateManageButtons()
        }
    }

    private fun updateManageButtons() {
        if (downloadAdapter.isSelectionMode) {
            binding.btnManage.visibility = View.GONE
            binding.btnDeleteSelected.visibility = View.VISIBLE
            binding.btnCancelSelection.visibility = View.VISIBLE
        } else {
            binding.btnManage.visibility = View.VISIBLE
            binding.btnDeleteSelected.visibility = View.GONE
            binding.btnCancelSelection.visibility = View.GONE
        }
    }

    private fun deleteSelectedDownloads() {
        val selectedIds = downloadAdapter.getSelectedItems()
        if (selectedIds.isEmpty()) {
            android.widget.Toast.makeText(this, "请选择要删除的下载记录", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("删除下载记录")
            .setMessage("确定要删除选中的 ${selectedIds.size} 条下载记录吗？")
            .setPositiveButton("删除") { _, _ ->
                getDownloadManager()?.removeDownloads(selectedIds.toList())
                downloadAdapter.isSelectionMode = false
                downloadAdapter.clearSelection()
                updateManageButtons()
                android.widget.Toast.makeText(this, "已删除 ${selectedIds.size} 条下载记录", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateUI() {
        if (downloadList.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.recyclerViewDownloads.visibility = View.GONE
        } else {
            binding.layoutEmpty.visibility = View.GONE
            binding.recyclerViewDownloads.visibility = View.VISIBLE
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        UploadProgressCenter.removeListener(uploadListener)
    }
}

