package com.aitv.launcher.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aitv.launcher.R
import com.aitv.launcher.launcher.AppEntry
import com.aitv.launcher.util.focusScale

class AppAdapter(
    private val layoutRes: Int,
    private val items: List<AppEntry>,
    private val onClick: (AppEntry) -> Unit,
    private val onLongClick: (AppEntry) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView? = view.findViewById(R.id.appName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        view.focusScale(1.06f)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name?.text = item.label
        holder.icon.setImageDrawable(item.icon)
        holder.itemView.contentDescription = item.label
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                onLongClick(item)
                true
            } else {
                false
            }
        }
    }
}
