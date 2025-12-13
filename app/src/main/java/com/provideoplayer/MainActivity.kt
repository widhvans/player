package com.provideoplayer

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.provideoplayer.adapter.FolderAdapter
import com.provideoplayer.adapter.VideoAdapter
import com.provideoplayer.databinding.ActivityMainBinding
import com.provideoplayer.model.FolderItem
import com.provideoplayer.model.VideoItem
import com.provideoplayer.utils.PermissionManager
import com.provideoplayer.utils.VideoScanner
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var folderAdapter: FolderAdapter
    
    private var allVideos: List<VideoItem> = emptyList()
    private var allFolders: List<FolderItem> = emptyList()
    private var currentFolderId: Long? = null
    private var isShowingFolders = true
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupRecyclerView()
        setupSwipeRefresh()
        setupFab()
        
        checkPermissionAndLoadVideos()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
    }

    private fun setupRecyclerView() {
        // Video adapter
        videoAdapter = VideoAdapter(
            onVideoClick = { video, position ->
                openPlayer(video, position)
            },
            onVideoLongClick = { video ->
                showVideoInfo(video)
                true
            }
        )
        
        // Folder adapter
        folderAdapter = FolderAdapter { folder ->
            openFolder(folder)
        }
        
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = folderAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(
            R.color.purple_500,
            R.color.purple_700,
            R.color.teal_200
        )
        binding.swipeRefresh.setOnRefreshListener {
            loadVideos()
        }
    }

    private fun setupFab() {
        binding.fabNetworkStream.setOnClickListener {
            openNetworkStreamDialog()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        
        // Setup search
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = "Search videos..."
        
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let { searchVideos(it) }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText ?: ""
                if (searchQuery.isEmpty()) {
                    if (currentFolderId != null) {
                        showVideosInFolder(currentFolderId!!)
                    } else {
                        showFolders()
                    }
                } else {
                    searchVideos(searchQuery)
                }
                return true
            }
        })
        
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.action_sort_name -> {
                sortVideos(SortType.NAME)
                true
            }
            R.id.action_sort_date -> {
                sortVideos(SortType.DATE)
                true
            }
            R.id.action_sort_size -> {
                sortVideos(SortType.SIZE)
                true
            }
            R.id.action_sort_duration -> {
                sortVideos(SortType.DURATION)
                true
            }
            R.id.action_view_grid -> {
                setLayoutMode(true)
                true
            }
            R.id.action_view_list -> {
                setLayoutMode(false)
                true
            }
            R.id.action_settings -> {
                openSettings()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            searchQuery.isNotEmpty() -> {
                // Clear search
                searchQuery = ""
                invalidateOptionsMenu()
                if (currentFolderId != null) {
                    showVideosInFolder(currentFolderId!!)
                } else {
                    showFolders()
                }
            }
            currentFolderId != null -> {
                // Go back to folder view
                currentFolderId = null
                showFolders()
                supportActionBar?.apply {
                    setDisplayHomeAsUpEnabled(false)
                    title = getString(R.string.app_name)
                }
            }
            else -> {
                super.onBackPressed()
            }
        }
    }

    private fun checkPermissionAndLoadVideos() {
        if (PermissionManager.hasStoragePermission(this)) {
            loadVideos()
        } else {
            showPermissionUI()
        }
    }

    private fun showPermissionUI() {
        binding.permissionLayout.visibility = View.VISIBLE
        binding.contentLayout.visibility = View.GONE
        
        binding.btnGrantPermission.setOnClickListener {
            PermissionManager.requestStoragePermission(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PermissionManager.STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                binding.permissionLayout.visibility = View.GONE
                binding.contentLayout.visibility = View.VISIBLE
                loadVideos()
            } else {
                if (PermissionManager.isPermissionPermanentlyDenied(this)) {
                    showSettingsDialog()
                } else {
                    Toast.makeText(this, "Permission required to access videos", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Permission Required")
            .setMessage("Storage permission is required to browse videos. Please enable it in app settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun loadVideos() {
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        
        lifecycleScope.launch {
            try {
                allVideos = VideoScanner.getAllVideos(this@MainActivity)
                allFolders = VideoScanner.getAllFolders(this@MainActivity)
                
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                
                if (allFolders.isEmpty()) {
                    binding.emptyView.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.emptyView.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    showFolders()
                }
            } catch (e: Exception) {
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Error loading videos: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showFolders() {
        isShowingFolders = true
        binding.recyclerView.adapter = folderAdapter
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        folderAdapter.submitList(allFolders)
    }

    private fun openFolder(folder: FolderItem) {
        currentFolderId = folder.id
        showVideosInFolder(folder.id)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = folder.name
        }
    }

    private fun showVideosInFolder(folderId: Long) {
        isShowingFolders = false
        binding.recyclerView.adapter = videoAdapter
        binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        
        val folderVideos = allVideos.filter { it.folderId == folderId }
        videoAdapter.submitList(folderVideos)
        
        if (folderVideos.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
        } else {
            binding.emptyView.visibility = View.GONE
        }
    }

    private fun searchVideos(query: String) {
        isShowingFolders = false
        binding.recyclerView.adapter = videoAdapter
        binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        
        val results = allVideos.filter { 
            it.title.contains(query, ignoreCase = true) ||
            it.folderName.contains(query, ignoreCase = true)
        }
        videoAdapter.submitList(results)
        
        if (results.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyText.text = "No videos found for \"$query\""
        } else {
            binding.emptyView.visibility = View.GONE
        }
    }

    private fun sortVideos(sortType: SortType) {
        val currentList = if (isShowingFolders) {
            return // Can't sort folders view
        } else {
            videoAdapter.currentList.toList()
        }
        
        val sorted = when (sortType) {
            SortType.NAME -> currentList.sortedBy { it.title.lowercase() }
            SortType.DATE -> currentList.sortedByDescending { it.dateAdded }
            SortType.SIZE -> currentList.sortedByDescending { it.size }
            SortType.DURATION -> currentList.sortedByDescending { it.duration }
        }
        
        videoAdapter.submitList(sorted)
        Toast.makeText(this, "Sorted by ${sortType.name.lowercase()}", Toast.LENGTH_SHORT).show()
    }

    private fun setLayoutMode(isGrid: Boolean) {
        binding.recyclerView.layoutManager = if (isGrid) {
            GridLayoutManager(this, 2)
        } else {
            LinearLayoutManager(this)
        }
    }

    private fun openPlayer(video: VideoItem, position: Int) {
        val playlist = if (isShowingFolders) {
            allVideos
        } else {
            videoAdapter.currentList
        }
        
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URI, video.uri.toString())
            putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, video.title)
            putExtra(PlayerActivity.EXTRA_VIDEO_POSITION, position)
            putStringArrayListExtra(
                PlayerActivity.EXTRA_PLAYLIST,
                ArrayList(playlist.map { it.uri.toString() })
            )
            putStringArrayListExtra(
                PlayerActivity.EXTRA_PLAYLIST_TITLES,
                ArrayList(playlist.map { it.title })
            )
        }
        startActivity(intent)
    }

    private fun showVideoInfo(video: VideoItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(video.title)
            .setMessage("""
                📁 Folder: ${video.folderName}
                ⏱️ Duration: ${video.getFormattedDuration()}
                📊 Size: ${video.getFormattedSize()}
                🎬 Resolution: ${video.resolution.ifEmpty { "Unknown" }}
                📂 Path: ${video.path}
            """.trimIndent())
            .setPositiveButton("Play") { _, _ ->
                openPlayer(video, 0)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openNetworkStreamDialog() {
        val intent = Intent(this, NetworkStreamActivity::class.java)
        startActivity(intent)
    }

    private fun openSettings() {
        Toast.makeText(this, "Settings coming soon", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        if (PermissionManager.hasStoragePermission(this) && allVideos.isEmpty()) {
            loadVideos()
        }
    }

    enum class SortType {
        NAME, DATE, SIZE, DURATION
    }
}
