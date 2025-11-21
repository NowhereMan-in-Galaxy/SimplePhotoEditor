package com.example.simplephotoeditor

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

// 这是一个专门用来展示照片网格的适配器
class GalleryAdapter(
    private val onPhotoClick: (Uri) -> Unit // 当点击某张照片时的回调函数
) : RecyclerView.Adapter<GalleryAdapter.PhotoViewHolder>() {

    // 暂时用一个空列表，等会我们从 MediaStore 读到数据后填进去
    private var photoList: List<Uri> = emptyList()

    fun submitList(uris: List<Uri>) {
        photoList = uris
        notifyDataSetChanged() // 告诉界面：数据变了，刷新一下！
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        // 加载刚才写的 item_gallery_photo.xml
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_photo, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val uri = photoList[position]
        // 使用 Glide 加载缩略图
        Glide.with(holder.imageView)
            .load(uri)
            .centerCrop()
            .into(holder.imageView)

        // 设置点击事件
        holder.imageView.setOnClickListener {
            onPhotoClick(uri)
        }
    }

    override fun getItemCount(): Int = photoList.size

    class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.thumbnailImage)
    }
}