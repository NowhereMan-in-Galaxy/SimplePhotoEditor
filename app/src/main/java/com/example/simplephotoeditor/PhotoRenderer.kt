package com.example.simplephotoeditor

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

class PhotoRenderer(private val glView: GLSurfaceView) : GLSurfaceView.Renderer {

    companion object {
        const val SHAPE_NONE = 0
        const val SHAPE_CIRCLE = 1
        const val SHAPE_SQUARE = 2
    }

    private var currentShapeType = SHAPE_NONE

    // 手势参数 (由 EditorFragment 控制)
    @Volatile private var userScale = 1f
    @Volatile private var userTransX = 0f
    @Volatile private var userTransY = 0f

    // 宽高比参数
    private var imgRatio = 1f
    private var viewRatio = 1f

    // 截图回调
    private var saveCallback: ((Bitmap) -> Unit)? = null
    private var viewWidth = 0
    private var viewHeight = 0

    // === 顶点数据 (标准全屏 Quad) ===
    private val vertexData = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    // 纹理坐标 (Android y轴向下，OpenGL y轴向上，这里预先翻转一下适配)
    private val textureData = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

    private val vertexBuffer = createFloatBuffer(vertexData)
    private val textureBuffer = createFloatBuffer(textureData)

    private var programId = 0
    private var textureId = 0
    private var currentBitmap: Bitmap? = null

    // === Vertex Shader (顶点着色器) ===
    // 重点：我们在这里只计算坐标，不移动物体位置(gl_Position)
    private val vertexShaderCode = """
        attribute vec4 aPosition; // 顶点位置 (-1..1)
        attribute vec2 aTexCoord; // 原始纹理坐标 (0..1)
        
        varying vec2 vTexCoord;   // 传给片元：用于采样图片 (会动)
        varying vec2 vMaskCoord;  // 传给片元：用于画蒙版 (不动)
        
        // 缩放与移动参数
        uniform float uScaleX;
        uniform float uScaleY;
        uniform float uUserScale;
        uniform float uUserTransX;
        uniform float uUserTransY;

        void main() {
            // 1. 蒙版坐标 = 原始屏幕坐标 (保证蒙版永远在屏幕中心)
            gl_Position = aPosition;
            vMaskCoord = aTexCoord;
            
            // 2. 图片坐标计算 (Texture Coordinate Transformation)
            // 公式解析：
            // (aTexCoord - 0.5) : 移到中心
            // * vec2(...)       : 修正长宽比 (Fit Center)
            // / uUserScale      : 应用用户缩放 (除法是因为放大图片=缩小采样范围)
            // - vec2(...)       : 应用用户移动 (减法实现了“手指往右，图片往右”的直觉)
            // + 0.5             : 移回原点
            
            vec2 center = vec2(0.5, 0.5);
            vec2 scaled = (aTexCoord - center) * vec2(uScaleX, uScaleY); 
            vTexCoord = scaled / uUserScale - vec2(uUserTransX, uUserTransY) + center;
        }
    """

    // === Fragment Shader (片元着色器) ===
    private val fragmentShaderCode = """
        precision mediump float;
        varying vec2 vTexCoord;   // 图片坐标
        varying vec2 vMaskCoord;  // 蒙版坐标
        
        uniform sampler2D uTexture;
        uniform int uShapeType;
        uniform float uViewRatio; // 屏幕宽高比 (用来把蒙版修正成正圆)
        
        void main() {
            // 1. 采样图片颜色
            // 边界检查：防止图片拖出边缘后出现拉伸的怪线 (Clamp to Border 效果)
            if (vTexCoord.x < 0.0 || vTexCoord.x > 1.0 || vTexCoord.y < 0.0 || vTexCoord.y > 1.0) {
                // 超出图片范围显示全透明
                gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
            } else {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }

            // 2. 应用蒙版 (使用不动的 vMaskCoord)
            vec2 center = vec2(0.5, 0.5);
            
            if (uShapeType == 1) { // 圆形
                // 修正蒙版比例：让圆在长方形屏幕上也是正圆
                vec2 pos = vMaskCoord - center;
                if (uViewRatio > 1.0) {
                    pos.x *= uViewRatio; // 横屏：修正X轴
                } else {
                    pos.y /= uViewRatio; // 竖屏：修正Y轴
                }
                
                // 半径 0.4 (留一点边距，别顶满屏幕)
                if (length(pos) > 0.4) { 
                    discard; 
                }
            } 
            else if (uShapeType == 2) { // 正方形
                // 简单的中心裁剪
                if (abs(vMaskCoord.x - 0.5) > 0.4 || abs(vMaskCoord.y - 0.5) > 0.4) {
                    discard;
                }
            }
        }
    """

