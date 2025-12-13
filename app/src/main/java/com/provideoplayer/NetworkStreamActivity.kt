package com.provideoplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.provideoplayer.databinding.ActivityNetworkStreamBinding

class NetworkStreamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNetworkStreamBinding
    
    // Sample streaming URLs for quick access
    private val sampleUrls = listOf(
        "https://sample-videos.com/video123/mp4/720/big_buck_bunny_720p_1mb.mp4" to "Big Buck Bunny (Sample)",
        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" to "Mux Test Stream (HLS)",
        "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8" to "Sintel (HLS)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNetworkStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupUI()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Network Stream"
        }
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }

    private fun setupUI() {
        // Play button
        binding.btnPlay.setOnClickListener {
            val url = binding.urlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                playNetworkStream(url, "Network Stream")
            } else {
                Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Clear button
        binding.btnClear.setOnClickListener {
            binding.urlInput.text?.clear()
        }
        
        // Paste button
        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text.toString()
                binding.urlInput.setText(text)
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Sample streams button
        binding.btnSampleStreams.setOnClickListener {
            showSampleStreamsDialog()
        }
        
        // Protocol chips
        binding.chipHttp.setOnClickListener {
            if (!binding.urlInput.text.toString().startsWith("http")) {
                binding.urlInput.setText("http://")
                binding.urlInput.setSelection(binding.urlInput.text?.length ?: 0)
            }
        }
        
        binding.chipHttps.setOnClickListener {
            if (!binding.urlInput.text.toString().startsWith("https")) {
                binding.urlInput.setText("https://")
                binding.urlInput.setSelection(binding.urlInput.text?.length ?: 0)
            }
        }
        
        binding.chipRtsp.setOnClickListener {
            if (!binding.urlInput.text.toString().startsWith("rtsp")) {
                binding.urlInput.setText("rtsp://")
                binding.urlInput.setSelection(binding.urlInput.text?.length ?: 0)
            }
        }
        
        binding.chipRtmp.setOnClickListener {
            if (!binding.urlInput.text.toString().startsWith("rtmp")) {
                binding.urlInput.setText("rtmp://")
                binding.urlInput.setSelection(binding.urlInput.text?.length ?: 0)
            }
        }
    }

    private fun showSampleStreamsDialog() {
        val names = sampleUrls.map { it.second }.toTypedArray()
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Sample Streams")
            .setItems(names) { dialog, which ->
                val (url, name) = sampleUrls[which]
                binding.urlInput.setText(url)
                dialog.dismiss()
            }
            .show()
    }

    private fun playNetworkStream(url: String, title: String) {
        // Validate URL
        if (!isValidUrl(url)) {
            Toast.makeText(this, "Invalid URL format", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URI, url)
            putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, title)
            putExtra(PlayerActivity.EXTRA_IS_NETWORK_STREAM, true)
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, arrayListOf(url))
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_TITLES, arrayListOf(title))
        }
        startActivity(intent)
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            scheme in listOf("http", "https", "rtsp", "rtmp", "mms", "mmsh", "mmst")
        } catch (e: Exception) {
            false
        }
    }
}
