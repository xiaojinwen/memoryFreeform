package xiaojw.memoryFreeform.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** 球里画什么字形。 */
enum class BallGlyph {
    /** 「小窗」—— 一个带标题栏的窗口轮廓（悬浮球本尊）。 */
    WINDOW,

    /** 「关闭」—— 一个 ✕（退出球）。 */
    CLOSE,

    /** 「切换」—— 上下两条带箭头的横线（切角球）。 */
    SWAP
}

/**
 * ★ fix59：悬浮球/控制球的**自绘**外观。
 *
 * ## 为什么不再用图片
 *
 * 原来悬浮球是一张 `ic_float_ball`（`#CC2196F3` 的实心圆 + 2dp 白描边），另外两个
 * 控制球一个用系统图标 `android.R.drawable.presence_online`（就是个绿色小圆点）、
 * 一个用 `TextView("⇄")` 加个圆角背景 —— 三种画法、三种质感，摆在一起像三个
 * 不同年代的控件，用户反馈"不好看"。
 *
 * 现在统一成**同一套画法**：一圈柔和投影 + 球面渐变 + 左上高光 + 极细内描边 +
 * 白色字形。三种球只是**配色**（强调蓝 / 石墨黑）和**字形**不同，画法完全共用，
 * 所以它们看起来是"同一套 UI 里的三颗球"。
 *
 * ## 为什么用 Canvas 而不是 XML 图形
 *
 * 球面要的是"光从左上打过来"的球体感 —— 那是 RadialGradient 的活，`<shape>`
 * 的 `<gradient>` 只有 linear/radial/sweep 三种且锚点写死，做不出偏移高光；
 * 而且三个字形（窗口 / ✕ / ⇄）用 Path 画比引三张图更省事，也永远不会被系统
 * 主题染色影响。
 *
 * ## 尺寸约定
 *
 * 一切尺寸都由**视图边长 `s`** 推出，所以同一个类在 52dp 的悬浮球和 48dp 的
 * 控制球上长得一样（只是绝对大小不同）。圆盘半径取 `0.395s`，外面那 `0.105s`
 * 是留给投影的 —— 投影太贴边会被视图边界裁成方角，那比不加投影还难看。
 */
