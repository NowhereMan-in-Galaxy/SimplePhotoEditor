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
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import kotlin.math.atan2

// 基础 UI 组件
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar


// 图形与颜色处理

import android.graphics.drawable.GradientDrawable

// 数据结构
import java.util.ArrayDeque

// 底部弹窗 (Material Design)
import com.google.android.material.bottomsheet.BottomSheetDialog

class JournalFragment : Fragment(R.layout.fragment_journal) {

    private lateinit var canvasContainer: FrameLayout
    private lateinit var galleryAdapter: GalleryAdapter

    // 简单的功能菜单
    private val journalTools = listOf(
        EditorTool(1, "清空", R.drawable.ic_empty),
        EditorTool(2, "背景", R.drawable.ic_background),
        EditorTool(3, "文字", R.drawable.ic_text) // 添加文字工具
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onResume() {
        super.onResume()
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
                        id = R.id.tvPlaceholder // Assign the ID here
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
                3 -> { // 文字工具
                    showTextInputDialog()
                }
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
        view.findViewById<View>(R.id.btnSave).setOnClickListener {
            saveCanvasToGallery()
        }
    }

    // 显示文字输入对话框
    private fun showTextInputDialog() {
        // 1. 加载我们画好的布局
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_text, null)
        val inputEditText = dialogView.findViewById<EditText>(R.id.etInput)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)
        val btnConfirm = dialogView.findViewById<View>(R.id.btnConfirm)

