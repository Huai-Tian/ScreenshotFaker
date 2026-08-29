package fake.screenshot.services.privileged.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.MediaPlayer
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceControl

/**
 * 纯 Surface 渲染后端：双层结构（内容层 + 手柄层），全部经 SurfaceControl
 * 直挂 SurfaceFlinger，完全不经过 WindowManager / ViewRootImpl——
 * Android 15 的 WMS session 加固（"Unknown pid" 报错）被整体绕开。
 *
 * ==================== 层结构 ====================
 *
 * root layer（随机名，无 buffer，仅作定位容器）
 * ├── content layer：图片 / 视频。图片用 Canvas 软绘；视频把 Surface
 * │   直接交给 MediaPlayer（buffer producer 独占——这正是必须分层的
 * │   原因：同一 layer 上无法再叠加绘制）。
 * └── handle layer：四角缩放手柄 + 顶部中线指示（Canvas 软绘，顶层 z）。
 *
 * root layer setLayer(0x7FFFFFFF) 置顶；截图排除双路径：
 * 12+ 每层 setSkipScreenshot（root 进程反射隐藏 API）；Android 11 创建期
 * metadata windowType=441731（WINDOW_TYPE_DONT_SCREENSHOT，系统圆角
 * overlay 同款）→ primaryDisplayOnly。两者同语义（A15 LayerSnapshot 证实
 * eLayerSkipScreenshot 即 outputFilter.toInternalDisplay）：截图与虚拟
 * 显示器（MediaProjection 录屏）均穿透——FLAG_SECURE 在无窗口场景不可用。
 *
 * Surface(SurfaceControl) 为公开构造（API 29+）；Builder 的
 * setBufferSize/setFormat/setParent 为 @hide，经 [OverlayHiddenApi]
 * 反射调用（root 进程 + hidden API 豁免后反射稳定可用）。
 *
 * 所有方法必须在同一线程（服务 handler 线程）调用。
 *
 * @param handler 宿主 handler 线程（本类所有方法必须在该线程调用；
 *   重绘节流的 postDelayed 也回到该线程，保证 canvas 无并发访问）
 * @param onFatal 渲染后端不可恢复错误（须触发回落）
 */
