package com.screamdino.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Whole game: loop, physics, rendering, menus. Everything is drawn procedurally (no image assets). */
@SuppressLint("ClickableViewAccessibility")
class GameView(private val act: MainActivity) : SurfaceView(act), SurfaceHolder.Callback {

    private enum class St { MENU, PLAY, PAUSE, OVER, SETTINGS }
    private class Obs(var x: Float, val kind: Int, val w: Float, val h: Float, val yOff: Float, val hs: FloatArray, val ph: Float)
    private class Part(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val max: Float, val size: Float, val color: Int)

    // ---- infrastructure
    @Volatile private var jumpReq = false
    private val prefs = Prefs(act)
    private val fx = SoundFx()
    private val voice = VoiceDetector { jumpReq = true }
    private val events = ConcurrentLinkedQueue<FloatArray>()
    private val rnd = Random()
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var st = St.MENU

    // ---- layout / paints
    private var w = 1f
    private var h = 1f
    private var s = 1f
    private var groundY = 0f
    private var dinoX = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    // ---- game state
    private val obs = ArrayList<Obs>()
    private val parts = ArrayList<Part>()
    private var dy = 0f
    private var dv = 0f
    private var onGround = true
    private var jumpBuf = 0f
    private var speed = 0f
    private var score = 0f
    private var envScore = 0f
    private var finalScore = 0
    private var newBest = false
    private var scroll = 0f
    private var legT = 0f
    private var time = 0f
    private var grace = 0f
    private var gapPx = 0f
    private var milestone = 0
    private var shake = 0f
    private var flash = 0f
    private var hintT = 0f
    private var dustT = 0f
    private var meter = 0f
    private var rawFrac = 0f
    private var dragSens = false
    private var pendingStart = false

    // ---- scenery
    private val cloudX = FloatArray(6)
    private val cloudY = FloatArray(6)
    private val cloudZ = FloatArray(6)
    private val starX = FloatArray(48)
    private val starY = FloatArray(48)
    private val starP = FloatArray(48)
    private val pbx = FloatArray(28)
    private val pbd = FloatArray(28)
    private val pbs = FloatArray(28)

    private val palTop = intArrayOf(0xFF4FB3F6.toInt(), 0xFF45368F.toInt(), 0xFF0A0F2E.toInt(), 0xFF7E73C9.toInt())
    private val palBot = intArrayOf(0xFFD3EEFF.toInt(), 0xFFFF9A5C.toInt(), 0xFF2C3270.toInt(), 0xFFFFCAD4.toInt())
    private val palSand = intArrayOf(0xFFF3D78F.toInt(), 0xFFE7A66B.toInt(), 0xFF5D5C82.toInt(), 0xFFECC4A2.toInt())
    private val palFar = intArrayOf(0xFFE9CC84.toInt(), 0xFFD88B5E.toInt(), 0xFF3F4372.toInt(), 0xFFD9A99C.toInt())
    private val palNear = intArrayOf(0xFFE2BB6C.toInt(), 0xFFC4714D.toInt(), 0xFF313563.toInt(), 0xFFC78F86.toInt())
    private val palNight = floatArrayOf(0f, 0.25f, 1f, 0.3f)
    private var cTop = 0
    private var cBot = 0
    private var cSand = 0
    private var cFar = 0
    private var cNear = 0
    private var night = 0f

    init {
        holder.addCallback(this)
        fx.onPlay = { voice.suppressUntil = System.nanoTime() / 1_000_000 + 350 }
        fx.enabled = prefs.soundEnabled
        voice.setSensitivity(prefs.sensitivity)
    }

