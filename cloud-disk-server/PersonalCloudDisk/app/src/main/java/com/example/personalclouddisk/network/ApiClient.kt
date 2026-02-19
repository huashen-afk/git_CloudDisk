package com.example.personalclouddisk.network

import android.content.Context
import android.util.Log
import com.example.personalclouddisk.model.LoginResponse
import com.example.personalclouddisk.model.RegisterResponse
import com.example.personalclouddisk.model.User
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.IOException
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.util.concurrent.TimeUnit

// API响应基类
data class ApiResponse<T>(
    val success: Boolean = true,
    val message: String? = null,
    val data: T? = null,
    val error: String? = null
)

// 注册请求体
data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String
)

// 登录请求体
data class LoginRequest(
    val username: String,
    val password: String
)

// 文件上传进度监听器
interface UploadProgressListener {
    fun onProgress(currentBytes: Long, totalBytes: Long)
}

// 文件下载进度监听器
interface DownloadProgressListener {
    fun onProgress(currentBytes: Long, totalBytes: Long)
    fun onSuccess(file: File)
    fun onError(error: String)
}

class ApiClient private constructor(context: Context) {

    private val client: OkHttpClient
    private val gson: Gson
    private var authToken: String = ""
    private val context: Context

    companion object {
        // 默认服务器地址（本机网络地址）
        private const val DEFAULT_BASE_URL = "https://unset-exclamatory-tanja.ngrok-free.dev/api"

        // 从SharedPreferences读取服务器地址的key
        private const val PREF_SERVER_URL = "server_url"

        @Volatile
        private var INSTANCE: ApiClient? = null

        fun getInstance(context: Context): ApiClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ApiClient(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        this.context = context.applicationContext
        client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()

                // 使用最新的authToken值
                val currentToken = this@ApiClient.authToken

                // 只在有令牌时添加Authorization头
                if (currentToken.isNotEmpty()) {
                    val authHeader = "Bearer $currentToken"
                    requestBuilder.header("Authorization", authHeader)
                    Log.d("ApiClient", "Adding Authorization header: ${authHeader.take(30)}...")
                } else {
                    Log.w("ApiClient", "No auth token available for request: ${original.url}")
                }

                // 只在非文件上传请求时设置Content-Type
                val contentType = original.header("Content-Type")
                val urlString = original.url.toString()
                val isUploadRequest = urlString.contains("/upload") || urlString.contains("/avatar")
                if (contentType == null && !isUploadRequest) {
                    requestBuilder.header("Content-Type", "application/json")
                }

                // 添加ngrok请求头（跳过浏览器警告页面）
                if (urlString.contains("ngrok-free.dev") || urlString.contains("ngrok.io")) {
                    requestBuilder.header("ngrok-skip-browser-warning", "true")
                }

                val request = requestBuilder.build()
                Log.d("ApiClient", "Request URL: ${request.url}, Headers: ${request.headers}")
                chain.proceed(request)
            }
            .build()

        gson = Gson()
    }

