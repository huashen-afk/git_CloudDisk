package com.example.personalclouddisk.model

import com.google.gson.annotations.SerializedName

data class LoginResponse(
    @SerializedName("access_token") val accessToken: String = "",
    @SerializedName("user") val user: User? = null
)