        // 2. 创建 Dialog
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        // 3. 【关键】设置背景透明，否则圆角会被系统的白色方框挡住
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // 4. 按钮点击事件
        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            val text = inputEditText.text.toString().trim()
            if (text.isNotEmpty()) {
                addTextToCanvas(text)
                dialog.dismiss()
            } else {
                Toast.makeText(context, "写点什么吧...", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()

        // 自动弹出键盘 (提升体验)
        inputEditText.requestFocus()
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

        // 添加触摸监听 (移动 & 缩放 & 旋转)
        val touchListener = StickerTouchListener(sticker)
        sticker.setOnTouchListener(touchListener)

        // 添加到画布
        canvasContainer.addView(sticker)
    }

    // 添加文字到画布
    private fun addTextToCanvas(text: String) {
        // 移除占位文字
        canvasContainer.findViewById<View>(R.id.tvPlaceholder)?.let {
            canvasContainer.removeView(it)
        }

        val textView = TextView(requireContext()).apply {
            this.text = text
            textSize = 28f
            setTextColor(Color.BLACK) // ✨ 改成白色，更百搭

            // ✨ 1. 增加文字阴影，保证在白色图片上也能看清
            // (半径, X偏移, Y偏移, 颜色)
            //setShadowLayer(12f, 0f, 4f, Color.parseColor("#80000000"))

            // ✨ 2. 换个高级字体 (衬线体 Serif，或者你有字体文件可以用 createFromAsset)
            typeface = Typeface.SERIF
            // 或者设为粗斜体增加设计感:
            // typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }

        // 绑定手势
        val touchListener = StickerTouchListener(textView)
        textView.setOnTouchListener(touchListener)

        canvasContainer.addView(textView)
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
    // 负责处理单张图片的移动、缩放和旋转
// ===============================================================
    // ✨ 升级版监听器：保留了你的旋转/缩放逻辑，新增了点击功能
    // ===============================================================
    private inner class StickerTouchListener(private val view: View) : View.OnTouchListener {
        private var scaleFactor = 1.0f
        private var lastX = 0f
        private var lastY = 0f
        private var initialRotation = 0f
        private var initialPointerAngle = 0.0

        // 1. ✨ 新增：点击识别器 (专门负责弹出样式框)
        private val gestureDetector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true // 必须返回 true，否则点下去没反应
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // 如果是文字，就弹出样式修改框
                if (view is TextView) {
                    showTextStyleDialog(view)
                }
                return true
            }
        })

        // 2. 原有：缩放检测器 (保持不变)
        private val scaleDetector = ScaleGestureDetector(view.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = Math.max(0.5f, Math.min(scaleFactor, 3.0f))
                view.scaleX = scaleFactor
                view.scaleY = scaleFactor
                return true
            }
        })

        // 3. 触摸主逻辑
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            // ✨ 第一步：先让点击识别器看看是不是点击
            gestureDetector.onTouchEvent(event)

            // 第二步：处理缩放
            scaleDetector.onTouchEvent(event)

            // 第三步：处理移动和旋转 (你的原有逻辑)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    lastX = event.rawX
                    lastY = event.rawY
                    scaleFactor = v.scaleX
                    initialRotation = v.rotation
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        // 计算两个手指按下时的初始角度差
                        initialPointerAngle = getAngle(event) - v.rotation
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress) {
                        if (event.pointerCount == 1) {
                            // 单指移动
                            val dx = event.rawX - lastX
                            val dy = event.rawY - lastY
                            view.x += dx
                            view.y += dy
                            lastX = event.rawX
                            lastY = event.rawY
                        } else if (event.pointerCount == 2) {
                            // 双指旋转
                            val currentAngle = getAngle(event)
                            view.rotation = (currentAngle - initialPointerAngle).toFloat()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    initialPointerAngle = 0.0
                }
            }
            return true
        }

        // 计算角度 (你的原有逻辑，稍微补充了 Math.atan2 防止报错)
        private fun getAngle(event: MotionEvent): Double {
            val x = event.getX(0) - event.getX(1)
            val y = event.getY(0) - event.getY(1)
            // 确保使用 Math.atan2 或者 kotlin.math.atan2
            return Math.toDegrees(Math.atan2(y.toDouble(), x.toDouble()))
        }
    }
    // === 1. 在 JournalFragment 类中定义数据 ===

    // 预设高级色板 (32色，涵盖黑白、莫兰迪、复古)
    private val presetColors = listOf(
        // Row 2: 基础与灰调
        "#000000", "#333333", "#666666", "#999999", "#CCCCCC", "#DDDDDD", "#F5F5F5", "#FFFFFF",
        // Row 3: 莫兰迪/复古暖色
        "#D4A5A5", "#FFDAC1", "#FF9AA2", "#E2F0CB", "#B5EAD7", "#C7CEEA", "#E0BBE4", "#957DAD",
        // Row 4: 深邃/经典色
        "#8E44AD", "#2980B9", "#27AE60", "#16A085", "#F39C12", "#D35400", "#C0392B", "#7F8C8D",
        // 补充
        "#2C3E50", "#34495E", "#E74C3C", "#ECF0F1", "#95A5A6", "#7F8C8D", "#3498DB", "#1ABC9C"
    )

    // 记录最近使用的颜色 (默认存几个基础色)
    private val recentColors = ArrayDeque<String>(listOf("#000000", "#D35400", "#2980B9", "#FFFFFF"))

    // 字体列表 (Name, Typeface)
    private val fontStyles = listOf(
        Pair("默认", Typeface.DEFAULT),
        Pair("粗黑", Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)),
        Pair("衬线", Typeface.SERIF),
        Pair("粗衬线", Typeface.create(Typeface.SERIF, Typeface.BOLD)),
        Pair("斜体", Typeface.create(Typeface.SERIF, Typeface.ITALIC)),
        Pair("等宽", Typeface.MONOSPACE),
        Pair("极细", Typeface.create("sans-serif-light", Typeface.NORMAL)),
        Pair("手写", Typeface.create("cursive", Typeface.NORMAL)),
        Pair("标题", Typeface.create("sans-serif-condensed", Typeface.BOLD))
    )

    // === 2. 新的显示方法 ===
