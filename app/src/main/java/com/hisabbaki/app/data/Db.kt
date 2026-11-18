package com.hisabbaki.app.data

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Runs database work on a background thread and delivers the result on the main thread. */
object Db {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun <T> async(work: () -> T, onResult: (Result<T>) -> Unit) {
        executor.execute {
            val result: Result<T> = try {
                Result.success(work())
            } catch (t: Throwable) {
                Result.failure(t)
            }
            mainHandler.post { onResult(result) }
        }
    }
}
