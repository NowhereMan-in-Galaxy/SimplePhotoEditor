# SimplePhotoEditor

一个基于 **OpenGL ES 2.0** 与 **Android Jetpack** 构建的高性能安卓修图与拼贴应用。
本项目演示了如何在 Android 平台实现高效的图形渲染、手势交互与媒体管理，适合作为 Android 图形学入门或图片处理类应用的参考架构。

## 📸 项目简介 (Introduction)
SimplePhotoEditor 采用双页面架构设计，旨在提供流畅的图片采样与二次创作体验：
* **Sampler (采样器)**：基于 OpenGL 的图片裁剪工具。支持圆形/方形蒙版实时渲染，利用 Shader 算法实现防畸变显示与非破坏性裁剪。
* **Journal (拼贴板)**：支持多图层的自由拼贴画布。用户可以将采样后的素材（保留透明通道）自由组合，并保存为最终作品。

## ✨ 核心功能 (Features)

* **OpenGL 渲染引擎**：自定义 `GLSurfaceView.Renderer`，通过 GLSL 着色器实现纹理映射与动态蒙版（圆形/方形）。
* **高级手势交互**：
    * 支持双指缩放 (Scale) 与单指平移 (Translate)。
    * 实现了纹理坐标与顶点坐标的解耦，确保蒙版固定而图片移动，解决传统 OpenGL 图片拉伸问题。
* **多媒体管理**：
    * 基于 `MediaStore` API 的异步媒体加载，支持图片与视频（缩略图）混合显示。
    * 适配 Android 10+ 分区存储与 Android 13+ 媒体权限。
* **UI/UX 组件**：
    * 集成 `ViewPager2` 实现左右页面的无缝切换与数据联动。
    * 包含自定义 View (`ShimmerTextView`)，基于 `LinearGradient` 实现动态流光标题效果。
* **无损导出**：支持 `glReadPixels` 读取 GPU 渲染结果，并导出为带有 Alpha 通道的 PNG 图片。

## 🖼️ 截图展示 (Screenshots)

<table width="100%">
  <tr>
    <th width="50%">Sampler Mode (OpenGL Editor)</th>
    <th width="50%">Journal Mode (Sticker Collage)</th>
  </tr>
  <tr>
    <td>
      <img src="screenshots/sampler.png" width="100%" alt="Sampler Mode"/>
    </td>
    <td>
      <img src="screenshots/journal.png" width="100%" alt="Journal Mode"/>
    </td>
  </tr>
</table>

# 待开发功能

## ui ux

- [x]  download feat
- [ ]  mask and others
- [ ] 

## Features

- journal
    - [x]  fix：图片移动和缩放不顺畅
    - [x]  fix：journal页图片缩放和旋转会颤抖滑动，文字缩放不了
    - [ ]  文字彻底改版，学习instagram的界面风格，还要加背景
    - [x]  Text journal页面添加文字部分，后期要能选字体
- SAMPLAR
    - [ ]  蒙版功能的改进
    - [ ]  滤镜功能，采样后可以改颜色，加滤镜
- 
## 🛠️ 构建与运行 (Build & Run)

### 环境要求 (Prerequisites)
* Android Studio Iguana | 2023.2.1 或更高版本
* JDK 17
* Android SDK API Level 34 (或更高)
* Gradle 8.0+

### 部署步骤
1.  **克隆仓库**：
    ```bash
    git clone [https://github.com/NowhereMan-in-Galaxy/SimplePhotoEditor.git](https://github.com/NowhereMan-in-Galaxy/SimplePhotoEditor.git)
    ```
2.  **导入项目**：
    * 打开 Android Studio，选择 `Open`，导航至项目根目录。
    * 等待 Gradle Sync 完成依赖下载。
3.  **运行应用**：
    * 连接 Android 真机（推荐 Android 10.0+ 以获得最佳沉浸式体验）。
    * 选择 `app` 模块，点击 **Run** (绿色播放键)。

---
# 📘 基础项目报告 (Basic Project Report)

## 1. 整体设计 (Architecture & Design)
本项目采用 **Single Activity + ViewPager2** 的双页面架构，旨在打造“暗房采样”与“手帐拼贴”的无缝工作流。

* **技术栈**：Kotlin, Jetpack (ViewModel, Coroutines), OpenGL ES 2.0, MediaStore API。
* **模块划分**：
    * **Sampler (编辑器)**：基于 `GLSurfaceView`，负责高性能的图片显示与蒙版裁切。核心渲染逻辑封装于 `PhotoRenderer` 类中。
    * **Journal (手帐)**：基于 `FrameLayout` 的自由画布，支持多图层叠加与交互。
    * **Data Layer**：统一封装 `MediaStore` 查询逻辑，通过协程实现异步加载，确保主线程流畅。

## 2. 开发遇到的困难与解决方案 (Challenges & Solutions)

### 难点一：OpenGL 图片拉伸与黑边问题
* **问题描述**：直接将非正方形图片贴图到 OpenGL 纹理时，图片会被强制拉伸以填充屏幕；且边缘在某些机型上出现黑色杂边。
* **解决思路**：
    1. **防畸变**：在 Kotlin 层计算 `imageRatio` 与 `viewRatio`，传入 Shader。在顶点着色器中对纹理坐标进行逆向缩放，实现类似 `ImageView.ScaleType.FIT_CENTER` 的效果。
    2. **去黑边**：设置纹理环绕模式为 `GL_CLAMP_TO_EDGE`，防止 UV 坐标越界采样。

### 难点二：手势冲突 (Gesture Conflicts)
* **问题描述**：在画布上进行单指拖拽或双指缩放时，极易误触 `ViewPager2` 的翻页操作。
* **解决思路**：实现 `View.OnTouchListener`，在 `ACTION_DOWN` 事件触发时，调用 `parent.requestDisallowInterceptTouchEvent(true)`，强制父容器交出事件控制权；在 `ACTION_UP` 时释放。

### 难点三：PNG 透明背景保存
* **问题描述**：保存圆形裁剪图片时，背景默认为黑色而非透明。
* **解决思路**：
    1. 配置 `GLSurfaceView` 支持 Alpha 通道 (`setEGLConfigChooser(8,8,8,8,16,0)`)。
    2. 将 `glClearColor` 设为全透明。
    3. 导出时使用 `Bitmap.CompressFormat.PNG` 格式。

---

# 🚀 进阶任务完成报告 (Advanced Task Report)

## P2: 图像编辑初体验 (Image Editing Basics)

### 1. 功能实现思路
为了实现**“蒙版固定、风景移动”**的高级交互感（类似 Instagram 头像裁剪）：
* **坐标系解耦**：我不移动 OpenGL 的顶点坐标（`gl_Position`），而是通过 Shader 变换 **纹理坐标（Texture Coordinates）**。
* **算法逻辑**：
    * 用户的手势位移 (`transX/Y`) 被反向作用于纹理采样坐标。
    * 蒙版计算使用原始坐标（保持静止），图片采样使用变换后的坐标（随手势移动）。
      这就实现了“窗户不动，窗外风景在动”的视觉效果。


### 生成发布包
在 Android Studio 的 Terminal 中执行：
```bash
# 生成 APK
./gradlew assembleRelease

# 生成 AAB (Google Play)
./gradlew bundleRelease


