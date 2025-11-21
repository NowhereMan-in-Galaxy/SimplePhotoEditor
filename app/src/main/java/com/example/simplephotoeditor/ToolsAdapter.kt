package com.example.simplephotoeditor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class EditorTool(
    val id: Int,
    val name: String,
    val iconRes: Int
)

class ToolsAdapter(
    private var tools: List<EditorTool>, // 这里的 var 允许修改
    private val onToolClick: (EditorTool) -> Unit
) : RecyclerView.Adapter<ToolsAdapter.ToolViewHolder>() {

    // 新增方法：更新数据并刷新界面
    fun updateTools(newTools: List<EditorTool>) {
        this.tools = newTools
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ToolViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tool, parent, false)
        return ToolViewHolder(view)
    }

    override fun onBindViewHolder(holder: ToolViewHolder, position: Int) {
        val tool = tools[position]
        holder.name.text = tool.name
        holder.icon.setImageResource(tool.iconRes)
        holder.itemView.setOnClickListener { onToolClick(tool) }
    }

    override fun getItemCount() = tools.size

    class ToolViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.toolIcon)
        val name: TextView = view.findViewById(R.id.toolName)
    }
}