internal class OverlaySurfaceBackend(
    private val handler: Handler,
    private val onFatal: (Throwable) -> Unit
) {

    private companion object {
        /** 帧间隔（~60fps）：canvas 重绘节流目标 */
        const val FRAME_INTERVAL_MS = 16L

        /**
         * Transaction.setBufferSize 的 apply() 返回 ≠ 已生效：BufferQueue
         * 默认尺寸在 vsync 提交期才应用，settle 后立即 lock canvas 可能
         * dequeue 到旧尺寸 buffer。
         *
         * 直接对策（非定时重试，正确性不依赖任何定时器）：绘制始终以
         * canvas 实际尺寸为基准——尺寸未就绪时以 setMatrix 补偿
         * （buffer × matrix = 目标矩形，与 live 缩放同一 GPU 合成路径，
         * 显示尺寸恒正确，仅分辨率暂时为旧 buffer 分辨率）；buffer 就绪
         * 后的下一次绘制自动归一并恢复全分辨率。CATCHUP 延迟重绘只为
         * 主动触发那次"归一绘制"提升分辨率（补偿态显示已正确），
         * 超限放弃即停留在补偿显示，无任何错误帧窗口。
         *
         * 补偿按层独立（content/handle 各自观测自己的 canvas、设置自己
         * 的 matrix）：两层的尺寸传导在不同 vsync 到位，共享单一状态会
         * 在传导交错期产生"已就绪层被拉伸 / 冻结层被归一"的错误帧
         * （图片超范围 / 不占满，单击触发的 settle 重绘即暴露点）。
         *
         * 【A15 SFdump 实测定论】SF 侧实际显示 buffer 的是 BLAST 自动创建
         * 的 bbq-wrapper 子层（parent = 我们的 content layer）。由此两条
         * 路径可靠性截然不同：
         * - 在 content layer 上设置的 matrix 经父子关系传导——可靠
         *   （live 拖动数百帧跟手正常即证明）；
         * - "live 矩阵刚变 + setBufferSize 刚 apply + 首个新尺寸 buffer
         *   提交"三合一窗口内的 buffer/bounds 更新——不可靠：SFdump
         *   证实松手后 700ms 内 activeBuffer/mBounds 停留旧值（提交未被
         *   latch），而 1 秒后的同尺寸提交（单击）正常 latch。
         *
         * 对策（settle 拆两拍，正确性只押注可靠路径）：
         * 第一拍（松手立即，纯 transaction 零 canvas）：以补偿 matrix 维持
         *   live 终态显示——结构上不可能出现溢出/不占满帧；
         * 第二拍（SECOND_BEAT_MS 后）：正统精确化（setBufferSize + 重绘 +
         *   归一）。若其提交又未被 latch，显示不受影响（第一拍矩阵仍在），
         *   追加重绘 + 追赶/重申机制逐步收敛。
         */
        const val CATCHUP_MS = 64L
        const val CATCHUP_MAX = 8

        /** matrix 延迟重申间隔：2 个 vsync，确保晚于 BLAST 事务落地。 */
        const val REASSERT_MS = 34L

        /**
         * settle 第二拍延迟：避开"live 矩阵 + 尺寸 + 新 buffer"三合一的
         * 不可靠提交窗口（实测单击路径——距 live 结束 >1s 的同尺寸提交
         * 可正常 latch）。
         */
        const val SECOND_BEAT_MS = 120L

        /**
         * latch 确认延迟：第二拍首绘的新尺寸提交实测可能长时间不被 SF
         * latch（dump 实证 >150ms），此期间 matrix 冻结在补偿态；届时
         * post 的同尺寸重绘（"必 latch"提交）完成后释放冻结并归一。
         */
        const val LATCH_CONFIRM_MS = 150L
    }

    // ==================== 状态字段 ====================

    private var root: SurfaceControl? = null
    private var contentLayer: SurfaceControl? = null
    private var handleLayer: SurfaceControl? = null

    private var contentSurface: Surface? = null
    private var handleSurface: Surface? = null

    private var width = 0
    private var height = 0
    private var alpha = 1f

    // 图片状态（与原 RootOverlayService 手势逻辑一致）
    private var bitmap: Bitmap? = null
    private var mediaWidth = 0
    private var mediaHeight = 0
    private var currentScale = 1.0f
    private var panX = 0f
    private var panY = 0f
    private var baseScale = 1.0f

    // 视频状态
    private var mediaPlayer: MediaPlayer? = null
    private var videoFd: ParcelFileDescriptor? = null
    private var isMuted = false
    var isVideo = false
        private set

    // 手柄绘制参数（与旧 CornerHandleView 一致）
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        // root 进程无 Resources：以 2.5f 近似 density（主流设备 2~3）
        strokeWidth = 6f * 2.5f
    }
    private val cornerSize = 30f * 2.5f

    // ==================== 生命周期 ====================

    /** 创建三层并显示。任何一步失败（含截图排除失败）即上报回落（调用方 cleanup）。 */
    fun attach(x: Int, y: Int, w: Int, h: Int) {
        if (root != null) {
            setGeometry(x, y, w, h)
            return
        }
        width = w
        height = h
        try {
            // Android 11：创建期 metadata 441731 截图排除（见 builderExcludeScreenshot）；
            // 12+：创建期无需处理，下方 applySkipScreenshot 覆盖
            val rootName = OverlayHiddenApi.randomName()
            val rootBuilder = OverlayHiddenApi.newLayerBuilder(rootName)
            val rootExcluded = OverlayHiddenApi.builderExcludeScreenshot(rootBuilder)
            val rootSc = rootBuilder.build()
            val tx = SurfaceControl.Transaction()
            OverlayHiddenApi.txSetLayer(tx, rootSc, 0x7FFFFFFF)
            OverlayHiddenApi.txSetPosition(tx, rootSc, x, y)
            OverlayHiddenApi.txShow(tx, rootSc)
            tx.apply()

            val contentName = OverlayHiddenApi.randomName()
            val (content, contentExcluded) = buildChildLayer(rootSc, contentName)
            val handleName = OverlayHiddenApi.randomName()
            val (handle, handleExcluded) = buildChildLayer(rootSc, handleName)
            val tx2 = SurfaceControl.Transaction()
            OverlayHiddenApi.txSetBufferSize(tx2, content, w, h)
            OverlayHiddenApi.txSetBufferSize(tx2, handle, w, h)
            OverlayHiddenApi.txSetLayer(tx2, content, 1)
            OverlayHiddenApi.txSetLayer(tx2, handle, 2)
            OverlayHiddenApi.txShow(tx2, content)
            OverlayHiddenApi.txShow(tx2, handle)
            tx2.apply()

            root = rootSc
            contentLayer = content
            handleLayer = handle
            contentSurface = Surface(content)
            handleSurface = Surface(handle)

            // 截图排除是本方案的核心承诺（无痕）：任一层失败即致命。
            // 静默降级会让悬浮窗出现在截图/录屏中，直接违背产品语义且
            // 用户无从感知——上报后由应用侧回落普通悬浮窗路线。
            // Android 11 验证创建期 metadata 路径（逐层独立生效）；
            // 12+ 验证 setSkipScreenshot 路径（两者按版本二选一生效）。
            val exclusionOk = if (android.os.Build.VERSION.SDK_INT >= 31) {
                listOf(rootSc, content, handle).all { OverlayHiddenApi.applySkipScreenshot(it) }
            } else {
                rootExcluded && contentExcluded && handleExcluded
            }
            if (!exclusionOk) {
                onFatal(IllegalStateException("screenshot exclusion unavailable"))
                detach()
                return
            }
            drawHandles()
        } catch (t: Throwable) {
            onFatal(t)
            detach()
        }
    }

    /** 返回 (layer, Android 11 创建期截图排除是否成功)。 */
    private fun buildChildLayer(parent: SurfaceControl, name: String): Pair<SurfaceControl, Boolean> {
        val builder = OverlayHiddenApi.newLayerBuilder(name)
        // Android 11 截图排除对每个 layer 独立生效（capture 遍历逐层过滤）
        val excluded = OverlayHiddenApi.builderExcludeScreenshot(builder)
        OverlayHiddenApi.builderSetFormat(builder, PixelFormat.TRANSLUCENT)
        OverlayHiddenApi.builderSetParent(builder, parent)
        return builder.build() to excluded
    }

    fun detach() {
        cancelPendingRedraw()
        releasePlayer()
        videoFd?.let { runCatching { it.close() } }
        videoFd = null
        bitmap = null
        mediaWidth = 0
        mediaHeight = 0
        liveScaling = false
        contentCompensated = false
        handleCompensated = false
        actualW = 0
        actualH = 0
        handleActualW = 0
        handleActualH = 0
        reassertPending = false
        runCatching { contentSurface?.release() }
        runCatching { handleSurface?.release() }
        contentSurface = null
        handleSurface = null
        contentLayer?.let { runCatching { it.release() } }
        handleLayer?.let { runCatching { it.release() } }
        root?.let { runCatching { it.release() } }
        contentLayer = null
        handleLayer = null
        root = null
        isVideo = false
    }

    val isAttached: Boolean get() = root != null

    // ==================== 重绘节流（resize/pan/缩放手势流畅度） ====================
    //
    // MOVE 事件 ~100Hz 远超 vsync 60Hz：逐事件全量重绘（lock canvas +
    // buffer 重分配 + 全图缩放绘制）必然积压卡顿。几何 Transaction 每
    // 事件立即 apply（便宜，SF 合成器侧处理）；canvas 重绘合并到帧间隔
    // （16ms）内至多一次，尾随 pending 保证最终帧不丢。

    private var redrawPending = false
    private var lastDrawAt = 0L

    // ---- buffer 尺寸滞后补偿（见 companion 文档）----

    /** 最近一次 lock canvas 观察到的实际 buffer 尺寸（live 缩放的冻结基准）。 */
    private var actualW = 0
    private var actualH = 0

    /**
     * content/handle 两层各自的补偿状态（≠ identity）。
     * 必须每层独立跟踪：Android 11 上两层的 setBufferSize 在不同 vsync
     * 传导到位，任一时刻可能一层就绪、另一层仍冻结——共享单一状态会
     * 以"错的那层"的 canvas 观测覆盖"对的那层"的 matrix（已就绪层被
     * 错误拉伸=图片超范围；冻结层被错误归一=图片不占满）。
     */
    private var contentCompensated = false
    private var handleCompensated = false

    /** 手柄层最近观测的 buffer 尺寸（内容层用 actualW/H；重申矩阵按层取值）。 */
    private var handleActualW = 0
    private var handleActualH = 0

    /**
     * latch 冻结（见 scheduleSecondBeat）：true 期间所有绘制只上 buffer、
     * 绝不触碰 matrix——保持第一拍的补偿矩阵，旧 buffer 显示恒正确；
     * 直到"必 latch"的同尺寸重绘提交完成后才放开归一（identity 与新
     * buffer 同一提交窗口落地，消除切换闪变）。
     */
    private var holdMatrix = false

    // 分辨率追赶重绘：补偿期间 buffer 一旦就绪，下一次绘制自动归一；
    // 此任务只是主动触发那次绘制（仅提升分辨率，显示正确性不依赖它）。
    // count 限制总次数——超限说明本 ROM 尺寸永不传导，停留在补偿显示
    // （尺寸仍正确），杜绝无限循环（每个 settle 周期重置预算）
    private var catchupPending = false
    private var catchupCount = 0
    private val catchupRedraw = Runnable {
        catchupPending = false
        if (contentCompensated || handleCompensated) doRedraw()
    }

    private fun scheduleCatchup() {
        if ((!contentCompensated && !handleCompensated) || catchupPending) return
        if (catchupCount >= CATCHUP_MAX) return
        catchupCount++
        catchupPending = true
        handler.postDelayed(catchupRedraw, CATCHUP_MS)
    }

    // ==================== matrix 延迟重申 ====================
    //
    // A15 实测（拖动松手后图片溢出、单击才恢复）锁定的机制：首个"新尺寸
    // buffer 提交"（BLAST）会把 lockCanvas 时刻的层几何烘焙进合成状态，
    // 覆盖我们 post 之后 apply 的归一 matrix——且此时 canvas 已是新尺寸
    // （compensated=false），追赶重绘被跳过，错误状态滞留到下一次任意
    // 绘制（即"单击恢复"）。
    //
    // 对策：每次绘制后 2 个 vsync 延迟重申两层 matrix——此刻 BLAST 事务
    // 已落地、无后续提交再覆盖，重申必然生效（把"单击才能恢复"变成
    // 34ms 内自愈）。live 期间跳过：live 每帧自己设 matrix，天然自愈且
    // 免与本任务交错。纯事务零 canvas 成本，幂等。
    private var reassertPending = false
    private val matrixReassert = Runnable {
        reassertPending = false
        if (root == null || liveScaling) return@Runnable
        reassertMatrices()
        // 第二遍兜底：个别 ROM 的 BLAST 落地晚于 2 vsync
        handler.postDelayed({
            if (root != null && !liveScaling) reassertMatrices()
        }, REASSERT_MS)
    }

    private fun scheduleMatrixReassert() {
        if (reassertPending || root == null) return
        reassertPending = true
        handler.postDelayed(matrixReassert, REASSERT_MS)
    }

    /** 无 canvas 操作地重申两层 matrix（按各层最近观测尺寸重算，幂等）。 */
    private fun reassertMatrices() {
        if (root == null || holdMatrix) return
        val cxs = sxFor(actualW)
        val cys = syFor(actualH)
        val hxs = sxFor(handleActualW)
        val hys = syFor(handleActualH)
        runCatching {
            val tx = SurfaceControl.Transaction()
            contentLayer?.let { OverlayHiddenApi.txSetMatrix(tx, it, cxs, cys) }
            handleLayer?.let { OverlayHiddenApi.txSetMatrix(tx, it, hxs, hys) }
            tx.apply()
        }
    }

    private fun sxFor(cw: Int) = if (cw > 0 && cw != width) width.toFloat() / cw else 1f
    private fun syFor(ch: Int) = if (ch > 0 && ch != height) height.toFloat() / ch else 1f

    // ==================== settle 第二拍调度 ====================
    //
    // 松手第一拍只做纯 transaction 补偿（显示正确性在此锁定），第二拍
    // 延迟 SECOND_BEAT_MS 后走正统精确化（复用 setGeometry 精确路径）。
    // 执行时若用户已开始新拖动（liveScaling）或后端已销毁则跳过——
    // 新手势的松手会重新排程。
    private var secondBeat: Runnable? = null

    private fun scheduleSecondBeat(x: Int, y: Int, w: Int, h: Int) {
        secondBeat?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            secondBeat = null
            if (root == null || liveScaling) return@Runnable
            // 第二拍：正统精确化（setGeometry 精确路径）。holdMatrix=true：
            // 绘制只上 buffer、matrix 保持第一拍补偿——旧 buffer 显示恒
            // 正确，无闪变窗口。
            holdMatrix = true
            setGeometry(x, y, w, h, live = false)
            // 释放任务（实测"首个新尺寸提交可能不被 latch，后续同尺寸
            // 提交必 latch"）：post 一次同尺寸重绘，post 完成后立即放开
            // 冻结——syncMatrixFor 在 unlockCanvasAndPost 之后归一，
            // identity 与新 buffer 同一提交窗口落地，切换无缝
            handler.postDelayed({
                if (root == null || liveScaling) return@postDelayed
                holdMatrix = false
                doRedraw()
            }, LATCH_CONFIRM_MS)
        }
        secondBeat = r
        handler.postDelayed(r, SECOND_BEAT_MS)
    }

    // 尾随重绘任务（持有引用：只移除自己的，绝不动宿主线程其他回调）
    private val trailingRedraw = Runnable {
        redrawPending = false
        doRedraw()
    }

    /** 节流调度一次内容+手柄重绘（手势高频路径）。 */
    private fun scheduleRedraw() {
        if (redrawPending) return
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastDrawAt
        if (elapsed >= FRAME_INTERVAL_MS) {
            doRedraw()
        } else {
            redrawPending = true
            handler.postDelayed(trailingRedraw, FRAME_INTERVAL_MS - elapsed)
        }
    }

    private fun doRedraw() {
        lastDrawAt = SystemClock.uptimeMillis()
        if (isVideo) {
            // 视频生产者按 layer bounds 重新适配
            mediaPlayer?.let { mp -> runCatching { mp.setSurface(contentSurface) } }
        } else {
            drawImage()
        }
        drawHandles()
    }

    private fun cancelPendingRedraw() {
        redrawPending = false
        catchupPending = false
        catchupCount = 0
        reassertPending = false
        holdMatrix = false
        secondBeat?.let { handler.removeCallbacks(it) }
        secondBeat = null
        handler.removeCallbacks(trailingRedraw)
        handler.removeCallbacks(catchupRedraw)
        handler.removeCallbacks(matrixReassert)
    }

    // ==================== 几何 / 外观 ====================

    // live resize 状态：手势期间 buffer 尺寸冻结在 baseW/baseH，
    // SF 合成器 setMatrix 缩放现有 buffer（GPU 路径，零 canvas 操作）。
    // UP 时 settle：buffer 精确尺寸 + 全量重绘；matrix 的归一/补偿由
    // 绘制时的 syncMatrixFor 按 canvas 实际尺寸决定（见 companion 文档）。
    private var liveScaling = false
    private var baseW = 0
    private var baseH = 0

    /**
     * @param live true = resize 手势进行中：仅合成器变换（跟手、零重绘）；
     *   false = 精确几何（外部设置 / 手势结束 settle）
     */
    fun setGeometry(x: Int, y: Int, w: Int, h: Int, live: Boolean = false) {
        val r = root ?: return

        // ---- 手势中：零 canvas 成本的快速路径 ----
        if (live) {
            // 纯移动（MOVE_WINDOW）：尺寸不变，只挪 root position，
            // 不触碰 buffer / matrix / 重绘——单 transaction 完成。
            if (w == width && h == height && !liveScaling) {
                val tx = SurfaceControl.Transaction()
                OverlayHiddenApi.txSetPosition(tx, r, x, y)
                tx.apply()
                return
            }
            // resize：进入/保持 live 缩放。setMatrix 让 SF 在合成期拉伸
            // 现有内容——纯 GPU，跟手。冻结基准 = 最近绘制观测的 buffer
            // 尺寸（actualW/H——若上一轮 settle 的尺寸尚未传导，actual
            // 仍是旧 buffer，补偿态下进入 live 无缝衔接）。稳态下
            // actualW == width（传导完成后两者一致），两值等价；传导
            // 窗口内 buffer 真值只在 actual 里。
            if (!liveScaling) {
                liveScaling = true
                baseW = if (actualW > 0) actualW else width
                baseH = if (actualH > 0) actualH else height
                // 手柄线条会被非等比拉伸变形：live 期间隐藏，settle 恢复
                handleLayer?.let { hl ->
                    val txh = SurfaceControl.Transaction()
                    OverlayHiddenApi.txSetAlpha(txh, hl, 0f)
                    txh.apply()
                }
            }
            if (baseW > 0 && baseH > 0) {
                val tx = SurfaceControl.Transaction()
                OverlayHiddenApi.txSetPosition(tx, r, x, y)
                // 以 root 原点（窗口左上角）为锚点向右下扩展——四角缩放
                // 统一为"新矩形左上角 + 现有 buffer 拉伸到新宽高"
                contentLayer?.let {
                    OverlayHiddenApi.txSetMatrix(tx, it, w.toFloat() / baseW, h.toFloat() / baseH)
                }
                tx.apply()
            }
            return
        }

        // ---- 精确路径（外部设置 / settle / 第二拍）----
        // live 结束：matrix 不在此归一——buffer 可能仍是冻结尺寸（Android 11
        // 的 setBufferSize 传导竞态，见 companion 文档），立即归一会得到
        // "旧尺寸内容 + identity"的错误帧。归一交由 syncMatrixFor 在绘制时
        // 确认 canvas 尺寸就绪后执行；期间以补偿 matrix 维持目标显示尺寸。
        val wasLive = liveScaling
        if (liveScaling) {
            liveScaling = false
            handleLayer?.let { hl ->
                val txh = SurfaceControl.Transaction()
                OverlayHiddenApi.txSetAlpha(txh, hl, 1f)
                txh.apply()
            }
        }
        width = w
        height = h

        // ---- settle 第一拍（松手立即，仅非视频）----
        // 纯 transaction 零 canvas：以补偿 matrix 维持 live 终态显示。
        // SFdump 实测"live 矩阵 + 尺寸 + 新 buffer"三合一提交不可靠
        // （见 companion 文档），故松手瞬间绝不做 setBufferSize / 绘制——
        // 溢出/不占满帧在结构上不可能出现。正统精确化推迟到第二拍。
        if (wasLive && !isVideo) {
            // 新手势的 settle：清掉上一轮可能残留的 latch 冻结（beat1 直接
            // 设 matrix，不受 hold 影响；hold 由第二拍重新置位）
            holdMatrix = false
            // 补偿基准 = 各层最近绘制观测的 buffer 尺寸（两层独立）
            val cbW = if (actualW > 0) actualW else w
            val cbH = if (actualH > 0) actualH else h
            val hbW = if (handleActualW > 0) handleActualW else w
            val hbH = if (handleActualH > 0) handleActualH else h
            runCatching {
                val tx1 = SurfaceControl.Transaction()
                OverlayHiddenApi.txSetPosition(tx1, r, x, y)
                contentLayer?.let {
                    OverlayHiddenApi.txSetMatrix(tx1, it, w.toFloat() / cbW, h.toFloat() / cbH)
                }
                handleLayer?.let {
                    OverlayHiddenApi.txSetMatrix(tx1, it, w.toFloat() / hbW, h.toFloat() / hbH)
                }
                tx1.apply()
            }
            // 新的尺寸变更事件：重置分辨率追赶预算（第二拍路径共用）
            catchupCount = 0
            scheduleSecondBeat(x, y, w, h)
            return
        }
        // 新的尺寸变更事件：重置分辨率追赶预算（见 scheduleCatchup）
        catchupCount = 0
        val tx = SurfaceControl.Transaction()
        OverlayHiddenApi.txSetPosition(tx, r, x, y)
        contentLayer?.let { OverlayHiddenApi.txSetBufferSize(tx, it, w, h) }
        handleLayer?.let { OverlayHiddenApi.txSetBufferSize(tx, it, w, h) }
        if (isVideo) {
            // 视频内容层无 lockCanvas 观测点（解码器持有 buffer 队列），
            // 不能走 syncMatrixFor 每层补偿：live 残留的拉伸 matrix 在
            // buffer 传导到 (w,h) 后会变成双重缩放（视频溢出）。settle
            // 即归一 identity——传导窗口内（1-2 vsync）短暂以冻结尺寸
            // 显示，随后解码器按新几何自动重适配（setSurface 触发）
            contentLayer?.let { OverlayHiddenApi.txSetMatrix(tx, it, 1f, 1f) }
        }
        tx.apply()
        if (isVideo) {
            mediaPlayer?.let { mp -> runCatching { mp.setSurface(contentSurface) } }
        } else {
            // 不预归一：v3 实测 identity 落地早于新 buffer latch（可滞后
            // 100~200ms），期间"旧 buffer × identity"= 缩小闪变。settle
            // 路径的 matrix 由 holdMatrix 机制在 latch 确认后统一归一
            // （见 scheduleSecondBeat）；非 settle 的精确调用沿用
            // syncMatrixFor 的 post 后归一（原行为）。
            drawImage()
        }
        drawHandles()
    }

    /** 手势结束（ACTION_UP/CANCEL）时由 controller 调用：精确化最终帧。 */
    fun settleGeometry(x: Int, y: Int, w: Int, h: Int) {
        setGeometry(x, y, w, h, live = false)
    }

    /** 中途切换媒体等场景：结束 live 状态（matrix 由下一次绘制统一归一/补偿）。 */
    private fun resetLiveScale() {
        if (!liveScaling) return
        liveScaling = false
        runCatching {
            val tx = SurfaceControl.Transaction()
            handleLayer?.let { OverlayHiddenApi.txSetAlpha(tx, it, 1f) }
            tx.apply()
        }
    }

    fun setAlpha(a: Float) {
        alpha = a.coerceIn(0f, 1f)
        val r = root ?: return
        val tx = SurfaceControl.Transaction()
        OverlayHiddenApi.txSetAlpha(tx, r, alpha)
        tx.apply()
    }

    // ==================== 图片渲染 ====================

    fun showImage(bm: Bitmap?) {
        resetLiveScale()
        releasePlayer()
        videoFd = null
        isVideo = false
        bitmap = bm
        mediaWidth = bm?.width ?: 0
        mediaHeight = bm?.height ?: 0
        currentScale = 1.0f
        panX = 0f
        panY = 0f
        baseScale = 1.0f
        drawImage()
        // 补偿态可能残留（live 中途切换）：同步手柄层 matrix 并触发追赶
        drawHandles()
    }

    fun scaleImage(factor: Float) {
        if (bitmap == null) return
        var newScale = currentScale * factor
        newScale = maxOf(newScale, 1.0f)
        if (newScale > 5.0f) return
        currentScale = newScale
        clampPan()
        scheduleRedraw()
    }

    fun panImage(dx: Float, dy: Float) {
        if (bitmap == null) return
        panX += dx
        panY += dy
        clampPan()
        scheduleRedraw()
    }

    /**
     * 以本层最近绘制观测的 buffer 尺寸为准同步**该层自己的** matrix
     * （buffer 尺寸滞后补偿，见 companion 文档）：
     * - 观测尺寸未就绪：matrix = 目标/实际，SF 合成期 GPU 拉伸到目标
     *   矩形——与 live 缩放同一路径，显示尺寸恒正确（仅分辨率暂旧）；
     * - 观测尺寸就绪：matrix 归一，恢复全分辨率。
     *
     * 【必须在 unlockCanvasAndPost 之后调用】lockHardwareCanvas 的提交
     * 经 HWUI/BLAST 自带事务上送 buffer，会覆盖同一提交窗口内先行
     * apply 的几何状态——live 路径（无 canvas 绘制）matrix 存活而
     * settle 绘制路径 matrix 失效，两组症状的差异即源于此
     * （A15 SFdump 实测确认）。
     *
     * 每层独立是正确性前提：两层的尺寸传导在不同 vsync 到位，共享状态
     * 会在传导交错期以"错的那层"的观测覆盖"对的那层"的 matrix。
     *
     * @param isHandle true = 手柄层；false = 内容层（内容层同步刷新
     *   actualW/H——live 缩放的冻结基准取自内容层）
     * @return true = 该层当前处于补偿态（观测尺寸 ≠ 目标）
     */
    private fun syncMatrixFor(cw: Int, ch: Int, layer: SurfaceControl?, isHandle: Boolean): Boolean {
        // latch 冻结：只记账（刷新观测尺寸），不碰 matrix、不排追赶——
        // 第一拍补偿矩阵保持生效，旧 buffer 显示恒正确；归一由释放任务
        // 在"必 latch"的同尺寸重绘 post 之后执行（post→identity 同窗口）
        if (holdMatrix) {
            if (isHandle) {
                handleActualW = cw
                handleActualH = ch
                handleCompensated = true
            } else {
                actualW = cw
                actualH = ch
                contentCompensated = true
            }
            return false
        }
        if (isHandle) {
            handleActualW = cw
            handleActualH = ch
            handleCompensated = cw != width || ch != height
        } else {
            actualW = cw
            actualH = ch
            if (cw == width && ch == height) catchupCount = 0
            contentCompensated = cw != width || ch != height
        }
        val sx = sxFor(cw)
        val sy = syFor(ch)
        // 恒定设置（含归一）：幂等无成本；仅在该层真正需要变化时才有
        // 视觉差异
        runCatching {
            val tx = SurfaceControl.Transaction()
            layer?.let { OverlayHiddenApi.txSetMatrix(tx, it, sx, sy) }
            tx.apply()
        }
        // 首个新尺寸 buffer 提交可能烘焙 lockCanvas 时刻几何覆盖本设置：
        // 排一次延迟重申兜底（见 matrixReassert 文档）
        scheduleMatrixReassert()
        return if (isHandle) handleCompensated else contentCompensated
    }

    private fun drawImage() {
        val bm = bitmap ?: run {
            // 无媒体：红底提示（与原实现一致）
            contentSurface?.let { s ->
                runCatching {
                    val canvas = s.lockHardwareCanvas()
                    canvas.drawColor(Color.RED)
                    s.unlockCanvasAndPost(canvas)
                }
            }
            return
        }
        val surface = contentSurface ?: return
        if (mediaWidth <= 0 || mediaHeight <= 0 || width <= 0 || height <= 0) return
        runCatching {
            val canvas = surface.lockHardwareCanvas()
            val cw = canvas.width
            val ch = canvas.height
            // 按实际 buffer 尺寸绘制；matrix 必须在 post 之后设置
            // （HWUI 提交事务会覆盖先设的几何状态，见 syncMatrixFor）
            drawBitmapFit(canvas, bm, cw, ch)
            surface.unlockCanvasAndPost(canvas)
            val compensated = syncMatrixFor(cw, ch, contentLayer, isHandle = false)
            if (compensated) scheduleCatchup()
        }
    }

    /**
     * 窗口矩形 (w,h) 内中心填充绘制（w/h 恒为 canvas 实际尺寸，补偿态下
     * 由 matrix 保证最终显示尺寸）。pan 的 clamp 按 (w,h) 局部计算
     * （不回写 panX/panY——补偿帧仅存活到 buffer 尺寸就绪，
     * 无需污染手势状态）。
     */
    private fun drawBitmapFit(canvas: Canvas, bm: Bitmap, w: Int, h: Int) {
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        val scaleX = w.toFloat() / mediaWidth
        val scaleY = h.toFloat() / mediaHeight
        baseScale = maxOf(scaleX, scaleY)
        val finalScale = baseScale * currentScale
        val scaledW = mediaWidth * finalScale
        val scaledH = mediaHeight * finalScale
        val px = if (scaledW > w) panX.coerceIn(-(scaledW - w) / 2f, (scaledW - w) / 2f) else 0f
        val py = if (scaledH > h) panY.coerceIn(-(scaledH - h) / 2f, (scaledH - h) / 2f) else 0f
        val matrix = Matrix().apply {
            setScale(finalScale, finalScale)
            postTranslate((w - scaledW) / 2f + px, (h - scaledH) / 2f + py)
        }
        canvas.drawBitmap(bm, matrix, null)
    }

    private fun clampPan() {
        if (mediaWidth <= 0 || mediaHeight <= 0 || width <= 0 || height <= 0) return
        val finalScale = baseScale * currentScale
        val scaledW = mediaWidth * finalScale
        val scaledH = mediaHeight * finalScale
        panX = if (scaledW > width) panX.coerceIn(-(scaledW - width) / 2, (scaledW - width) / 2) else 0f
        panY = if (scaledH > height) panY.coerceIn(-(scaledH - height) / 2, (scaledH - height) / 2) else 0f
    }

    // ==================== 手柄绘制（与旧 CornerHandleView 一致） ====================

    private fun drawHandles() {
        val surface = handleSurface ?: return
        runCatching {
            val canvas: Canvas = surface.lockHardwareCanvas()
            val cw = canvas.width
            val ch = canvas.height
            // 与 drawImage 同理：以实际 buffer 尺寸绘制，滞后时由补偿
            // matrix 保证显示位置（四角标记天然适配任意 canvas 尺寸）。
            // 手柄层独立补偿——其 buffer 传导时序与内容层无关；
            // matrix 在 post 之后设置（见 syncMatrixFor）
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            val w = cw.toFloat()
            val h = ch.toFloat()
            val size = cornerSize
            canvas.drawLine(0f, 0f, size, 0f, handlePaint)
            canvas.drawLine(0f, 0f, 0f, size, handlePaint)
            canvas.drawLine(w - size, 0f, w, 0f, handlePaint)
            canvas.drawLine(w, 0f, w, size, handlePaint)
            canvas.drawLine(0f, h - size, 0f, h, handlePaint)
            canvas.drawLine(0f, h, size, h, handlePaint)
            canvas.drawLine(w - size, h, w, h, handlePaint)
            canvas.drawLine(w, h - size, w, h, handlePaint)
            val centerX = w / 2
            canvas.drawLine(centerX - size, 0f, centerX + size, 0f, handlePaint)
            surface.unlockCanvasAndPost(canvas)
            val compensated = syncMatrixFor(cw, ch, handleLayer, isHandle = true)
            if (compensated) scheduleCatchup()
        }
    }

    // ==================== 视频渲染 ====================

    fun showVideo(fd: ParcelFileDescriptor) {
        resetLiveScale()
        clearMedia()
        isVideo = true
        videoFd = fd
        val surface = contentSurface ?: run {
            runCatching { fd.close() }
            videoFd = null
            return
        }
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(fd.fileDescriptor)
                setSurface(surface)
                setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                isLooping = true
                setOnPreparedListener {
                    applyMuteState()
                    start()
                }
                setOnErrorListener { _, _, _ -> false }
                prepare()
            }
        } catch (_: Exception) {
            releasePlayer()
        }
    }

    fun clearMedia() {
        releasePlayer()
        videoFd?.let { runCatching { it.close() } }
        videoFd = null
        isVideo = false
        bitmap = null
        mediaWidth = 0
        mediaHeight = 0
        currentScale = 1.0f
        panX = 0f
        panY = 0f
    }

    private fun releasePlayer() {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    private fun applyMuteState() {
        mediaPlayer?.setVolume(if (isMuted) 0f else 1f, if (isMuted) 0f else 1f)
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        applyMuteState()
    }

    // ==================== 视频控制 ====================

    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        runCatching {
            if (mp.isPlaying) mp.pause() else mp.start()
        }
    }

    fun seekBy(deltaMs: Int) {
        val mp = mediaPlayer ?: return
        runCatching {
            val duration = mp.duration
            if (duration > 0) {
                val target = (mp.currentPosition + deltaMs)
                    .coerceIn(0, (duration - 250).coerceAtLeast(0))
                mp.seekTo(target)
            }
        }
    }
}
