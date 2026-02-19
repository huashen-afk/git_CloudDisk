package com.example.personalclouddisk.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.personalclouddisk.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        // 设置返回按钮
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "关于"

        // 隐私政策点击
        binding.tvPrivacyPolicy.setOnClickListener {
            showPrivacyPolicy()
        }

        // 用户协议点击
        binding.tvUserAgreement.setOnClickListener {
            showUserAgreement()
        }
    }

    private fun showPrivacyPolicy() {
        // 显示隐私政策对话框
        val message = """
            我们重视您的隐私。本应用收集和使用您的信息仅用于提供云盘服务。
            
            1. 信息收集
            - 用户名和邮箱用于账户管理
            - 上传的文件仅存储在您的个人目录中
            
            2. 信息使用
            - 不会向第三方分享您的个人信息
            - 文件仅您本人可以访问
            
            3. 数据安全
            - 所有数据传输均用令牌加密
            - 服务器端数据使用hashmap加密
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle("隐私政策")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showUserAgreement() {
        // 显示用户协议对话框
        val message = """
            使用本应用即表示您同意以下条款：
            
            技术说明：
            后端使用Flask框架构建API服务。数据库用的SQL存储用户信息，用JWT令牌来进行认证和信息传递和Okhttp网络通信。
            
            1. 服务使用
            - 您有责任保护账户安全
            - 不得上传违法、侵权内容
            
            2. 存储限制
            - 每个用户默认存储空间为1GB
            - 超出限制可能影响服务使用
            
            3. 服务变更
            - 我们保留修改服务的权利
            - 重要变更会提前通知用户
            
            4. 免责声明
            - 请定期备份重要文件
            - 作者没钱去买服务器和公网ip，使用的ngrok内网穿透，网速慢请见谅
            - 由于服务端搭载在我的个人电脑上，因此软件晚上不可访问
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle("用户协议")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

