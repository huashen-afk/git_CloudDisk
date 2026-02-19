package com.example.personalclouddisk.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.personalclouddisk.R
import com.example.personalclouddisk.network.FileItem
import com.example.personalclouddisk.network.FolderItem
import java.text.SimpleDateFormat
import java.util.*

// 统一的列表项类型
sealed class ListItem {
    data class File(val file: FileItem) : ListItem()
    data class Folder(val folder: FolderItem) : ListItem()
}

class FileAdapter(
    private val itemList: MutableList<ListItem> = mutableListOf(),
    private val onItemClickListener: (ListItem) -> Unit = {},
    private val onDownloadClickListener: (FileItem) -> Unit = {},
    private val onDeleteClickListener: (FileItem) -> Unit = {},
    private val onFolderClickListener: (FolderItem) -> Unit = {},
    private val onDeleteFolderClickListener: (FolderItem) -> Unit = {},
    private val onShareFileClickListener: (FileItem) -> Unit = {},
    private val onShareFolderClickListener: (FolderItem) -> Unit = {},
    private val onMoveFileClickListener: (FileItem) -> Unit = {},  // 移动文件回调
    private val onRenameFileClickListener: (FileItem) -> Unit = {},  // 重命名文件回调
    private val onRenameFolderClickListener: (FolderItem) -> Unit = {},  // 重命名文件夹回调
    private val hideActionButtons: Boolean = false,  // 是否隐藏所有操作按钮（用于分享页面）
    private val isSelectionMode: Boolean = false,  // 是否启用选择模式
    private val selectedFileIds: MutableSet<Int> = mutableSetOf(),  // 选中的文件ID集合
    private val selectedFolderIds: MutableSet<Int> = mutableSetOf(),  // 选中的文件夹ID集合
    private val onSelectionChanged: ((Int, Boolean) -> Unit)? = null  // 选择状态改变回调
) : RecyclerView.Adapter<FileAdapter.ItemViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        android.util.Log.d("FileAdapter", "onCreateViewHolder被调用，viewType: $viewType")
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        android.util.Log.d("FileAdapter", "ViewHolder已创建")
        return ItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        if (position < itemList.size) {
            val item = itemList[position]
            val itemInfo = when (item) {
                is ListItem.File -> "文件: ${item.file.filename}"
                is ListItem.Folder -> "文件夹: ${item.folder.folder_name}"
            }
            android.util.Log.d("FileAdapter", "绑定项目[$position]: $itemInfo")
            holder.bind(item)
        } else {
            android.util.Log.e("FileAdapter", "位置越界: position=$position, size=${itemList.size}")
        }
    }

    override fun getItemCount(): Int {
        val count = itemList.size
        android.util.Log.d("FileAdapter", "getItemCount被调用: $count")
        return count
    }

    fun updateItems(folders: List<FolderItem>, files: List<FileItem>) {
        android.util.Log.d("FileAdapter", "updateItems: 输入 - 文件夹数量: ${folders.size}, 文件数量: ${files.size}")
        val oldSize = itemList.size

        // 先清空列表
        itemList.clear()

        // 使用Map来去重（基于ID），保留最新的数据
        val folderMap = mutableMapOf<Int, FolderItem>()
        val fileMap = mutableMapOf<Int, FileItem>()

        // 收集文件夹（去重）
        folders.forEach { folder ->
            if (!folderMap.containsKey(folder.id)) {
                folderMap[folder.id] = folder
                android.util.Log.d("FileAdapter", "收集文件夹: ${folder.folder_name} (ID: ${folder.id})")
            } else {
                android.util.Log.w("FileAdapter", "跳过重复文件夹: ${folder.folder_name} (ID: ${folder.id})")
            }
        }

        // 收集文件（去重）
        files.forEach { file ->
            if (!fileMap.containsKey(file.id)) {
                fileMap[file.id] = file
                android.util.Log.d("FileAdapter", "收集文件: ${file.filename} (ID: ${file.id}, folder_id: ${file.folder_id})")
            } else {
                android.util.Log.w("FileAdapter", "跳过重复文件: ${file.filename} (ID: ${file.id})")
            }
        }

        // 先添加文件夹（按字典序排序），再添加文件（按字典序排序）
        folderMap.values.sortedBy { it.folder_name.lowercase() }.forEach { folder ->
            itemList.add(ListItem.Folder(folder))
        }
        fileMap.values.sortedBy { it.filename.lowercase() }.forEach { file ->
            itemList.add(ListItem.File(file))
        }

        android.util.Log.d("FileAdapter", "列表更新: 之前=$oldSize, 现在=${itemList.size} (文件夹: ${folderMap.size}, 文件: ${fileMap.size})")
        notifyDataSetChanged()
        android.util.Log.d("FileAdapter", "已调用 notifyDataSetChanged()")
    }

    fun addFile(file: FileItem) {
        // 检查是否已存在相同ID的文件，避免重复
        val exists = itemList.any {
            it is ListItem.File && it.file.id == file.id
        }
        if (!exists) {
            itemList.add(0, ListItem.File(file))
            notifyItemInserted(0)
            android.util.Log.d("FileAdapter", "添加文件到列表: ${file.filename} (ID: ${file.id})")
        } else {
            android.util.Log.d("FileAdapter", "文件已存在，跳过添加: ${file.filename} (ID: ${file.id})")
        }
    }

    fun removeFile(file: FileItem) {
        val position = itemList.indexOfFirst {
            it is ListItem.File && it.file.id == file.id
        }
        if (position != -1) {
            itemList.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val checkboxSelect: android.widget.CheckBox = itemView.findViewById(R.id.checkboxSelect)
        private val ivFileIcon: ImageView = itemView.findViewById(R.id.ivFileIcon)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvFileSize: TextView = itemView.findViewById(R.id.tvFileSize)
        private val tvUploadTime: TextView = itemView.findViewById(R.id.tvUploadTime)
        private val tvFileType: TextView = itemView.findViewById(R.id.tvFileType)
        private val btnRename: ImageView = itemView.findViewById(R.id.btnRename)
        private val btnMove: ImageView = itemView.findViewById(R.id.btnMove)
        private val btnShare: ImageView = itemView.findViewById(R.id.btnShare)
        private val btnDownload: ImageView = itemView.findViewById(R.id.btnDownload)
        private val btnDelete: ImageView = itemView.findViewById(R.id.btnDelete)

        fun bind(item: ListItem) {
            try {
                when (item) {
                    is ListItem.Folder -> {
                        android.util.Log.d("FileAdapter", "绑定文件夹: ${item.folder.folder_name}")
                        bindFolder(item.folder)
                    }
                    is ListItem.File -> {
                        android.util.Log.d("FileAdapter", "绑定文件: ${item.file.filename}")
                        bindFile(item.file)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("FileAdapter", "绑定项目失败: ${e.message}", e)
            }
        }

        private fun bindFolder(folder: FolderItem) {
            // 设置文件夹名称
            tvFileName.text = folder.folder_name

            // 文件夹不显示大小
            tvFileSize.text = "文件夹"

            // 设置创建时间
            tvUploadTime.text = formatDateTime(folder.created_date)

            // 设置文件夹类型标签
            tvFileType.text = "文件夹"

            // 设置文件夹图标（使用ic_file作为文件夹图标，如果没有ic_folder资源）
            ivFileIcon.setImageResource(R.drawable.ic_file)

            // 选择模式：显示复选框
            if (isSelectionMode) {
                checkboxSelect.visibility = View.VISIBLE
                checkboxSelect.isChecked = selectedFolderIds.contains(folder.id)
                checkboxSelect.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedFolderIds.add(folder.id)
                    } else {
                        selectedFolderIds.remove(folder.id)
                    }
                    onSelectionChanged?.invoke(folder.id, isChecked)
                }
            } else {
                checkboxSelect.visibility = View.GONE
            }

            // 根据hideActionButtons参数决定是否显示按钮
            if (hideActionButtons) {
                btnRename.visibility = View.GONE
                btnMove.visibility = View.GONE
                btnShare.visibility = View.GONE
                btnDownload.visibility = View.GONE
                btnDelete.visibility = View.GONE
            } else {
                // 文件夹显示重命名、分享和删除按钮（不显示移动按钮）
                btnRename.visibility = View.VISIBLE
                btnMove.visibility = View.GONE
                btnShare.visibility = View.VISIBLE
                btnDownload.visibility = View.GONE
                btnDelete.visibility = View.VISIBLE
            }

            // 文件夹点击事件：进入文件夹（选择模式下，点击复选框区域不触发）
            itemView.setOnClickListener {
                if (isSelectionMode && checkboxSelect.visibility == View.VISIBLE) {
                    // 选择模式下，点击切换选择状态
                    checkboxSelect.isChecked = !checkboxSelect.isChecked
                } else {
                    // 非选择模式或点击非复选框区域，进入文件夹
                    onFolderClickListener(folder)
                }
            }

            // 文件夹重命名按钮点击事件
            btnRename.setOnClickListener {
                onRenameFolderClickListener(folder)
            }

            // 文件夹分享按钮点击事件
            btnShare.setOnClickListener {
                onShareFolderClickListener(folder)
            }

            // 文件夹删除按钮点击事件
            btnDelete.setOnClickListener {
                onDeleteFolderClickListener(folder)
            }
        }

        private fun bindFile(file: FileItem) {
            // 设置文件名（优先显示original_filename，如果没有则使用filename）
            // 注意：FileItem中的filename字段在服务器返回时实际上是original_filename
            val displayName = file.filename
            tvFileName.text = displayName

            // 设置文件大小
            tvFileSize.text = file.getFormattedSize()

            // 设置上传时间
            tvUploadTime.text = formatDateTime(file.upload_date)

            // 设置文件类型（如果file_type为空，从filename中提取）
            val fileType = if (file.file_type.isNullOrEmpty()) {
                // 从filename中提取扩展名
                val filename = file.filename
                if (filename.contains('.')) {
                    filename.substringAfterLast('.', "").uppercase()
                } else {
                    "未知"
                }
            } else {
                file.file_type.uppercase()
            }
            tvFileType.text = fileType

            // 设置文件图标（使用file_type或从filename提取的扩展名）
            val iconFileType = file.file_type ?: if (file.filename.contains('.')) {
                file.filename.substringAfterLast('.', "").lowercase()
            } else {
                null
            }
            val iconRes = getFileIcon(iconFileType)
            ivFileIcon.setImageResource(iconRes)

            // 选择模式：显示复选框
            if (isSelectionMode) {
                checkboxSelect.visibility = View.VISIBLE
                checkboxSelect.isChecked = selectedFileIds.contains(file.id)
                checkboxSelect.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedFileIds.add(file.id)
                    } else {
                        selectedFileIds.remove(file.id)
                    }
                    onSelectionChanged?.invoke(file.id, isChecked)
                }
            } else {
                checkboxSelect.visibility = View.GONE
            }

            // 根据hideActionButtons参数决定是否显示按钮
            // 先显式设置所有按钮的可见性，确保状态正确
            if (hideActionButtons) {
                btnRename.visibility = View.GONE
                btnMove.visibility = View.GONE
                btnShare.visibility = View.GONE
                btnDownload.visibility = View.GONE
                btnDelete.visibility = View.GONE
            } else {
                // 显示重命名、移动、分享、下载和删除按钮
                // 确保重命名按钮始终可见（对于文件）
                btnRename.visibility = View.VISIBLE
                btnMove.visibility = View.VISIBLE
                btnShare.visibility = View.VISIBLE
                btnDownload.visibility = View.VISIBLE
                btnDelete.visibility = View.VISIBLE
            }

            // 文件项点击事件
            itemView.setOnClickListener {
                if (isSelectionMode && checkboxSelect.visibility == View.VISIBLE) {
                    // 选择模式下，点击切换选择状态
                    checkboxSelect.isChecked = !checkboxSelect.isChecked
                } else {
                    // 非选择模式，触发点击回调
                    onItemClickListener(ListItem.File(file))
                }
            }

            // 重命名按钮点击事件
            btnRename.setOnClickListener {
                onRenameFileClickListener(file)
            }

            // 移动按钮点击事件
            btnMove.setOnClickListener {
                onMoveFileClickListener(file)
            }

            // 分享按钮点击事件
            btnShare.setOnClickListener {
                onShareFileClickListener(file)
            }

            // 下载按钮点击事件
            btnDownload.setOnClickListener {
                onDownloadClickListener(file)
            }

            // 删除按钮点击事件
            btnDelete.setOnClickListener {
                onDeleteClickListener(file)
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

        private fun getFileIcon(fileType: String?): Int {
            return when (fileType?.lowercase()) {
                "jpg", "jpeg", "png", "gif", "bmp", "webp" -> R.drawable.ic_image
                "mp4", "avi", "mov", "mkv", "wmv", "flv" -> R.drawable.ic_video
                "mp3", "wav", "flac", "aac", "ogg" -> R.drawable.ic_audio
                "pdf" -> R.drawable.ic_pdf
                "doc", "docx" -> R.drawable.ic_word
                "xls", "xlsx" -> R.drawable.ic_excel
                "ppt", "pptx" -> R.drawable.ic_ppt
                "zip", "rar", "7z", "tar", "gz" -> R.drawable.ic_zip
                "txt" -> R.drawable.ic_text
                else -> R.drawable.ic_file
            }
        }
    }
}
