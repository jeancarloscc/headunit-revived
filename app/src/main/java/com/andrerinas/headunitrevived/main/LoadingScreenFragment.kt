package com.andrerinas.headunitrevived.main

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.PickMediaContract
import com.andrerinas.headunitrevived.utils.Settings
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import java.io.File

class LoadingScreenFragment : Fragment() {

    private lateinit var settings: Settings

    private var previewArea: FrameLayout? = null
    private var previewPlaceholder: View? = null
    private var previewImage: ImageView? = null
    private var previewStatusText: View? = null
    private var toggleContainer: View? = null
    private var toggleShowText: Switch? = null
    private var toggleKeepAspectRatio: Switch? = null
    private var btnRemove: View? = null
    private var fullscreenOverlay: FrameLayout? = null
    private var fullscreenImage: ImageView? = null
    private var fullscreenStatusText: View? = null

    // Video playback managed programmatically (not in XML to avoid inflation issues)
    private var previewMediaPlayer: MediaPlayer? = null
    private var fullscreenMediaPlayer: MediaPlayer? = null

    private val filePicker = registerForActivityResult(PickMediaContract()) { uri ->
        uri?.let { handleFileSelected(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_loading_screen, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            setupViews(view)
        } catch (e: Exception) {
            AppLog.e("LoadingScreenFragment setup failed: ${e.message}")
            Toast.makeText(context, R.string.loading_screen_file_error, Toast.LENGTH_SHORT).show()
            navigateBack()
        }
    }

    private fun setupViews(view: View) {
        settings = App.provide(requireContext()).settings

        previewArea = view.findViewById(R.id.preview_area)
        previewPlaceholder = view.findViewById(R.id.preview_placeholder)
        previewImage = view.findViewById(R.id.preview_image)
        previewStatusText = view.findViewById(R.id.preview_status_text)
        toggleContainer = view.findViewById(R.id.toggle_container)
        toggleShowText = view.findViewById(R.id.toggle_show_text)
        btnRemove = view.findViewById(R.id.btn_remove)
        fullscreenOverlay = view.findViewById(R.id.fullscreen_overlay)
        fullscreenImage = view.findViewById(R.id.fullscreen_image)
        fullscreenStatusText = view.findViewById(R.id.fullscreen_status_text)

        // Resolution and aspect ratio recommendation
        try {
            val dm = resources.displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            view.findViewById<TextView>(R.id.recommendation_text)?.text =
                getString(R.string.loading_screen_recommendation, w, h)

            val gcd = gcd(w, h)
            val ratioW = w / gcd
            val ratioH = h / gcd
            view.findViewById<TextView>(R.id.aspect_ratio_text)?.text =
                getString(R.string.loading_screen_recommended_aspect_ratio, ratioW, ratioH)
        } catch (_: Exception) {}

        // Toolbar
        view.findViewById<MaterialToolbar>(R.id.toolbar)?.setNavigationOnClickListener {
            navigateBack()
        }

        // Back press
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenOverlay?.visibility == View.VISIBLE) {
                    hideFullscreen()
                } else {
                    navigateBack()
                }
            }
        })

        toggleKeepAspectRatio = view.findViewById(R.id.toggle_keep_aspect_ratio)

        // Toggles
        toggleShowText?.isChecked = settings.loadingScreenShowText
        toggleShowText?.setOnCheckedChangeListener { _, isChecked ->
            settings.loadingScreenShowText = isChecked
            updateStatusTextVisibility()
        }

        toggleKeepAspectRatio?.isChecked = settings.loadingScreenKeepAspectRatio
        toggleKeepAspectRatio?.setOnCheckedChangeListener { _, isChecked ->
            settings.loadingScreenKeepAspectRatio = isChecked
            updatePreviewScaleType()
            // Reload preview to reflect the change immediately
            val path = settings.loadingScreenMediaPath
            val type = settings.loadingScreenMediaType
            if (path.isNotEmpty() && type.isNotEmpty()) {
                loadImagePreview(path, type)
            }
        }

        // Select file
        view.findViewById<View>(R.id.btn_select_file)?.setOnClickListener {
            try {
                filePicker.launch(Unit)
            } catch (e: Exception) {
                AppLog.e("File picker failed: ${e.message}")
                Toast.makeText(context, R.string.loading_screen_file_error, Toast.LENGTH_SHORT).show()
            }
        }

        // Remove
        btnRemove?.setOnClickListener { removeMedia() }

        // Preview tap → fullscreen
        previewArea?.setOnClickListener {
            if (settings.loadingScreenMediaPath.isNotEmpty()) {
                showFullscreen()
            }
        }

        // Fullscreen tap → close
        fullscreenOverlay?.setOnClickListener { hideFullscreen() }

        refreshUI()
    }

    override fun onPause() {
        super.onPause()
        releaseMediaPlayers()
    }

    override fun onDestroyView() {
        releaseMediaPlayers()
        super.onDestroyView()
    }

    private fun navigateBack() {
        try {
            if (!findNavController().navigateUp()) {
                requireActivity().finish()
            }
        } catch (_: Exception) {
            try { requireActivity().finish() } catch (_: Exception) {}
        }
    }

    // --- UI State ---

    private fun refreshUI() {
        val path = settings.loadingScreenMediaPath
        val type = settings.loadingScreenMediaType
        val file = if (path.isNotEmpty()) File(path) else null
        val hasMedia = file != null && file.exists() && type.isNotEmpty()

        val fullscreenHint = view?.findViewById<View>(R.id.fullscreen_hint)

        if (hasMedia) {
            previewPlaceholder?.visibility = View.GONE
            toggleContainer?.visibility = View.VISIBLE
            btnRemove?.visibility = View.VISIBLE
            fullscreenHint?.visibility = View.VISIBLE
            loadImagePreview(path, type)
        } else {
            previewPlaceholder?.visibility = View.VISIBLE
            previewImage?.visibility = View.GONE
            toggleContainer?.visibility = View.GONE
            btnRemove?.visibility = View.GONE
            fullscreenHint?.visibility = View.GONE
            if (path.isNotEmpty()) {
                settings.loadingScreenMediaPath = ""
                settings.loadingScreenMediaType = ""
            }
        }
        updateStatusTextVisibility()
        updatePreviewScaleType()
    }

    private fun updatePreviewScaleType() {
        val keepRatio = settings.loadingScreenKeepAspectRatio
        previewImage?.scaleType = if (keepRatio) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_XY
        fullscreenImage?.scaleType = if (keepRatio) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_XY
    }

    private fun updateStatusTextVisibility() {
        val hasMedia = settings.loadingScreenMediaPath.isNotEmpty()
        val show = hasMedia && settings.loadingScreenShowText
        previewStatusText?.visibility = if (show) View.VISIBLE else View.GONE
        fullscreenStatusText?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun loadImagePreview(path: String, type: String) {
        val file = File(path)
        if (!file.exists()) return

        // Apply aspect ratio setting BEFORE loading
        val keepRatio = settings.loadingScreenKeepAspectRatio
        previewImage?.scaleType = if (keepRatio) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_XY

        previewImage?.visibility = View.VISIBLE
        try {
            if (type == "gif") {
                Glide.with(this).asGif().load(file).into(previewImage!!)
            } else {
                Glide.with(this).load(file).into(previewImage!!)
            }
        } catch (e: Exception) {
            AppLog.e("Failed to load preview: ${e.message}")
            previewImage?.visibility = View.GONE
            previewPlaceholder?.visibility = View.VISIBLE
        }
    }

    // --- File Selection ---

    private fun handleFileSelected(uri: Uri) {
        val ctx = context ?: return
        val contentResolver = ctx.contentResolver

        val mimeType = contentResolver.getType(uri)
        val mediaType = when {
            mimeType == null -> null
            mimeType == "image/gif" -> "gif"
            mimeType.startsWith("image/") -> "image"
            mimeType.startsWith("video/") -> "video"
            else -> null
        }
        if (mediaType == null) {
            Toast.makeText(ctx, R.string.loading_screen_unsupported_format, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val size = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
            if (size > 10L * 1024 * 1024) {
                Toast.makeText(ctx, R.string.loading_screen_file_too_large, Toast.LENGTH_SHORT).show()
                return
            }
        } catch (_: Exception) {}

        val ext = when (mimeType) {
            "image/gif" -> "gif"; "image/png" -> "png"; "image/jpeg" -> "jpg"
            "image/webp" -> "webp"; "image/bmp" -> "bmp"
            "video/mp4" -> "mp4"; "video/x-matroska" -> "mkv"
            "video/webm" -> "webm"; "video/3gpp" -> "3gp"
            else -> if (mimeType?.startsWith("video/") == true) "mp4" else "img"
        }

        val dir = File(ctx.filesDir, "loading_media")
        if (!dir.exists()) dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }

        val destFile = File(dir, "loading_screen.$ext")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw Exception("null input stream")
        } catch (e: Exception) {
            AppLog.e("Copy failed: ${e.message}")
            Toast.makeText(ctx, R.string.loading_screen_file_error, Toast.LENGTH_SHORT).show()
            return
        }

        settings.loadingScreenMediaPath = destFile.absolutePath
        settings.loadingScreenMediaType = mediaType

        releaseMediaPlayers()
        try { previewImage?.let { Glide.with(this).clear(it) } } catch (_: Exception) {}
        refreshUI()
    }

    private fun removeMedia() {
        val path = settings.loadingScreenMediaPath
        if (path.isNotEmpty()) {
            try { File(path).delete() } catch (_: Exception) {}
        }
        settings.loadingScreenMediaPath = ""
        settings.loadingScreenMediaType = ""
        settings.loadingScreenShowText = false

        releaseMediaPlayers()
        try {
            previewImage?.let { Glide.with(this).clear(it) }
            fullscreenImage?.let { Glide.with(this).clear(it) }
        } catch (_: Exception) {}
        refreshUI()
    }

    // --- Fullscreen Preview ---

    private fun showFullscreen() {
        val path = settings.loadingScreenMediaPath
        val type = settings.loadingScreenMediaType
        if (path.isEmpty() || type.isEmpty()) return

        val file = File(path)
        if (!file.exists()) return

        fullscreenOverlay?.visibility = View.VISIBLE
        fullscreenOverlay?.alpha = 0f
        fullscreenOverlay?.animate()?.alpha(1f)?.setDuration(200)?.start()

        try {
            if (type == "video") {
                // For video fullscreen, use a SurfaceView + MediaPlayer
                fullscreenImage?.visibility = View.GONE
                setupFullscreenVideo(file)
            } else {
                fullscreenImage?.visibility = View.VISIBLE
                if (type == "gif") {
                    Glide.with(this).asGif().load(file).into(fullscreenImage!!)
                } else {
                    Glide.with(this).load(file).into(fullscreenImage!!)
                    // Ken Burns for static images
                    val anim = ObjectAnimator.ofPropertyValuesHolder(
                        fullscreenImage!!,
                        PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.05f),
                        PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.05f)
                    ).apply {
                        duration = 8000
                        repeatMode = ObjectAnimator.REVERSE
                        repeatCount = ObjectAnimator.INFINITE
                        start()
                    }
                    fullscreenImage?.tag = anim
                }
            }
        } catch (e: Exception) {
            AppLog.e("Fullscreen preview failed: ${e.message}")
            hideFullscreen()
        }

        updateStatusTextVisibility()
    }

    private fun setupFullscreenVideo(file: File) {
        try {
            val surfaceView = SurfaceView(requireContext())
            surfaceView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            fullscreenOverlay?.addView(surfaceView, 0)

            val mp = MediaPlayer()
            fullscreenMediaPlayer = mp
            mp.setDataSource(file.absolutePath)
            mp.isLooping = true
            mp.setVolume(0f, 0f)

            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    try {
                        mp.setDisplay(holder)
                        mp.prepareAsync()
                    } catch (_: Exception) {}
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    try { mp.setDisplay(null) } catch (_: Exception) {}
                }
            })

            mp.setOnPreparedListener { it.start() }
            mp.setOnErrorListener { _, _, _ ->
                AppLog.e("Fullscreen video error")
                true
            }
        } catch (e: Exception) {
            AppLog.e("Video setup failed: ${e.message}")
        }
    }

    private fun hideFullscreen() {
        fullscreenOverlay?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
            fullscreenOverlay?.visibility = View.GONE
            // Release fullscreen media player
            releaseFullscreenMediaPlayer()
            // Remove any dynamically added SurfaceView
            val overlay = fullscreenOverlay ?: return@withEndAction
            for (i in overlay.childCount - 1 downTo 0) {
                val child = overlay.getChildAt(i)
                if (child is SurfaceView) overlay.removeView(child)
            }
            // Cancel Ken Burns
            (fullscreenImage?.tag as? ObjectAnimator)?.cancel()
            fullscreenImage?.scaleX = 1f
            fullscreenImage?.scaleY = 1f
            try { fullscreenImage?.let { Glide.with(this@LoadingScreenFragment).clear(it) } } catch (_: Exception) {}
        }?.start()
    }

    private fun releaseMediaPlayers() {
        releaseFullscreenMediaPlayer()
        try {
            previewMediaPlayer?.release()
            previewMediaPlayer = null
        } catch (_: Exception) {}
    }

    private fun releaseFullscreenMediaPlayer() {
        try {
            fullscreenMediaPlayer?.release()
            fullscreenMediaPlayer = null
        } catch (_: Exception) {}
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
