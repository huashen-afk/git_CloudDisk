package com.example.personalclouddisk.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.personalclouddisk.databinding.ActivityProfileBinding
import com.example.personalclouddisk.model.User
import com.example.personalclouddisk.network.ApiClient
import com.example.personalclouddisk.utils.SharedPrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import okhttp3.OkHttpClient
import okhttp3.Request

class ProfileActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProfileBinding
    private lateinit var apiClient: ApiClient
    private lateinit var sharedPrefManager: SharedPrefManager
    private var currentUser: User? = null
    private var selectedAvatarUri: Uri? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedAvatarUri = it
            binding.ivAvatar.setImageURI(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiClient = ApiClient.getInstance(this)
        sharedPrefManager = SharedPrefManager.getInstance(this)

        setupUI()
        loadUserInfo()
    }

    private fun setupUI() {
        // 设置返回按钮
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "编辑资料"

        // 头像点击
        binding.ivAvatar.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        // 保存按钮
        binding.btnSave.setOnClickListener {
            saveProfile()
        }
    }

    private fun loadUserInfo() {
        currentUser = sharedPrefManager.getUser()
        currentUser?.let { user ->
            binding.etUserId.setText(user.id.toString())
            binding.etUsername.setText(user.username)
            binding.etEmail.setText(user.email)
        }

        // 加载用户信息
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = apiClient.getProfile()
                if (result.isSuccess) {
                    val profile = result.getOrNull()
                    profile?.user?.let { user ->
                        currentUser = user
                        binding.etUserId.setText(user.id.toString())
                        binding.etUsername.setText(user.username)
                        binding.etEmail.setText(user.email)
                        // 加载头像
                        loadAvatar(user.avatarUrl)
                    }
                }
            } catch (e: Exception) {
                Log.e("ProfileActivity", "加载用户信息失败: ${e.message}")
            }
        }
    }

    private fun saveProfile() {
        val username = binding.etUsername.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()

        if (username.isEmpty()) {
            Toast.makeText(this, "用户名不能为空", Toast.LENGTH_SHORT).show()
            return
        }

        if (email.isEmpty()) {
            Toast.makeText(this, "邮箱不能为空", Toast.LENGTH_SHORT).show()
            return
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "邮箱格式不正确", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val progressDialog = android.app.ProgressDialog(this@ProfileActivity).apply {
                    setMessage("正在保存...")
                    setCancelable(false)
                }
                progressDialog.show()

                // 先上传头像（如果有）
                selectedAvatarUri?.let { uri ->
                    try {
                        val inputStream = contentResolver.openInputStream(uri)
                        inputStream?.use { stream ->
                            val fileName = "avatar_${System.currentTimeMillis()}.jpg"
                            val tempDir = File(cacheDir, "avatars")
                            tempDir.mkdirs()
                            val tempFile = File(tempDir, fileName)

                            FileOutputStream(tempFile).use { outputStream ->
                                stream.copyTo(outputStream)
                            }

                            val uploadResult = apiClient.uploadAvatar(tempFile, fileName)
                            if (uploadResult.isSuccess) {
                                Log.d("ProfileActivity", "头像上传成功")
                            } else {
                                Log.w("ProfileActivity", "头像上传失败: ${uploadResult.exceptionOrNull()?.message}")
                            }

                            // 删除临时文件
                            tempFile.delete()
                        }
                    } catch (e: Exception) {
                        Log.e("ProfileActivity", "处理头像失败: ${e.message}", e)
                    }
                }

                // 更新用户信息
                val updateResult = apiClient.updateProfile(username, email)
                progressDialog.dismiss()

                if (updateResult.isSuccess) {
                    val updatedUser = updateResult.getOrNull()
                    updatedUser?.let {
                        sharedPrefManager.saveLoginInfo(sharedPrefManager.getAccessToken(), it)
                        // 重新加载用户信息以获取最新的头像URL
                        val profileResult = apiClient.getProfile()
                        profileResult.getOrNull()?.user?.let { updatedUserWithAvatar ->
                            loadAvatar(updatedUserWithAvatar.avatarUrl)
                        }
                        Toast.makeText(this@ProfileActivity, "保存成功", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } else {
                    val error = updateResult.exceptionOrNull()
                    Toast.makeText(
                        this@ProfileActivity,
                        "保存失败: ${error?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("ProfileActivity", "保存异常: ${e.message}", e)
                Toast.makeText(
                    this@ProfileActivity,
                    "保存异常: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun loadAvatar(avatarUrl: String?) {
        if (avatarUrl.isNullOrEmpty()) {
            return
        }
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    loadAvatarFromUrl(avatarUrl)
                }
                bitmap?.let {
                    binding.ivAvatar.setImageBitmap(it)
                }
            } catch (e: Exception) {
                Log.e("ProfileActivity", "加载头像失败: ${e.message}", e)
            }
        }
    }
    
    private fun loadAvatarFromUrl(avatarUrl: String): Bitmap? {
        return try {
            val apiClient = ApiClient.getInstance(this)
            val baseUrl = apiClient.getServerUrl().replace("/api", "")
            val fullUrl = if (avatarUrl.startsWith("http")) {
                avatarUrl
            } else {
                "$baseUrl/api/profile/avatar/$avatarUrl"
            }
            
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(fullUrl)
                .header("Authorization", "Bearer ${sharedPrefManager.getAccessToken()}")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val inputStream = response.body?.byteStream()
                inputStream?.use {
                    BitmapFactory.decodeStream(it)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("ProfileActivity", "下载头像失败: ${e.message}", e)
            null
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

