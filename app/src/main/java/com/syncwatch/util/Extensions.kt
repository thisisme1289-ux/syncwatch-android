package com.syncwatch.util

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── View helpers ────────────────────────────────────────────────────────────

fun View.show() { visibility = View.VISIBLE }
fun View.hide() { visibility = View.GONE }
fun View.invisible() { visibility = View.INVISIBLE }

// ── Toast helpers ────────────────────────────────────────────────────────────

fun Context.toast(message: String, long: Boolean = false) {
    Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

fun Fragment.toast(message: String, long: Boolean = false) {
    requireContext().toast(message, long)
}

// ── Flow collection tied to Fragment lifecycle ───────────────────────────────

/**
 * Collects a Flow safely in a Fragment, automatically stopping when the view
 * is destroyed and restarting when it comes back.
 *
 * Usage:
 * ```
 * collectFlow(viewModel.uiState) { state -> render(state) }
 * ```
 */
fun <T> Fragment.collectFlow(flow: Flow<T>, action: suspend (T) -> Unit) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect { action(it) }
        }
    }
}

// ── Formatting helpers ────────────────────────────────────────────────────────

/** Formats a duration in seconds as "MM:SS" or "H:MM:SS". */
fun Double.toTimestamp(): String {
    val totalSeconds = toLong()
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%02d:%02d".format(m, s)
}

/** Formats bytes as a human-readable size string (KB / MB / GB). */
fun Long.toReadableBytes(): String = when {
    this < 1_024               -> "${this} B"
    this < 1_048_576           -> "${"%.1f".format(this / 1_024.0)} KB"
    this < 1_073_741_824       -> "${"%.1f".format(this / 1_048_576.0)} MB"
    else                       -> "${"%.2f".format(this / 1_073_741_824.0)} GB"
}

/** Formats a unix-ms timestamp as a short "HH:mm" string for chat messages. */
fun Long.toChatTime(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(this))
