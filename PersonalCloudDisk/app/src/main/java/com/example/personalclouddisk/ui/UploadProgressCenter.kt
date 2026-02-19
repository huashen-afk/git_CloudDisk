package com.example.personalclouddisk.ui

/**
 * 简单的上传进度分发中心，用于在 MainActivity 与 DownloadActivity 之间同步上传状态。
 */
object UploadProgressCenter {

    data class State(
        val uploading: Boolean = false,
        val fileName: String? = null,
        val progress: Int = 0
    )

    interface Listener {
        fun onStateChanged(state: State)
    }

    private val listeners = mutableSetOf<Listener>()
    @Volatile
    private var state: State = State()

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onStateChanged(state)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun start(fileName: String) {
        updateState(State(uploading = true, fileName = fileName, progress = 0))
    }

    fun updateProgress(progress: Int, fileName: String? = state.fileName) {
        val safeProgress = progress.coerceIn(0, 100)
        updateState(State(uploading = true, fileName = fileName, progress = safeProgress))
    }

    fun finish() {
        updateState(State(uploading = false, fileName = null, progress = 0))
    }

    private fun updateState(newState: State) {
        state = newState
        listeners.forEach { it.onStateChanged(state) }
    }
}