class BallView(
    context: Context,
    /** 字形。 */
    private val glyph: BallGlyph = BallGlyph.WINDOW,
    /** true = 强调色（蓝），false = 石墨色。 */
    private val accent: Boolean = true
) : View(context) {

    /**
     * 小窗开着没有（只对悬浮球有意义：开着是高饱和实心，关着是发灰半透明）。
     *
     * ★ fix59：改由 [xiaojw.memoryFreeform.core.StateManager.windowAliveFlow] 驱动，
     *   不再靠"点完菜单延迟 400ms 刷一次 alpha"那种事后补丁。
     */
    var alive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val path = Path()
    private val density = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val s = if (w < h) w else h
        val cx = w / 2f
        val cy = h / 2f
        val r = s * 0.395f

        // ① 柔和投影。中心略微下移、半径比球大一圈，边缘收成透明 —— 视觉上就是
        //    "球浮在屏幕上"，比一张硬边圆片轻快得多。
        fill.shader = RadialGradient(
            cx, cy + r * 0.12f, r * 1.24f,
            intArrayOf(0x48000000, 0x1E000000, 0x00000000),
            floatArrayOf(0.72f, 0.87f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.24f, fill)

        // ② 球体。渐变的**圆心偏左上**是球感的关键（高光处颜色最亮），
        //    半径取 1.75r 让右下角自然压暗成暗部。
        val top: Int
        val mid: Int
        val bottom: Int
        when {
            accent && alive -> {
                top = 0xFF8FCEFF.toInt(); mid = 0xFF2E8AE6.toInt(); bottom = 0xFF0C4A96.toInt()
            }
            accent -> {
                top = 0xFFC2D2E0.toInt(); mid = 0xFF7C93AC.toInt(); bottom = 0xFF47607B.toInt()
            }
            else -> {
                top = 0xFF6B7480.toInt(); mid = 0xFF2B3138.toInt(); bottom = 0xFF12161B.toInt()
            }
        }
        fill.shader = RadialGradient(
            cx - r * 0.34f, cy - r * 0.42f, r * 1.75f,
            intArrayOf(top, mid, bottom),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fill)

        // ③ 高光。贴着球体边缘裁（就用同一个半径画），所以只会出现在左上那一弧。
        fill.shader = RadialGradient(
            cx - r * 0.30f, cy - r * 0.55f, r * 1.05f,
            intArrayOf(0x59FFFFFF, 0x0FFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fill)

        // ④ 内描边：1 物理像素级的一圈白，把球体从任何背景上"切"出来。
        fill.shader = null
        line.strokeWidth = 1f * density
        line.color = if (accent && alive) 0x75FFFFFF else 0x59FFFFFF
        canvas.drawCircle(cx, cy, r - line.strokeWidth / 2f, line)

        // ⑤ 字形
        line.strokeWidth = (s * 0.045f).coerceAtLeast(1.6f * density)
        line.color = if (accent && !alive) 0xD9FFFFFF.toInt() else 0xF2FFFFFF.toInt()
        solid.color = line.color
        when (glyph) {
            BallGlyph.WINDOW -> drawWindowGlyph(canvas, cx, cy, r)
            BallGlyph.CLOSE -> drawCloseGlyph(canvas, cx, cy, r)
            BallGlyph.SWAP -> drawSwapGlyph(canvas, cx, cy, r)
        }
    }

    /**
     * 小窗字形：一个圆角矩形 + 顶部实心标题栏。
     *
     * 标题栏单独画（上圆角、下直角）而不是"在矩形里塞一条线"，是为了让它读起来
     * 像**窗口**而不是"一个带下划线的方块"；这个尺寸下多一根线就糊成一团了。
     */
    private fun drawWindowGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val halfW = r * 0.60f
        val halfH = r * 0.48f
        val l = cx - halfW
        val t = cy - halfH
        val rr = cx + halfW
        val b = cy + halfH
        val corner = r * 0.20f
        rect.set(l, t, rr, b)
        canvas.drawRoundRect(rect, corner, corner, line)

        val barH = (b - t) * 0.32f
        path.reset()
        path.moveTo(l, t + barH)
        path.lineTo(l, t + corner)
        path.quadTo(l, t, l + corner, t)
        path.lineTo(rr - corner, t)
        path.quadTo(rr, t, rr, t + corner)
        path.lineTo(rr, t + barH)
        path.close()
        canvas.drawPath(path, solid)
    }

    /** ✕：两条对角线。线帽取圆的，交点不会出现尖角毛刺。 */
    private fun drawCloseGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val d = r * 0.40f
        line.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx - d, cy - d, cx + d, cy + d, line)
        canvas.drawLine(cx + d, cy - d, cx - d, cy + d, line)
        line.strokeCap = Paint.Cap.BUTT
    }

    /** ⇄：上下两条横线，箭头用两条短线自己折出来（不引 Path 资源）。 */
    private fun drawSwapGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val d = r * 0.46f
        val head = r * 0.20f
        val gap = r * 0.20f
        line.strokeCap = Paint.Cap.ROUND
        // 上：向右
        canvas.drawLine(cx - d, cy - gap, cx + d, cy - gap, line)
        canvas.drawLine(cx + d - head, cy - gap - head, cx + d, cy - gap, line)
        canvas.drawLine(cx + d - head, cy - gap + head, cx + d, cy - gap, line)
        // 下：向左
        canvas.drawLine(cx + d, cy + gap, cx - d, cy + gap, line)
        canvas.drawLine(cx - d + head, cy + gap - head, cx - d, cy + gap, line)
        canvas.drawLine(cx - d + head, cy + gap + head, cx - d, cy + gap, line)
        line.strokeCap = Paint.Cap.BUTT
    }
}
