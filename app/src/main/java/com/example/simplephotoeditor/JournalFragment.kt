package com.example.simplephotoeditor

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.drawToBitmap
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class JournalFragment : Fragment(R.layout.fragment_journal) {

    private lateinit var canvasContainer: FrameLayout
    private lateinit var galleryAdapter: GalleryAdapter

    // 简单的功能菜单
    private val journalTools = listOf(
        EditorTool(1, "清空", android.R.drawable.ic_menu_delete),
        EditorTool(2, "背景", android.R.drawable.ic_menu_gallery)
    )

    @SuppressLint("ClickableViewAccessibility")
    // JournalFragment.kt

    override fun onResume() {
        super.onResume()
        // 每次滑过来，刷新相册，让你刚做好的图立刻出现
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            loadPhotosFromSystem()
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        canvasContainer = view.findViewById(R.id.canvasContainer)

        // 1. 沉浸式适配
        val topBar = view.findViewById<View>(R.id.journalTopBar)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        // 2. 初始化功能栏
        val toolsRecycler = view.findViewById<RecyclerView>(R.id.journalToolsRecycler)
        toolsRecycler.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        toolsRecycler.adapter = ToolsAdapter(journalTools) { tool ->
            when (tool.id) {
                1 -> { // 清空画布
                    canvasContainer.removeAllViews()
                    // 把占位文字加回来
                    val placeholder = android.widget.TextView(context).apply {
                        text = "Tap photos to add stickers"
                        setTextColor(0xFFDDDDDD.toInt())
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                        ).apply { gravity = android.view.Gravity.CENTER }
                    }
                    canvasContainer.addView(placeholder)
                }
                2 -> Toast.makeText(context, "背景功能待开发...", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. 初始化相册 (复用 GalleryAdapter)
        val galleryRecycler = view.findViewById<RecyclerView>(R.id.journalGalleryRecycler)
        galleryRecycler.layoutManager = GridLayoutManager(context, 4)
        galleryAdapter = GalleryAdapter { uri ->
            // === 核心功能：点击相册图片，添加到画布 ===
            addStickerToCanvas(uri)
        }
        galleryRecycler.adapter = galleryAdapter

        // 4. 加载照片
        checkPermissionAndLoadPhotos()

        // 5. 保存按钮逻辑
        view.findViewById<View>(R.id.btnSaveJournal).setOnClickListener {
            saveCanvasToGallery()
        }
    }

    // === A. 添加贴纸逻辑 ===
    private fun addStickerToCanvas(uri: Uri) {
        // 移除占位文字
        val placeholder = canvasContainer.findViewById<View>(R.id.tvPlaceholder)
        if (placeholder != null) {
            canvasContainer.removeView(placeholder)
        }

        // 创建一个新的 ImageView
        val sticker = ImageView(context)
        val size = 400 // 默认贴纸大小
        val params = FrameLayout.LayoutParams(size, size)
        params.gravity = android.view.Gravity.CENTER // 默认居中
        sticker.layoutParams = params

        // 加载图片
        Glide.with(this).load(uri).into(sticker)

        // 添加触摸监听 (移动 & 缩放)
        val touchListener = StickerTouchListener(sticker)
        sticker.setOnTouchListener(touchListener)

        // 添加到画布
        canvasContainer.addView(sticker)
    }

    // === B. 保存画布逻辑 (View -> Bitmap -> MediaStore) ===
    private fun saveCanvasToGallery() {
        // 1. 截图 (使用 Android KTX 扩展方法)
        val bitmap = try {
            // 创建一个和画布一样大的 Bitmap
            val b = Bitmap.createBitmap(canvasContainer.width, canvasContainer.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            // 把画布画上去
            canvasContainer.draw(c)
            b
        } catch (e: Exception) {
            null
        }

        if (bitmap != null) {
            saveBitmapToMediaStore(bitmap)
        } else {
            Toast.makeText(context, "保存失败：画布为空", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val filename = "JOURNAL_${System.currentTimeMillis()}.jpg"
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SimplePhotoEditor")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val resolver = requireContext().contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let {
                    val stream: OutputStream? = resolver.openOutputStream(it)
                    stream?.use { os ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, os)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(it, contentValues, null, null)
                    }
                    withContext(Dispatchers.Main) {
                        // Toast.makeText(context, "手帐保存成功！", Toast.LENGTH_SHORT).show()
                        // 刷新相册列表
                        loadPhotosFromSystem()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "保存出错", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // === C. 相册权限与加载 (与 EditorFragment 相同) ===
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) loadPhotosFromSystem()
    }

    private fun checkPermissionAndLoadPhotos() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            loadPhotosFromSystem()
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun loadPhotosFromSystem() {
        lifecycleScope.launch(Dispatchers.IO) {
            val photoList = mutableListOf<Uri>()
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            val cursor = context?.contentResolver?.query(collection, projection, null, null, sortOrder)
            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                var count = 0
                while (it.moveToNext() && count < 50) {
                    val id = it.getLong(idColumn)
                    photoList.add(ContentUris.withAppendedId(collection, id))
                    count++
                }
            }

            withContext(Dispatchers.Main) {
                if (photoList.isNotEmpty()) {
                    galleryAdapter.submitList(photoList)
                }
            }
        }
    }

    // === D. 贴纸手势处理逻辑 (内部类) ===
    // 负责处理单张图片的移动和缩放
    private inner class StickerTouchListener(private val view: View) : View.OnTouchListener {
        private var scaleFactor = 1.0f
        private var lastX = 0f
        private var lastY = 0f

        // 缩放检测器
        private val scaleDetector = ScaleGestureDetector(view.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = Math.max(0.5f, Math.min(scaleFactor, 3.0f)) // 限制缩放范围
                view.scaleX = scaleFactor
                view.scaleY = scaleFactor
                return true
            }
        })

        // 触摸事件处理
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            // 1. 让 ScaleDetector 处理缩放
            scaleDetector.onTouchEvent(event)

            // 2. 处理移动和冲突
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 手指按下时，告诉父容器(ViewPager)别拦截，我要自己动！
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    lastX = event.rawX
                    lastY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress) { // 如果不是在缩放，才进行移动
                        val dx = event.rawX - lastX
                        val dy = event.rawY - lastY
                        view.x += dx
                        view.y += dy
                        lastX = event.rawX
                        lastY = event.rawY
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }
    }
}