// === 2. 修改后的显示方法 ===
    private fun showTextStyleDialog(targetView: TextView) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_text_style, null)
        dialog.setContentView(view)

        // 关键：设置背景透明，否则圆角会被挡住
        (view.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)

        val gridRecent = view.findViewById<GridLayout>(R.id.gridRecentColors)
        val gridPreset = view.findViewById<GridLayout>(R.id.gridPresetColors)
        val containerFonts = view.findViewById<LinearLayout>(R.id.containerFonts)

        // HSV 组件
        val viewPreview = view.findViewById<View>(R.id.viewColorPreview)
        val seekH = view.findViewById<SeekBar>(R.id.seekBarHue)
        val seekS = view.findViewById<SeekBar>(R.id.seekBarSat)
        val seekV = view.findViewById<SeekBar>(R.id.seekBarVal)
        val btnConfirm = view.findViewById<View>(R.id.btnConfirmColor)

        // --- A. 刷新颜色的辅助函数 ---
        fun refreshColorGrids() {
            // 1. 最近使用 (取前10个)
            gridRecent.removeAllViews()
            recentColors.take(12).forEach { colorHex ->
                addColorCircleToGrid(gridRecent, colorHex, targetView) { }
            }

            // 2. 预设色板
            gridPreset.removeAllViews()
            presetColors.forEach { colorHex ->
                addColorCircleToGrid(gridPreset, colorHex, targetView) {
                    if (recentColors.contains(colorHex)) recentColors.remove(colorHex)
                    recentColors.addFirst(colorHex)
                    refreshColorGrids()
                }
            }
        }

        refreshColorGrids()

        // --- B. 填充字体 (缩小版) ---
        containerFonts.removeAllViews()
        fontStyles.forEach { (name, typeface) ->
            val tvFont = android.widget.TextView(context).apply {
                text = "Aa\n$name"
                this.typeface = typeface
                textSize = 12f // 字体变小
                setTextColor(Color.parseColor("#333333"))
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#F5F5F5"))
                    cornerRadius = 12f
                }
                // 尺寸改小：120x120
                layoutParams = LinearLayout.LayoutParams(120, 120).apply { marginEnd = 16 }

                setOnClickListener {
                    targetView.typeface = typeface
                }
            }
            containerFonts.addView(tvFont)
        }

        // --- C. HSV 滑条逻辑 ---
        val currentHsv = FloatArray(3)
        Color.colorToHSV(targetView.currentTextColor, currentHsv)

        seekH.progress = currentHsv[0].toInt()
        seekS.progress = (currentHsv[1] * 100).toInt()
        seekV.progress = (currentHsv[2] * 100).toInt()
        viewPreview.setBackgroundColor(targetView.currentTextColor)

        val seekBarListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentHsv[0] = seekH.progress.toFloat()
                    currentHsv[1] = seekS.progress / 100f
                    currentHsv[2] = seekV.progress / 100f

                    val color = Color.HSVToColor(currentHsv)
                    // 只改变文字和预览条，不存入最近使用
                    targetView.setTextColor(color)
                    viewPreview.setBackgroundColor(color)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        seekH.setOnSeekBarChangeListener(seekBarListener)
        seekS.setOnSeekBarChangeListener(seekBarListener)
        seekV.setOnSeekBarChangeListener(seekBarListener)

        // --- D. 确认按钮逻辑 (存入最近使用) ---
        btnConfirm.setOnClickListener {
            val color = Color.HSVToColor(currentHsv)
            val hex = String.format("#%06X", (0xFFFFFF and color))

            if (recentColors.contains(hex)) recentColors.remove(hex)
            recentColors.addFirst(hex)
            refreshColorGrids()

            Toast.makeText(context, "颜色已保存", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    // 辅助：更小巧精致的圆点
    private fun addColorCircleToGrid(grid: GridLayout, colorHex: String, targetView: TextView, onClick: () -> Unit) {
        // 尺寸改为 24dp (约 70px)
        val size = 70

        val view = View(context).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(6, 6, 6, 6) // 间距紧凑一点
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor(colorHex))
                setStroke(1, Color.parseColor("#DDDDDD"))
            }
            setOnClickListener {
                targetView.setTextColor(Color.parseColor(colorHex))
                onClick()
            }
        }
        grid.addView(view)
    }

    // 辅助：往 Grid 里加一个小圆点
}
