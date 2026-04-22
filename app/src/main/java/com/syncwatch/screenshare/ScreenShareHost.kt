package com.syncwatch.screenshare

import android.content.Context
import android.content.Intent

class ScreenShareHost(
    private val context: Context,
    private val roomId: String,
    private val resultCode: Int,
    private val resultData: Intent
) {
    fun start() {
    }

    fun startCapture(resultCode: Int, resultData: Intent) {
    }

    fun stop() {
    }
}
