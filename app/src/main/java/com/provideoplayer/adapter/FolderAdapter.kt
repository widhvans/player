package com.provideoplayer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.provideoplayer.R
import com.provideoplayer.model.FolderItem

/**
 * RecyclerView adapter for displaying folder list
 */
class FolderAdapter(
    private val onFolderClick: (FolderItem) -> Unit
) : ListAdapter<FolderItem, FolderAdapter.FolderViewHolder>(FolderDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_folder, parent, false)
        return FolderViewHolder(view)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.folderIcon)
        private val name: TextView = itemView.findViewById(R.id.folderName)
        private val count: TextView = itemView.findViewById(R.id.videoCount)

        fun bind(folder: FolderItem) {
            name.text = folder.name
            count.text = "${folder.videoCount} videos"
            icon.setImageResource(R.drawable.ic_folder)

            itemView.setOnClickListener {
                onFolderClick(folder)
            }
        }
    }

    class FolderDiffCallback : DiffUtil.ItemCallback<FolderItem>() {
        override fun areItemsTheSame(oldItem: FolderItem, newItem: FolderItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: FolderItem, newItem: FolderItem): Boolean {
            return oldItem == newItem
        }
    }
}
