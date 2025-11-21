package com.example.simplephotoeditor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 找到那个滑块容器
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        // 2. 创建我们写的“页面管理员” (Adapter)
        val adapter = MainPagerAdapter(this)

        // 3. 把管理员指派给滑块
        viewPager.adapter = adapter
    }

    // === 这是一个内部类：页面管理员 ===
    // 它的工作很简单：告诉 App 有几页，每一页是谁
    inner class MainPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {

        // 一共有 2 页
        override fun getItemCount(): Int = 2

        // 根据位置 (position) 返回对应的界面
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> EditorFragment()   // 第 0 页：黑色的采样台 (Editor)
                else -> JournalFragment() // 第 1 页：白色的手账本 (Journal)
            }
        }
    }
}