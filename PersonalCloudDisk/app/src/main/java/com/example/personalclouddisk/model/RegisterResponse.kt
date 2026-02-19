package com.example.personalclouddisk.model

data class RegisterResponse(
    val message: String = "",
    val user: User? = null,
    // 如果服务器返回其他字段，可以在这里添加
)