    // =============================================================== lifecycle
    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        thread = Thread { loop() }.also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        w = width.toFloat(); h = height.toFloat(); s = h / 400f
        groundY = h * 0.8f
        dinoX = w * 0.14f
        initScenery()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
        voice.stop()
    }

    fun release() { voice.stop(); fx.release() }

    fun onHostPause() { voice.stop(); events.add(floatArrayOf(101f, 0f, 0f)) }
    fun onMicResult(granted: Boolean) { events.add(floatArrayOf(100f, if (granted) 1f else 0f, 0f)) }
    fun onBack(): Boolean {
        if (st == St.MENU) return false
        events.add(floatArrayOf(102f, 0f, 0f))
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                events.add(floatArrayOf(0f, e.getX(i), e.getY(i)))
            }
            MotionEvent.ACTION_MOVE -> events.add(floatArrayOf(1f, e.x, e.y))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> events.add(floatArrayOf(2f, e.x, e.y))
        }
        return true
    }

    private fun loop() {
        var last = System.nanoTime()
        while (running) {
            val start = System.nanoTime()
            val dt = ((start - last) / 1e9f).coerceIn(0f, 0.05f)
            last = start
            while (true) { handle(events.poll() ?: break) }
            update(dt)
            val hd = holder
            val cv = try { hd.lockCanvas() } catch (_: Exception) { null }
            if (cv != null) {
                try { render(cv) } finally { try { hd.unlockCanvasAndPost(cv) } catch (_: Exception) {} }
            }
            // 60 fps while playing, 30 fps on static screens (saves battery)
            val target = if (st == St.PLAY) 16_000_000L else 33_000_000L
            val sleepMs = (target - (System.nanoTime() - start)) / 1_000_000
            if (sleepMs > 0) try { Thread.sleep(sleepMs) } catch (_: InterruptedException) {}
        }
    }

    // =============================================================== helpers
    private fun tr(k: String) = Strings.t(prefs.lang, k)
    private fun rtl() = prefs.lang == "fa"
    private fun fmt(n: Int) = Strings.digits(prefs.lang, n.coerceAtLeast(0).toString().padStart(5, '0'))
    private fun frac(db: Float) = ((db + 60f) / 60f).coerceIn(0f, 1f)
    private fun wrap(v: Float, m: Float): Float { val r = v % m; return if (r < 0f) r + m else r }

    private fun lerpC(a: Int, b: Int, t: Float): Int {
        val r = Color.red(a) + ((Color.red(b) - Color.red(a)) * t).toInt()
        val g = Color.green(a) + ((Color.green(b) - Color.green(a)) * t).toInt()
        val bl = Color.blue(a) + ((Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun toast(key: String) {
        act.runOnUiThread { Toast.makeText(act, tr(key), Toast.LENGTH_SHORT).show() }
    }

    private fun askMic() { act.runOnUiThread { act.requestMic() } }

    @Suppress("DEPRECATION")
    private fun buzz() {
        try {
            val v = act.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(70)
        } catch (_: Exception) {}
    }

    private fun initScenery() {
        for (i in cloudX.indices) { cloudX[i] = rnd.nextFloat() * w; cloudY[i] = h * (0.06f + rnd.nextFloat() * 0.32f); cloudZ[i] = 0.7f + rnd.nextFloat() * 0.8f }
        for (i in starX.indices) { starX[i] = rnd.nextFloat(); starY[i] = rnd.nextFloat() * 0.5f; starP[i] = rnd.nextFloat() * 6.28f }
        for (i in pbx.indices) { pbx[i] = rnd.nextFloat(); pbd[i] = rnd.nextFloat(); pbs[i] = 1.5f + rnd.nextFloat() * 3.5f }
    }

    // =============================================================== state changes
    private fun setState(n: St) {
        st = n
        if (n == St.MENU) { envScore = 0f; obs.clear(); dy = 0f; dv = 0f; onGround = true; score = 0f; parts.clear() }
        syncVoice()
    }

    /** Microphone runs only while playing or in the settings screen, and only if voice control is on. */
    private fun syncVoice() {
        val want = prefs.voiceEnabled && act.hasMic() && (st == St.PLAY || st == St.SETTINGS)
        if (want) { if (!voice.isRunning) voice.start() } else voice.stop()
    }

    private fun onPlayPressed() {
        if (prefs.voiceEnabled && !act.hasMic()) { pendingStart = true; askMic() } else startGame()
    }

    private fun startGame() {
        obs.clear(); parts.clear()
        score = 0f; envScore = 0f; dy = 0f; dv = 0f; onGround = true
        jumpReq = false; jumpBuf = 0f; grace = 1.1f; gapPx = 0f; milestone = 0
        hintT = 4f; flash = 0f; shake = 0f
        setState(St.PLAY)
    }

    private fun die() {
        fx.play("hit"); shake = 0.6f; buzz()
        finalScore = score.toInt()
        newBest = finalScore > prefs.highScore
        if (newBest) prefs.highScore = finalScore
        burst(dinoX + 25f * s, groundY - dy - 27f * s, 28)
        setState(St.OVER)
    }

    private fun micResult(granted: Boolean) {
        if (granted) syncVoice() else { prefs.voiceEnabled = false; toast("denied") }
        if (pendingStart) { pendingStart = false; if (st == St.MENU) startGame() }
    }

    private fun goBack() {
        when (st) {
            St.PLAY -> setState(St.PAUSE)
            St.PAUSE, St.OVER -> setState(St.MENU)
            St.SETTINGS -> setState(St.MENU)
            else -> {}
        }
    }

    // =============================================================== input
    private fun handle(e: FloatArray) {
        val k = e[0].toInt()
        val x = e[1]
        val y = e[2]
        when (k) {
            0 -> touchDown(x, y)
            1 -> if (dragSens) setSens(x)
            2 -> dragSens = false
            100 -> micResult(x > 0.5f)
            101 -> if (st == St.PLAY) setState(St.PAUSE)
            102 -> goBack()
        }
    }

    private fun touchDown(x: Float, y: Float) {
        when (st) {
            St.MENU -> {
                if (playRect().contains(x, y)) onPlayPressed()
                else if (setRect().contains(x, y)) setState(St.SETTINGS)
            }
            St.PLAY -> {
                val r = RectF(pauseRect())
                r.inset(-10f * s, -10f * s)
                if (r.contains(x, y)) setState(St.PAUSE) else jumpReq = true
            }
            St.PAUSE -> {
                if (mainRect().contains(x, y)) setState(St.PLAY)
                else if (secRect().contains(x, y)) setState(St.MENU)
            }
            St.OVER -> {
                if (mainRect().contains(x, y)) startGame()
                else if (secRect().contains(x, y)) setState(St.MENU)
            }
            St.SETTINGS -> settingsTouch(x, y)
        }
    }

    private fun settingsTouch(x: Float, y: Float) {
        if (backRect().contains(x, y)) { setState(St.MENU); return }
        for (i in 0..4) {
            if (!rowRect(i).contains(x, y)) continue
            when (i) {
                0 -> {
                    prefs.voiceEnabled = !prefs.voiceEnabled
                    if (prefs.voiceEnabled && !act.hasMic()) askMic() else syncVoice()
                }
                1 -> {
                    val c = RectF(ctrl(1))
                    c.inset(-14f * s, -14f * s)
                    if (c.contains(x, y)) { dragSens = true; setSens(x) }
                }
                3 -> { prefs.soundEnabled = !prefs.soundEnabled; fx.enabled = prefs.soundEnabled; fx.play("jump") }
                4 -> prefs.lang = if (prefs.lang == "fa") "en" else "fa"
            }
            return
        }
    }

    private fun setSens(x: Float) {
        val c = ctrl(1)
        val l = c.left + 12f * s
        val r = c.right - 12f * s
        val v = (((x - l) / (r - l)) * 100f).roundToInt().coerceIn(0, 100)
        prefs.sensitivity = v
        voice.setSensitivity(v)
    }

    // =============================================================== layout rects
    private fun btn(cx: Float, cy: Float, bw: Float, bh: Float) = RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
    private fun playRect() = btn(w / 2, h * 0.50f, 240f * s, 56f * s)
    private fun setRect() = btn(w / 2, h * 0.50f + 68f * s, 240f * s, 50f * s)
    private fun mainRect() = btn(w / 2, h * 0.58f, 240f * s, 56f * s)
    private fun secRect() = btn(w / 2, h * 0.58f + 66f * s, 240f * s, 48f * s)
    private fun pauseRect() = RectF(36f * s, 14f * s, 80f * s, 58f * s)
    private fun panelRect(): RectF { val pw = min(560f * s, w - 40f * s); val l = (w - pw) / 2; return RectF(l, 20f * s, l + pw, 366f * s) }
    private fun rowCy(i: Int) = (98f + 44f * i) * s
    private fun rowRect(i: Int): RectF { val pr = panelRect(); return RectF(pr.left + 12f * s, rowCy(i) - 22f * s, pr.right - 12f * s, rowCy(i) + 22f * s) }
    private fun ctrl(i: Int): RectF {
        val pr = panelRect()
        val cw = min(230f * s, pr.width() * 0.45f)
        val cy = rowCy(i)
        return if (rtl()) RectF(pr.left + 28f * s, cy - 15f * s, pr.left + 28f * s + cw, cy + 15f * s)
        else RectF(pr.right - 28f * s - cw, cy - 15f * s, pr.right - 28f * s, cy + 15f * s)
    }
    private fun backRect() = btn(w / 2, 326f * s, 200f * s, 42f * s)

    // =============================================================== update
    private fun update(dt: Float) {
        time += dt
        for (i in cloudX.indices) {
            cloudX[i] -= (14f * s * cloudZ[i] + (if (st == St.PLAY) speed * 0.03f else 0f)) * dt
            if (cloudX[i] < -140f * s) { cloudX[i] = w + 40f * s + rnd.nextFloat() * 80f * s; cloudY[i] = h * (0.06f + rnd.nextFloat() * 0.32f) }
        }
        when (st) {
            St.MENU, St.SETTINGS -> { scroll += 210f * s * dt; legT += dt * 10f }
            St.PLAY -> updatePlay(dt)
            else -> {}
        }
        updateParts(dt)
        shake = max(0f, shake - dt * 1.6f)
        flash = max(0f, flash - dt)
        if (voice.isRunning) {
            val f = frac(voice.levelDb)
            rawFrac = f
            meter = if (f > meter) f else max(f, meter - dt * 1.3f)
        } else { rawFrac = 0f; meter = max(0f, meter - dt * 2f) }
    }

    private fun tryJump() {
        if (onGround) {
            dv = 820f * s; onGround = false; dy = 0.5f
            fx.play("jump"); dust(5)
        } else jumpBuf = 0.12f // jump pressed slightly early: remembered for 120 ms
    }

    private fun updatePlay(dt: Float) {
        hintT = max(0f, hintT - dt)
        speed = min(1050f, 380f + score * 0.4f) * s
        envScore = score
        score += speed * dt / (42f * s)
        scroll += speed * dt
        legT += dt * (7f + speed / (s * 95f))
        if (jumpBuf > 0f) jumpBuf -= dt
        if (jumpReq) { jumpReq = false; tryJump() }

        if (!onGround) {
            dv -= 2600f * s * dt
            dy += dv * dt
            if (dy <= 0f) {
                dy = 0f; dv = 0f; onGround = true; dust(4)
                if (jumpBuf > 0f) { jumpBuf = 0f; tryJump() }
            }
        } else {
            dustT -= dt
            if (dustT <= 0f) { dustT = 0.07f; dust(1) }
        }

        for (o in obs) o.x -= speed * (if (o.kind == 1) 1.12f else 1f) * dt
        obs.removeAll { it.x + it.w < -30f * s }

        grace -= dt
        if (grace <= 0f) {
            val lastEdge = if (obs.isEmpty()) -1f else obs.maxOf { it.x + it.w }
            if (obs.isEmpty() || w - lastEdge > gapPx) spawn()
        }

        // collisions (hit boxes are slightly forgiving)
        val dl = dinoX + 10f * s
        val dr = dinoX + 42f * s
        val dtp = groundY - dy - 50f * s
        val db = groundY - dy - 3f * s
        for (o in obs) {
            if (o.kind == 0) {
                for (i in o.hs.indices) {
                    val l = o.x + i * 28f * s + 5f * s
                    val r = o.x + i * 28f * s + 19f * s
                    val t = groundY - o.hs[i] + 3f * s
                    if (dr > l && dl < r && db > t) { die(); return }
                }
            } else {
                val l = o.x + 6f * s
                val r = o.x + o.w - 8f * s
                val t = groundY - o.yOff - o.h + 7f * s
                val b = groundY - o.yOff - 6f * s
                if (dr > l && dl < r && db > t && dtp < b) { die(); return }
            }
        }

        val m = score.toInt() / 100
        if (m > milestone) { milestone = m; flash = 0.9f; fx.play("point") }
    }

    private fun spawn() {
        val bird = score > 250f && rnd.nextFloat() < 0.3f
        if (bird) {
            val yo = floatArrayOf(10f, 40f, 75f)[rnd.nextInt(3)] * s
            obs.add(Obs(w + 40f * s, 1, 44f * s, 30f * s, yo, FloatArray(0), rnd.nextFloat() * 6f))
        } else {
            val c = 1 + rnd.nextInt(if (score > 150f) 3 else 2)
            val hs = FloatArray(c) { (34f + rnd.nextInt(26)) * s }
            obs.add(Obs(w + 40f * s, 0, (c * 28f - 4f) * s, hs.max(), 0f, hs, 0f))
        }
        gapPx = speed * (0.85f + rnd.nextFloat() * 0.9f)
    }

    private fun dust(n: Int) {
        repeat(n) {
            if (parts.size < 160) parts.add(
                Part(dinoX + (10f + rnd.nextFloat() * 14f) * s, groundY - 2f * s,
                    -(60f + rnd.nextFloat() * 120f) * s - speed * 0.2f, -(20f + rnd.nextFloat() * 70f) * s,
                    0.45f, 0.45f, (2f + rnd.nextFloat() * 2.5f) * s, lerpC(cSand, Color.WHITE, 0.35f))
            )
        }
    }

    private fun burst(x: Float, y: Float, n: Int) {
        val cols = intArrayOf(0xFF4CAF50.toInt(), 0xFFFF9800.toInt(), Color.WHITE, 0xFFA5D6A7.toInt())
        repeat(n) {
            val a = rnd.nextFloat() * 6.283f
            val v = (120f + rnd.nextFloat() * 380f) * s
            parts.add(Part(x, y, kotlin.math.cos(a) * v, sin(a) * v - 120f * s, 0.9f, 0.9f, (2.5f + rnd.nextFloat() * 4f) * s, cols[rnd.nextInt(cols.size)]))
        }
    }

    private fun updateParts(dt: Float) {
        val it = parts.iterator()
        while (it.hasNext()) {
            val q = it.next()
            q.life -= dt
            if (q.life <= 0f) { it.remove(); continue }
            q.vy += 900f * s * dt
            q.x += q.vx * dt
            q.y += q.vy * dt
        }
    }

    // =============================================================== render
    private fun updateEnv() {
        val x = envScore / 250f
        val fl = floor(x)
        val i = fl.toInt() % 4
        val j = (i + 1) % 4
        var t = ((x - fl) - 0.6f) / 0.4f
        t = t.coerceIn(0f, 1f)
        t = t * t * (3f - 2f * t)
        cTop = lerpC(palTop[i], palTop[j], t)
        cBot = lerpC(palBot[i], palBot[j], t)
        cSand = lerpC(palSand[i], palSand[j], t)
        cFar = lerpC(palFar[i], palFar[j], t)
        cNear = lerpC(palNear[i], palNear[j], t)
        night = palNight[i] + (palNight[j] - palNight[i]) * t
    }

    private fun render(cv: Canvas) {
        cv.save()
        if (shake > 0f) cv.translate((rnd.nextFloat() - 0.5f) * shake * 22f * s, (rnd.nextFloat() - 0.5f) * shake * 22f * s)
        renderWorld(cv)
        cv.restore()

        when (st) {
            St.MENU -> {
                drawTitle(cv)
                button(cv, playRect(), tr("play"), 0xFFFF9800.toInt(), 0xFFE65100.toInt())
                button(cv, setRect(), tr("settings"), 0xFF42A5F5.toInt(), 0xFF1565C0.toInt())
                text(cv, tr("best") + " " + fmt(prefs.highScore), 40f * s, h - 18f * s, 18f * s, Color.WHITE, Paint.Align.LEFT)
            }
            St.PLAY -> hud(cv)
            St.PAUSE -> {
                hud(cv); dim(cv, 150)
                text(cv, tr("paused"), w / 2, h * 0.34f, 52f * s, 0xFFFFD54F.toInt())
                button(cv, mainRect(), tr("resume"), 0xFF66BB6A.toInt(), 0xFF2E7D32.toInt())
                button(cv, secRect(), tr("menu"), 0xFF42A5F5.toInt(), 0xFF1565C0.toInt())
            }
            St.OVER -> {
                hud(cv); dim(cv, 150)
                text(cv, tr("gameover"), w / 2, h * 0.27f, 52f * s, 0xFFEF5350.toInt())
                text(cv, tr("score") + "  " + fmt(finalScore), w / 2, h * 0.39f, 28f * s, Color.WHITE)
                if (newBest && ((time * 3f).toInt() % 2 == 0)) text(cv, tr("newbest"), w / 2, h * 0.39f + 34f * s, 22f * s, 0xFFFFD54F.toInt())
                else text(cv, tr("best") + "  " + fmt(prefs.highScore), w / 2, h * 0.39f + 34f * s, 20f * s, 0xFFDDDDDD.toInt())
                button(cv, mainRect(), tr("restart"), 0xFFFF9800.toInt(), 0xFFE65100.toInt())
                button(cv, secRect(), tr("menu"), 0xFF42A5F5.toInt(), 0xFF1565C0.toInt())
            }
            St.SETTINGS -> drawSettings(cv)
        }
    }

    private fun renderWorld(cv: Canvas) {
        updateEnv()
        // sky
        p.color = cTop; cv.drawRect(-60f, -60f, w + 60f, h + 60f, p)
        val bands = 32
        for (i in 0 until bands) {
            p.color = lerpC(cTop, cBot, i / (bands - 1f))
            cv.drawRect(-60f, h * i / bands - 1f, w + 60f, h * (i + 1) / bands + 1f, p)
        }
        // stars
        if (night > 0.05f) for (i in starX.indices) {
            val tw = 0.5f + 0.5f * sin(time * 2f + starP[i])
            p.color = Color.WHITE; p.alpha = (night * 255f * tw).toInt()
            cv.drawCircle(starX[i] * w, starY[i] * groundY, (1f + (i % 3) * 0.5f) * s, p)
        }
        // sun / moon
        val sunA = ((1f - night) * 255f).toInt()
        if (sunA > 5) {
            val sc = lerpC(0xFFFFE27A.toInt(), 0xFFFF8A50.toInt(), (night * 3f).coerceAtMost(1f))
            p.color = sc; p.alpha = sunA / 5; cv.drawCircle(w * 0.82f, h * 0.2f, 50f * s, p)
            p.color = sc; p.alpha = sunA; cv.drawCircle(w * 0.82f, h * 0.2f, 30f * s, p)
        }
        if (night > 0.05f) {
            val a = (night * 255f).toInt()
            p.color = 0xFFF5F3CE.toInt(); p.alpha = a; cv.drawCircle(w * 0.82f, h * 0.2f, 24f * s, p)
            p.color = 0xFFD9D6A8.toInt(); p.alpha = a / 2
            cv.drawCircle(w * 0.82f - 7f * s, h * 0.2f - 5f * s, 5f * s, p)
            cv.drawCircle(w * 0.82f + 8f * s, h * 0.2f + 6f * s, 4f * s, p)
        }
        // clouds
        val cc = lerpC(Color.WHITE, 0xFF6C74B0.toInt(), night)
        for (i in cloudX.indices) {
            val z = cloudZ[i]; val x = cloudX[i]; val y = cloudY[i]
            p.color = cc; p.alpha = 225
            rect.set(x, y + 8f * s * z, x + 84f * s * z, y + 26f * s * z); cv.drawRoundRect(rect, 9f * s * z, 9f * s * z, p)
            cv.drawCircle(x + 28f * s * z, y + 10f * s * z, 15f * s * z, p)
            cv.drawCircle(x + 52f * s * z, y + 12f * s * z, 18f * s * z, p)
        }
        // dunes
        dunes(cv, groundY - 38f * s, 22f * s, 420f * s, scroll * 0.12f, cFar, 0.5f)
        dunes(cv, groundY - 18f * s, 12f * s, 260f * s, scroll * 0.32f, cNear, 2.1f)
        // ground
        p.color = cSand; cv.drawRect(-60f, groundY, w + 60f, h + 60f, p)
        p.color = lerpC(cSand, Color.BLACK, 0.28f); cv.drawRect(-60f, groundY, w + 60f, groundY + 3f * s, p)
        val ww = w + 80f * s
        p.color = lerpC(cSand, Color.BLACK, 0.22f)
        for (i in pbx.indices) {
            val par = 0.75f + pbd[i] * 0.5f
            val x = wrap(pbx[i] * ww - scroll * par, ww) - 40f * s
            val y = groundY + 12f * s + pbd[i] * (h - groundY - 22f * s)
            rect.set(x, y, x + pbs[i] * 2.2f * s, y + pbs[i] * s); cv.drawRoundRect(rect, s, s, p)
        }
        // obstacles
        for (o in obs) {
            if (o.kind == 0) for (i in o.hs.indices) drawCactus(cv, o.x + i * 28f * s, o.hs[i])
            else drawBird(cv, o)
        }
        // dino
        val top = groundY - dy - 54f * s
        drawDino(cv, dinoX, top, legT, !onGround, !onGround, st == St.OVER)
        // particles
        for (q in parts) {
            val a = (q.life / q.max).coerceIn(0f, 1f)
            p.color = q.color; p.alpha = (a * 255f).toInt()
            cv.drawCircle(q.x, q.y, q.size * (0.5f + 0.5f * a), p)
        }
        p.alpha = 255
    }

    private fun dunes(cv: Canvas, base: Float, amp: Float, wl: Float, off: Float, color: Int, ph: Float) {
        path.reset()
        path.moveTo(-20f, groundY + 2f)
        var x = -20f
        while (x <= w + 36f) {
            val a = (x + off) / wl * 6.2832f
            val y = base - amp * (0.55f * sin(a + ph) + 0.45f * sin(a * 2.3f + 1.3f))
            path.lineTo(x, y)
            x += 16f * s
        }
        path.lineTo(w + 40f, groundY + 2f)
        path.close()
        p.color = color
        cv.drawPath(path, p)
    }

    private fun drawCactus(cv: Canvas, x: Float, hh: Float) {
        val cx0 = x + 12f * s
        p.color = 0xFF2E7D32.toInt()
        val ay1 = groundY - hh * 0.58f
        rect.set(x + 1f * s, ay1, cx0, ay1 + 5f * s); cv.drawRoundRect(rect, 2.5f * s, 2.5f * s, p)
        rect.set(x + 1f * s, ay1 - 13f * s, x + 6.5f * s, ay1 + 5f * s); cv.drawRoundRect(rect, 2.7f * s, 2.7f * s, p)
        val ay2 = groundY - hh * 0.40f
        rect.set(cx0, ay2, x + 23f * s, ay2 + 5f * s); cv.drawRoundRect(rect, 2.5f * s, 2.5f * s, p)
        rect.set(x + 17.5f * s, ay2 - 11f * s, x + 23f * s, ay2 + 5f * s); cv.drawRoundRect(rect, 2.7f * s, 2.7f * s, p)
        rect.set(cx0 - 6f * s, groundY - hh, cx0 + 6f * s, groundY); cv.drawRoundRect(rect, 6f * s, 6f * s, p)
        p.color = 0xFF4CAF50.toInt()
        rect.set(cx0 - 3.5f * s, groundY - hh + 4f * s, cx0 - 1.2f * s, groundY - 2f * s); cv.drawRoundRect(rect, s, s, p)
        p.color = 0xFFC8E6C9.toInt()
        for (k in 0..2) cv.drawCircle(cx0 + 2f * s, groundY - hh * (0.25f + k * 0.22f), 0.9f * s, p)
    }

    private fun drawBird(cv: Canvas, o: Obs) {
        val bx = o.x
        val by = groundY - o.yOff - o.h
        val flap = sin(time * 14f + o.ph)
        // tail
        p.color = 0xFF5D4A66.toInt()
        path.reset(); path.moveTo(bx + 34f * s, by + 17f * s); path.lineTo(bx + 46f * s, by + 12f * s); path.lineTo(bx + 46f * s, by + 22f * s); path.close()
        cv.drawPath(path, p)
        // body + head
        rect.set(bx + 8f * s, by + 12f * s, bx + 38f * s, by + 26f * s); cv.drawOval(rect, p)
        cv.drawCircle(bx + 10f * s, by + 14f * s, 7f * s, p)
        // beak
        p.color = 0xFFFFA726.toInt()
        path.reset(); path.moveTo(bx + 4f * s, by + 11f * s); path.lineTo(bx - 7f * s, by + 15f * s); path.lineTo(bx + 4f * s, by + 18f * s); path.close()
        cv.drawPath(path, p)
        // eye
        p.color = Color.WHITE; cv.drawCircle(bx + 10f * s, by + 12f * s, 2.6f * s, p)
        p.color = Color.BLACK; cv.drawCircle(bx + 9f * s, by + 12f * s, 1.3f * s, p)
        // wing
        p.color = 0xFF7B6585.toInt()
        path.reset(); path.moveTo(bx + 14f * s, by + 16f * s); path.lineTo(bx + 28f * s, by + 16f * s - 16f * s * flap); path.lineTo(bx + 34f * s, by + 17f * s); path.close()
        cv.drawPath(path, p)
    }

    private fun drawDino(cv: Canvas, x: Float, y: Float, legPh: Float, air: Boolean, open: Boolean, dead: Boolean) {
        val body = 0xFF4CAF50.toInt()
        val leg = 0xFF388E3C.toInt()
        cv.save(); cv.translate(x, y); cv.scale(s, s)
        // tail
        p.color = body
        path.reset(); path.moveTo(10f, 24f); path.lineTo(-8f, 36f); path.lineTo(-2f, 40f); path.lineTo(12f, 38f); path.close()
        cv.drawPath(path, p)
        // legs (animated)
        val sn = sin(legPh)
        val lb = if (air) 49f else 54f - max(0f, sn) * 7f
        val rb = if (air) 49f else 54f - max(0f, -sn) * 7f
        p.color = leg
        rect.set(11f, 38f, 19f, lb); cv.drawRoundRect(rect, 3f, 3f, p)
        rect.set(11f, lb - 4f, 23f, lb); cv.drawRoundRect(rect, 2f, 2f, p)
        rect.set(23f, 38f, 31f, rb); cv.drawRoundRect(rect, 3f, 3f, p)
        rect.set(23f, rb - 4f, 35f, rb); cv.drawRoundRect(rect, 2f, 2f, p)
        // back spikes
        p.color = 0xFFFF9800.toInt()
        for (i in 0..2) {
            val sx = 10f + i * 8f
            path.reset(); path.moveTo(sx, 20f); path.lineTo(sx + 4f, 12f); path.lineTo(sx + 8f, 20f); path.close()
            cv.drawPath(path, p)
        }
        // body, belly
        p.color = body; rect.set(5f, 17f, 37f, 44f); cv.drawRoundRect(rect, 11f, 11f, p)
        p.color = 0xFFA5D6A7.toInt(); rect.set(13f, 28f, 34f, 43f); cv.drawRoundRect(rect, 7f, 7f, p)
        // neck + head
        p.color = body; rect.set(24f, 10f, 38f, 26f); cv.drawRect(rect, p)
        rect.set(26f, 1f, 50f, 22f); cv.drawRoundRect(rect, 8f, 8f, p)
        // arm
        p.color = leg; rect.set(32f, 27f, 41f, 31f); cv.drawRoundRect(rect, 2f, 2f, p)
        // eye
        if (dead) {
            p.color = 0xFF1B1B1B.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 2f
            cv.drawLine(35f, 6f, 41f, 12f, p); cv.drawLine(41f, 6f, 35f, 12f, p)
            p.style = Paint.Style.FILL
        } else {
            p.color = Color.WHITE; cv.drawCircle(38.5f, 9.5f, 4f, p)
            p.color = 0xFF1B1B1B.toInt(); cv.drawCircle(39.8f, 9.8f, 2f, p)
        }
        p.color = 0xFF2E7D32.toInt(); cv.drawCircle(47f, 6f, 1.2f, p)
        // mouth (open while jumping = "scream")
        if (open) {
            p.color = 0xFF7A1F2B.toInt()
            path.reset(); path.moveTo(37f, 14f); path.lineTo(50f, 13f); path.lineTo(50f, 21f); path.lineTo(37f, 19f); path.close()
            cv.drawPath(path, p)
            p.color = 0xFFEF9A9A.toInt(); cv.drawCircle(44f, 19f, 2f, p)
        } else {
            p.color = 0xFF2E7D32.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f
            cv.drawLine(38f, 17f, 50f, 17f, p)
            p.style = Paint.Style.FILL
        }
        cv.restore()
    }

    // =============================================================== UI drawing
    private fun text(cv: Canvas, t: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER, shadow: Boolean = true) {
        tp.style = Paint.Style.FILL
        tp.letterSpacing = 0f
        tp.textSize = size; tp.textAlign = align; tp.typeface = Typeface.DEFAULT_BOLD
        if (shadow) {
            tp.color = Color.argb((Color.alpha(color) * 0.45f).toInt(), 0, 0, 0)
            cv.drawText(t, x + size * 0.06f, y + size * 0.06f, tp)
        }
        tp.color = color
        cv.drawText(t, x, y, tp)
    }

    private fun button(cv: Canvas, r: RectF, label: String, face: Int, edge: Int) {
        val d = 5f * s
        rect.set(r.left, r.top + d, r.right, r.bottom + d); p.color = edge; cv.drawRoundRect(rect, 14f * s, 14f * s, p)
        p.color = face; cv.drawRoundRect(r, 14f * s, 14f * s, p)
        text(cv, label, r.centerX(), r.centerY() + r.height() * 0.17f, r.height() * 0.44f, Color.WHITE)
    }

    private fun dim(cv: Canvas, a: Int) { p.color = Color.argb(a, 0, 0, 0); cv.drawRect(0f, 0f, w, h, p) }

    private fun drawTitle(cv: Canvas) {
        val y = h * 0.27f
        tp.textAlign = Paint.Align.CENTER; tp.typeface = Typeface.DEFAULT_BOLD; tp.textSize = 62f * s
        tp.letterSpacing = 0.05f
        tp.style = Paint.Style.STROKE; tp.strokeWidth = 10f * s; tp.strokeJoin = Paint.Join.ROUND
        tp.color = 0xFF3E2723.toInt(); cv.drawText("SCREAM DINO", w / 2, y + 4f * s, tp)
        tp.style = Paint.Style.FILL; tp.color = 0xFFFFD54F.toInt(); cv.drawText("SCREAM DINO", w / 2, y, tp)
        text(cv, tr("sub"), w / 2, y + 36f * s, 22f * s, Color.WHITE)
    }

    private fun hud(cv: Canvas) {
        val pr = pauseRect()
        p.color = Color.argb(100, 0, 0, 0); cv.drawRoundRect(pr, 10f * s, 10f * s, p)
        p.color = Color.WHITE
        rect.set(pr.centerX() - 9f * s, pr.centerY() - 9f * s, pr.centerX() - 3f * s, pr.centerY() + 9f * s); cv.drawRoundRect(rect, 2f * s, 2f * s, p)
        rect.set(pr.centerX() + 3f * s, pr.centerY() - 9f * s, pr.centerX() + 9f * s, pr.centerY() + 9f * s); cv.drawRoundRect(rect, 2f * s, 2f * s, p)

        val blink = flash > 0f && ((time * 12f).toInt() % 2 == 0)
        val cur = if (st == St.PLAY) score.toInt() else finalScore
        if (!blink) text(cv, fmt(cur), w - 36f * s, 44f * s, 32f * s, Color.WHITE, Paint.Align.RIGHT)
        text(cv, tr("best") + " " + fmt(prefs.highScore), w - 36f * s, 66f * s, 15f * s, 0xCCFFFFFF.toInt(), Paint.Align.RIGHT)

        if (prefs.voiceEnabled && voice.isRunning) {
            val bw = 150f * s; val bx = w / 2 - bw / 2 + 10f * s; val by = 22f * s
            val tf = frac(voice.thresholdDb)
            p.color = Color.argb(110, 0, 0, 0); rect.set(bx, by, bx + bw, by + 9f * s); cv.drawRoundRect(rect, 5f * s, 5f * s, p)
            p.color = if (rawFrac > tf) 0xFFFF7043.toInt() else 0xFF66BB6A.toInt()
            rect.set(bx, by, bx + bw * meter, by + 9f * s); cv.drawRoundRect(rect, 5f * s, 5f * s, p)
            p.color = Color.WHITE; rect.set(bx + bw * tf - 1f * s, by - 3f * s, bx + bw * tf + 1f * s, by + 12f * s); cv.drawRect(rect, p)
            // tiny mic icon
            rect.set(bx - 17f * s, by - 4f * s, bx - 11f * s, by + 6f * s); cv.drawRoundRect(rect, 3f * s, 3f * s, p)
            rect.set(bx - 14.5f * s, by + 7f * s, bx - 13.5f * s, by + 11f * s); cv.drawRect(rect, p)
        }
        if (hintT > 0f && st == St.PLAY) {
            val a = (min(1f, hintT) * 255f).toInt()
            text(cv, tr("hint"), w / 2, 78f * s, 21f * s, Color.argb(a, 255, 255, 255))
        }
    }

    private fun drawSettings(cv: Canvas) {
        dim(cv, 140)
        val pr = panelRect()
        p.color = 0xF21B2036.toInt(); cv.drawRoundRect(pr, 22f * s, 22f * s, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f * s; p.color = 0xFF3D4A7A.toInt(); cv.drawRoundRect(pr, 22f * s, 22f * s, p)
        p.style = Paint.Style.FILL
        text(cv, tr("settings"), w / 2, 56f * s, 28f * s, 0xFFFFD54F.toInt())

        val labels = arrayOf(tr("voice"), tr("sens") + ": " + Strings.digits(prefs.lang, prefs.sensitivity.toString()), tr("level"), tr("sound"), tr("lang"))
        val lx = if (rtl()) pr.right - 28f * s else pr.left + 28f * s
        val la = if (rtl()) Paint.Align.RIGHT else Paint.Align.LEFT
        for (i in 0..4) {
            val dimmed = (i == 1 || i == 2) && !prefs.voiceEnabled
            text(cv, labels[i], lx, rowCy(i) + 7f * s, 19f * s, if (dimmed) 0x88FFFFFF.toInt() else Color.WHITE, la, false)
        }
        toggle(cv, 0, prefs.voiceEnabled)
        toggle(cv, 3, prefs.soundEnabled)

        // sensitivity slider
        val c1 = ctrl(1)
        val tl = c1.left + 12f * s; val trr = c1.right - 12f * s
        val sf = prefs.sensitivity / 100f
        p.color = 0xFF39425F.toInt(); rect.set(tl, c1.centerY() - 4f * s, trr, c1.centerY() + 4f * s); cv.drawRoundRect(rect, 4f * s, 4f * s, p)
        p.color = if (prefs.voiceEnabled) 0xFFFF9800.toInt() else 0xFF777777.toInt()
        rect.set(tl, c1.centerY() - 4f * s, tl + (trr - tl) * sf, c1.centerY() + 4f * s); cv.drawRoundRect(rect, 4f * s, 4f * s, p)
        p.color = Color.WHITE; cv.drawCircle(tl + (trr - tl) * sf, c1.centerY(), 12f * s, p)

        // live mic meter
        val c2 = ctrl(2)
        p.color = 0xFF39425F.toInt(); rect.set(c2.left, c2.centerY() - 7f * s, c2.right, c2.centerY() + 7f * s); cv.drawRoundRect(rect, 7f * s, 7f * s, p)
        if (prefs.voiceEnabled && voice.isRunning) {
            val tf = frac(voice.thresholdDb)
            p.color = if (rawFrac > tf) 0xFFFF7043.toInt() else 0xFF66BB6A.toInt()
            rect.set(c2.left, c2.centerY() - 7f * s, c2.left + c2.width() * meter, c2.centerY() + 7f * s); cv.drawRoundRect(rect, 7f * s, 7f * s, p)
            p.color = Color.WHITE; rect.set(c2.left + c2.width() * tf - 1.5f * s, c2.centerY() - 11f * s, c2.left + c2.width() * tf + 1.5f * s, c2.centerY() + 11f * s); cv.drawRect(rect, p)
        }

        // language segmented control
        val c4 = ctrl(4)
        p.color = 0xFF39425F.toInt(); cv.drawRoundRect(c4, 15f * s, 15f * s, p)
        val half = c4.width() / 2
        val fa = prefs.lang == "fa"
        p.color = 0xFFFF9800.toInt()
        if (fa) rect.set(c4.left + half, c4.top, c4.right, c4.bottom) else rect.set(c4.left, c4.top, c4.left + half, c4.bottom)
        cv.drawRoundRect(rect, 15f * s, 15f * s, p)
        text(cv, "English", c4.left + half / 2, c4.centerY() + 6f * s, 16f * s, Color.WHITE, Paint.Align.CENTER, false)
        text(cv, "فارسی", c4.left + half * 1.5f, c4.centerY() + 6f * s, 16f * s, Color.WHITE, Paint.Align.CENTER, false)

        button(cv, backRect(), tr("back"), 0xFF66BB6A.toInt(), 0xFF2E7D32.toInt())
    }

    private fun toggle(cv: Canvas, i: Int, on: Boolean) {
        val c = ctrl(i)
        val tw = 62f * s
        val x0 = if (rtl()) c.left else c.right - tw
        p.color = if (on) 0xFF66BB6A.toInt() else 0xFF5A6280.toInt()
        rect.set(x0, c.centerY() - 14f * s, x0 + tw, c.centerY() + 14f * s); cv.drawRoundRect(rect, 14f * s, 14f * s, p)
        p.color = Color.WHITE
        cv.drawCircle(if (on) x0 + tw - 14f * s else x0 + 14f * s, c.centerY(), 10f * s, p)
    }
}
