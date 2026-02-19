package com.example.personalclouddisk.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.personalclouddisk.databinding.ActivitySettingsBinding
import com.example.personalclouddisk.network.ApiClient
import com.example.personalclouddisk.utils.SharedPrefManager
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var sharedPrefManager: SharedPrefManager
    private lateinit var apiClient: ApiClient
    
    private val downloadPathLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                // 保存 URI（用于 Android 10+）
                val uriString = uri.toString()
                sharedPrefManager.saveDownloadUri(uriString)
                
                // 同时保存路径（用于显示和兼容旧版本）
                val path = getPathFromUri(uri)
                if (path != null) {
                    sharedPrefManager.saveDownloadPath(path)
                }
                
                // 持久化 URI 权限
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                
                updateDownloadPathDisplay()
                Toast.makeText(this, "下载路径已更新", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPrefManager = SharedPrefManager.getInstance(this)
        apiClient = ApiClient.getInstance(this)

        setupUI()
        updateDownloadPathDisplay()
        updateServerUrlDisplay()
    }

    private fun setupUI() {
        // 设置返回按钮
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "设置"

        // 选择下载路径按钮
        binding.btnSelectDownloadPath.setOnClickListener {
            showDownloadPathDialog()
        }

        // 修改服务器地址按钮
        binding.btnEditServerUrl.setOnClickListener {
            showServerSettingsDialog()
        }
    }

    private fun showDownloadPathDialog() {
        val options = arrayOf("使用默认下载目录", "选择自定义目录")
        
        AlertDialog.Builder(this)
            .setTitle("选择下载路径")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        // 使用默认下载目录
                        val defaultPath = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        ).absolutePath
                        saveDownloadPath(defaultPath)
                        updateDownloadPathDisplay()
                        Toast.makeText(this, "已设置为默认下载目录", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        // 选择自定义目录
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            downloadPathLauncher.launch(intent)
                        } else {
                            Toast.makeText(this, "Android 5.0 以下不支持选择目录", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getPathFromUri(uri: Uri): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                val split = docId.split(":".toRegex()).toTypedArray()
                if (split.size >= 2) {
                    val type = split[0]
                    val path = split[1]
                    if ("primary".equals(type, ignoreCase = true)) {
                        Environment.getExternalStorageDirectory().absolutePath + "/" + path
                    } else {
                        "/storage/$type/$path"
                    }
                } else {
                    null
                }
            } else {
                uri.path
            }
        } catch (e: Exception) {
            Log.e("SettingsActivity", "获取路径失败: ${e.message}", e)
            null
        }
    }

    private fun saveDownloadPath(path: String) {
        sharedPrefManager.saveDownloadPath(path)
    }

    private fun updateDownloadPathDisplay() {
        val downloadPath = sharedPrefManager.getDownloadPath()
        binding.tvDownloadPath.text = "当前下载路径：$downloadPath"
    }

    private fun updateServerUrlDisplay() {
        val serverUrl = apiClient.getServerUrl()
        binding.tvServerUrl.text = "当前服务器地址：$serverUrl"
    }

    private fun showServerSettingsDialog() {
        val currentUrl = apiClient.getServerUrl()
        val input = android.widget.EditText(this)
        input.setText(currentUrl)
        input.setSelection(input.text.length)

        AlertDialog.Builder(this)
            .setTitle("服务器地址设置")
            .setMessage("当前地址: $currentUrl")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    // 验证URL格式
                    if (newUrl.startsWith("http://") || newUrl.startsWith("https://")) {
                        apiClient.setServerUrl(newUrl)
                        updateServerUrlDisplay()
                        Toast.makeText(this, "服务器地址已更新为: $newUrl", Toast.LENGTH_LONG).show()
                        Log.d("SettingsActivity", "服务器地址已更新: $newUrl")
                    } else {
                        Toast.makeText(this, "URL格式错误，必须以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "服务器地址不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

