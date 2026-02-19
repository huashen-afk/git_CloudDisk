package com.example.personalclouddisk.utils

import android.content.Context
import android.content.SharedPreferences
import com.example.personalclouddisk.model.User
import com.google.gson.Gson

class SharedPrefManager private constructor(context: Context) {
    private val sharedPreferences: SharedPreferences
    private val gson = Gson()

    companion object {
        private const val PREF_NAME = "cloud_disk_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_USER = "user"
        private const val KEY_DOWNLOAD_PATH = "download_path"
        private const val KEY_DOWNLOAD_URI = "download_uri"

        @Volatile
        private var INSTANCE: SharedPrefManager? = null

        fun getInstance(context: Context): SharedPrefManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SharedPrefManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveLoginInfo(accessToken: String, user: User?) {
        with(sharedPreferences.edit()) {
            putString(KEY_ACCESS_TOKEN, accessToken)
            if (user != null) {
                putString(KEY_USER, gson.toJson(user))
            }
            apply()
        }
    }

    fun getAccessToken(): String {
        return sharedPreferences.getString(KEY_ACCESS_TOKEN, "") ?: ""
    }

    fun getUser(): User? {
        val userJson = sharedPreferences.getString(KEY_USER, null)
        return if (userJson != null) {
            gson.fromJson(userJson, User::class.java)
        } else {
            null
        }
    }

    fun isLoggedIn(): Boolean {
        return getAccessToken().isNotEmpty()
    }

    fun clear() {
        sharedPreferences.edit().clear().apply()
    }

    fun saveDownloadPath(path: String) {
        sharedPreferences.edit().putString(KEY_DOWNLOAD_PATH, path).apply()
    }

    fun getDownloadPath(): String {
        return sharedPreferences.getString(KEY_DOWNLOAD_PATH, null)
            ?: android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ).absolutePath
    }

    fun saveDownloadUri(uri: String?) {
        sharedPreferences.edit().putString(KEY_DOWNLOAD_URI, uri).apply()
    }

    fun getDownloadUri(): String? {
        return sharedPreferences.getString(KEY_DOWNLOAD_URI, null)
    }
}
