package com.example.personalclouddisk.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.personalclouddisk.databinding.ActivityLoginBinding
import com.example.personalclouddisk.model.LoginResponse
import com.example.personalclouddisk.model.User
import com.example.personalclouddisk.network.ApiClient
import com.example.personalclouddisk.utils.SharedPrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var apiClient: ApiClient
    private lateinit var sharedPrefManager: SharedPrefManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiClient = ApiClient.getInstance(this)
        sharedPrefManager = SharedPrefManager.getInstance(this)

        if (sharedPrefManager.isLoggedIn()) {
            navigateToMain()
            return
        }

        binding.btnLogin.setOnClickListener {
            val username = binding.etUsername.text.toString()
            val password = binding.etPassword.text.toString()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "请输入用户名和密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            performLogin(username, password)
        }
        binding.btnRegister.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
        }

        binding.btnServerSettings.setOnClickListener {
            showServerSettingsDialog()
        }
    }

    private fun performLogin(username: String, password: String) {
        CoroutineScope(Dispatchers.Main).launch {
            binding.btnLogin.isEnabled = false
            binding.btnLogin.text = "登录中..."

            try {
                val result = apiClient.login(username, password)
                if (result.isSuccess) {
                    val loginResponse = result.getOrThrow()
                    Log.d("LoginActivity", "登录成功，accessToken: ${if (loginResponse.accessToken.isNotEmpty()) "${loginResponse.accessToken.take(20)}..." else "空"}")
                    sharedPrefManager.saveLoginInfo(loginResponse.accessToken, loginResponse.user)
                    Log.d("LoginActivity", "已保存登录信息到SharedPreferences")
                    navigateToMain()
                } else {
                    Toast.makeText(this@LoginActivity, "登录失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, "登录异常: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnLogin.isEnabled = true
                binding.btnLogin.text = "登录"
            }
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /**
     * 显示服务器设置对话框
     */
    private fun showServerSettingsDialog() {
        val currentUrl = apiClient.getServerUrl()
        val input = android.widget.EditText(this)
        input.setText(currentUrl)
        input.setSelection(input.text.length)

        AlertDialog.Builder(this)
            .setTitle("服务器地址设置")
            .setMessage("当前地址: $currentUrl")
            .setView(input)
            .setPositiveButton("保存并测试") { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    // 验证URL格式
                    if (newUrl.startsWith("http://") || newUrl.startsWith("https://")) {
                        apiClient.setServerUrl(newUrl)
                        Log.d("LoginActivity", "服务器地址已更新: $newUrl")
                        // 测试连接
                        testServerConnection(newUrl)
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

    /**
     * 测试服务器连接
     */
    private fun testServerConnection(serverUrl: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val progressDialog = android.app.ProgressDialog(this@LoginActivity).apply {
                setMessage("正在测试连接...")
                setCancelable(false)
            }
            progressDialog.show()

            try {
                val isConnected = withContext(Dispatchers.IO) {
                    testConnection(serverUrl)
                }

                progressDialog.dismiss()

                if (isConnected) {
                    Toast.makeText(
                        this@LoginActivity,
                        "连接成功！服务器地址: $serverUrl",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.d("LoginActivity", "服务器连接测试成功: $serverUrl")
                } else {
                    Toast.makeText(
                        this@LoginActivity,
                        "连接失败，请检查服务器地址是否正确",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.w("LoginActivity", "服务器连接测试失败: $serverUrl")
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(
                    this@LoginActivity,
                    "连接测试异常: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                Log.e("LoginActivity", "连接测试异常: ${e.message}", e)
            }
        }
    }

    /**
     * 测试服务器连接（同步方法）
     */
    private suspend fun testConnection(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val testClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url("$serverUrl/health")
                .get()
                .build()

            val response = testClient.newCall(request).execute()
            val isSuccess = response.isSuccessful
            Log.d("LoginActivity", "测试连接 $serverUrl: ${if (isSuccess) "成功" else "失败 (${response.code})"}")
            isSuccess
        } catch (e: Exception) {
            Log.d("LoginActivity", "测试连接 $serverUrl 失败: ${e.message}")
            false
        }
    }
}
