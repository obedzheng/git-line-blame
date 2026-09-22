package io.github.obedz.gitlineblame

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.Graphics
import java.awt.Rectangle

/**
 * 通过 editorFactoryListener 扩展点由 IDEA 自动实例化。
 * 每个编辑器创建时，为其挂载 blame 提示控制器。
 */
class GitLineBlameListener : EditorFactoryListener {

    private val log = Logger.getInstance(GitLineBlameListener::class.java)

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        try {
            if (editor.project == null) {
                log.info("skip editor without project")
                return
            }
            log.info("attach blame controller to editor: ${editor.document.text.take(40)}")
            EditorBlameController.attach(editor)
        } catch (e: Throwable) {
            log.error("failed to attach blame controller", e)
        }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        EditorBlameController.detach(event.editor)
    }
}

/**
 * 绑定单个编辑器的 blame 提示。
 */
class EditorBlameController private constructor(
    private val editor: EditorEx
) : Disposable {

    private val log = Logger.getInstance(EditorBlameController::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 当前正在显示 blame 的行；-1 表示无
    @Volatile
    private var shownLine: Int = -1

    private var inlay: Inlay<*>? = null

    // 每次光标移动 +1；协程返回时校验是否仍是最新请求，避免旧结果覆盖新行
    private var requestId = 0L

    private val caretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
            onCaretMoved(event.newPosition.line)
        }
    }

    init {
        editor.caretModel.addCaretListener(caretListener)
    }

    private fun onCaretMoved(line: Int) {
        val id = ++requestId

        val project: Project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        if (file == null) {
            // 无文件：清空提示
            log.info("no virtual file for document")
            show(null, line, id)
            return
        }
        log.info("caret moved to line $line, file=${file.path}")
        val svc = project.getService(BlameCacheService::class.java)
        scope.launch {
            val info = try {
                svc.getBlame(file, line)
            } catch (e: Throwable) {
                log.error("getBlame failed", e)
                null
            }
            show(info, line, id)
        }
    }

    /**
     * 统一入口：只有当这个请求 ID 仍是最新的，才真正更新 inlay，
     * 否则丢弃（光标已经移走了，迟到的结果不能画到当前行）。
     */
    private fun show(info: BlameInfo?, line: Int, id: Long) {
        if (id != requestId) {
            log.info("drop stale result for line $line (req=$id, current=$requestId)")
            return
        }
        if (info == null) {
            clearInlay()
            shownLine = -1
            return
        }
        if (line == shownLine && inlay != null) {
            // 同一行已经在显示，无需重建
            return
        }
        clearInlay()
        if (line < 0 || line >= editor.document.lineCount) {
            shownLine = -1
            return
        }
        val lineEnd = editor.document.getLineEndOffset(line)
        log.info("show blame: author=${info.author}, line=$line")
        inlay = editor.inlayModel.addInlineElement(lineEnd, false, BlameRenderer(info))
        shownLine = line
    }

    private fun clearInlay() {
        inlay?.let { Disposer.dispose(it) }
        inlay = null
    }

    override fun dispose() {
        scope.cancel()
        clearInlay()
        editor.caretModel.removeCaretListener(caretListener)
    }

    companion object {
        private val controllers = java.util.concurrent.ConcurrentHashMap<Editor, EditorBlameController>()

        fun attach(editor: Editor) {
            controllers.computeIfAbsent(editor) { EditorBlameController(editor as EditorEx) }
        }

        fun detach(editor: Editor) {
            controllers.remove(editor)?.dispose()
        }
    }
}

/**
 * 行尾文本渲染器：灰色小字 "作者 · 日期时间 · 提交信息"。
 * 注意：EditorCustomElementRenderer 在 263 是接口（interface）。
 */
internal class BlameRenderer(private val info: BlameInfo) : EditorCustomElementRenderer {

    private val text: String = buildString {
        if (info.author.isNotEmpty()) {
            append(info.author)
        }
        if (info.dateTime.isNotEmpty()) {
            if (isNotEmpty()) append("  ·  ")
            append(info.dateTime)
        }
        if (info.subject.isNotEmpty()) {
            if (isNotEmpty()) append("  ·  ")
            append(info.subject.take(SUBJECT_MAX))
            if (info.subject.length > SUBJECT_MAX) append("…")
        }
    }.ifEmpty { " " }

    private fun fontFor(inlay: Inlay<*>) =
        inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val metrics = inlay.editor.contentComponent.getFontMetrics(fontFor(inlay))
        return metrics.stringWidth(text) + H_PADDING * 2
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes
    ) {
        g.font = fontFor(inlay)
        val metrics = g.fontMetrics
        val y = targetRegion.y + (targetRegion.height - metrics.height) / 2 + metrics.ascent
        g.color = JBColor(0x9E9E9E, 0x8A8A8A)
        g.drawString(text, targetRegion.x + H_PADDING, y)
    }

    companion object {
        private const val H_PADDING = 12
        private const val SUBJECT_MAX = 50
    }
}