    /**
     * 获取服务器基础URL
     * 优先从SharedPreferences读取，如果没有则使用默认值
     */
    private fun getBaseUrl(): String {
        val prefs = context.getSharedPreferences("cloud_disk_prefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(PREF_SERVER_URL, null)
        return savedUrl ?: DEFAULT_BASE_URL
    }

    /**
     * 设置服务器地址
     * @param serverUrl 服务器地址，例如: "http://192.168.1.100:5000/api" 或 "https://yourdomain.com/api"
     */
    fun setServerUrl(serverUrl: String) {
        val prefs = context.getSharedPreferences("cloud_disk_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_SERVER_URL, serverUrl).apply()
        Log.d("ApiClient", "服务器地址已更新为: $serverUrl")
    }

    /**
     * 获取当前服务器地址
     */
    fun getServerUrl(): String {
        return getBaseUrl()
    }

    fun getAuthToken(): String {
        return authToken
    }

    fun getClient(): OkHttpClient {
        return client
    }

    /**
     * 网络信息数据类
     */
    fun setAuthToken(token: String) {
        this.authToken = token
        Log.d("ApiClient", "AuthToken已设置: ${if (token.isNotEmpty()) "${token.take(20)}..." else "空"}")
    }

    // 用户注册
    suspend fun register(
        username: String,
        email: String,
        password: String
    ): Result<RegisterResponse> = withContext(Dispatchers.IO) {
        Log.d("ApiClient", "Starting registration for $username")
        return@withContext try {
            val registerRequest = RegisterRequest(username, email, password)
            val jsonBody = gson.toJson(registerRequest)
            Log.d("ApiClient", "Request body: $jsonBody")

            val request = Request.Builder()
                .url("${getBaseUrl()}/register")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            Log.d("ApiClient", "Request built: ${request.url}")

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Response received. Code: ${response.code}, Body: $responseBody")

            if (response.isSuccessful) {
                Log.d("ApiClient", "Registration successful")
                val registerResponse = gson.fromJson(responseBody, RegisterResponse::class.java)
                Result.success(registerResponse)
            } else {
                val errorResponse = try {
                    gson.fromJson(responseBody, ApiResponse::class.java)
                } catch (e: Exception) {
                    null
                }

                val errorMessage = errorResponse?.error ?: responseBody.ifEmpty { "注册失败: HTTP ${response.code}" }
                Log.e("ApiClient", "Registration failed: $errorMessage")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Registration exception: ${e.message}", e)
            Result.failure(Exception("网络请求失败: ${e.message}"))
        }
    }

    // 用户登录
    suspend fun login(username: String, password: String): Result<LoginResponse> = withContext(Dispatchers.IO) {
        Log.d("ApiClient", "Starting login for $username")
        return@withContext try {
            val loginRequest = LoginRequest(username, password)
            val jsonBody = gson.toJson(loginRequest)
            Log.d("ApiClient", "Login request body: $jsonBody")

            val request = Request.Builder()
                .url("${getBaseUrl()}/login")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Login response. Code: ${response.code}, Body: $responseBody")

            if (response.isSuccessful) {
                Log.d("ApiClient", "Login successful")
                Log.d("ApiClient", "Response body: $responseBody")
                val loginResponse = gson.fromJson(responseBody, LoginResponse::class.java)
                Log.d("ApiClient", "Parsed accessToken: ${if (loginResponse.accessToken.isNotEmpty()) "${loginResponse.accessToken.take(20)}..." else "空"}")
                Result.success(loginResponse)
            } else {
                val errorResponse = try {
                    gson.fromJson(responseBody, ApiResponse::class.java)
                } catch (e: Exception) {
                    null
                }

                val errorMessage = errorResponse?.error ?: responseBody.ifEmpty { "登录失败: HTTP ${response.code}" }
                Log.e("ApiClient", "Login failed: $errorMessage")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Login exception: ${e.message}", e)
            Result.failure(Exception("网络请求失败: ${e.message}"))
        }
    }

    // 获取文件列表
    suspend fun getFiles(folderId: Int? = null): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        Log.d("ApiClient", "Getting file list, folderId: $folderId")
        return@withContext try {
            val url = if (folderId != null) {
                "${getBaseUrl()}/files?folder_id=$folderId"
            } else {
                "${getBaseUrl()}/files"
            }
            Log.d("ApiClient", "Request URL: $url")
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Get files response. Code: ${response.code}")

            if (response.isSuccessful) {
                val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                Log.d("ApiClient", "API Response success: ${apiResponse.success}, error: ${apiResponse.error}")
                Log.d("ApiClient", "API Response data: ${apiResponse.data}")

                if (apiResponse.success) {
                    if (apiResponse.data != null) {
                        val dataJson = gson.toJsonTree(apiResponse.data)
                        Log.d("ApiClient", "Data JSON: $dataJson")

                        val filesList = mutableListOf<FileItem>()
                        if (dataJson.isJsonObject) {
                            val filesArray = dataJson.asJsonObject.getAsJsonArray("files")
                            Log.d("ApiClient", "Files array: $filesArray")

                            filesArray?.forEach { jsonElement ->
                                try {
                                    val file = gson.fromJson(jsonElement, FileItem::class.java)
                                    Log.d("ApiClient", "Parsed file: $file")
                                    filesList.add(file)
                                } catch (e: Exception) {
                                    Log.e("ApiClient", "Error parsing file item: ${e.message}")
                                }
                            }
                        } else if (dataJson.isJsonArray) {
                            dataJson.asJsonArray.forEach { jsonElement ->
                                try {
                                    val file = gson.fromJson(jsonElement, FileItem::class.java)
                                    Log.d("ApiClient", "Parsed file: $file")
                                    filesList.add(file)
                                } catch (e: Exception) {
                                    Log.e("ApiClient", "Error parsing file item: ${e.message}")
                                }
                            }
                        }

                        Log.d("ApiClient", "Total files parsed: ${filesList.size}")
                        Result.success(filesList)
                    } else {
                        Log.d("ApiClient", "No files data returned")
                        Result.success(emptyList())
                    }
                } else {
                    val errorMsg = apiResponse.error ?: "获取文件列表失败"
                    Log.e("ApiClient", "Get files API error: $errorMsg")
                    Result.failure(Exception(errorMsg))
                }
            } else {
                Log.e("ApiClient", "Get files HTTP error: ${response.code}, Body: $responseBody")
                Result.failure(Exception("HTTP ${response.code}: ${responseBody.take(200)}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Get files exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    // 上传文件
    suspend fun uploadFile(
        file: File,
        fileName: String,
        folderId: Int? = null,
        onProgress: (currentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        Log.d("ApiClient", "Starting upload file: $fileName, size: ${file.length()}, folderId: $folderId")
        return@withContext try {
            // 创建文件请求体（带进度回调）
            val fileRequestBody = object : RequestBody() {
                override fun contentType(): MediaType? {
                    // 根据文件扩展名推断MIME类型
                    val extension = fileName.substringAfterLast('.', "").lowercase()
                    val mimeType = when (extension) {
                        "jpg", "jpeg" -> "image/jpeg"
                        "png" -> "image/png"
                        "gif" -> "image/gif"
                        "pdf" -> "application/pdf"
                        "txt" -> "text/plain"
                        "zip" -> "application/zip"
                        else -> "application/octet-stream"
                    }
                    return mimeType.toMediaType()
                }

                override fun contentLength(): Long {
                    return file.length()
                }

                override fun writeTo(sink: okio.BufferedSink) {
                    val source = file.source()
                    var totalBytesRead = 0L
                    val buffer = okio.Buffer()

                    while (true) {
                        val bytesRead = source.read(buffer, 8192)
                        if (bytesRead == -1L) break

                        sink.write(buffer, bytesRead)
                        totalBytesRead += bytesRead
                        onProgress(totalBytesRead, file.length())
                    }
                    source.close()
                }
            }

            // 构建multipart请求体
            // 注意：第二个参数是文件名，需要正确编码以支持中文
            // 使用原始文件名作为multipart的文件名，同时单独传递original_filename参数
            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, fileRequestBody)
                .addFormDataPart("original_filename", fileName)  // 单独传递原始文件名，确保中文文件名正确传递

            Log.d("ApiClient", "上传文件名: $fileName")

            // 如果提供了folder_id，添加到请求中
            if (folderId != null) {
                multipartBuilder.addFormDataPart("folder_id", folderId.toString())
                Log.d("ApiClient", "Adding folder_id to upload request: $folderId")
            }

            val requestBody = multipartBuilder.build()

            val request = Request.Builder()
                .url("${getBaseUrl()}/upload")
                .post(requestBody)
                .build()

            Log.d("ApiClient", "Upload request URL: ${request.url}")

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Upload response. Code: ${response.code}, Body: ${responseBody.take(500)}")

            if (response.isSuccessful) {
                try {
                    val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                    Log.d("ApiClient", "Upload API response: success=${apiResponse.success}, error=${apiResponse.error}")

                    if (apiResponse.success && apiResponse.data != null) {
                        val dataJson = gson.toJsonTree(apiResponse.data)
                        val fileItem = gson.fromJson(dataJson, FileItem::class.java)
                        Log.d("ApiClient", "File uploaded successfully: $fileItem")
                        Result.success(fileItem)
                    } else {
                        val errorMsg = apiResponse.error ?: "上传失败"
                        Log.e("ApiClient", "Upload failed: $errorMsg")
                        Result.failure(Exception(errorMsg))
                    }
                } catch (e: Exception) {
                    Log.e("ApiClient", "Error parsing upload response: ${e.message}")
                    Result.failure(Exception("解析响应失败: ${e.message}"))
                }
            } else {
                val code = response.code
                val bodyPreview = responseBody.take(500)
                Log.e("ApiClient", "Upload HTTP error: $code, Body: $bodyPreview")
                val friendlyMsg = when (code) {
                    413 -> "上传失败: 文件超出服务器限制 (HTTP 413)，请调大后端限制或使用分片上传"
                    503 -> "上传失败: 服务器暂不可用或正在重启 (HTTP 503)，请稍后重试"
                    else -> {
                        if (responseBody.isNotBlank()) {
                            try {
                                val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                                apiResponse.error ?: "上传失败: HTTP $code"
                            } catch (_: Exception) {
                                "上传失败: HTTP $code"
                            }
                        } else {
                            "上传失败: HTTP $code"
                        }
                    }
                }
                Result.failure(Exception(friendlyMsg))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Upload exception: ${e.message}", e)
            Result.failure(Exception("上传失败: ${e.message}"))
        }
    }

    // 上传用户头像
    suspend fun uploadAvatar(file: File, fileName: String): Result<String> = withContext(Dispatchers.IO) {
        Log.d("ApiClient", "Starting upload avatar: $fileName")
        return@withContext try {
            val fileRequestBody = object : RequestBody() {
                override fun contentType(): MediaType? {
                    return "image/*".toMediaType()
                }

                override fun contentLength(): Long {
                    return file.length()
                }

                override fun writeTo(sink: okio.BufferedSink) {
                    val source = file.source()
                    try {
                        sink.writeAll(source)
                    } finally {
                        source.close()
                    }
                }
            }

            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("avatar", fileName, fileRequestBody)

            val requestBody = multipartBuilder.build()

            val request = Request.Builder()
                .url("${getBaseUrl()}/profile/avatar")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Upload avatar response. Code: ${response.code}")

            if (response.isSuccessful) {
                val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                if (apiResponse.success) {
                    val avatarUrl = if (apiResponse.data != null) {
                        val dataJson = gson.toJsonTree(apiResponse.data)
                        dataJson.asJsonObject.get("avatar_url")?.asString ?: ""
                    } else {
                        ""
                    }
                    Log.d("ApiClient", "Avatar uploaded successfully")
                    Result.success(avatarUrl)
                } else {
                    val errorMsg = apiResponse.error ?: "上传头像失败"
                    Log.e("ApiClient", "Upload avatar failed: $errorMsg")
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorResponse = try {
                    gson.fromJson(responseBody, ApiResponse::class.java)
                } catch (e: Exception) {
                    null
                }
                val errorMsg = errorResponse?.error ?: "上传头像失败: HTTP ${response.code}"
                Log.e("ApiClient", "Upload avatar HTTP error: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Upload avatar exception: ${e.message}", e)
            Result.failure(Exception("上传头像失败: ${e.message}"))
        }
    }

    // 更新用户信息
    suspend fun updateProfile(username: String, email: String): Result<User> = withContext(Dispatchers.IO) {
        Log.d("ApiClient", "Updating profile: username=$username, email=$email")
        return@withContext try {
            val requestBody = gson.toJson(mapOf(
                "username" to username,
                "email" to email
            )).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${getBaseUrl()}/profile")
                .method("PUT", requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Update profile response. Code: ${response.code}")

            if (response.isSuccessful) {
                val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                if (apiResponse.success && apiResponse.data != null) {
                    val dataJson = gson.toJsonTree(apiResponse.data)
                    val user = gson.fromJson(dataJson, User::class.java)
                    Log.d("ApiClient", "Profile updated successfully")
                    Result.success(user)
                } else {
                    val errorMsg = apiResponse.error ?: "更新用户信息失败"
                    Log.e("ApiClient", "Update profile failed: $errorMsg")
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorResponse = try {
                    gson.fromJson(responseBody, ApiResponse::class.java)
                } catch (e: Exception) {
                    null
                }
                val errorMsg = errorResponse?.error ?: "更新用户信息失败: HTTP ${response.code}"
                Log.e("ApiClient", "Update profile HTTP error: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Update profile exception: ${e.message}", e)
            Result.failure(Exception("更新用户信息失败: ${e.message}"))
        }
    }

    // 获取用户信息
    suspend fun getProfile(): Result<UserProfile> = withContext(Dispatchers.IO) {
        Log.d("ApiClient", "Getting user profile")
        return@withContext try {
            val request = Request.Builder()
                .url("${getBaseUrl()}/profile")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Profile response. Code: ${response.code}")

            if (response.isSuccessful) {
                val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                if (apiResponse.success && apiResponse.data != null) {
                    val dataJson = gson.toJsonTree(apiResponse.data)
                    val userProfile = gson.fromJson(dataJson, UserProfile::class.java)
                    Log.d("ApiClient", "Profile loaded successfully")
                    Result.success(userProfile)
                } else {
                    val errorMsg = apiResponse.error ?: "获取用户信息失败"
                    Log.e("ApiClient", "Get profile failed: $errorMsg")
                    Result.failure(Exception(errorMsg))
                }
            } else {
                Log.e("ApiClient", "Get profile HTTP error: ${response.code}")
                Result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Get profile exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    // 分享文件
    suspend fun shareFile(fileId: Int): Result<ShareResponse> = withContext(Dispatchers.IO) {
        return@withContext shareFileInternal(fileId, "files")
    }

    suspend fun shareFolder(folderId: Int): Result<ShareResponse> = withContext(Dispatchers.IO) {
        return@withContext shareFileInternal(folderId, "folders")
    }

    private suspend fun shareFileInternal(id: Int, type: String): Result<ShareResponse> = withContext(Dispatchers.IO) {
        Log.d("ApiClient", "Sharing $type ID: $id")
        return@withContext try {
            val request = Request.Builder()
                .url("${getBaseUrl()}/$type/$id/share")
                .post("".toRequestBody(null))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Share response. Code: ${response.code}")

            if (response.isSuccessful) {
                val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                if (apiResponse.success && apiResponse.data != null) {
                    val dataJson = gson.toJsonTree(apiResponse.data)
                    val shareResponse = gson.fromJson(dataJson, ShareResponse::class.java)
                    Log.d("ApiClient", "Share created successfully: ${shareResponse.share_token}")
                    Result.success(shareResponse)
                } else {
                    val errorMsg = apiResponse.error ?: "分享失败"
                    Log.e("ApiClient", "Share failed: $errorMsg")
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorResponse = try {
                    gson.fromJson(responseBody, ApiResponse::class.java)
                } catch (e: Exception) {
                    null
                }

                val errorMessage = errorResponse?.error ?: responseBody.ifEmpty { "分享失败: HTTP ${response.code}" }
                Log.e("ApiClient", "Share failed: $errorMessage")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Share exception: ${e.message}", e)
            Result.failure(Exception("网络请求失败: ${e.message}"))
        }
    }

    /**
     * 获取分享内容
     */
    suspend fun getSharedContent(shareToken: String): Result<SharedContent> = withContext(Dispatchers.IO) {
        return@withContext try {
            val request = Request.Builder()
                .url("${getBaseUrl()}/share/$shareToken")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Get shared content response. Code: ${response.code}")
            Log.d("ApiClient", "Get shared content response body (first 200 chars): ${responseBody.take(200)}")

            // 检查是否是HTML响应（可能是ngrok警告页面或404页面）
            if (responseBody.trimStart().startsWith("<!") || responseBody.contains("<html")) {
                val errorMsg = if (response.code == 404) {
                    "分享链接无效或已过期"
                } else {
                    "服务器返回了HTML页面，可能是ngrok配置问题。请检查服务器地址是否正确。"
                }
                Log.e("ApiClient", "Received HTML response instead of JSON: $errorMsg")
                return@withContext Result.failure(Exception(errorMsg))
            }

            if (response.isSuccessful) {
                val apiResponse = try {
                    gson.fromJson(responseBody, ApiResponse::class.java)
                } catch (e: Exception) {
                    Log.e("ApiClient", "Failed to parse JSON response: ${e.message}")
                    Log.e("ApiClient", "Response body: $responseBody")
                    return@withContext Result.failure(Exception("服务器返回格式错误: ${e.message}"))
                }

                if (apiResponse.success && apiResponse.data != null) {
                    val dataJson = gson.toJsonTree(apiResponse.data)
                    val type = dataJson.asJsonObject.get("type")?.asString ?: "file"

                    val filesList = mutableListOf<FileItem>()
                    val foldersList = mutableListOf<FolderItem>()

                    val filesArray = dataJson.asJsonObject.getAsJsonArray("files")
                    filesArray?.forEach { jsonElement ->
                        try {
                            val file = gson.fromJson(jsonElement, FileItem::class.java)
                            filesList.add(file)
                        } catch (e: Exception) {
                            Log.e("ApiClient", "Error parsing shared file: ${e.message}")
                        }
                    }

                    val foldersArray = dataJson.asJsonObject.getAsJsonArray("folders")
                    foldersArray?.forEach { jsonElement ->
                        try {
                            val folder = gson.fromJson(jsonElement, FolderItem::class.java)
                            foldersList.add(folder)
                        } catch (e: Exception) {
                            Log.e("ApiClient", "Error parsing shared folder: ${e.message}")
                        }
                    }

                    val sharedContent = SharedContent(
                        type = type,
                        files = filesList,
                        folders = foldersList,
                        folder_name = dataJson.asJsonObject.get("folder_name")?.asString,
                        filename = dataJson.asJsonObject.get("filename")?.asString,
                        file_size = dataJson.asJsonObject.get("file_size")?.asInt,
                        file_type = dataJson.asJsonObject.get("file_type")?.asString,
                        owner = dataJson.asJsonObject.get("owner")?.asString
                    )

                    Log.d("ApiClient", "Shared content retrieved: type=$type, files=${filesList.size}, folders=${foldersList.size}")
                    Result.success(sharedContent)
                } else {
                    val errorMsg = apiResponse.error ?: "获取分享内容失败"
                    Log.e("ApiClient", "Get shared content failed: $errorMsg")
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorResponse = try {
                    gson.fromJson(responseBody, ApiResponse::class.java)
                } catch (e: Exception) {
                    null
                }
                val errorMessage = errorResponse?.error ?: responseBody.ifEmpty { "获取分享内容失败: HTTP ${response.code}" }
                Log.e("ApiClient", "Get shared content HTTP error: $errorMessage")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Get shared content exception: ${e.message}", e)
            Result.failure(Exception("网络请求失败: ${e.message}"))
        }
    }

    /**
     * 保存分享的内容到用户目录（保存所有内容）
     */
    suspend fun saveSharedContent(shareToken: String, targetFolderId: Int? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        return@withContext try {
            val requestBody = if (targetFolderId != null) {
                gson.toJson(mapOf("folder_id" to targetFolderId)).toRequestBody("application/json".toMediaType())
            } else {
                "{}".toRequestBody("application/json".toMediaType())
            }

            val request = Request.Builder()
                .url("${getBaseUrl()}/share/$shareToken/save")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Save shared content response. Code: ${response.code}")

            if (response.isSuccessful) {
                val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                if (apiResponse.success) {
                    Log.d("ApiClient", "Shared content saved successfully")
                    Result.success(true)
                } else {
                    val errorMsg = apiResponse.error ?: "保存失败"
                    Log.e("ApiClient", "Save shared content failed: $errorMsg")
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorResponse = try {
                    gson.fromJson(responseBody, ApiResponse::class.java)
                } catch (e: Exception) {
                    null
                }
                val errorMessage = errorResponse?.error ?: responseBody.ifEmpty { "保存失败: HTTP ${response.code}" }
                Log.e("ApiClient", "Save shared content HTTP error: $errorMessage")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Save shared content exception: ${e.message}", e)
            Result.failure(Exception("网络请求失败: ${e.message}"))
        }
    }

    /**
     * 保存选中的分享内容到用户目录
     */
    suspend fun saveSelectedSharedContent(
        shareToken: String,
        fileIds: List<Int>,
        folderIds: List<Int>,
        targetFolderId: Int? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        return@withContext try {
            val requestBody = gson.toJson(mapOf(
                "folder_id" to targetFolderId,
                "file_ids" to fileIds,
                "folder_ids" to folderIds
            )).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${getBaseUrl()}/share/$shareToken/save")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Save selected shared content response. Code: ${response.code}")

            if (response.isSuccessful) {
                val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                if (apiResponse.success) {
                    // 从响应中获取保存的文件数量
                    val savedCount = try {
                        val dataJson = gson.toJsonTree(apiResponse.data)
                        dataJson.asJsonObject.get("saved_count")?.asInt ?: (fileIds.size + folderIds.size)
                    } catch (e: Exception) {
                        fileIds.size + folderIds.size
                    }
                    Log.d("ApiClient", "Selected shared content saved successfully: $savedCount items")
                    Result.success(savedCount)
                } else {
                    val errorMsg = apiResponse.error ?: "保存失败"
                    Log.e("ApiClient", "Save selected shared content failed: $errorMsg")
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorResponse = try {
                    gson.fromJson(responseBody, ApiResponse::class.java)
                } catch (e: Exception) {
                    null
                }
                val errorMessage = errorResponse?.error ?: responseBody.ifEmpty { "保存失败: HTTP ${response.code}" }
                Log.e("ApiClient", "Save selected shared content HTTP error: $errorMessage")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Save selected shared content exception: ${e.message}", e)
            Result.failure(Exception("网络请求失败: ${e.message}"))
        }
    }

    // 下载文件
    suspend fun downloadFile(
        fileId: Int,
        destination: File,
        onProgress: (currentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        Log.d("ApiClient", "Downloading file ID: $fileId to ${destination.absolutePath}")
        return@withContext try {
            val request = Request.Builder()
                .url("${getBaseUrl()}/files/$fileId/download")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                response.body?.let { body ->
                    val contentLength = body.contentLength()
                    Log.d("ApiClient", "Download content length: $contentLength")

                    destination.parentFile?.mkdirs()
                    destination.delete()

                    val sink = destination.sink().buffer()
                    val source = body.source()

                    var totalBytesRead = 0L
                    var bytesRead: Long
                    val buffer = okio.Buffer()

                    while (source.read(buffer, 8192).also { bytesRead = it } != -1L) {
                        sink.write(buffer, bytesRead)
                        totalBytesRead += bytesRead
                        onProgress(totalBytesRead, contentLength)
                    }

                    sink.close()
                    source.close()

                    Log.d("ApiClient", "Download completed: $totalBytesRead bytes")
                    Result.success(destination)
                } ?: run {
                    Log.e("ApiClient", "Download response body is null")
                    Result.failure(Exception("下载失败: 响应体为空"))
                }
            } else {
                val responseBody = response.body?.string() ?: ""
                Log.e("ApiClient", "Download HTTP error: ${response.code}, Body: $responseBody")
                Result.failure(Exception("下载失败: HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Download exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    // 获取文件夹列表
    suspend fun getFolders(parentFolderId: Int? = null): Result<List<FolderItem>> = withContext(Dispatchers.IO) {
        Log.d("ApiClient", "Getting folder list, parentFolderId: $parentFolderId")
        return@withContext try {
            val url = if (parentFolderId != null) {
                "${getBaseUrl()}/folders?parent_folder_id=$parentFolderId"
            } else {
                "${getBaseUrl()}/folders"
            }
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Get folders response. Code: ${response.code}")

            if (response.isSuccessful) {
                val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                if (apiResponse.success && apiResponse.data != null) {
                    val dataJson = gson.toJsonTree(apiResponse.data)
                    val foldersList = mutableListOf<FolderItem>()

                    if (dataJson.isJsonObject) {
                        val foldersArray = dataJson.asJsonObject.getAsJsonArray("folders")
                        foldersArray?.forEach { jsonElement ->
                            try {
                                val folder = gson.fromJson(jsonElement, FolderItem::class.java)
                                foldersList.add(folder)
                            } catch (e: Exception) {
                                Log.e("ApiClient", "Error parsing folder item: ${e.message}")
                            }
                        }
                    }

                    Log.d("ApiClient", "Total folders parsed: ${foldersList.size}")
                    Result.success(foldersList)
                } else {
                    val errorMsg = apiResponse.error ?: "获取文件夹列表失败"
                    Log.e("ApiClient", "Get folders API error: $errorMsg")
                    Result.failure(Exception(errorMsg))
                }
            } else {
                Log.e("ApiClient", "Get folders HTTP error: ${response.code}")
                Result.failure(Exception("HTTP ${response.code}: ${responseBody.take(200)}"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Get folders exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    // 创建文件夹
    suspend fun createFolder(folderName: String, parentFolderId: Int? = null): Result<FolderItem> = withContext(Dispatchers.IO) {
        Log.d("ApiClient", "Creating folder: $folderName, parentFolderId: $parentFolderId")
        return@withContext try {
            val requestBody = if (parentFolderId != null) {
                gson.toJson(mapOf("folder_name" to folderName, "parent_folder_id" to parentFolderId))
                    .toRequestBody("application/json".toMediaType())
            } else {
                gson.toJson(mapOf("folder_name" to folderName))
                    .toRequestBody("application/json".toMediaType())
            }

            val request = Request.Builder()
                .url("${getBaseUrl()}/folders")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Create folder response. Code: ${response.code}, Body: $responseBody")

            if (response.isSuccessful) {
                val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                if (apiResponse.success && apiResponse.data != null) {
                    val dataJson = gson.toJsonTree(apiResponse.data)
                    val folderItem = gson.fromJson(dataJson, FolderItem::class.java)
                    Log.d("ApiClient", "Folder created successfully: $folderItem")
                    Result.success(folderItem)
                } else {
                    val errorMsg = apiResponse.error ?: "创建文件夹失败"
                    Log.e("ApiClient", "Create folder failed: $errorMsg")
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorResponse = try {
                    gson.fromJson(responseBody, ApiResponse::class.java)
                } catch (e: Exception) {
                    null
                }
                val errorMsg = errorResponse?.error ?: "创建文件夹失败: HTTP ${response.code}"
                Log.e("ApiClient", "Create folder HTTP error: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Create folder exception: ${e.message}", e)
            Result.failure(Exception("创建文件夹失败: ${e.message}"))
        }
    }

    // 同步文件（扫描文件系统中的文件并添加到数据库）
    suspend fun syncFiles(): Result<Int> = withContext(Dispatchers.IO) {
        Log.d("ApiClient", "Syncing files from filesystem")
        return@withContext try {
            val request = Request.Builder()
                .url("${getBaseUrl()}/files/sync")
                .post("".toRequestBody(null))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Sync response. Code: ${response.code}, Body: $responseBody")

            if (response.isSuccessful) {
                val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                if (apiResponse.success && apiResponse.data != null) {
                    val dataJson = gson.toJsonTree(apiResponse.data)
                    val syncedCount = dataJson.asJsonObject.get("synced_count")?.asInt ?: 0
                    Log.d("ApiClient", "Files synced successfully: $syncedCount files")
                    Result.success(syncedCount)
                } else {
                    val errorMsg = apiResponse.error ?: "同步失败"
                    Log.e("ApiClient", "Sync failed: $errorMsg")
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorResponse = try {
                    gson.fromJson(responseBody, ApiResponse::class.java)
                } catch (e: Exception) {
                    null
                }
                val errorMsg = errorResponse?.error ?: "同步失败: HTTP ${response.code}"
                Log.e("ApiClient", "Sync HTTP error: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Sync exception: ${e.message}", e)
            Result.failure(Exception("同步失败: ${e.message}"))
        }
    }

    // 删除文件夹
    suspend fun deleteFolder(folderId: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        Log.d("ApiClient", "Deleting folder ID: $folderId")
        return@withContext try {
            val request = Request.Builder()
                .url("${getBaseUrl()}/folders/$folderId")
                .delete()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Delete folder response. Code: ${response.code}")

            if (response.isSuccessful) {
                val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                if (apiResponse.success) {
                    Log.d("ApiClient", "Folder deleted successfully")
                    Result.success(true)
                } else {
                    val errorMsg = apiResponse.error ?: "删除失败"
                    Log.e("ApiClient", "Delete folder failed: $errorMsg")
                    Result.failure(Exception(errorMsg))
                }
            } else {
                Log.e("ApiClient", "Delete folder HTTP error: ${response.code}")
                Result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Delete folder exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    // 删除文件
    // 移动文件到指定文件夹
    suspend fun moveFile(fileId: Int, targetFolderId: Int?): Result<Boolean> = withContext(Dispatchers.IO) {
        return@withContext try {
            val json = gson.toJson(mapOf("folder_id" to targetFolderId))
            val requestBody = json.toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("${getBaseUrl()}/files/$fileId/move")
                .put(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Move file response. Code: ${response.code}, Body: $responseBody")

            if (response.isSuccessful) {
                val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                if (apiResponse.success) {
                    Result.success(true)
                } else {
                    val errorMsg = apiResponse.error ?: "移动文件失败"
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorResponse = try {
                    gson.fromJson(responseBody, ApiResponse::class.java)
                } catch (e: Exception) {
                    null
                }
                val errorMessage = errorResponse?.error ?: responseBody.ifEmpty { "移动文件失败: HTTP ${response.code}" }
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Move file exception: ${e.message}", e)
            Result.failure(Exception("移动文件异常: ${e.message}"))
        }
    }

    // 重命名文件夹
    suspend fun renameFolder(folderId: Int, newFolderName: String): Result<Boolean> = withContext(Dispatchers.IO) {
        return@withContext try {
            val json = gson.toJson(mapOf("folder_name" to newFolderName))
            val requestBody = json.toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("${getBaseUrl()}/folders/$folderId/rename")
                .put(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Rename folder response. Code: ${response.code}, Body: $responseBody")

            if (response.isSuccessful) {
                val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                if (apiResponse.success) {
                    Result.success(true)
                } else {
                    val errorMsg = apiResponse.error ?: "重命名文件夹失败"
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorResponse = try {
                    gson.fromJson(responseBody, ApiResponse::class.java)
                } catch (e: Exception) {
                    null
                }
                val errorMessage = errorResponse?.error ?: responseBody.ifEmpty { "重命名文件夹失败: HTTP ${response.code}" }
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Rename folder exception: ${e.message}", e)
            Result.failure(Exception("重命名文件夹异常: ${e.message}"))
        }
    }

    // 重命名文件
    suspend fun renameFile(fileId: Int, newFilename: String): Result<Boolean> = withContext(Dispatchers.IO) {
        return@withContext try {
            val json = gson.toJson(mapOf("filename" to newFilename))
            val requestBody = json.toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("${getBaseUrl()}/files/$fileId/rename")
                .put(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Rename file response. Code: ${response.code}, Body: $responseBody")

            if (response.isSuccessful) {
                val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                if (apiResponse.success) {
                    Result.success(true)
                } else {
                    val errorMsg = apiResponse.error ?: "重命名文件失败"
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorResponse = try {
                    gson.fromJson(responseBody, ApiResponse::class.java)
                } catch (e: Exception) {
                    null
                }
                val errorMessage = errorResponse?.error ?: responseBody.ifEmpty { "重命名文件失败: HTTP ${response.code}" }
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Rename file exception: ${e.message}", e)
            Result.failure(Exception("重命名文件异常: ${e.message}"))
        }
    }

    suspend fun deleteFile(fileId: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        Log.d("ApiClient", "Deleting file ID: $fileId")
        return@withContext try {
            val request = Request.Builder()
                .url("${getBaseUrl()}/files/$fileId")
                .delete()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d("ApiClient", "Delete response. Code: ${response.code}")

            if (response.isSuccessful) {
                val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                if (apiResponse.success) {
                    Log.d("ApiClient", "File deleted successfully")
                    Result.success(true)
                } else {
                    val errorMsg = apiResponse.error ?: "删除失败"
                    Log.e("ApiClient", "Delete failed: $errorMsg")
                    Result.failure(Exception(errorMsg))
                }
            } else {
                Log.e("ApiClient", "Delete HTTP error: ${response.code}")
                Result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Delete exception: ${e.message}", e)
            Result.failure(e)
        }
    }
}

// 文件项数据类
data class FileItem(
    val id: Int = 0,
    val filename: String = "",
    val file_size: Int = 0,
    val file_type: String? = null,
    val upload_date: String? = null,
    val share_token: String? = null,
    val share_expiry: String? = null,
    val owner_id: Int = 0,
    val folder_id: Int? = null  // 所属文件夹ID
) {
    fun getFormattedSize(): String {
        return when {
            file_size < 1024 -> "$file_size B"
            file_size < 1024 * 1024 -> String.format("%.1f KB", file_size / 1024.0)
            else -> String.format("%.1f MB", file_size / (1024.0 * 1024.0))
        }
    }
}

// 用户信息数据类
data class UserProfile(
    val user: User? = null,
    val stats: UserStats? = null
)

data class UserStats(
    val files_count: Int = 0,
    val total_size: Long = 0,
    val total_size_mb: Double = 0.0
)

// 分享响应数据类
data class ShareResponse(
    val message: String = "",
    val share_token: String? = null,
    val share_url: String? = null,
    val expiry: String? = null
)

// 分享内容数据类
data class SharedContent(
    val type: String,  // "file" or "folder"
    val files: List<FileItem>,
    val folders: List<FolderItem>,
    val folder_name: String? = null,
    val filename: String? = null,
    val file_size: Int? = null,
    val file_type: String? = null,
    val owner: String? = null
)

// 文件夹数据类
data class FolderItem(
    val id: Int = 0,
    val folder_name: String = "",
    val created_date: String? = null,
    val owner_id: Int = 0,
    val parent_folder_id: Int? = null
)