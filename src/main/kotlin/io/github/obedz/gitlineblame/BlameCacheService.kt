package io.github.obedz.gitlineblame

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.vcs.annotate.LineAnnotationAspect
import com.intellij.openapi.vfs.VirtualFile
import git4idea.annotate.GitFileAnnotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat

/**
 * 单行 blame 信息。
 */
data class BlameInfo(
    val author: String,
    val dateTime: String,
    val subject: String
)

/**
 * 对文件缓存整份 blame 结果。光标移动时只查内存，不重复跑 git blame。
 *
 * 全部基于官方通用 VCS API（AnnotationProvider / FileAnnotation），
 * 不依赖已拆分/变动的 git4idea 内部实现，跨 262/263 稳定。
 */
@Service(Service.Level.PROJECT)
class BlameCacheService(private val project: Project) {

    private val log = Logger.getInstance(BlameCacheService::class.java)

    // 文件路径 -> (modificationStamp, 每行 BlameInfo)。空 map 表示"已确认该文件无 blame 数据"。
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Map<Int, BlameInfo>>>()

    // 正在计算中的文件，用于并发去重：同一文件同时只跑一次 git blame。
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun getBlame(file: VirtualFile, line: Int): BlameInfo? {
        if (line < 0) return null
        val ts = file.modificationStamp
        val cached = cache[file.path]
        if (cached != null && cached.first == ts) {
            return cached.second[line]
        }

        // 若已有协程在算该文件，直接等它算完的结果，避免并发重复跑 git blame。
        if (!inFlight.add(file.path)) {
            // 简单自旋等待：绝大多数情况下 blame 很快，等几百 ms 即可拿到缓存。
            repeat(200) {
                kotlinx.coroutines.delay(10)
                val now = cache[file.path]
                if (now != null && now.first == ts) return now.second[line]
            }
            // 等了 2s 还没好，再自己跑一次兜底。
        }

        try {
            val map = rebuild(file, ts)
            return map[line]
        } finally {
            inFlight.remove(file.path)
        }
    }

    private suspend fun rebuild(file: VirtualFile, ts: Long): Map<Int, BlameInfo> {
        val map = withContext(Dispatchers.Default) {
            try {
                computeBlame(file)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                log.warn("compute blame failed for ${file.path}", e)
                null
            }
        }
        if (map != null) {
            cache[file.path] = ts to map
            return map
        }
        // 未取到注解（VCS 未就绪等）：不写缓存，下次重试；
        // 但返回空 map，至少本次不抛错。
        return emptyMap()
    }

    private fun computeBlame(file: VirtualFile): Map<Int, BlameInfo>? {
        val annotation = buildAnnotation(file) ?: return null
        try {
            val lineCount = annotation.lineCount
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

            // Git 的 getRevisions() 会返回空（author/date/subject 都在 LineInfo 里），
            // 所以绝不能靠 revisions 映射。author/date 走通用 aspects + getLineDate()，
            // 提交信息（subject）只有 Git 能干净拿到，走 GitFileAnnotation.getLineInfo().subject。
            val authorAspect: LineAnnotationAspect? =
                annotation.aspects.firstOrNull { it.id == LineAnnotationAspect.AUTHOR }
            val gitAnnotation = annotation as? GitFileAnnotation

            val result = HashMap<Int, BlameInfo>(lineCount * 2)
            for (i in 0 until lineCount) {
                val author = authorAspect?.getValue(i)?.takeIf { it.isNotBlank() }
                    ?: gitAnnotation?.getLineInfo(i)?.author.orEmpty()

                val date = annotation.getLineDate(i)
                val dateTime = if (date != null) dateFormat.format(date) else ""

                // subject 优先走 Git 的 LineInfo；非 Git 时退化为空。
                val subject = gitAnnotation?.getLineInfo(i)?.subject?.trim().orEmpty()

                if (author.isNotEmpty() || dateTime.isNotEmpty() || subject.isNotEmpty()) {
                    result[i] = BlameInfo(author = author, dateTime = dateTime, subject = subject)
                }
            }
            return result
        } finally {
            // FileAnnotation 持有可能的临时资源，用完释放。
            // 注意：用 dispose() 而非 close()——close() 是 263 才新增的 final 方法，
            // 为了兼容 since-build=261（2026.1），这里统一用贯穿 261~263 的 dispose()。
            // dispose() 在 263 标记弃用但仍可用，故抑制弃用告警。
            try {
                @Suppress("DEPRECATION")
                annotation.dispose()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 通过 VCS 的通用入口获取文件的 blame 注解。
     *
     * getVcsFor(file) 在 VCS 尚未完成映射时可能返回 null（IDE 刚打开的瞬间），
     * 因此这里对 null 做一次短暂的重试；仍为 null 才放弃。
     */
    private fun buildAnnotation(file: VirtualFile): FileAnnotation? {
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        var vcs = vcsManager.getVcsFor(file)
        if (vcs == null) {
            // 首次可能因 VCS 初始化时序未就绪而拿不到，主动等待/重试一次。
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
            }
            ProgressManager.checkCanceled()
            vcs = vcsManager.getVcsFor(file)
        }
        if (vcs == null) {
            log.warn("no VCS for file: ${file.path}")
            return null
        }
        val provider = vcs.annotationProvider
        if (provider == null) {
            log.warn("no annotation provider for vcs: ${vcs.name}")
            return null
        }
        return try {
            ProgressManager.checkCanceled()
            provider.annotate(file).also {
                log.info("annotation fetched for ${file.path}: lines=${it.lineCount}, revisions=${it.revisions?.size ?: 0}")
            }
        } catch (e: VcsException) {
            log.error("annotate failed for ${file.path}", e)
            null
        } catch (e: Exception) {
            log.error("unexpected error annotating ${file.path}", e)
            null
        }
    }

    companion object {
        fun getInstance(project: Project): BlameCacheService =
            project.getService(BlameCacheService::class.java)
    }
}
