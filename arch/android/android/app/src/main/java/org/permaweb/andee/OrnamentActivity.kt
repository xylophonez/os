package org.permaweb.andee

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class OrnamentActivity : Activity() {
    private lateinit var ornamentView: OrnamentView
    private var batteryOptimizationPrompted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        startForegroundService(Intent(this, AndeeService::class.java))
        ornamentView = OrnamentView(
            this,
            object : OrnamentView.Actions {
                override fun pickNextBootConfig() = this@OrnamentActivity.pickNextBootConfig()
                override fun terminate() = this@OrnamentActivity.terminateApp()
            },
        )
        setContentView(ornamentView)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        startForegroundService(Intent(this, AndeeService::class.java))
        ornamentView.resumePresentation()
        hideSystemBars()
    }

    override fun onPause() {
        ornamentView.pausePresentation()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.insetsController?.hide(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    @Deprecated("Deprecated by Activity; used here to avoid adding an AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BOOT_CONFIG || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val text = readPickedText(uri)
            AndeeBootConfigStore.stageNextBootConfig(this, text)
            ornamentView.setNextBootConfigPending(true)
            hideSystemBars()
        } catch (exc: Exception) {
            Log.e(TAG, "failed to import next boot config", exc)
        }
    }

    private fun pickNextBootConfig() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/json", "text/json", "text/plain"),
            )
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }
        startActivityForResult(intent, REQUEST_BOOT_CONFIG)
    }

    private fun readPickedText(uri: Uri): String {
        return contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IllegalArgumentException("selected config was not readable: $uri")
    }

    private fun terminateApp() {
        try {
            AndeeBootConfigStore.commitPendingForNextBoot(this)
            ornamentView.setNextBootConfigPending(false)
        } catch (exc: Exception) {
            Log.e(TAG, "failed to commit pending boot config before terminate", exc)
            return
        }
        startForegroundService(
            Intent(this, AndeeService::class.java)
                .setAction(AndeeService.ACTION_TERMINATE),
        )
        finishAndRemoveTask()
    }



    companion object {
        private const val TAG = "OrnamentActivity"
        private const val REQUEST_BOOT_CONFIG = 7342
    }
}