    // === 更新手势 (EditorFragment 调用) ===
    fun updateTransform(scale: Float, x: Float, y: Float) {
        this.userScale = scale
        this.userTransX = x
        this.userTransY = y
        glView.requestRender()
    }

    // === 设置图片 ===
    fun setImage(bitmap: Bitmap) {
        currentBitmap = bitmap
        imgRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        // 重置手势
        userScale = 1f
        userTransX = 0f
        userTransY = 0f
        glView.requestRender()
    }

    fun setShape(shapeType: Int) {
        this.currentShapeType = shapeType
        glView.requestRender()
    }

    fun saveFrame(callback: (Bitmap) -> Unit) {
        this.saveCallback = callback
        glView.requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // 背景全透明
        GLES20.glClearColor(0f, 0f, 0f, 0f)

        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vShader)
        GLES20.glAttachShader(programId, fShader)
        GLES20.glLinkProgram(programId)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
        viewRatio = width.toFloat() / height.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (currentBitmap == null && textureId == 0) return

        GLES20.glUseProgram(programId)

        // 上传纹理
        currentBitmap?.let {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            // 设置过滤模式，防止缩小锯齿
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            // 边缘模式设为 Clamp，防止黑边
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, it, 0)
            currentBitmap = null
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

        // === 核心计算：Fit Center 适配算法 ===
        // 计算如何缩放纹理坐标，才能让图片在屏幕中保持比例且居中
        var scaleX = 1f
        var scaleY = 1f

        if (imgRatio > viewRatio) {
            // 图片比屏幕宽 (Fit Width)
            // 屏幕Y轴需要覆盖更多的图片内容，所以 TextureY 范围要变大
            scaleY = imgRatio / viewRatio
        } else {
            // 图片比屏幕瘦 (Fit Height)
            // 屏幕X轴需要覆盖更多的图片内容，所以 TextureX 范围要变大
            scaleX = viewRatio / imgRatio
        }

        // 传参
        val posHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val texHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uTexture"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uShapeType"), currentShapeType)

        // 传适配参数
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uScaleX"), scaleX)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uScaleY"), scaleY)

        // 传手势参数
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uUserScale"), userScale)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uUserTransX"), userTransX)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uUserTransY"), userTransY)

        // 传屏幕比例（修正蒙版用）
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uViewRatio"), viewRatio)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 截图
        saveCallback?.let { cb ->
            val bitmap = createBitmapFromGL(viewWidth, viewHeight)
            glView.post { cb(bitmap) }
            saveCallback = null
        }
    }

    // ... (createBitmapFromGL, createFloatBuffer, loadShader 保持不变) ...
    private fun createBitmapFromGL(w: Int, h: Int): Bitmap {
        val bitmapBuffer = IntArray(w * h)
        val bitmapSource = IntArray(w * h)
        val intBuffer = java.nio.IntBuffer.wrap(bitmapBuffer)
        intBuffer.position(0)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, intBuffer)
        val offset1 = 0
        val offset2 = 0
        for (i in 0 until h) {
            val offset1 = i * w
            val offset2 = (h - i - 1) * w
            for (j in 0 until w) {
                val pixel = bitmapBuffer[offset1 + j]
                val blue = (pixel shr 16) and 0xff
                val red = (pixel shl 16) and 0x00ff0000
                val pixel2 = (pixel and 0xff00ff00.toInt()) or red or blue
                bitmapSource[offset2 + j] = pixel2
            }
        }
        return Bitmap.createBitmap(bitmapSource, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun createFloatBuffer(arr: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(arr.size * 4)
        bb.order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(arr)
        fb.position(0)
        return fb
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        return shader
    }
}