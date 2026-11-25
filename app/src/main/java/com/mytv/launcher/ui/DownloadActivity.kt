package com.mytv.launcher.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytv.launcher.R
import com.mytv.launcher.databinding.ActivityDownloadBinding
import com.mytv.launcher.databinding.ItemApkBinding
import com.mytv.launcher.launcher.JptvHelper
import com.mytv.launcher.model.ApkFile
import com.mytv.launcher.util.Async
import com.mytv.launcher.util.StorageUtils
import com.mytv.launcher.util.focusScale
import com.mytv.launcher.util.formatSize
import com.mytv.launcher.util.immersive
import com.mytv.launcher.util.keepScreenOn
import com.mytv.launcher.util.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadActivity : Activity() {

    private lateinit var binding: ActivityDownloadBinding
    private var apks: List<ApkFile> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        immersive()
        keepScreenOn()

        binding.apkList.layoutManager = LinearLayoutManager(this)
        binding.btnRefresh.setOnClickListener { load() }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        binding.countText.text = "扫描中…"
        binding.pathText.text = "下载目录：" + StorageUtils.downloadDirs().joinToString(" ; ") { it.absolutePath }
        Async.io {
            val list = JptvHelper.apks(this)
            runOnUiThread {
                apks = list
                binding.countText.text = "${list.size} 个安装包"
                binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.apkList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE

                binding.apkList.adapter = ApkAdapter(list) { apk -> handle(apk) }
                binding.apkList.post { binding.apkList.getChildAt(0)?.requestFocus() }
            }
        }
    }

    private fun handle(apk: ApkFile) {
        if (JptvHelper.isInstalled(this) && JptvHelper.isJptv(apk.name)) {
            if (!JptvHelper.launch(this)) toast("启动荐片失败")
            return
        }
        JptvHelper.install(this, apk)
    }
}

class ApkAdapter(
    private val items: List<ApkFile>,
    private val onClick: (ApkFile) -> Unit
) : RecyclerView.Adapter<ApkAdapter.VH>() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.apkName)
        val meta: TextView = view.findViewById(R.id.apkMeta)
        val action: TextView = view.findViewById(R.id.apkAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_apk, parent, false)
        view.focusScale(1.02f, 6f)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.meta.text = "${formatSize(item.size)} · ${dateFormat.format(Date(item.lastModified))}" +
            if (JptvHelper.isJptv(item.name)) " · JPTV" else ""

        val context = holder.itemView.context
        val installed = JptvHelper.isJptv(item.name) && JptvHelper.isInstalled(context)
        holder.action.text = if (installed) "打开" else "安装"
        holder.action.setTextColor(
            if (JptvHelper.isJptv(item.name)) android.graphics.Color.parseColor("#4EA1FF")
            else android.graphics.Color.parseColor("#F4F7FB")
        )
        holder.itemView.setOnClickListener { onClick(item) }
    }
}
