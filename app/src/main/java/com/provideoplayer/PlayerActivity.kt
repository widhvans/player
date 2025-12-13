package com.provideoplayer

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.provideoplayer.databinding.ActivityPlayerBinding
import com.provideoplayer.model.AspectRatioMode
import com.provideoplayer.model.VideoFilter
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URI = "video_uri"
        const val EXTRA_VIDEO_TITLE = "video_title"
        const val EXTRA_VIDEO_POSITION = "video_position"
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_PLAYLIST_TITLES = "playlist_titles"
        const val EXTRA_IS_NETWORK_STREAM = "is_network_stream"
        
        private const val SEEK_INCREMENT = 10000L // 10 seconds
        private const val HIDE_CONTROLS_DELAY = 4000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var audioManager: AudioManager
    private lateinit var gestureDetector: GestureDetectorCompat
    
    private var playlist: List<String> = emptyList()
    private var playlistTitles: List<String> = emptyList()
    private var currentIndex = 0
    private var isNetworkStream = false
    
    // Gesture controls
    private var screenWidth = 0
    private var screenHeight = 0
    private var currentBrightness = 0.5f
    private var currentVolume = 0
    private var maxVolume = 0
    private var isGestureActive = false
    private var gestureType = GestureType.NONE
    
    // Control visibility
    private val hideHandler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var isLocked = false
    
    // Playback state
    private var playbackSpeed = 1.0f
    private var currentAspectRatio = AspectRatioMode.FIT
    private var currentFilter = VideoFilter.NONE
    
    // PiP
    private var isPipMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Fullscreen immersive
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Get screen dimensions
        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        
        // Initialize audio manager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        
        // Get current brightness
        currentBrightness = window.attributes.screenBrightness
        if (currentBrightness < 0) {
            currentBrightness = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                125
            ) / 255f
        }
        
        // Parse intent
        parseIntent()
        
        // Setup player
        initializePlayer()
        setupGestureControls()
        setupUIControls()
        
        // Hide system UI
        hideSystemUI()
    }

    private fun parseIntent() {
        intent?.let { intentData ->
            val uri = intentData.getStringExtra(EXTRA_VIDEO_URI)
            val title = intentData.getStringExtra(EXTRA_VIDEO_TITLE) ?: "Video"
            currentIndex = intentData.getIntExtra(EXTRA_VIDEO_POSITION, 0)
            isNetworkStream = intentData.getBooleanExtra(EXTRA_IS_NETWORK_STREAM, false)
            
            // Get playlist from intent
            val playlistFromIntent = intentData.getStringArrayListExtra(EXTRA_PLAYLIST)
            val titlesFromIntent = intentData.getStringArrayListExtra(EXTRA_PLAYLIST_TITLES)
            
            // Handle different scenarios
            when {
                // Case 1: Playlist provided in extras
                !playlistFromIntent.isNullOrEmpty() -> {
                    playlist = playlistFromIntent.filter { it.isNotEmpty() }
                    playlistTitles = titlesFromIntent ?: playlist.map { "Video" }
                }
                // Case 2: Single URI provided
                !uri.isNullOrEmpty() -> {
                    playlist = listOf(uri)
                    playlistTitles = listOf(title)
                    currentIndex = 0
                }
                // Case 3: Intent data (from file manager or external app)
                intentData.data != null -> {
                    playlist = listOf(intentData.data.toString())
                    playlistTitles = listOf(intentData.data?.lastPathSegment ?: "Video")
                    currentIndex = 0
                }
                else -> {
                    // No video source
                    playlist = emptyList()
                    playlistTitles = emptyList()
                }
            }
            
            // Validate currentIndex
            currentIndex = currentIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
            
            // Set title
            binding.videoTitle.text = playlistTitles.getOrNull(currentIndex) ?: "Video"
            
            // Debug log
            android.util.Log.d("PlayerActivity", "Playlist size: ${playlist.size}, currentIndex: $currentIndex")
            if (playlist.isNotEmpty()) {
                android.util.Log.d("PlayerActivity", "First URI: ${playlist[0]}")
            }
        }
    }

    private fun initializePlayer() {
        // Check if we have videos to play
        if (playlist.isEmpty()) {
            Toast.makeText(this, "No video to play", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Track selector - NO quality restrictions
        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters()
                .setForceHighestSupportedBitrate(true) // Always use best quality
            )
        }
        
        // Build ExoPlayer
        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true // Handle audio focus
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer
                binding.playerView.useController = false // Use custom controls
                
                // Add listener
                exoPlayer.addListener(playerListener)
                
                // Load playlist
                loadPlaylist()
                
                // Start playback
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
    }

    private fun loadPlaylist() {
        player?.let { exoPlayer ->
            if (playlist.isEmpty()) {
                Toast.makeText(this, "No video to play", Toast.LENGTH_SHORT).show()
                return@let
            }
            
            val mediaItems = playlist.mapNotNull { uriString ->
                try {
                    val uri = Uri.parse(uriString)
                    MediaItem.Builder()
                        .setUri(uri)
                        .build()
                } catch (e: Exception) {
                    null
                }
            }
            
            if (mediaItems.isEmpty()) {
                Toast.makeText(this, "Failed to load video", Toast.LENGTH_SHORT).show()
                return@let
            }
            
            // Ensure currentIndex is valid
            val validIndex = currentIndex.coerceIn(0, mediaItems.size - 1)
            exoPlayer.setMediaItems(mediaItems, validIndex, 0)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val stateName = when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            android.util.Log.d("PlayerActivity", "Playback state changed: $stateName")
            
            when (state) {
                Player.STATE_BUFFERING -> {
                    binding.progressBar.visibility = View.VISIBLE
                }
                Player.STATE_READY -> {
                    binding.progressBar.visibility = View.GONE
                    updateDuration()
                    android.util.Log.d("PlayerActivity", "Video ready - Duration: ${player?.duration}")
                }
                Player.STATE_ENDED -> {
                    if (currentIndex < playlist.size - 1) {
                        playNext()
                    }
                }
                Player.STATE_IDLE -> {
                    // Check if there's an error
                    player?.playerError?.let { error ->
                        android.util.Log.e("PlayerActivity", "Player error in IDLE: ${error.message}", error)
                    }
                }
            }
        }
        
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            android.util.Log.d("PlayerActivity", "isPlaying changed: $isPlaying")
            updatePlayPauseButton()
            if (isPlaying) {
                startProgressUpdates()
                scheduleHideControls()
            } else {
                stopProgressUpdates()
                showControls()
            }
        }
        
        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.e("PlayerActivity", "Player error: ${error.errorCodeName} - ${error.message}", error)
            Toast.makeText(
                this@PlayerActivity,
                "Playback error: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
            binding.progressBar.visibility = View.GONE
        }
        
        override fun onTracksChanged(tracks: Tracks) {
            android.util.Log.d("PlayerActivity", "Tracks changed - groups: ${tracks.groups.size}")
            for (group in tracks.groups) {
                android.util.Log.d("PlayerActivity", "Track type: ${group.type}, length: ${group.length}")
            }
        }
        
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            android.util.Log.d("PlayerActivity", "Media item transition - URI: ${mediaItem?.localConfiguration?.uri}")
            currentIndex = player?.currentMediaItemIndex ?: 0
            binding.videoTitle.text = playlistTitles.getOrNull(currentIndex) ?: "Video"
            updatePrevNextButtons()
        }
    }

    private fun setupGestureControls() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isLocked) {
                    toggleControls()
                }
                return true
            }
            
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isLocked) {
                    val x = e.x
                    if (x < screenWidth / 3) {
                        // Left third - rewind
                        seekBackward()
                        showSeekIndicator(-10)
                    } else if (x > screenWidth * 2 / 3) {
                        // Right third - forward
                        seekForward()
                        showSeekIndicator(10)
                    } else {
                        // Center - play/pause
                        togglePlayPause()
                    }
                }
                return true
            }
            
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isLocked || e1 == null) return false
                
                val deltaY = e1.y - e2.y
                val deltaX = e1.x - e2.x
                
                // Determine gesture type on first scroll
                if (!isGestureActive) {
                    isGestureActive = true
                    gestureType = when {
                        abs(deltaX) > abs(deltaY) -> GestureType.SEEK
                        e1.x < screenWidth / 2 -> GestureType.BRIGHTNESS
                        else -> GestureType.VOLUME
                    }
                }
                
                when (gestureType) {
                    GestureType.BRIGHTNESS -> {
                        adjustBrightness(deltaY / screenHeight)
                    }
                    GestureType.VOLUME -> {
                        adjustVolume(deltaY / screenHeight)
                    }
                    GestureType.SEEK -> {
                        // Horizontal seek handled separately
                    }
                    GestureType.NONE -> {}
                }
                
                return true
            }
        })
        
        binding.gestureOverlay.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                isGestureActive = false
                gestureType = GestureType.NONE
                hideGestureIndicator()
            }
            
            true
        }
    }

    private fun setupUIControls() {
        // Back button
        binding.btnBack.setOnClickListener {
            onBackPressed()
        }
        
        // Play/Pause button
        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
        }
        
        // Rewind button
        binding.btnRewind.setOnClickListener {
            seekBackward()
        }
        
        // Forward button
        binding.btnForward.setOnClickListener {
            seekForward()
        }
        
        // Previous video
        binding.btnPrevious.setOnClickListener {
            playPrevious()
        }
        
        // Next video
        binding.btnNext.setOnClickListener {
            playNext()
        }
        
        // Lock button
        binding.btnLock.setOnClickListener {
            toggleLock()
        }
        
        // Unlock button
        binding.btnUnlock.setOnClickListener {
            toggleLock()
        }
        
        // Settings button
        binding.btnSettings.setOnClickListener {
            showSettingsMenu()
        }
        
        // Subtitle button
        binding.btnSubtitle.setOnClickListener {
            showSubtitleDialog()
        }
        
        // Audio track button
        binding.btnAudioTrack.setOnClickListener {
            showAudioTrackDialog()
        }
        
        // Filter button
        binding.btnFilter.setOnClickListener {
            showFilterDialog()
        }
        
        // Aspect ratio button
        binding.btnAspectRatio.setOnClickListener {
            cycleAspectRatio()
        }
        
        // Speed button
        binding.btnSpeed.setOnClickListener {
            showSpeedDialog()
        }
        
        // PiP button
        binding.btnPip.setOnClickListener {
            enterPictureInPicture()
        }
        
        // Rotate button
        binding.btnRotate.setOnClickListener {
            toggleOrientation()
        }
        
        // SeekBar
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration ?: 0
                    val position = (duration * progress / 100).toLong()
                    binding.currentTime.text = formatTime(position)
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                stopProgressUpdates()
            }
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val duration = player?.duration ?: 0
                val progress = seekBar?.progress ?: 0
                val position = (duration * progress / 100).toLong()
                player?.seekTo(position)
                startProgressUpdates()
            }
        })
        
        updatePrevNextButtons()
    }

    private fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
            }
        }
    }

    private fun updatePlayPauseButton() {
        val isPlaying = player?.isPlaying == true
        binding.btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun seekForward() {
        player?.let {
            val newPosition = (it.currentPosition + SEEK_INCREMENT).coerceAtMost(it.duration)
            it.seekTo(newPosition)
        }
    }

    private fun seekBackward() {
        player?.let {
            val newPosition = (it.currentPosition - SEEK_INCREMENT).coerceAtLeast(0)
            it.seekTo(newPosition)
        }
    }

    private fun playNext() {
        player?.let {
            if (it.hasNextMediaItem()) {
                it.seekToNextMediaItem()
            }
        }
    }

    private fun playPrevious() {
        player?.let {
            if (it.hasPreviousMediaItem()) {
                it.seekToPreviousMediaItem()
            } else {
                it.seekTo(0)
            }
        }
    }

    private fun updatePrevNextButtons() {
        binding.btnPrevious.alpha = if (player?.hasPreviousMediaItem() == true) 1f else 0.5f
        binding.btnNext.alpha = if (player?.hasNextMediaItem() == true) 1f else 0.5f
    }

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            binding.controlsContainer.visibility = View.GONE
            binding.topBar.visibility = View.GONE
            binding.bottomBar.visibility = View.GONE
            binding.lockContainer.visibility = View.VISIBLE
        } else {
            binding.lockContainer.visibility = View.GONE
            showControls()
        }
    }

    private fun toggleControls() {
        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        controlsVisible = true
        binding.controlsContainer.visibility = View.VISIBLE
        binding.topBar.visibility = View.VISIBLE
        binding.bottomBar.visibility = View.VISIBLE
        scheduleHideControls()
    }

    private fun hideControls() {
        controlsVisible = false
        binding.controlsContainer.visibility = View.GONE
        binding.topBar.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
    }

    private fun scheduleHideControls() {
        hideHandler.removeCallbacksAndMessages(null)
        hideHandler.postDelayed({
            if (player?.isPlaying == true && !isLocked) {
                hideControls()
            }
        }, HIDE_CONTROLS_DELAY)
    }

    private fun adjustBrightness(delta: Float) {
        currentBrightness = (currentBrightness + delta).coerceIn(0.01f, 1f)
        
        val layoutParams = window.attributes
        layoutParams.screenBrightness = currentBrightness
        window.attributes = layoutParams
        
        showGestureIndicator(GestureType.BRIGHTNESS, (currentBrightness * 100).toInt())
    }

    private fun adjustVolume(delta: Float) {
        val newVolume = (currentVolume + delta * maxVolume).toInt().coerceIn(0, maxVolume)
        currentVolume = newVolume
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
        
        showGestureIndicator(GestureType.VOLUME, (currentVolume * 100 / maxVolume))
    }

    private fun showGestureIndicator(type: GestureType, value: Int) {
        binding.gestureIndicator.visibility = View.VISIBLE
        when (type) {
            GestureType.BRIGHTNESS -> {
                binding.gestureIcon.setImageResource(R.drawable.ic_brightness)
                binding.gestureText.text = "$value%"
            }
            GestureType.VOLUME -> {
                binding.gestureIcon.setImageResource(R.drawable.ic_volume)
                binding.gestureText.text = "$value%"
            }
            else -> {}
        }
        binding.gestureProgress.progress = value
    }

    private fun hideGestureIndicator() {
        binding.gestureIndicator.visibility = View.GONE
    }

    private fun showSeekIndicator(seconds: Int) {
        binding.seekIndicator.visibility = View.VISIBLE
        binding.seekIndicator.text = "${if (seconds > 0) "+" else ""}$seconds sec"
        
        hideHandler.postDelayed({
            binding.seekIndicator.visibility = View.GONE
        }, 800)
    }

    private fun showSettingsMenu() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        
        view.findViewById<LinearLayout>(R.id.menuSpeed).setOnClickListener {
            dialog.dismiss()
            showSpeedDialog()
        }
        
        view.findViewById<LinearLayout>(R.id.menuAspectRatio).setOnClickListener {
            dialog.dismiss()
            showAspectRatioDialog()
        }
        
        view.findViewById<LinearLayout>(R.id.menuSubtitle).setOnClickListener {
            dialog.dismiss()
            showSubtitleDialog()
        }
        
        view.findViewById<LinearLayout>(R.id.menuAudioTrack).setOnClickListener {
            dialog.dismiss()
            showAudioTrackDialog()
        }
        
        view.findViewById<LinearLayout>(R.id.menuFilter).setOnClickListener {
            dialog.dismiss()
            showFilterDialog()
        }
        
        dialog.setContentView(view)
        dialog.show()
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf("0.25x", "0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "1.75x", "2.0x")
        val speedValues = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val currentIndex = speedValues.indexOf(playbackSpeed).takeIf { it >= 0 } ?: 3
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Playback Speed")
            .setSingleChoiceItems(speeds, currentIndex) { dialog, which ->
                playbackSpeed = speedValues[which]
                player?.setPlaybackSpeed(playbackSpeed)
                binding.btnSpeed.text = speeds[which].replace(" (Normal)", "")
                dialog.dismiss()
            }
            .show()
    }

    private fun showAspectRatioDialog() {
        val modes = AspectRatioMode.values()
        val names = modes.map { it.displayName }.toTypedArray()
        val currentIndex = modes.indexOf(currentAspectRatio)
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Aspect Ratio")
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                currentAspectRatio = modes[which]
                applyAspectRatio(currentAspectRatio)
                dialog.dismiss()
            }
            .show()
    }

    private fun cycleAspectRatio() {
        val modes = AspectRatioMode.values()
        val currentIndex = modes.indexOf(currentAspectRatio)
        currentAspectRatio = modes[(currentIndex + 1) % modes.size]
        applyAspectRatio(currentAspectRatio)
        Toast.makeText(this, currentAspectRatio.displayName, Toast.LENGTH_SHORT).show()
    }

    private fun applyAspectRatio(mode: AspectRatioMode) {
        binding.playerView.resizeMode = when (mode) {
            AspectRatioMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectRatioMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioMode.STRETCH -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioMode.RATIO_16_9 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            AspectRatioMode.RATIO_4_3 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            AspectRatioMode.RATIO_21_9 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
        }
    }

    private fun showSubtitleDialog() {
        val tracks = player?.currentTracks ?: return
        val textTracks = mutableListOf<Pair<String, Int>>()
        textTracks.add("Off" to -1)
        
        var trackIndex = 0
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = format.label ?: format.language ?: "Track ${trackIndex + 1}"
                    textTracks.add(label to trackIndex)
                    trackIndex++
                }
            }
        }
        
        if (textTracks.size == 1) {
            Toast.makeText(this, "No subtitles available", Toast.LENGTH_SHORT).show()
            return
        }
        
        val names = textTracks.map { it.first }.toTypedArray()
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Subtitles")
            .setItems(names) { dialog, which ->
                if (which == 0) {
                    // Disable subtitles
                    trackSelector.setParameters(
                        trackSelector.buildUponParameters()
                            .setRendererDisabled(C.TRACK_TYPE_TEXT, true)
                    )
                } else {
                    trackSelector.setParameters(
                        trackSelector.buildUponParameters()
                            .setRendererDisabled(C.TRACK_TYPE_TEXT, false)
                    )
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun showAudioTrackDialog() {
        val tracks = player?.currentTracks ?: return
        val audioTracks = mutableListOf<Pair<String, Tracks.Group>>()
        
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = buildString {
                        append(format.label ?: format.language ?: "Audio")
                        if (format.channelCount > 0) {
                            append(" (${format.channelCount}ch)")
                        }
                        if (format.sampleRate > 0) {
                            append(" ${format.sampleRate / 1000}kHz")
                        }
                    }
                    audioTracks.add(label to group)
                }
            }
        }
        
        if (audioTracks.isEmpty()) {
            Toast.makeText(this, "No audio tracks available", Toast.LENGTH_SHORT).show()
            return
        }
        
        val names = audioTracks.map { it.first }.toTypedArray()
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Audio Track")
            .setItems(names) { dialog, which ->
                val group = audioTracks[which].second
                trackSelector.setParameters(
                    trackSelector.buildUponParameters()
                        .setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, 0)
                        )
                )
                dialog.dismiss()
            }
            .show()
    }

    private fun showFilterDialog() {
        val filters = VideoFilter.values()
        val names = filters.map { it.displayName }.toTypedArray()
        val currentIndex = filters.indexOf(currentFilter)
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Video Filter")
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                currentFilter = filters[which]
                Toast.makeText(this, "Filter: ${currentFilter.displayName}", Toast.LENGTH_SHORT).show()
                // Note: Actual filter implementation requires custom shader/surface
                dialog.dismiss()
            }
            .show()
    }

    private fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                val aspectRatio = Rational(16, 9)
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .build()
                enterPictureInPictureMode(params)
            } else {
                Toast.makeText(this, "PiP not supported on this device", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "PiP requires Android 8.0+", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPipMode = isInPictureInPictureMode
        
        if (isInPictureInPictureMode) {
            // Hide all UI in PiP
            binding.controlsContainer.visibility = View.GONE
            binding.topBar.visibility = View.GONE
            binding.bottomBar.visibility = View.GONE
            binding.gestureOverlay.visibility = View.GONE
        } else {
            binding.gestureOverlay.visibility = View.VISIBLE
            showControls()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Auto-enter PiP when user presses home while video is playing
        if (player?.isPlaying == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPicture()
        }
    }

    // Progress updates
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            hideHandler.postDelayed(this, 1000)
        }
    }

    private fun startProgressUpdates() {
        hideHandler.post(progressRunnable)
    }

    private fun stopProgressUpdates() {
        hideHandler.removeCallbacks(progressRunnable)
    }

    private fun updateProgress() {
        player?.let {
            val position = it.currentPosition
            val duration = it.duration.takeIf { d -> d > 0 } ?: 0
            
            binding.currentTime.text = formatTime(position)
            binding.totalTime.text = formatTime(duration)
            
            if (duration > 0) {
                binding.seekBar.progress = (position * 100 / duration).toInt()
            }
        }
    }

    private fun updateDuration() {
        player?.let {
            binding.totalTime.text = formatTime(it.duration)
        }
    }

    private fun formatTime(millis: Long): String {
        val hours = millis / 3600000
        val minutes = (millis % 3600000) / 60000
        val seconds = (millis % 60000) / 1000
        
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        if (player == null) {
            initializePlayer()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        if (!isPipMode) {
            player?.playWhenReady = false
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isPipMode) {
            player?.playWhenReady = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isLocked) {
            Toast.makeText(this, "Unlock to go back", Toast.LENGTH_SHORT).show()
        } else {
            super.onBackPressed()
        }
    }

    enum class GestureType {
        NONE, BRIGHTNESS, VOLUME, SEEK
    }
}
