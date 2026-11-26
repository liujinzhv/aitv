package com.aitv.launcher.ui

import android.app.Activity
import android.os.Bundle
import androidx.recyclerview.widget.GridLayoutManager
import com.aitv.launcher.Prefs
import com.aitv.launcher.R
import com.aitv.launcher.databinding.ActivityAllAppsBinding
import com.aitv.launcher.launcher.AppRepo
import com.aitv.launcher.util.Async
import com.aitv.launcher.util.immersive
import com.aitv.launcher.util.keepScreenOn
import com.aitv.launcher.util.toast

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
