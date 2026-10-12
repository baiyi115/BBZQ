package io.github.bbzq.feats.download

import android.app.Activity
import android.os.Handler
import android.os.Looper
import io.github.bbzq.ModuleSettings
import io.github.bbzq.ModuleSettingsBridge
import io.github.bbzq.feats.hook.NetworkTypeDetector
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

object VideoDownloadManager {
    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class QualityInfo(
        val qn: Int, 
        val description: String, 
        val videoUrl: String, 
        val audioUrl: String
    )

    fun fetchVideoInfo(
        activity: Activity, 
        bvid: String,
        cid: Long?,
        cookie: String,
        onResult: (List<QualityInfo>?, String?) -> Unit
    ) {
        val targetBvid = bvid

        if (cid != null && cid > 0 && targetBvid.startsWith("BV", ignoreCase = true)) {
            fetchPlayUrl(activity, targetBvid, cid, cookie, onResult)
            return
        }

        val idParam = if (targetBvid.startsWith("BV", ignoreCase = true)) "bvid=$targetBvid" else "aid=${targetBvid.removePrefix("av").removePrefix("AV")}"
        val url = "https://api.bilibili.com/x/web-interface/view?$idParam"
        val reqBuilder = Request.Builder().url(url)
        if (cookie.isNotBlank()) {
            reqBuilder.header("Cookie", cookie)
        }

        client.newCall(reqBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                postResult(activity, onResult, null, "网络请求失败: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val rawBody = response.body?.string().orEmpty()
                    try {
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val viewJson = JSONObject(rawBody)
                        val code = viewJson.optInt("code", -1)
                        if (code != 0) {
                            val msg = viewJson.optString("message", "获取视频信息失败")
                            throw Exception("接口错误 ($code): $msg [ID: $targetBvid]")
                        }
                        
                        val data = viewJson.getJSONObject("data")
                        val realBvid = data.optString("bvid", targetBvid)
                        val cid = data.optLong("cid", 0L)
                        if (cid <= 0L) throw Exception("未找到视频 cid [ID: $realBvid]")
                        
                        fetchPlayUrl(activity, realBvid, cid, cookie, onResult)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        postResult(activity, onResult, null, e.message ?: "解析视频信息失败")
                    }
                }
            }
        })
    }

    private fun fetchPlayUrl(
        activity: Activity, 
        bvid: String, 
        cid: Long, 
        cookie: String, 
        onResult: (List<QualityInfo>?, String?) -> Unit
    ) {
        val url = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&platform=web&qn=127&fnval=16&fourk=1"
        val reqBuilder = Request.Builder().url(url)
        if (cookie.isNotBlank()) {
            reqBuilder.header("Cookie", cookie)
        }

        client.newCall(reqBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                postResult(activity, onResult, null, "网络请求失败: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val rawBody = response.body?.string().orEmpty()
                    try {
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val playurlJson = JSONObject(rawBody)
                        val code = playurlJson.optInt("code", -1)
                        if (code != 0) {
                            val msg = playurlJson.optString("message", "获取 playurl 失败")
                            throw Exception("接口错误 ($code): $msg [BVID: $bvid, CID: $cid]")
                        }

                        val data = playurlJson.optJSONObject("data") ?: throw Exception("返回数据为空 [BVID: $bvid]")
                        val dash = data.optJSONObject("dash") ?: throw Exception("非 DASH 流数据 [BVID: $bvid]")
                        
                        // Parse audio URL
                        val audios = dash.optJSONArray("audio")
                        val audioUrl = if (audios != null && audios.length() > 0) {
                            val aObj = audios.getJSONObject(0)
                            aObj.optString("baseUrl").ifEmpty { aObj.optString("base_url") }
                        } else {
                            ""
                        }

                        val videos = dash.optJSONArray("video") ?: throw Exception("无可用视频流 [BVID: $bvid]")
                        val acceptDescription = data.optJSONArray("accept_description")
                        val acceptQuality = data.optJSONArray("accept_quality")
                        val qnDescMap = mutableMapOf<Int, String>()
                        if (acceptQuality != null && acceptDescription != null) {
                            for (i in 0 until minOf(acceptQuality.length(), acceptDescription.length())) {
                                qnDescMap[acceptQuality.getInt(i)] = acceptDescription.getString(i)
                            }
                        }
                        // Fallback quality names
                        val fallbackMap = mapOf(
                            127 to "8K 超高清", 126 to "杜比视界", 125 to "HDR 真彩",
                            120 to "4K 超清", 116 to "1080P 60帧", 112 to "1080P 高码率",
                            80 to "1080P 高清", 74 to "720P 60帧", 64 to "720P 高清",
                            32 to "480P 清晰", 16 to "360P 流畅"
                        )

                        val qualities = mutableListOf<QualityInfo>()
                        for (i in 0 until videos.length()) {
                            val v = videos.getJSONObject(i)
                            val id = v.optInt("id", 0)
                            val baseUrl = v.optString("baseUrl").ifEmpty { v.optString("base_url") }
                            if (baseUrl.isNotBlank()) {
                                val desc = qnDescMap[id] ?: fallbackMap[id] ?: "画质 $id"
                                qualities.add(QualityInfo(id, desc, baseUrl, audioUrl))
                            }
                        }
                        
                        val resultList = qualities.distinctBy { it.qn }
                        if (resultList.isEmpty()) {
                            throw Exception("未解析到有效画质 [BVID: $bvid]")
                        }
                        postResult(activity, onResult, resultList, null)
                    } catch (e: Exception) {
                        android.util.Log.e("BBZQ", "fetchPlayUrl failed", e)
                        e.printStackTrace()
                        postResult(activity, onResult, null, e.message ?: "解析 playurl 失败")
                    }
                }
            }
        })
    }

    private fun postResult(activity: Activity, onResult: (List<QualityInfo>?, String?) -> Unit, list: List<QualityInfo>?, error: String?) {
        mainHandler.post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                onResult(list, error)
            }
        }
    }

    private val isDownloading = java.util.concurrent.atomic.AtomicBoolean(false)

    fun downloadAndMux(
        activity: Activity, 
        bvid: String, 
        videoUrl: String, 
        audioUrl: String, 
        onProgress: (String, Int) -> Unit
    ) {
        if (!isDownloading.compareAndSet(false, true)) {
            postProgress(activity, onProgress, "已有下载任务正在进行中，请稍候...", -1)
            return
        }

        Thread {
            val taskId = System.currentTimeMillis()
            val tempDir = File(activity.cacheDir, "bbzq_dl").apply { mkdirs() }
            val videoFile = File(tempDir, "${bvid}_video_$taskId.m4s")
            val audioFile = File(tempDir, "${bvid}_audio_$taskId.m4s")
            val publicDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "BBZQ").apply { mkdirs() }
            val outFile = File(publicDir, "$bvid.mp4")

            try {
                postProgress(activity, onProgress, "正在并行下载视频与音频...", 0)

                val hosts = resolveCdnHosts(activity)
                val videoSources = DownloadSourcePlan.plan(videoUrl, hosts)
                val audioSources = DownloadSourcePlan.plan(audioUrl, hosts)

                var videoError: Throwable? = null
                var audioError: Throwable? = null
                var videoPercent = 0
                var audioPercent = 0

                val videoThread = Thread {
                    try {
                        downloadWithFallback(videoSources, videoFile) { p ->
                            videoPercent = p
                            postProgress(activity, onProgress, "下载中: 视频 $p% / 音频 $audioPercent%", (videoPercent + audioPercent) / 2)
                        }
                    } catch (t: Throwable) {
                        videoError = t
                    }
                }

                val audioThread = Thread {
                    try {
                        if (audioUrl.isNotBlank()) {
                            downloadWithFallback(audioSources, audioFile) { p ->
                                audioPercent = p
                                postProgress(activity, onProgress, "下载中: 视频 $videoPercent% / 音频 $p%", (videoPercent + audioPercent) / 2)
                            }
                        } else {
                            audioPercent = 100
                        }
                    } catch (t: Throwable) {
                        audioError = t
                    }
                }

                videoThread.start()
                audioThread.start()

                videoThread.join()
                audioThread.join()

                if (videoError != null) throw videoError!!
                if (audioError != null) throw audioError!!

                postProgress(activity, onProgress, "正在合并 MP4 音视频轨道...", 95)
                val success = if (audioFile.exists() && audioFile.length() > 0) {
                    MediaMuxerUtil.mux(videoFile.absolutePath, audioFile.absolutePath, outFile.absolutePath)
                } else {
                    videoFile.renameTo(outFile)
                }
                
                if (success) {
                    postProgress(activity, onProgress, "下载成功: ${outFile.name}", 100)
                } else {
                    postProgress(activity, onProgress, "合并失败 (请查看日志)", -1)
                }
            } catch (e: Exception) {
                android.util.Log.e("BBZQ", "downloadAndMux failed", e)
                e.printStackTrace()
                postProgress(activity, onProgress, "下载失败: ${e.message}", -1)
            } finally {
                runCatching { if (videoFile.exists()) videoFile.delete() }
                runCatching { if (audioFile.exists()) audioFile.delete() }
                isDownloading.set(false)
            }
        }.start()
    }

    private fun postProgress(activity: Activity, onProgress: (String, Int) -> Unit, msg: String, percent: Int) {
        mainHandler.post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                onProgress(msg, percent)
            }
        }
    }

    /** 用户为下载配置的 CDN 主机。蜂窝与 WiFi 可以是两份不同的列表。 */
    private fun resolveCdnHosts(activity: Activity): List<String> = runCatching {
        val isCellular = NetworkTypeDetector.isCellular(activity)
        ModuleSettings.getActiveCdnHosts(ModuleSettingsBridge.instance, isCellular)
    }.getOrElse {
        android.util.Log.w(TAG, "resolveCdnHosts failed, using the playurl host", it)
        emptyList()
    }

    /**
     * 依次尝试各候选源。已经写入的字节会保留，换源时用 Range 从断点续传，
     * 不必重下已拿到的部分。
     */
    private fun downloadWithFallback(
        sources: List<DownloadSourcePlan.Source>,
        dest: File,
        progressCallback: (Int) -> Unit,
    ) {
        if (sources.isEmpty()) throw IOException("没有可用的下载源")
        var lastError: Throwable? = null
        for (source in sources) {
            try {
                val total = downloadFrom(source.url, dest, progressCallback)
                if (total > 0L && dest.length() != total) {
                    throw IOException("下载字节数不符：${dest.length()} / $total")
                }
                return
            } catch (t: Throwable) {
                lastError = t
                android.util.Log.w(TAG, "download from ${source.label} failed, trying next", t)
            }
        }
        throw IOException("所有下载源均失败", lastError)
    }

    /**
     * 从单个源下载，返回服务端给出的总字节数（未知时为 `0`）。
     *
     * `dest` 里已有的字节会被保留：带 `Range` 从断点续传。服务端忽略 `Range`
     * 而回了 200 时把文件截断重写，否则整段会被接在断点后面。
     */
    private fun downloadFrom(url: String, dest: File, progressCallback: (Int) -> Unit): Long {
        val resumeFrom = if (dest.exists()) dest.length() else 0L
        val request = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .header("Referer", "https://www.bilibili.com")
            .header("User-Agent", USER_AGENT)
            .apply { if (resumeFrom > 0L) header("Range", "bytes=$resumeFrom-") }
            .build()

        client.newCall(request).execute().use { response ->
            // 断点恰好等于文件长度时服务端回 416：文件其实已经完整
            if (response.code == 416) {
                val total = DownloadSourcePlan.contentRangeTotal(response.header("Content-Range")) ?: 0L
                if (total > 0L && resumeFrom == total) return total
                throw IOException("HTTP 416 during download")
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} during download")

            val body = response.body ?: throw IOException("Empty response body")
            val resuming = resumeFrom > 0L && response.code == 206
            val offset = if (resuming) resumeFrom else 0L
            val total = if (resuming) {
                DownloadSourcePlan.contentRangeTotal(response.header("Content-Range")) ?: 0L
            } else {
                body.contentLength().takeIf { it > 0L } ?: 0L
            }

            RandomAccessFile(dest, "rw").use { file ->
                if (offset == 0L) file.setLength(0L)
                file.seek(offset)
                var written = offset

                body.byteStream().buffered(COPY_BUFFER_SIZE).use { input ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        file.write(buffer, 0, read)
                        written += read
                        if (total > 0L) {
                            val percent = ((written * 100L) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                progressCallback(percent)
                            }
                        }
                    }
                }
            }
            return total
        }
    }

    private const val TAG = "BBZQ"
    private const val COPY_BUFFER_SIZE = 128 * 1024
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36"
}
