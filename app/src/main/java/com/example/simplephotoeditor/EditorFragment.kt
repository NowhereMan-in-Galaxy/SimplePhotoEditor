package com.example.simplephotoeditor

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class EditorFragment : Fragment(R.layout.fragment_editor) {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: PhotoRenderer
    private lateinit var toolsAdapter: ToolsAdapter
    private lateinit var galleryAdapter: GalleryAdapter

    // === 手势检测器 ===
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    // 当前变换状态
    private var mScaleFactor = 1.0f
    private var mTransX = 0f
    private var mTransY = 0f

    // 菜单 ID 定义
    companion object {
        const val TOOL_MASK = 1
        const val TOOL_CLOUD = 2
        const val TOOL_AI = 3

        const val ACTION_BACK = 99
        const val SHAPE_CIRCLE = 101
        const val SHAPE_SQUARE = 102
        const val SHAPE_RESET = 100
    }

    // 菜单数据
    private val mainTools = listOf(
        EditorTool(TOOL_MASK, "蒙版", R.drawable.ic_mask),
        EditorTool(TOOL_CLOUD, "色彩云", R.drawable.ic_cloud),
        EditorTool(TOOL_AI, "AI识别", R.drawable.ic_detect)
    )

    private val maskTools = listOf(
        EditorTool(ACTION_BACK, "返回", android.R.drawable.ic_menu_revert),
        EditorTool(SHAPE_RESET, "原图", android.R.drawable.ic_menu_gallery),
        EditorTool(SHAPE_CIRCLE, "圆形", android.R.drawable.radiobutton_off_background),
        EditorTool(SHAPE_SQUARE, "方形", android.R.drawable.ic_menu_crop)
    )

    @SuppressLint("ClickableViewAccessibility") // 忽略触摸覆盖警告
    override fun onResume() {
        super.onResume()
        // 每次回到这个界面，都默默刷新一下相册列表
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        // 只在有权限的时候刷新，且不请求权限（避免弹窗打扰）
        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            // autoSelectFirst = false (关键！只刷新列表，别动我的画布)
            loadPhotosFromSystem(false)
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. 沉浸式适配
        val topToolbar = view.findViewById<View>(R.id.topToolbar)
        ViewCompat.setOnApplyWindowInsetsListener(topToolbar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        // 2. 初始化 OpenGL
        glSurfaceView = view.findViewById(R.id.glCanvas)
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        renderer = PhotoRenderer(glSurfaceView)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        // === 3. 初始化手势监听 ===
        initTouchListeners()
        // 将触摸事件传给检测器
        glSurfaceView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 手指按下的一瞬间，告诉父容器(ViewPager2)：
                    // "不要拦截我的事件！这一系列动作（直到手指抬起）都归我管！"
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 手指抬起或取消时，允许父容器恢复拦截（恢复左右滑页功能）
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true // 消费事件
        }

        // 4. 初始化工具栏
        val toolsRecycler = view.findViewById<RecyclerView>(R.id.toolsRecyclerView)
        toolsRecycler.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        toolsAdapter = ToolsAdapter(mainTools) { tool -> handleToolClick(tool.id) }
        toolsRecycler.adapter = toolsAdapter

        // 5. 初始化图库
        val galleryRecycler = view.findViewById<RecyclerView>(R.id.galleryRecyclerView)
        galleryRecycler.layoutManager = GridLayoutManager(context, 4)
        galleryAdapter = GalleryAdapter { uri -> displayImage(uri) }
        galleryRecycler.adapter = galleryAdapter

        // 6. 权限与图片加载
        checkPermissionAndLoadPhotos()

        // 7. 保存按钮逻辑
        view.findViewById<View>(R.id.btnSave).setOnClickListener {
            saveImageToGallery()
        }
    }

    // === 核心逻辑：手势处理 ===
    private fun initTouchListeners() {
        // 双指缩放
        scaleDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                mScaleFactor *= detector.scaleFactor
                // 限制缩放范围 (0.5倍 到 5倍)
                mScaleFactor = Math.max(0.5f, Math.min(mScaleFactor, 5.0f))
                updateRenderer()
                return true
            }
        })

        // 单指拖动
        gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                // 注意：OpenGL坐标系和屏幕坐标系比例不同，简单除以 500 只是一个经验值，
                // 严谨做法是根据 View 宽高换算，但这里为了手感顺滑直接调参即可。
                mTransX -= distanceX / 500f
                mTransY -= distanceY / 500f // Y轴方向相反
                updateRenderer()
                return true
            }

            // 双击复位
            override fun onDoubleTap(e: MotionEvent): Boolean {
                mScaleFactor = 1.0f
                mTransX = 0f
                mTransY = 0f
                updateRenderer()
                return true
            }
        })
    }

    private fun updateRenderer() {
        glSurfaceView.queueEvent {
            renderer.updateTransform(mScaleFactor, mTransX, mTransY)
        }
    }

    // === 核心逻辑：保存图片到相册 ===
    private fun saveImageToGallery() {
        //Toast.makeText(context, "正在保存...", Toast.LENGTH_SHORT).show()

        // 1. 请求渲染器截图
        glSurfaceView.queueEvent {
            renderer.saveFrame { bitmap ->
                // 回调已经在主线程
                saveBitmapToMediaStore(bitmap)
            }
        }
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val filename = "IMG_${System.currentTimeMillis()}.png"
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    // 存到 Pictures/SimplePhotoEditor 文件夹
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
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(it, contentValues, null, null)
                    }

                    withContext(Dispatchers.Main) {
                        //Toast.makeText(context, "保存成功！", Toast.LENGTH_SHORT).show()
                        loadPhotosFromSystem(autoSelectFirst = false)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // === 下面是之前的工具栏逻辑，保持不变 ===
    private fun handleToolClick(id: Int) {
        when (id) {
            TOOL_MASK -> toolsAdapter.updateTools(maskTools)
            TOOL_CLOUD -> Toast.makeText(context, "启动粒子云...", Toast.LENGTH_SHORT).show()
            ACTION_BACK -> toolsAdapter.updateTools(mainTools)
            SHAPE_CIRCLE -> glSurfaceView.queueEvent { renderer.setShape(PhotoRenderer.SHAPE_CIRCLE) }
            SHAPE_SQUARE -> glSurfaceView.queueEvent { renderer.setShape(PhotoRenderer.SHAPE_SQUARE) }
            SHAPE_RESET -> glSurfaceView.queueEvent { renderer.setShape(PhotoRenderer.SHAPE_NONE) }
        }
    }

    private fun displayImage(uri: Uri) {
        // 重置变换状态
        mScaleFactor = 1.0f
        mTransX = 0f
        mTransY = 0f

        Glide.with(this).asBitmap().load(uri).into(object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                glSurfaceView.queueEvent { renderer.setImage(resource) }
            }
            override fun onLoadCleared(placeholder: Drawable?) {}
        })
    }

    // === 权限相关保持不变 ===
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

    private fun loadPhotosFromSystem(autoSelectFirst: Boolean = true) {
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

                    // 关键修改：只有在需要的时候才自动选第一张
                    if (autoSelectFirst) {
                        displayImage(photoList[0])
                    }
                }
            }
        }
    }
}