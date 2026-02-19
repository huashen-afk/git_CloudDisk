package com.example.personalclouddisk.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalclouddisk.databinding.ActivityRegisterBinding
import com.example.personalclouddisk.network.ApiClient
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var apiClient: ApiClient

    // 添加日志标签
    companion object {
        private const val TAG = "RegisterActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d(TAG, "Activity created")

        try {
            apiClient = ApiClient.getInstance(applicationContext)
            Log.d(TAG, "ApiClient initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "ApiClient initialization failed", e)
            Toast.makeText(this, "初始化失败，请重启应用", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnRegister.setOnClickListener {
            Log.d(TAG, "Register button clicked")
            performRegister()
        }

        binding.btnBackToLogin.setOnClickListener {
            Log.d(TAG, "Back to login clicked")
            navigateToLogin()
        }
    }

    private fun performRegister() {
        val username = binding.etUsername.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val confirmPassword = binding.etConfirmPassword.text.toString()

        Log.d(TAG, "Starting registration with username: $username, email: $email")

        if (!validateInputs(username, email, password, confirmPassword)) {
            Log.w(TAG, "Input validation failed")
            return
        }

        binding.btnRegister.isEnabled = false
        binding.btnRegister.text = "注册中..."

        Log.d(TAG, "Starting registration coroutine")

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Calling register API")
                val result = apiClient.register(username, email, password)

                if (result.isSuccess) {
                    Log.i(TAG, "Registration successful")
                    Toast.makeText(
                        this@RegisterActivity,
                        "注册成功",
                        Toast.LENGTH_SHORT
                    ).show()
                    navigateToLogin()
                } else {
                    val error = result.exceptionOrNull()
                    Log.e(TAG, "Registration failed: ${error?.message}", error)
                    Toast.makeText(
                        this@RegisterActivity,
                        error?.message ?: "注册失败",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Registration exception: ${e.message}", e)
                Toast.makeText(
                    this@RegisterActivity,
                    "网络请求异常: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                Log.d(TAG, "Registration process completed")
                binding.btnRegister.isEnabled = true
                binding.btnRegister.text = "注册"
            }
        }
    }

    private fun validateInputs(
        username: String,
        email: String,
        password: String,
        confirmPassword: String
    ): Boolean {
        if (username.isEmpty()) {
            Log.w(TAG, "Username is empty")
            Toast.makeText(this, "请输入用户名", Toast.LENGTH_SHORT).show()
            return false
        }

        if (email.isEmpty()) {
            Log.w(TAG, "Email is empty")
            Toast.makeText(this, "请输入邮箱", Toast.LENGTH_SHORT).show()
            return false
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Log.w(TAG, "Invalid email format: $email")
            Toast.makeText(this, "请输入有效的邮箱地址", Toast.LENGTH_SHORT).show()
            return false
        }

        if (password.isEmpty()) {
            Log.w(TAG, "Password is empty")
            Toast.makeText(this, "请输入密码", Toast.LENGTH_SHORT).show()
            return false
        }

        if (password.length < 6) {
            Log.w(TAG, "Password too short (${password.length} chars)")
            Toast.makeText(this, "密码至少需要6个字符", Toast.LENGTH_SHORT).show()
            return false
        }

        if (password != confirmPassword) {
            Log.w(TAG, "Password mismatch")
            Toast.makeText(this, "两次输入的密码不一致", Toast.LENGTH_SHORT).show()
            return false
        }

        Log.d(TAG, "Input validation passed")
        return true
    }

    private fun navigateToLogin() {
        Log.d(TAG, "Navigating to login activity")
        val intent = Intent(this, LoginActivity::class.java)
        startActivity(intent)
        finish()
    }
}
