package com.provideoplayer.utils

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.provideoplayer.model.FolderItem
import com.provideoplayer.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans device storage for video files using MediaStore API
 */
object VideoScanner {
    
    /**
     * Get all videos from device storage
     */
    suspend fun getAllVideos(context: Context): List<VideoItem> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<VideoItem>()
        
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.RESOLUTION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.MIME_TYPE
        )
        
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        
        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val resolutionColumn = cursor.getColumnIndex(MediaStore.Video.Media.RESOLUTION)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val folderNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val folderIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "Unknown"
                val data = cursor.getString(dataColumn) ?: ""
                val duration = cursor.getLong(durationColumn)
                val size = cursor.getLong(sizeColumn)
                val resolution = if (resolutionColumn >= 0) cursor.getString(resolutionColumn) ?: "" else ""
                val dateAdded = cursor.getLong(dateColumn)
                val folderName = cursor.getString(folderNameColumn) ?: "Internal Storage"
                val folderId = cursor.getLong(folderIdColumn)
                val mimeType = cursor.getString(mimeColumn) ?: "video/*"
                
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                
                // Only add videos with valid duration (filter out corrupted files)
                if (duration > 0) {
                    videos.add(
                        VideoItem(
                            id = id,
                            title = name,
                            path = data,
                            uri = contentUri,
                            duration = duration,
                            size = size,
                            resolution = resolution,
                            dateAdded = dateAdded,
                            folderName = folderName,
                            folderId = folderId,
                            mimeType = mimeType
                        )
                    )
                }
            }
        }
        
        videos
    }
    
    /**
     * Get all folders containing videos
     */
    suspend fun getAllFolders(context: Context): List<FolderItem> = withContext(Dispatchers.IO) {
        val folderMap = mutableMapOf<Long, FolderItem>()
        
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        
        val projection = arrayOf(
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATA
        )
        
        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val folderIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val folderNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            
            while (cursor.moveToNext()) {
                val folderId = cursor.getLong(folderIdColumn)
                val folderName = cursor.getString(folderNameColumn) ?: "Internal Storage"
                val path = cursor.getString(dataColumn) ?: ""
                val folderPath = path.substringBeforeLast("/")
                
                if (folderMap.containsKey(folderId)) {
                    val existing = folderMap[folderId]!!
                    folderMap[folderId] = existing.copy(videoCount = existing.videoCount + 1)
                } else {
                    folderMap[folderId] = FolderItem(
                        id = folderId,
                        name = folderName,
                        path = folderPath,
                        videoCount = 1
                    )
                }
            }
        }
        
        folderMap.values.toList().sortedByDescending { it.videoCount }
    }
    
    /**
     * Get videos from a specific folder
     */
    suspend fun getVideosInFolder(context: Context, folderId: Long): List<VideoItem> = withContext(Dispatchers.IO) {
        getAllVideos(context).filter { it.folderId == folderId }
    }
    
    /**
     * Search videos by name
     */
    suspend fun searchVideos(context: Context, query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        getAllVideos(context).filter { 
            it.title.contains(query, ignoreCase = true) ||
            it.folderName.contains(query, ignoreCase = true)
        }
    }
}
