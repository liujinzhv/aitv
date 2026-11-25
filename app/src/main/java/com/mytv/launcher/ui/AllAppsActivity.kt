package com.mytv.launcher.ui

import android.app.Activity
import android.os.Bundle
import androidx.recyclerview.widget.GridLayoutManager
import com.mytv.launcher.Prefs
import com.mytv.launcher.R
import com.mytv.launcher.databinding.ActivityAllAppsBinding
import com.mytv.launcher.launcher.AppRepo
import com.mytv.launcher.util.Async
import com.mytv.launcher.util.immersive
import com.mytv.launcher.util.keepScreenOn
import com.mytv.launcher.util.toast

class AllAppsActivity : Activity() {

    private lateinit var binding: ActivityAllAppsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAllAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        immersive()
        keepScreenOn()

        binding.appGrid.layoutManager = GridLayoutManager(this, Prefs.columns)
        loadApps()
    }

    private fun loadApps() {
        binding.countText.text = "加载中…"
        Async.io {
            val apps = AppRepo.load(this)
            runOnUiThread {
                binding.countText.text = "共 ${apps.size} 个"
                binding.emptyText.visibility = if (apps.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                binding.appGrid.adapter = AppAdapter(R.layout.item_app_grid, apps) { entry ->
                    if (!AppRepo.launch(this, entry.packageName)) {
                        toast("无法打开 ${entry.label}")
                    }
                }
                binding.appGrid.post {
                    binding.appGrid.getChildAt(0)?.requestFocus()
                }
            }
        }
    }
}
