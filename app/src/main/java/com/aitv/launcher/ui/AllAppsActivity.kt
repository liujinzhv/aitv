package com.aitv.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import com.aitv.launcher.Prefs
import com.aitv.launcher.R
import com.aitv.launcher.databinding.ActivityAllAppsBinding
import com.aitv.launcher.launcher.AppEntry
import com.aitv.launcher.launcher.AppRepo
import com.aitv.launcher.util.Async
import com.aitv.launcher.util.immersive
import com.aitv.launcher.util.lightPageChrome
import com.aitv.launcher.util.keepScreenOn
import com.aitv.launcher.util.toast

class AllAppsActivity : Activity() {

    private lateinit var binding: ActivityAllAppsBinding
    private var loading = false
    private var firstResume = true
    private var focusPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAllAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        immersive()
        lightPageChrome()
        keepScreenOn()

        binding.appGrid.layoutManager = GridLayoutManager(this, Prefs.columns)
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
        } else {
            // 从系统卸载器或应用返回后刷新，避免已卸载应用残留。
            loadApps()
        }
    }

    private fun loadApps() {
        if (loading) return
        loading = true
        binding.countText.text = "加载中…"
        Async.io {
            val apps = AppRepo.load(this)
            runOnUiThread {
                loading = false
                binding.countText.text = "共 ${apps.size} 个"
                binding.emptyText.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
                binding.appGrid.adapter = AppAdapter(
                    R.layout.item_app_grid,
                    apps,
                    onClick = { entry ->
                        if (!AppRepo.launch(this, entry.packageName)) {
                            toast("无法打开 ${entry.label}")
                        }
                    },
                    onLongClick = { entry -> showAppActions(entry) }
                )
                val target = focusPackage
                    ?.let { pkg -> apps.indexOfFirst { it.packageName == pkg } }
                    ?.takeIf { it >= 0 }
                    ?: 0
                binding.appGrid.post {
                    if (target in apps.indices) binding.appGrid.scrollToPosition(target)
                    binding.appGrid.post {
                        val holder = binding.appGrid.findViewHolderForAdapterPosition(target)
                        (holder?.itemView ?: binding.appGrid.getChildAt(0))?.requestFocus()
                    }
                }
            }
        }
    }

    /** 长按 OK / 菜单键：仅对可卸载应用展示卸载入口，系统预装应用不显示。 */
    private fun showAppActions(entry: AppEntry) {
        focusPackage = entry.packageName
        if (!AppRepo.canUninstall(this, entry.packageName)) {
            toast("「${entry.label}」是系统预装应用，暂不支持卸载")
            return
        }
        AlertDialog.Builder(this, R.style.LightDialogTheme)
            .setTitle("${entry.label} · 可卸载")
            .setItems(arrayOf("卸载")) { _, _ -> confirmUninstall(entry) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmUninstall(entry: AppEntry) {
        AlertDialog.Builder(this, R.style.LightDialogTheme)
            .setTitle("卸载应用")
            .setMessage("将打开系统卸载界面卸载「${entry.label}」。")
            .setNegativeButton("取消", null)
            .setPositiveButton("卸载") { _, _ ->
                if (!AppRepo.uninstall(this, entry.packageName)) {
                    toast("无法打开系统卸载界面")
                }
            }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        loadApps()
    }
}
