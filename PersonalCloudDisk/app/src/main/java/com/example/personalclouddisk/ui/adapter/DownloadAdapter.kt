package com.example.personalclouddisk.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.personalclouddisk.R
import com.example.personalclouddisk.ui.DownloadItem

class DownloadAdapter(
    private val downloadList: MutableList<DownloadItem>,
    private val onPauseClickListener: (DownloadItem) -> Unit,
    private val onItemLongClickListener: ((DownloadItem) -> Unit)? = null
) : RecyclerView.Adapter<DownloadAdapter.DownloadViewHolder>() {

    var isSelectionMode = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val selectedItems = mutableSetOf<Int>()

    fun getSelectedItems(): Set<Int> = selectedItems.toSet()

    fun toggleSelection(fileId: Int) {
        if (selectedItems.contains(fileId)) {
            selectedItems.remove(fileId)
        } else {
            selectedItems.add(fileId)
        }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return DownloadViewHolder(view)
    }

    override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
        val item = downloadList[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = downloadList.size

    fun updateItem(item: DownloadItem) {
        val index = downloadList.indexOfFirst { it.fileId == item.fileId }
        if (index != -1) {
            downloadList[index] = item
            notifyItemChanged(index)
        }
    }

    fun addItem(item: DownloadItem) {
        // 检查是否已存在，避免重复添加
        if (downloadList.none { it.fileId == item.fileId }) {
            downloadList.add(item)
            notifyItemInserted(downloadList.size - 1)
        }
    }

    fun removeItem(fileId: Int) {
        // 删除所有匹配的项目（虽然理论上不应该有重复，但为了安全起见）
        var removed = false
        var lastIndex = -1
        while (true) {
            val index = downloadList.indexOfFirst { it.fileId == fileId }
            if (index != -1) {
                downloadList.removeAt(index)
                lastIndex = index
                removed = true
            } else {
                break
            }
        }
        if (removed && lastIndex != -1) {
            // 通知适配器数据已更改
            notifyDataSetChanged()
        }
    }

    inner class DownloadViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val tvProgress: TextView = itemView.findViewById(R.id.tvProgress)
        private val tvSpeed: TextView = itemView.findViewById(R.id.tvSpeed)
        private val btnPause: ImageButton = itemView.findViewById(R.id.btnPause)
        private val checkboxSelect: CheckBox = itemView.findViewById(R.id.checkboxSelect)

        fun bind(item: DownloadItem) {
            // 设置选择模式
            if (isSelectionMode) {
                checkboxSelect?.visibility = View.VISIBLE
                btnPause.visibility = View.GONE
                itemView.isSelected = selectedItems.contains(item.fileId)
                checkboxSelect?.isChecked = selectedItems.contains(item.fileId)
                checkboxSelect?.setOnClickListener {
                    toggleSelection(item.fileId)
                }
                itemView.setOnClickListener {
                    toggleSelection(item.fileId)
                }
            } else {
                checkboxSelect?.visibility = View.GONE
                itemView.isSelected = false
                itemView.setOnClickListener(null)
                itemView.setOnLongClickListener {
                    onItemLongClickListener?.invoke(item)
                    true
                }
            }
            tvFileName.text = item.fileName

            // 根据状态显示不同的UI
            when (item.status) {
                DownloadItem.DownloadStatus.COMPLETED -> {
                    // 下载完成：隐藏进度条，显示"已完成"
                    progressBar.visibility = View.GONE
                    tvProgress.text = "已完成"
                    tvProgress.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark))
                    tvSpeed.text = ""
                    // 隐藏暂停按钮
                    if (!isSelectionMode) {
                        btnPause.visibility = View.GONE
                    }
                }
                DownloadItem.DownloadStatus.FAILED -> {
                    // 下载失败：隐藏进度条，显示"下载失败"
                    progressBar.visibility = View.GONE
                    tvProgress.text = "下载失败"
                    tvProgress.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark))
                    tvSpeed.text = ""
                    // 显示重试按钮
                    if (!isSelectionMode) {
                        btnPause.visibility = View.VISIBLE
                        btnPause.setImageResource(android.R.drawable.ic_menu_revert)
                        btnPause.contentDescription = "重试"
                        btnPause.setOnClickListener {
                            onPauseClickListener(item)
                        }
                    }
                }
                DownloadItem.DownloadStatus.DOWNLOADING, DownloadItem.DownloadStatus.PAUSED -> {
                    // 下载中或暂停：显示进度条和百分比
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = item.progress
                    tvProgress.text = "${item.progress}%"
                    tvProgress.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.darker_gray))
                    
                    // 显示下载速度（仅在下载中时显示）
                    if (item.status == DownloadItem.DownloadStatus.DOWNLOADING && item.speed > 0) {
                        val speedText = when {
                            item.speed >= 1024 * 1024 -> String.format("%.2f MB/s", item.speed / (1024.0 * 1024.0))
                            item.speed >= 1024 -> String.format("%.2f KB/s", item.speed / 1024.0)
                            else -> "${item.speed} B/s"
                        }
                        tvSpeed.text = speedText
                    } else {
                        tvSpeed.text = ""
                    }

                    // 显示暂停/继续按钮
                    if (!isSelectionMode) {
                        btnPause.visibility = View.VISIBLE
                        when (item.status) {
                            DownloadItem.DownloadStatus.DOWNLOADING -> {
                                btnPause.setImageResource(android.R.drawable.ic_media_pause)
                                btnPause.contentDescription = "暂停"
                            }
                            DownloadItem.DownloadStatus.PAUSED -> {
                                btnPause.setImageResource(android.R.drawable.ic_media_play)
                                btnPause.contentDescription = "继续"
                            }
                            else -> {}
                        }
                        btnPause.setOnClickListener {
                            onPauseClickListener(item)
                        }
                    }
                }
            }
        }
    }
}

