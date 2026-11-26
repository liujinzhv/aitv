package com.aitv.launcher.server

import android.content.Context
import android.media.MediaScannerConnection
import fi.iki.elonen.NanoHTTPD
import com.aitv.launcher.util.StorageUtils
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder

/**
 * 手机上传服务器：手机浏览器打开 http://电视IP:端口 ，
 * 页面把文件以原始字节 POST 到 /upload/<文件名>，直接写入 U 盘相册目录。
 */
class UploadServer(
    private val targetDir: File,
    private val ctx: Context,
    private val onUploaded: (String) -> Unit,
    port: Int
) : NanoHTTPD(port) {

    private val allowedExts = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif",
        "mp4", "mov", "3gp", "m4v"
    )

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && (session.uri == "/" || session.uri == "/index.html") ->
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html()).apply {
                        addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                        addHeader("Pragma", "no-cache")
                        addHeader("Expires", "0")
                    }

                session.method == Method.GET && session.uri == "/info" ->
                    json(ok = true, message = targetDir.absolutePath)

                session.method == Method.POST && session.uri.startsWith("/upload/") ->
                    handleUpload(session)

                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain; charset=utf-8",
                    "Not found"
                )
            }
        } catch (t: Throwable) {
            json(ok = false, message = "上传失败：${t.message}")
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val raw = session.uri.removePrefix("/upload/")
        val decoded = try {
            if (raw.contains("%")) URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") else raw
        } catch (t: Throwable) {
            raw
        }
        val safeName = sanitize(decoded)
        if (safeName.isBlank()) {
            return json(ok = false, message = "文件名为空")
        }
        val ext = safeName.substringAfterLast('.', "").lowercase()
        if (ext !in allowedExts) {
            return json(ok = false, message = "不支持的格式：$ext", name = safeName)
        }

        val length = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (length <= 0L) {
            return json(ok = false, message = "文件为空", name = safeName)
        }
        if (length > 300L * 1024L * 1024L) {
            return json(ok = false, message = "单文件不能超过 300MB", name = safeName)
        }

        targetDir.mkdirs()
        val dest = StorageUtils.uniqueFile(targetDir, safeName)
        var written = 0L

        try {
            FileOutputStream(dest).use { out ->
                val input = session.inputStream
                val buffer = ByteArray(8192)
                while (written < length) {
                    val want = minOf(buffer.size.toLong(), length - written).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    written += read
                }
                out.flush()
            }
        } catch (t: Throwable) {
            dest.delete()
            return json(ok = false, message = "写入失败：${t.message}", name = safeName)
        }

        if (written <= 0L) {
            dest.delete()
            return json(ok = false, message = "没有收到数据", name = safeName)
        }

        runCatching {
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null)
        }
        onUploaded(dest.name)
        return json(ok = true, message = "已保存到 ${dest.absolutePath}", name = dest.name)
    }

    private fun sanitize(name: String): String {
        var cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.length > 120) {
            val ext = cleaned.substringAfterLast('.', "")
            val base = cleaned.substringBeforeLast('.', cleaned).take(100)
            cleaned = if (ext.isNotBlank()) "$base.$ext" else base
        }
        return cleaned
    }

    private fun json(ok: Boolean, message: String, name: String = ""): Response {
        val body = "{\"ok\":$ok,\"message\":\"${escape(message)}\",\"name\":\"${escape(name)}\"}"
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)
    }

    private fun escape(value: String): String {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    private fun html(): String {
        val target = escape(targetDir.absolutePath)
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>MyTV · 手机传照片</title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#0b0f14;color:#f4f7fb;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;padding:22px 18px 40px}
h1{font-size:22px;margin:0 0 6px}
.sub{color:#9aa7b8;font-size:13px;line-height:1.6}
.card{background:#151b23;border:1px solid rgba(255,255,255,.1);border-radius:16px;padding:18px;margin-top:18px}
.drop{border:2px dashed rgba(78,161,255,.5);border-radius:14px;padding:24px 16px;text-align:center;color:#9aa7b8;font-size:14px}
.pick{display:block;margin-top:12px;padding:14px;border:1px solid rgba(255,255,255,.12);border-radius:12px;background:rgba(255,255,255,.04)}
.pick b{display:block;font-size:14px;color:#f4f7fb;font-weight:600}
.pick input{display:block;width:100%;margin-top:10px;color:#f4f7fb;font-size:14px}
.chosen{margin-top:12px;color:#9aa7b8;font-size:13px}
.btn{display:block;width:100%;margin-top:16px;padding:14px;border:0;border-radius:12px;background:#4ea1ff;color:#fff;font-size:16px;font-weight:600}
.btn:disabled{opacity:.5}
.item{margin-top:14px;font-size:13px;color:#9aa7b8}
.bar{height:5px;background:rgba(255,255,255,.12);border-radius:3px;overflow:hidden;margin-top:7px}
.bar>i{display:block;height:100%;width:0;background:#4ea1ff;transition:width .15s}
.ok{color:#6ee7a8}.err{color:#ff8b9a}
.path{word-break:break-all;color:#7f8b9c;font-size:12px;margin-top:10px}
</style>
</head>
<body>
<h1>手机传照片到电视</h1>
<div class="sub">照片和视频都可以一次选多个，选好后点「开始上传」，会直接存到电视 U 盘的相册目录。</div>
<div class="card">
  <div class="drop">支持多选 · 上传过程中请保持页面打开</div>
  <label class="pick"><b>选择照片（可多选）</b><input id="photos" type="file" accept="image/*" multiple></label>
  <label class="pick"><b>选择视频（可多选）</b><input id="videos" type="file" accept="video/*" multiple></label>
  <div id="chosen" class="chosen">尚未选择文件</div>
  <button id="btn" class="btn">开始上传</button>
  <div class="path">保存目录：$target</div>
</div>
<div id="list"></div>
<script>
var photoInput = document.getElementById('photos');
var videoInput = document.getElementById('videos');
var chosen = document.getElementById('chosen');
var btn = document.getElementById('btn');
var list = document.getElementById('list');

function selectedFiles() {
  var files = [];
  var seen = {};
  [photoInput, videoInput].forEach(function(input) {
    if (!input.files) return;
    for (var i = 0; i < input.files.length; i++) {
      var f = input.files[i];
      var key = f.name + '|' + f.size + '|' + f.lastModified;
      if (seen[key]) continue;
      seen[key] = true;
      files.push(f);
    }
  });
  return files;
}

function refreshChosen() {
  var count = selectedFiles().length;
  chosen.textContent = count ? '已选择 ' + count + ' 个文件' : '尚未选择文件';
}

photoInput.onchange = refreshChosen;
videoInput.onchange = refreshChosen;

function addItem(name) {
  var div = document.createElement('div');
  div.className = 'item';
  div.innerHTML = '<span class="name"></span><div class="bar"><i></i></div><span class="state">等待上传</span>';
  div.querySelector('.name').textContent = name;
  list.appendChild(div);
  return div;
}

function uploadOne(file, div) {
  return new Promise(function(resolve) {
    var bar = div.querySelector('.bar > i');
    var state = div.querySelector('.state');
    var xhr = new XMLHttpRequest();
    xhr.open('POST', '/upload/' + encodeURIComponent(file.name));
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    xhr.upload.onprogress = function(e) {
      if (e.lengthComputable) bar.style.width = Math.round(e.loaded * 100 / e.total) + '%';
      state.textContent = '上传中 ' + Math.round(e.loaded * 100 / (e.total || 1)) + '%';
    };
    xhr.onload = function() {
      var res = {};
      try { res = JSON.parse(xhr.responseText); } catch (e) {}
      if (xhr.status === 200 && res.ok) {
        bar.style.width = '100%';
        state.className = 'state ok';
        state.textContent = '已保存';
      } else {
        state.className = 'state err';
        state.textContent = '失败：' + (res.message || xhr.status);
      }
      resolve();
    };
    xhr.onerror = function() {
      state.className = 'state err';
      state.textContent = '网络错误';
      resolve();
    };
    xhr.send(file);
  });
}

btn.onclick = async function() {
  var files = selectedFiles();
  if (files.length === 0) { alert('请先选择照片或视频'); return; }
  btn.disabled = true;
  list.innerHTML = '';
  var divs = [];
  for (var i = 0; i < files.length; i++) {
    divs.push(addItem(files[i].name));
  }
  for (var j = 0; j < files.length; j++) {
    divs[j].querySelector('.state').textContent = '准备中';
    await uploadOne(files[j], divs[j]);
  }
  photoInput.value = '';
  videoInput.value = '';
  refreshChosen();
  btn.disabled = false;
  btn.textContent = '继续上传';
};
</script>
</body>
</html>
        """.trimIndent()
    }
}