private class OrnamentView(
    context: android.content.Context,
    private val actions: Actions,
) : View(context) {
    interface Actions {
        fun pickNextBootConfig()
        fun terminate()
    }

    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val nodeStatus = AtomicReference(NodeStatus.booting())
    private val nextBootConfigPending = AtomicBoolean(AndeeBootConfigStore.hasPending(context))
    private val presentationActive = AtomicBoolean(false)
    private val pollerRunning = AtomicBoolean(false)
    private var pollerThread: Thread? = null
    private var animationFramePending = false
    private var startedAt = System.nanoTime()
    private var logoRevealStartedAt = 0L
    private var configTouchActive = false
    private var cachedQrUrl = ""
    private var cachedQrRows = QrV2L.rowsFor("http://<node>:8734/")
    private var qrBitmap: Bitmap? = null
    private var qrBitmapStatus: NodeStatus? = null
    private var qrBitmapPending = false
    private val configButtonBounds = RectF()
    private val clipBounds = Rect()
    private val clipBoundsF = RectF()
    private val animationDirty = Rect()
    private val ambientBounds = RectF()

    private var layoutWidth = 0
    private var layoutHeight = 0
    private var greeterTextSize = 0f
    private var greeterLineHeight = 0f
    private var greeterCellWidth = 0f
    private var greeterMargin = 0f
    private var greeterTopMargin = 0f
    private var greeterBottom = 0f
    private var ambientCodeTextSize = 0f
    private var ambientSparkleTextSize = 0f
    private val qrDrawBounds = RectF()
    private var phoneTextSize = 0f
    private var phoneCellW = 0f
    private var phoneLineHeight = 0f
    private var phoneCols = 0
    private var phoneRows = 0
    private var phoneOriginX = 0f
    private var phoneOriginY = 0f
    private var phoneCenterCol = 0f
    private var phoneCenterRow = 0f
    private var phoneScale = 0f
    private var phoneAspect = 0f
    private val phonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = mono
        isSubpixelText = true
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = mono
        isSubpixelText = true
    }
    private val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = mono
        isSubpixelText = true
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(91, 238, 255)
        typeface = mono
        isSubpixelText = true
    }
    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private data class Vec3(val x: Float, val y: Float, val z: Float)
    private data class Edge(val a: Vec3, val b: Vec3, val accent: Boolean)

    private fun rotateY(v: Vec3, yaw: Float): Vec3 {
        val ca = cos(yaw)
        val sa = sin(yaw)
        return Vec3(v.x * ca + v.z * sa, v.y, -v.x * sa + v.z * ca)
    }

    // Orthographic tilt projection to grid-cell coordinates. The aspect value
    // compensates for non-square Android monospace glyph cells.
    private fun projectToCell(
        v: Vec3,
        centerCol: Float,
        centerRow: Float,
        scale: Float,
        aspect: Float,
        tilt: Float,
    ): IntArray {
        val yt = v.y * cos(tilt) - v.z * sin(tilt)
        val col = (centerCol + v.x * scale).roundToInt()
        val row = (centerRow - yt * scale * aspect).roundToInt()
        return intArrayOf(col, row)
    }

    private fun pickLineChar(dx: Int, dy: Int): Char = when {
        dy.toLong() * 2 < dx.toLong() -> '-'
        dx.toLong() * 2 < dy.toLong() -> '|'
        (dx > 0) == (dy > 0) -> '\\'
        else -> '/'
    }

    private fun plotLine(grid: CharArray, cols: Int, rows: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        val ch = pickLineChar(abs(x1 - x0), abs(y1 - y0))
        var x = x0
        var y = y0
        val dx = abs(x1 - x0)
        val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            setCell(grid, cols, rows, x, y, ch)
            if (x == x1 && y == y1) {
                setCell(grid, cols, rows, x, y, '+')
                return
            }
            val e2 = 2 * err
            val nx: Int
            val ny: Int
            if (e2 >= dy) {
                nx = x + sx
                err += dy
            } else {
                nx = x
            }
            if (e2 <= dx) {
                ny = y + sy
                err += dx
            } else {
                ny = y
            }
            x = nx
            y = ny
        }
    }

    private fun setCell(grid: CharArray, cols: Int, rows: Int, col: Int, row: Int, ch: Char) {
        if (col < 0 || col >= cols || row < 0 || row >= rows) return
        grid[row * cols + col] = ch
    }

    private val animationTick = object : Runnable {
        override fun run() {
            animationFramePending = false
            if (!presentationActive.get()) return
            invalidateAmbient()
            scheduleAnimationFrame()
        }
    }

    fun setNextBootConfigPending(pending: Boolean) {
        if (nextBootConfigPending.getAndSet(pending) != pending) {
            qrBitmapStatus = null
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resumePresentation()
    }

    override fun onDetachedFromWindow() {
        pausePresentation()
        super.onDetachedFromWindow()
    }

    fun resumePresentation() {
        startedAt = System.nanoTime()
        logoRevealStartedAt = startedAt
        configTouchActive = false
        if (presentationActive.compareAndSet(false, true)) startPoller()
        invalidate()
        scheduleAnimationFrame()
    }

    fun pausePresentation() {
        presentationActive.set(false)
        removeCallbacks(animationTick)
        animationFramePending = false
        stopPoller()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        layoutWidth = 0
        layoutHeight = 0
        qrBitmap?.recycle()
        qrBitmap = null
    }

    private fun startPoller() {
        if (!pollerRunning.compareAndSet(false, true)) return
        pollerThread = Thread(::pollNode, "AndEE-Ornament-Probe").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopPoller() {
        pollerRunning.set(false)
        pollerThread?.interrupt()
        pollerThread = null
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        ensureLayout()
        if (!canvas.getClipBounds(clipBounds)) {
            clipBounds.set(0, 0, width, height)
        }
        clipBoundsF.set(clipBounds)

        val status = nodeStatus.get()
        drawBackground(canvas)
        if (Rect.intersects(clipBounds, animationDirty)) {
            drawAmbient(canvas)
            drawPhone(canvas)
        }
        if (clipBounds.top < greeterBottom) drawGreeter(canvas)
        if (RectF.intersects(qrDrawBounds, clipBoundsF)) drawQr(canvas, status)
    }

    private fun pollNode() {
        while (presentationActive.get() && pollerRunning.get()) {
            val status = NodeProbe.snapshot()
            val pending = AndeeBootConfigStore.hasPending(context)
            post {
                var changed = false
                if (nodeStatus.get() != status) {
                    nodeStatus.set(status)
                    qrBitmapStatus = null
                    changed = true
                }
                if (nextBootConfigPending.get() != pending) {
                    nextBootConfigPending.set(pending)
                    qrBitmapStatus = null
                    changed = true
                }
                if (changed) invalidate()
            }
            try {
                Thread.sleep(if (status.ready && status.ip != null) STEADY_POLL_MS else BOOT_POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun scheduleAnimationFrame() {
        if (animationFramePending || !presentationActive.get() || !isAttachedToWindow) return
        animationFramePending = true
        postDelayed(animationTick, ANIMATION_FRAME_MS)
    }

    private fun invalidateAmbient() {
        if (width <= 0 || height <= 0) {
            invalidate()
            return
        }
        ensureLayout()
        postInvalidateOnAnimation(
            animationDirty.left,
            animationDirty.top,
            animationDirty.right,
            animationDirty.bottom,
        )
    }

    private fun drawBackground(canvas: Canvas) {
        canvas.drawColor(BACKGROUND)
    }

    private fun ensureLayout() {
        if (layoutWidth == width && layoutHeight == height) return
        layoutWidth = width
        layoutHeight = height
        greeterMargin = dp(22f)
        greeterTopMargin = dp(40f)
        var size = (width / 58f).coerceIn(dp(10f), dp(21f))
        textPaint.typeface = mono
        textPaint.textSize = size
        var cellWidth = measureLogoCellWidth(size)
        var maxLine = cellWidth * logoColumnCount()
        val available = width - greeterMargin * 2f
        if (maxLine > available) {
            size *= available / maxLine
            textPaint.textSize = size
            cellWidth = measureLogoCellWidth(size)
        }
        greeterTextSize = size
        greeterLineHeight = size * 1.08f
        greeterCellWidth = cellWidth
        greeterBottom = greeterTopMargin + size + HYPERBEAM_GREETER.size * greeterLineHeight + size * 0.55f

        val moduleCount = cachedQrRows.size
        val target = min(width * 0.34f, height * 0.16f).coerceAtLeast(dp(132f))
        val modulePx = max(3, floor(target / moduleCount).toInt())
        val qrSize = (modulePx * moduleCount).toFloat()
        val qrY = height - qrSize - dp(126f)

        ambientCodeTextSize = (width / 36f).coerceIn(dp(12f), dp(24f))
        ambientSparkleTextSize = (width / 34f).coerceIn(dp(14f), dp(30f))
        ambientBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        animationDirty.set(0, 0, width, height)
        val qrX = greeterMargin
        qrDrawBounds.set(0f, qrY - dp(28f), width.toFloat(), qrY + qrSize + dp(74f))

        val phoneTop = greeterBottom + dp(8f)
        val phoneBottom = (qrDrawBounds.top - dp(20f)).coerceAtLeast(phoneTop + dp(80f))
        val phoneAreaH = phoneBottom - phoneTop
        phoneTextSize = (width / 44f).coerceIn(dp(9f), dp(17f))
        phonePaint.typeface = mono
        phonePaint.textSize = phoneTextSize
        phoneCellW = phonePaint.measureText("M").coerceAtLeast(1f)
        phoneLineHeight = phoneTextSize
        phoneCols = ((width - greeterMargin * 2f) / phoneCellW).toInt().coerceAtLeast(20)
        phoneRows = (phoneAreaH / phoneLineHeight).toInt().coerceAtLeast(12)
        phoneOriginX = greeterMargin
        phoneOriginY = phoneTop
        phoneCenterCol = (width / 2f - phoneOriginX) / phoneCellW
        phoneCenterRow = (phoneAreaH / 2f) / phoneLineHeight
        phoneScale = min(
            phoneCols * 1.35f / (2f * PHONE_HALF_WIDTH),
            phoneRows * 1.20f / (2f * PHONE_HALF_HEIGHT),
        )
        phoneAspect = phoneCellW / phoneLineHeight
    }

    private fun drawGreeter(canvas: Canvas) {
        textPaint.typeface = mono
        textPaint.textSize = greeterTextSize
        textPaint.clearShadowLayer()
        drawExactLogo(canvas, 1f)
    }

    private fun drawAmbient(canvas: Canvas) {
        val elapsed = elapsedSeconds()
        val frame = floor(elapsed * BEAM_FPS).toInt()
        val progress = logoRevealProgress()
        drawLogoPassingWords(canvas, ambientBounds, elapsed, frame)
        drawAmbientFuzz(canvas, ambientBounds, elapsed, frame, progress)
    }

    private fun drawPhone(canvas: Canvas) {
        if (phoneCols <= 0 || phoneRows <= 0) return
        val fade = (elapsedSeconds() / PHONE_FADE_SECONDS).coerceIn(0f, 1f)
        val yaw = elapsedSeconds() * PHONE_YAW_RATE
        val cols = phoneCols
        val rows = phoneRows
        val grid = CharArray(cols * rows) { ' ' }
        for (edge in PHONE_EDGES) {
            val a = projectToCell(
                rotateY(edge.a, yaw),
                phoneCenterCol,
                phoneCenterRow,
                phoneScale,
                phoneAspect,
                PHONE_TILT,
            )
            val b = projectToCell(
                rotateY(edge.b, yaw),
                phoneCenterCol,
                phoneCenterRow,
                phoneScale,
                phoneAspect,
                PHONE_TILT,
            )
            plotLine(grid, cols, rows, a[0], a[1], b[0], b[1])
        }

        phonePaint.typeface = mono
        phonePaint.isFakeBoldText = false
        phonePaint.textSize = phoneTextSize
        val fm = phonePaint.fontMetrics
        val baseline = phoneOriginY - fm.ascent
        phonePaint.color = withAlpha(Color.WHITE, fade)
        drawPhoneGrid(canvas, grid, cols, rows, baseline, phonePaint)

        val accentGrid = CharArray(cols * rows) { ' ' }
        for (edge in PHONE_EDGES) {
            if (!edge.accent) continue
            val a = projectToCell(
                rotateY(edge.a, yaw),
                phoneCenterCol,
                phoneCenterRow,
                phoneScale,
                phoneAspect,
                PHONE_TILT,
            )
            val b = projectToCell(
                rotateY(edge.b, yaw),
                phoneCenterCol,
                phoneCenterRow,
                phoneScale,
                phoneAspect,
                PHONE_TILT,
            )
            plotLine(accentGrid, cols, rows, a[0], a[1], b[0], b[1])
        }

        accentPaint.typeface = mono
        accentPaint.isFakeBoldText = false
        accentPaint.textSize = phoneTextSize
        accentPaint.color = withAlpha(Color.rgb(91, 238, 255), fade)
        drawPhoneGrid(canvas, accentGrid, cols, rows, baseline, accentPaint)
    }

    private fun drawPhoneGrid(canvas: Canvas, grid: CharArray, cols: Int, rows: Int, baseline: Float, paint: Paint) {
        for (row in 0 until rows) {
            val rowBase = baseline + row * phoneLineHeight
            for (col in 0 until cols) {
                val ch = grid[row * cols + col]
                if (ch == ' ') continue
                canvas.drawText(ch.toString(), phoneOriginX + col * phoneCellW, rowBase, paint)
            }
        }
    }

    private fun elapsedSeconds(): Float =
        ((System.nanoTime() - startedAt) / 1_000_000_000.0).toFloat()



    private fun drawExactLogo(canvas: Canvas, alpha: Float) {
        textPaint.typeface = mono
        textPaint.textSize = greeterTextSize
        textPaint.color = withAlpha(Color.WHITE, alpha)
        textPaint.clearShadowLayer()
        val charWidth = logoCharWidth()
        for (row in HYPERBEAM_GREETER.indices) {
            val y = greeterTopMargin + greeterTextSize + row * greeterLineHeight
            val line = HYPERBEAM_GREETER[row]
            for (col in line.indices) {
                val ch = line[col]
                if (ch == ' ') continue
                canvas.drawText(ch.toString(), greeterMargin + col * charWidth, y, textPaint)
            }
        }
    }

    private fun drawAmbientFuzz(canvas: Canvas, area: RectF, elapsed: Float, frame: Int, progress: Float) {
        val top = greeterBottom + dp(10f)
        val bottom = (qrDrawBounds.top - dp(28f)).coerceAtLeast(top + dp(80f))

        codePaint.typeface = mono
        codePaint.isFakeBoldText = true
        for (i in 0 until BEAM_PARTICLE_COUNT) {
            val speed = 0.020f + hashUnit(i, 0, 0, 71) * 0.082f
            val orbit = elapsed * speed + hashUnit(i, 0, 0, 72) * 6.28318f
            val x = area.left + area.width() * hashUnit(i, 0, 0, 73) +
                sin(orbit + i) * area.width() * (0.012f + hashUnit(i, 0, 0, 74) * 0.026f)
            val y = top + (bottom - top) * hashUnit(i, 0, 0, 75) +
                sin(orbit * (1.4f + hashUnit(i, 0, 0, 76)) + i) * dp(18f)
            val ch = LOGO_FUZZ_CHARS[(hashUnit(i, frame, 0, 77) * LOGO_FUZZ_CHARS.size)
                .toInt()
                .coerceIn(0, LOGO_FUZZ_CHARS.lastIndex)]
            val alpha = (0.035f + hashUnit(i, frame, 0, 78) * 0.10f) * (1f - progress * 0.36f)
            codePaint.textSize = (ambientSparkleTextSize * (0.36f + hashUnit(i, 0, 0, 79) * 0.26f))
                .coerceIn(dp(6f), dp(14f))
            codePaint.color = withAlpha(BEAM_COLORS[(i + frame / 4) % BEAM_COLORS.size], alpha)
            canvas.drawText(ch.toString(), x, y, codePaint)
        }
        codePaint.isFakeBoldText = false
    }

    private fun drawLogoPassingWords(canvas: Canvas, area: RectF, elapsed: Float, frame: Int) {
        val previousSize = codePaint.textSize
        val previousBold = codePaint.isFakeBoldText
        codePaint.textSize = (ambientCodeTextSize * 0.58f).coerceIn(dp(8f), dp(13f))
        codePaint.isFakeBoldText = true

        val verticalTop = greeterBottom + dp(22f)
        val verticalBottom = (qrDrawBounds.top - dp(48f)).coerceAtLeast(verticalTop + dp(120f))
        val travelWidth = area.width() + dp(210f)
        for (i in BEAM_WORDS.indices) {
            val word = BEAM_WORDS[i]
            val speed = BEAM_WORD_SPEEDS[i % BEAM_WORD_SPEEDS.size]
            val phase = fract(elapsed * speed + i * 0.231f)
            val wordWidth = codePaint.measureText(word)
            val x = if (i % 2 == 0) {
                area.right + dp(64f) - phase * (travelWidth + wordWidth)
            } else {
                area.left - wordWidth - dp(64f) + phase * (travelWidth + wordWidth)
            }
            val yBand = fract(i * 0.41421356f + 0.16f)
            val y = verticalTop + (verticalBottom - verticalTop) * yBand +
                sin(elapsed * (0.11f + i * 0.013f) + i) * dp(7f)
            val alpha = (burstAlpha(phase) * 0.10f).coerceIn(0.01f, 0.10f)
            codePaint.color = withAlpha(BEAM_COLORS[(i + frame / 5) % BEAM_COLORS.size], alpha)
            canvas.drawText(word, x, y, codePaint)
        }

        codePaint.textSize = previousSize
        codePaint.isFakeBoldText = previousBold
    }



    private fun logoRevealProgress(): Float {
        val start = logoRevealStartedAt
        if (start == 0L) return 0f
        val seconds = ((System.nanoTime() - start) / 1_000_000_000.0).toFloat()
        return easeInOut((seconds / LOGO_REVEAL_SECONDS).coerceIn(0f, 1f))
    }

    private fun logoCharWidth(): Float {
        if (greeterCellWidth > 0f) return greeterCellWidth
        textPaint.typeface = mono
        textPaint.textSize = greeterTextSize
        return textPaint.measureText("M").coerceAtLeast(1f)
    }

    private fun logoWidth(): Float {
        return logoCharWidth() * logoColumnCount()
    }

    private fun logoColumnCount(): Int =
        HYPERBEAM_GREETER.maxOf { it.length }

    private fun measureLogoCellWidth(size: Float): Float {
        textPaint.typeface = mono
        textPaint.textSize = size
        var width = textPaint.measureText("M")
        for (line in HYPERBEAM_GREETER) {
            for (ch in line) {
                if (ch != ' ') width = max(width, textPaint.measureText(ch.toString()))
            }
        }
        return width.coerceAtLeast(1f)
    }

    private fun beamColor(row: Int, col: Int, frame: Int): Int {
        val band = Math.floorMod((row * 3.2f + col * 1.45f + frame * 0.18f).toInt(), BEAM_COLORS.size)
        return BEAM_COLORS[band]
    }

    private fun easeInOut(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun hashUnit(a: Int, b: Int, c: Int, salt: Int): Float {
        val value = sin(a * 12.9898f + b * 78.233f + c * 37.719f + salt * 19.19f) * 43758.5453f
        return fract(value)
    }

    private fun drawQr(canvas: Canvas, status: NodeStatus) {
        val bitmap = cachedQrBitmap(status)
        canvas.drawBitmap(bitmap, qrDrawBounds.left, qrDrawBounds.top, null)
    }

    private fun cachedQrBitmap(status: NodeStatus): Bitmap {
        if (cachedQrUrl != status.url) {
            cachedQrUrl = status.url
            cachedQrRows = QrV2L.rowsFor(status.url)
            layoutWidth = 0
            layoutHeight = 0
            ensureLayout()
            qrBitmap?.recycle()
            qrBitmap = null
            qrBitmapStatus = null
        }

        val left = floor(qrDrawBounds.left).toInt()
        val top = floor(qrDrawBounds.top).toInt()
        val bitmapWidth = kotlin.math.ceil(qrDrawBounds.width()).toInt().coerceAtLeast(1)
        val bitmapHeight = kotlin.math.ceil(qrDrawBounds.height()).toInt().coerceAtLeast(1)
        val pending = nextBootConfigPending.get()
        val current = qrBitmap
        if (
            current != null &&
            !current.isRecycled &&
            current.width == bitmapWidth &&
            current.height == bitmapHeight &&
            qrBitmapStatus == status &&
            qrBitmapPending == pending
        ) {
            return current
        }

        current?.recycle()
        val next = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        next.eraseColor(Color.TRANSPARENT)
        Canvas(next).also { bitmapCanvas ->
            bitmapCanvas.translate(-left.toFloat(), -top.toFloat())
            drawQrDirect(bitmapCanvas, status)
        }
        qrBitmap = next
        qrBitmapStatus = status
        qrBitmapPending = pending
        return next
    }

    private fun drawQrDirect(canvas: Canvas, status: NodeStatus) {
        val margin = dp(22f)

        val moduleCount = cachedQrRows.size
        val target = min(width * 0.34f, height * 0.16f).coerceAtLeast(dp(132f))
        val modulePx = max(3, floor(target / moduleCount).toInt())
        val qrSize = (modulePx * moduleCount).toFloat()
        val qrX = margin
        val qrY = height - qrSize - dp(126f)

        rectPaint.style = Paint.Style.FILL
        rectPaint.color = Color.WHITE
        rectPaint.alpha = if (status.ip == null) 188 else 255
        canvas.drawRect(qrX, qrY, qrX + qrSize, qrY + qrSize, rectPaint)

        rectPaint.alpha = 255
        rectPaint.color = Color.rgb(1, 10, 29)
        for (r in 0 until moduleCount) {
            for (c in 0 until moduleCount) {
                if (cachedQrRows[r][c]) {
                    val left = qrX + c * modulePx
                    val top = qrY + r * modulePx
                    canvas.drawRect(left, top, left + modulePx, top + modulePx, rectPaint)
                }
            }
        }

        val labelX = qrX + qrSize + dp(18f)
        val labelWidth = width - labelX - margin
        accentPaint.typeface = mono
        accentPaint.textSize = (width / 42f).coerceIn(dp(10f), dp(18f))
        accentPaint.color = Color.WHITE
        val controlY = height - dp(108f)
        val labelY = controlY + accentPaint.textSize
        val labelText = "NODE URL"

        textPaint.typeface = mono
        textPaint.textSize = (accentPaint.textSize * 0.78f).coerceAtLeast(dp(8f))
        textPaint.color = Color.argb(235, 238, 251, 255)
        textPaint.clearShadowLayer()
        val url = fitMiddle(status.url, max(10, (labelWidth / textPaint.measureText("M")).toInt()))
        val lines = listOf(
            fitToWidth("LIVE AT: $url", textPaint, labelWidth),
        )
        val backingWidth = (
            listOf(accentPaint.measureText(labelText)) +
                lines.map { textPaint.measureText(it) }
        ).maxOrNull()?.coerceAtMost(labelWidth) ?: 0f

        rectPaint.style = Paint.Style.FILL
        rectPaint.color = BACKGROUND
        canvas.drawRect(
            labelX - dp(4f),
            labelY - accentPaint.textSize * 1.25f,
            labelX + backingWidth + dp(6f),
            labelY + accentPaint.textSize * 2.25f,
            rectPaint,
        )
        canvas.drawText(labelText, labelX, labelY, accentPaint)

        var textY = labelY + accentPaint.textSize * 1.4f
        for (line in lines) {
            canvas.drawText(line, labelX, textY, textPaint)
            textY += textPaint.textSize * 1.35f
        }
        drawConfigButton(canvas, qrX, controlY, qrSize)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val inside = configButtonBounds.contains(event.x, event.y)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                configTouchActive = inside
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (configTouchActive) {
                    configTouchActive = false
                    if (inside) {
                        if (nextBootConfigPending.get()) {
                            actions.terminate()
                        } else {
                            actions.pickNextBootConfig()
                        }
                    }
                    return true
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                configTouchActive = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun drawConfigButton(canvas: Canvas, x: Float, y: Float, width: Float) {
        val terminate = nextBootConfigPending.get()
        val height = dp(44f)
        configButtonBounds.set(x, y, x + width, y + height)

        rectPaint.style = Paint.Style.FILL
        rectPaint.color = if (terminate) TERMINATE_ORANGE else Color.WHITE
        rectPaint.alpha = 255
        canvas.drawRect(configButtonBounds, rectPaint)

        textPaint.typeface = mono
        textPaint.textSize = (width / 18.2f).coerceIn(dp(10f), dp(16f))
        textPaint.color = if (terminate) Color.WHITE else BACKGROUND
        textPaint.clearShadowLayer()
        val label = if (terminate) "RESTART TO APPLY" else "LOAD CONFIG"
        val textWidth = textPaint.measureText(label)
        val metrics = textPaint.fontMetrics
        val textX = x + (width - textWidth) / 2f
        val textY = y + (height - metrics.ascent - metrics.descent) / 2f
        canvas.drawText(label, textX, textY, textPaint)
    }

    private fun burstAlpha(phase: Float): Float =
        when {
            phase < 0.18f -> phase / 0.18f
            phase > 0.82f -> (1f - phase) / 0.18f
            else -> 1f
        }.coerceIn(0.18f, 1f)

    private fun fract(value: Float): Float =
        value - floor(value)

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb(
            (alpha.coerceIn(0f, 1f) * 255f).roundToInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    private fun fitToWidth(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val charWidth = paint.measureText("M").coerceAtLeast(1f)
        return fitMiddle(text, max(4, (maxWidth / charWidth).toInt()))
    }

    private fun fitMiddle(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        if (maxChars <= 3) return text.take(maxChars)
        val left = max(1, (maxChars - 1) / 2)
        val right = max(1, maxChars - left - 1)
        return text.take(left) + "~" + text.takeLast(right)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private val BACKGROUND = Color.rgb(21, 18, 58)
        private val TERMINATE_ORANGE = Color.rgb(164, 76, 0)
        private const val ANIMATION_FRAME_MS = 100L
        private const val BOOT_POLL_MS = 1_000L
        private const val STEADY_POLL_MS = 5_000L
        private const val BEAM_FPS = 12f
        private const val LOGO_REVEAL_SECONDS = 1.85f
        private const val BEAM_PARTICLE_COUNT = 22
        private val BEAM_COLORS = intArrayOf(
            Color.rgb(79, 255, 90),
            Color.rgb(255, 230, 94),
            Color.rgb(118, 221, 255),
            Color.rgb(185, 92, 255),
            Color.rgb(255, 72, 67),
            Color.rgb(38, 170, 255),
        )
        private val LOGO_FUZZ_CHARS = charArrayOf('.', ':', '*', '+', '~', ';', '=')
        private val TAP_CLUSTER_TOKENS = arrayOf(".", ":", "*", "+", "~", "::", "[]", "{}", "ao", "p4", "hb")
        private val BEAM_WORDS = arrayOf(
            "~arweave@2.9",
            "~bundle@1.0",
            "~json@1.0",
            "~meta@1.0",
            "~p4@1.0",
            "~ao@1.0",
        )
        private val BEAM_WORD_SPEEDS = floatArrayOf(
            0.010f,
            0.014f,
            0.021f,
            0.032f,
        )
        private val HYPERBEAM_GREETER = listOf(
            "\u2588\u2588\u2557  \u2588\u2588\u2557\u2588\u2588\u2557   \u2588\u2588\u2557\u2588\u2588\u2588\u2588\u2588\u2588\u2557 \u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2557\u2588\u2588\u2588\u2588\u2588\u2588\u2557",
            "\u2588\u2588\u2551  \u2588\u2588\u2551\u255a\u2588\u2588\u2557 \u2588\u2588\u2554\u255d\u2588\u2588\u2554\u2550\u2550\u2588\u2588\u2557\u2588\u2588\u2554\u2550\u2550\u2550\u2550\u255d\u2588\u2588\u2554\u2550\u2550\u2588\u2588\u2557",
            "\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2551 \u255a\u2588\u2588\u2588\u2588\u2554\u255d \u2588\u2588\u2588\u2588\u2588\u2588\u2554\u255d\u2588\u2588\u2588\u2588\u2588\u2557  \u2588\u2588\u2588\u2588\u2588\u2588\u2554\u255d",
            "\u2588\u2588\u2554\u2550\u2550\u2588\u2588\u2551  \u255a\u2588\u2588\u2554\u255d  \u2588\u2588\u2554\u2550\u2550\u2550\u255d \u2588\u2588\u2554\u2550\u2550\u255d  \u2588\u2588\u2554\u2550\u2550\u2588\u2588\u2557",
            "\u2588\u2588\u2551  \u2588\u2588\u2551   \u2588\u2588\u2551   \u2588\u2588\u2551     \u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2557\u2588\u2588\u2551  \u2588\u2588\u2551",
            "\u255a\u2550\u255d  \u255a\u2550\u255d   \u255a\u2550\u255d   \u255a\u2550\u255d     \u255a\u2550\u2550\u2550\u2550\u2550\u2550\u255d\u255a\u2550\u255d  \u255a\u2550\u255d",
            "\u2588\u2588\u2588\u2588\u2588\u2588\u2557 \u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2557 \u2588\u2588\u2588\u2588\u2588\u2557 \u2588\u2588\u2588\u2557   \u2588\u2588\u2588\u2557",
            "\u2588\u2588\u2554\u2550\u2550\u2588\u2588\u2557\u2588\u2588\u2554\u2550\u2550\u2550\u2550\u255d\u2588\u2588\u2554\u2550\u2550\u2588\u2588\u2557\u2588\u2588\u2588\u2588\u2557 \u2588\u2588\u2588\u2588\u2551",
            "\u2588\u2588\u2588\u2588\u2588\u2588\u2554\u255d\u2588\u2588\u2588\u2588\u2588\u2557  \u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2551\u2588\u2588\u2554\u2588\u2588\u2588\u2588\u2554\u2588\u2588\u2551",
            "\u2588\u2588\u2554\u2550\u2550\u2588\u2588\u2557\u2588\u2588\u2554\u2550\u2550\u255d  \u2588\u2588\u2554\u2550\u2550\u2588\u2588\u2551\u2588\u2588\u2551\u255a\u2588\u2588\u2554\u255d\u2588\u2588\u2551 EAT GLASS,",
            "\u2588\u2588\u2588\u2588\u2588\u2588\u2554\u255d\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2557\u2588\u2588\u2551  \u2588\u2588\u2551\u2588\u2588\u2551 \u255a\u2550\u255d \u2588\u2588\u2551 BUILD THE",
            "\u255a\u2550\u2550\u2550\u2550\u2550\u255d \u255a\u2550\u2550\u2550\u2550\u2550\u2550\u255d\u255a\u2550\u255d  \u255a\u2550\u255d\u255a\u2550\u255d     \u255a\u2550\u255d FUTURE.",
        )
        private const val PHONE_HALF_WIDTH = 1.0f
        private const val PHONE_HALF_HEIGHT = 2.0f
        private const val PHONE_HALF_DEPTH = 0.16f
        private const val PHONE_TILT = 0.38f
        private const val PHONE_YAW_RATE = 1.2f
        private const val PHONE_FADE_SECONDS = 0.6f
        private val PHONE_EDGES = run {
            val fbl = Vec3(-PHONE_HALF_WIDTH, -PHONE_HALF_HEIGHT, PHONE_HALF_DEPTH)
            val fbr = Vec3(PHONE_HALF_WIDTH, -PHONE_HALF_HEIGHT, PHONE_HALF_DEPTH)
            val ftl = Vec3(-PHONE_HALF_WIDTH, PHONE_HALF_HEIGHT, PHONE_HALF_DEPTH)
            val ftr = Vec3(PHONE_HALF_WIDTH, PHONE_HALF_HEIGHT, PHONE_HALF_DEPTH)
            val bbl = Vec3(-PHONE_HALF_WIDTH, -PHONE_HALF_HEIGHT, -PHONE_HALF_DEPTH)
            val bbr = Vec3(PHONE_HALF_WIDTH, -PHONE_HALF_HEIGHT, -PHONE_HALF_DEPTH)
            val btl = Vec3(-PHONE_HALF_WIDTH, PHONE_HALF_HEIGHT, -PHONE_HALF_DEPTH)
            val btr = Vec3(PHONE_HALF_WIDTH, PHONE_HALF_HEIGHT, -PHONE_HALF_DEPTH)
            val sbl = Vec3(-0.84f, -1.84f, PHONE_HALF_DEPTH)
            val sbr = Vec3(0.84f, -1.84f, PHONE_HALF_DEPTH)
            val stl = Vec3(-0.84f, 1.70f, PHONE_HALF_DEPTH)
            val str = Vec3(0.84f, 1.70f, PHONE_HALF_DEPTH)
            val c0 = Vec3(-0.62f, 1.30f, -PHONE_HALF_DEPTH)
            val c1 = Vec3(-0.18f, 1.30f, -PHONE_HALF_DEPTH)
            val c2 = Vec3(-0.62f, 1.66f, -PHONE_HALF_DEPTH)
            val c3 = Vec3(-0.18f, 1.66f, -PHONE_HALF_DEPTH)
            listOf(
                Edge(fbl, fbr, false),
                Edge(ftl, ftr, false),
                Edge(fbl, ftl, false),
                Edge(fbr, ftr, false),
                Edge(bbl, bbr, false),
                Edge(btl, btr, false),
                Edge(bbl, btl, false),
                Edge(bbr, btr, false),
                Edge(fbl, bbl, false),
                Edge(fbr, bbr, false),
                Edge(ftl, btl, false),
                Edge(ftr, btr, false),
                Edge(sbl, sbr, true),
                Edge(stl, str, true),
                Edge(sbl, stl, true),
                Edge(sbr, str, true),
                Edge(
                    Vec3(-0.22f, 1.88f, PHONE_HALF_DEPTH),
                    Vec3(0.22f, 1.88f, PHONE_HALF_DEPTH),
                    true,
                ),
                Edge(c0, c1, false),
                Edge(c2, c3, false),
                Edge(c0, c2, false),
                Edge(c1, c3, false),
            )
        }
    }
}

private data class NodeStatus(
    val ready: Boolean,
    val ip: String?,
    val url: String,
) {
    companion object {
        fun booting(): NodeStatus = NodeStatus(
            ready = false,
            ip = null,
            url = "http://<node>:8734/",
        )
    }
}

private object NodeProbe {
    fun snapshot(): NodeStatus {
        val ip = lanIpv4()
        val url = when (ip) {
            null -> "http://<node>:8734/"
            else -> "http://$ip:8734/"
        }
        return NodeStatus(
            ready = hyperbeamReady(),
            ip = ip,
            url = url,
        )
    }

    private fun hyperbeamReady(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", 8734), 250)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun lanIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) {
                    continue
                }
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (
                        address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
                    ) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

private object QrV2L {
    fun rowsFor(data: String): Array<BooleanArray> {
        val bytes = data.toByteArray(Charsets.UTF_8)
        val payload = if (bytes.size <= 32) bytes else "http://node-too-long/".toByteArray(Charsets.UTF_8)
        val size = 25
        val modules = Array(size) { BooleanArray(size) }
        val reserved = Array(size) { BooleanArray(size) }

        drawFinder(modules, reserved, 0, 0)
        drawFinder(modules, reserved, 0, size - 7)
        drawFinder(modules, reserved, size - 7, 0)
        drawAlignment(modules, reserved, 18, 18)
        drawTiming(modules, reserved, size)
        put(modules, reserved, 4 * 2 + 9, 8, true, true)
        reserveFormat(reserved, size)

        val dataCodewords = dataCodewords(payload, 34)
        val eccCodewords = rsRemainder(dataCodewords, 10)
        val bits = ArrayList<Int>((dataCodewords.size + eccCodewords.size) * 8)
        for (codeword in dataCodewords + eccCodewords) {
            bits.addAll(bitsInt(codeword, 8))
        }
        placeBits(modules, reserved, bits, size)
        applyFormat(modules, size)
        return addQuietZone(modules, size, 4)
    }

    private fun dataCodewords(data: ByteArray, target: Int): IntArray {
        val bits = ArrayList<Int>(target * 8)
        bits.addAll(listOf(0, 1, 0, 0))
        bits.addAll(bitsInt(data.size, 8))
        for (byte in data) bits.addAll(bitsInt(byte.toInt() and 0xff, 8))
        val maxBits = target * 8
        repeat(max(0, min(4, maxBits - bits.size))) { bits.add(0) }
        while (bits.size % 8 != 0) bits.add(0)

        val codewords = ArrayList<Int>(target)
        var i = 0
        while (i < bits.size && codewords.size < target) {
            var value = 0
            for (j in 0 until 8) value = (value shl 1) or bits[i + j]
            codewords.add(value)
            i += 8
        }
        var pad = 0xec
        while (codewords.size < target) {
            codewords.add(pad)
            pad = if (pad == 0xec) 0x11 else 0xec
        }
        return codewords.toIntArray()
    }

    private fun bitsInt(value: Int, width: Int): List<Int> {
        return (width - 1 downTo 0).map { shift -> (value ushr shift) and 1 }
    }

    private fun rsRemainder(data: IntArray, eccLen: Int): IntArray {
        val generator = rsGenerator(eccLen)
        val remainder = IntArray(eccLen)
        for (byte in data) {
            val factor = byte xor remainder[0]
            for (i in 0 until eccLen - 1) {
                remainder[i] = remainder[i + 1] xor gfMul(factor, generator[i + 1])
            }
            remainder[eccLen - 1] = gfMul(factor, generator[eccLen])
        }
        return remainder
    }

    private fun rsGenerator(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            poly = polyMul(poly, intArrayOf(1, gfPow2(i)))
        }
        return poly
    }

    private fun polyMul(left: IntArray, right: IntArray): IntArray {
        val out = IntArray(left.size + right.size - 1)
        for (i in left.indices) {
            for (j in right.indices) {
                out[i + j] = out[i + j] xor gfMul(left[i], right[j])
            }
        }
        return out
    }

    private fun gfPow2(power: Int): Int {
        var value = 1
        repeat(power) { value = gfMul(value, 2) }
        return value
    }

    private fun gfMul(a0: Int, b0: Int): Int {
        var a = a0
        var b = b0
        var acc = 0
        while (b != 0) {
            if ((b and 1) != 0) acc = acc xor a
            a = if ((a and 0x80) != 0) ((a shl 1) xor 0x11d) and 0xff else (a shl 1) and 0xff
            b = b ushr 1
        }
        return acc
    }

    private fun drawFinder(
        modules: Array<BooleanArray>,
        reserved: Array<BooleanArray>,
        row0: Int,
        col0: Int,
    ) {
        for (r in -1..7) {
            for (c in -1..7) {
                val separator = r == -1 || r == 7 || c == -1 || c == 7
                val dark = !separator &&
                    (r == 0 || r == 6 || c == 0 || c == 6 || (r in 2..4 && c in 2..4))
                put(modules, reserved, row0 + r, col0 + c, dark, true)
            }
        }
    }

    private fun drawAlignment(
        modules: Array<BooleanArray>,
        reserved: Array<BooleanArray>,
        row0: Int,
        col0: Int,
    ) {
        for (r in -2..2) {
            for (c in -2..2) {
                val dark = abs(r) == 2 || abs(c) == 2 || (r == 0 && c == 0)
                put(modules, reserved, row0 + r, col0 + c, dark, true)
            }
        }
    }

    private fun drawTiming(modules: Array<BooleanArray>, reserved: Array<BooleanArray>, size: Int) {
        for (i in 8 until size - 8) {
            val dark = i % 2 == 0
            put(modules, reserved, 6, i, dark, true)
            put(modules, reserved, i, 6, dark, true)
        }
    }

    private fun reserveFormat(reserved: Array<BooleanArray>, size: Int) {
        for ((row, col) in formatCoords(size)) {
            if (row in 0 until size && col in 0 until size) reserved[row][col] = true
        }
    }

    private fun placeBits(
        modules: Array<BooleanArray>,
        reserved: Array<BooleanArray>,
        bits: List<Int>,
        size: Int,
    ) {
        var bitIndex = 0
        var upward = true
        var col = size - 1
        while (col > 0) {
            if (col == 6) col--
            val rowRange = if (upward) size - 1 downTo 0 else 0 until size
            for (row in rowRange) {
                for (c in col downTo col - 1) {
                    if (!reserved[row][c]) {
                        val bit = bits.getOrElse(bitIndex) { 0 }
                        bitIndex++
                        val mask = (row + c) % 2 == 0
                        modules[row][c] = (bit == 1) xor mask
                    }
                }
            }
            upward = !upward
            col -= 2
        }
    }

    private fun applyFormat(modules: Array<BooleanArray>, size: Int) {
        val bits = IntArray(15) { i -> (0x77c4 ushr i) and 1 }
        val coords = formatCoords(size)
        for (i in coords.indices) {
            val (row, col) = coords[i]
            modules[row][col] = bits[i % 15] == 1
        }
    }

    private fun addQuietZone(modules: Array<BooleanArray>, size: Int, quiet: Int): Array<BooleanArray> {
        val total = size + quiet * 2
        val out = Array(total) { BooleanArray(total) }
        for (r in 0 until total) {
            for (c in 0 until total) {
                val innerR = r - quiet
                val innerC = c - quiet
                if (innerR in 0 until size && innerC in 0 until size) {
                    out[r][c] = modules[innerR][innerC]
                }
            }
        }
        return out
    }

    private fun put(
        modules: Array<BooleanArray>,
        reserved: Array<BooleanArray>,
        row: Int,
        col: Int,
        dark: Boolean,
        reserve: Boolean,
    ) {
        if (row in modules.indices && col in modules[row].indices) {
            modules[row][col] = dark
            if (reserve) reserved[row][col] = true
        }
    }

    private fun formatCoords(size: Int): List<Pair<Int, Int>> {
        return listOf(
            0 to 8,
            1 to 8,
            2 to 8,
            3 to 8,
            4 to 8,
            5 to 8,
            7 to 8,
            8 to 8,
            8 to 7,
            8 to 5,
            8 to 4,
            8 to 3,
            8 to 2,
            8 to 1,
            8 to 0,
            8 to size - 1,
            8 to size - 2,
            8 to size - 3,
            8 to size - 4,
            8 to size - 5,
            8 to size - 6,
            8 to size - 7,
            8 to size - 8,
            size - 7 to 8,
            size - 6 to 8,
            size - 5 to 8,
            size - 4 to 8,
            size - 3 to 8,
            size - 2 to 8,
            size - 1 to 8,
        )
    }
}
