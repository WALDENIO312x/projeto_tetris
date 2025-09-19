// TetrisGame.kt - Jogo de Tetris com menu, anúncios e salvamento
// Configuração necessária:
// 1. Adicione ao build.gradle (module): implementation 'com.google.android.gms:play-services-ads:23.3.0'
// 2. No AndroidManifest.xml, adicione: <meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="SUA_ADMOB_APP_ID"/>
// 3. Use ID de teste para anúncios: ca-app-pub-3940256099942544/1033173712
// 4. Controles: Swipe esquerda/direita para mover, swipe baixo para acelerar, toque para rotacionar.
// 5. Menu: Iniciar Novo Jogo, Continuar Jogo Anterior (se disponível).
// 6. Anúncio: Exibido ao perder, obrigatório para reiniciar.

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import kotlin.random.Random

class TetrisGame : AppCompatActivity() {
    private lateinit var tetrisView: TetrisView
    private lateinit var menuLayout: LinearLayout
    private lateinit var prefs: SharedPreferences
    private var interstitialAd: InterstitialAd? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializar AdMob
        MobileAds.initialize(this) {}
        loadInterstitialAd()

        prefs = getSharedPreferences("TetrisGame", MODE_PRIVATE)
        menuLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val newGameButton = Button(this).apply {
            text = "Iniciar Novo Jogo"
            setOnClickListener {
                tetrisView = TetrisView(this@TetrisGame, true)
                setContentView(tetrisView)
            }
        }

        val continueButton = Button(this).apply {
            text = "Continuar Jogo Anterior"
            isEnabled = prefs.getBoolean("hasSavedGame", false)
            setOnClickListener {
                tetrisView = TetrisView(this@TetrisGame, false)
                setContentView(tetrisView)
            }
        }

        menuLayout.addView(newGameButton)
        menuLayout.addView(continueButton)
        setContentView(menuLayout)
    }

    private fun loadInterstitialAd() {
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            this,
            "ca-app-pub-3940256099942544/1033173712",
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        interstitialAd = null
    }
}

class TetrisView(context: Context, newGame: Boolean) : SurfaceView(context), SurfaceHolder.Callback {
    private val thread: GameThread
    private var grid = Array(20) { IntArray(10) }
    private var currentPiece: Piece? = null
    private var nextPiece: Piece? = null
    private var score = 0
    private var level = 1
    private var gameOver = false
    private val paint = Paint().apply { textSize = 30f }
    private val handler = Handler(Looper.getMainLooper())
    private var dropInterval = 1000L
    private var lastDropTime = 0L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val prefs = context.getSharedPreferences("TetrisGame", Context.MODE_PRIVATE)

    init {
        holder.addCallback(this)
        thread = GameThread(holder, this)
        if (newGame) {
            spawnNewPiece()
        } else {
            loadGame()
        }
    }

    private fun saveGame() {
        with(prefs.edit()) {
            putBoolean("hasSavedGame", true)
            putInt("score", score)
            putInt("level", level)
            putLong("dropInterval", dropInterval)
            putInt("currentPieceType", currentPiece?.type ?: 0)
            putInt("currentPieceX", currentPiece?.x ?: 0)
            putInt("currentPieceY", currentPiece?.y ?: 0)
            putInt("nextPieceType", nextPiece?.type ?: 0)
            for (y in grid.indices) {
                for (x in grid[0].indices) {
                    putInt("grid_$y_$x", grid[y][x])
                }
            }
            apply()
        }
    }

    private fun loadGame() {
        score = prefs.getInt("score", 0)
        level = prefs.getInt("level", 1)
        dropInterval = prefs.getLong("dropInterval", 1000L)
        for (y in grid.indices) {
            for (x in grid[0].indices) {
                grid[y][x] = prefs.getInt("grid_$y_$x", 0)
            }
        }
        currentPiece = Piece(prefs.getInt("currentPieceType", 0)).apply {
            x = prefs.getInt("currentPieceX", 3)
            y = prefs.getInt("currentPieceY", 0)
        }
        nextPiece = Piece(prefs.getInt("nextPieceType", 0))
    }

    fun spawnNewPiece() {
        currentPiece = nextPiece ?: Piece(Random.nextInt(7))
        nextPiece = Piece(Random.nextInt(7))
        if (collides(currentPiece!!)) {
            gameOver = true
            saveGame()
        } else {
            saveGame()
        }
    }

    fun update() {
        if (gameOver) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastDropTime >= dropInterval) {
            moveDown()
            lastDropTime = currentTime
        }
    }

    fun moveDown() {
        currentPiece?.let {
            it.y++
            if (collides(it)) {
                it.y--
                merge(it)
                clearLines()
                spawnNewPiece()
            } else {
                saveGame()
            }
        }
    }

    fun moveLeft() {
        currentPiece?.let {
            it.x--
            if (collides(it)) it.x++ else saveGame()
        }
    }

    fun moveRight() {
        currentPiece?.let {
            it.x++
            if (collides(it)) it.x-- else saveGame()
        }
    }

    fun rotate() {
        currentPiece?.let {
            val oldShape = it.shape
            it.rotate()
            if (collides(it)) it.shape = oldShape else saveGame()
        }
    }

    fun collides(piece: Piece): Boolean {
        for (y in piece.shape.indices) {
            for (x in piece.shape[0].indices) {
                if (piece.shape[y][x] == 1) {
                    val nx = piece.x + x
                    val ny = piece.y + y
                    if (nx < 0 || nx >= 10 || ny >= 20 || (ny >= 0 && grid[ny][nx] != 0)) return true
                }
            }
        }
        return false
    }

    fun merge(piece: Piece) {
        for (y in piece.shape.indices) {
            for (x in piece.shape[0].indices) {
                if (piece.shape[y][x] == 1) {
                    grid[piece.y + y][piece.x + x] = piece.color
                }
            }
        }
    }

    fun clearLines() {
        var linesCleared = 0
        var y = 19
        while (y >= 0) {
            if (grid[y].all { it != 0 }) {
                for (yy in y downTo 1) {
                    grid[yy] = grid[yy - 1].copyOf()
                }
                grid[0] = IntArray(10)
                linesCleared++
            } else {
                y--
            }
        }
        if (linesCleared > 0) {
            score += when (linesCleared) {
                1 -> 40 * level
                2 -> 100 * level
                3 -> 300 * level
                4 -> 1200 * level
                else -> 0
            }
            level = (score / 1000) + 1
            dropInterval = (1000 - (level * 50)).coerceAtLeast(100L)
            saveGame()
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        canvas.drawColor(Color.BLACK)

        // Desenhar grid
        paint.color = Color.GREEN
        for (y in 0..19) {
            for (x in 0..9) {
                if (grid[y][x] != 0) {
                    paint.color = grid[y][x]
                    canvas.drawRect(x * 30f, y * 30f, (x + 1) * 30f, (y + 1) * 30f, paint)
                }
            }
        }

        // Desenhar peça atual
        currentPiece?.let { drawPiece(canvas, it, it.x * 30f, it.y * 30f) }

        // Desenhar próxima peça
        paint.color = Color.WHITE
        canvas.drawText("Next:", 310f, 30f, paint)
        nextPiece?.let { drawPiece(canvas, it, 310f, 50f, true) }

        // Pontuação e nível
        canvas.drawText("Score: $score", 310f, 200f, paint)
        canvas.drawText("Level: $level", 310f, 230f, paint)

        if (gameOver) {
            paint.color = Color.RED
            paint.textSize = 40f
            canvas.drawText("Game Over", 50f, 300f, paint)
            canvas.drawText("Watch Ad to Restart", 50f, 350f, paint)
        }
    }

    private fun drawPiece(canvas: Canvas, piece: Piece, offsetX: Float, offsetY: Float, preview: Boolean = false) {
        paint.color = piece.color
        for (y in piece.shape.indices) {
            for (x in piece.shape[0].indices) {
                if (piece.shape[y][x] == 1) {
                    val size = if (preview) 20f else 30f
                    canvas.drawRect(offsetX + x * size, offsetY + y * size, offsetX + (x + 1) * size, offsetY + (y + 1) * size, paint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gameOver) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                (context as TetrisGame).interstitialAd?.let { ad ->
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            resetGame()
                            (context as TetrisGame).loadInterstitialAd()
                        }
                        override fun onAdFailedToShowFullScreenContent(error: AdError) {
                            resetGame() // Fallback: reinicia mesmo se o anúncio falhar
                        }
                    }
                    ad.show(context as TetrisGame)
                } ?: resetGame() // Fallback se não houver anúncio
            }
            return true
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                if (Math.abs(dx) < 50 && Math.abs(dy) < 50) {
                    rotate()
                } else if (Math.abs(dx) > Math.abs(dy)) {
                    if (dx < -50) moveLeft()
                    else if (dx > 50) moveRight()
                } else {
                    if (dy > 50) moveDown()
                }
                invalidate()
            }
        }
        return true
    }

    private fun resetGame() {
        grid = Array(20) { IntArray(10) }
        score = 0
        level = 1
        dropInterval = 1000L
        gameOver = false
        spawnNewPiece()
        thread.running = true
        with(prefs.edit()) {
            putBoolean("hasSavedGame", false)
            apply()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        thread.running = true
        thread.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        thread.running = false
        try {
            thread.join()
        } catch (e: InterruptedException) {}
    }
}

class GameThread(private val holder: SurfaceHolder, private val view: TetrisView) : Thread() {
    var running = false

    override fun run() {
        while (running) {
            val canvas = holder.lockCanvas()
            synchronized(holder) {
                view.update()
                view.draw(canvas)
            }
            holder.unlockCanvasAndPost(canvas)
            sleep(16)
        }
    }
}

class Piece(val type: Int) {
    var x = 3
    var y = 0
    var shape: Array<IntArray> = when (type) {
        0 -> arrayOf(intArrayOf(1,1,1,1)) // I
        1 -> arrayOf(intArrayOf(1,1), intArrayOf(1,1)) // O
        2 -> arrayOf(intArrayOf(0,1,0), intArrayOf(1,1,1)) // T
        3 -> arrayOf(intArrayOf(1,0,0), intArrayOf(1,1,1)) // L
        4 -> arrayOf(intArrayOf(0,0,1), intArrayOf(1,1,1)) // J
        5 -> arrayOf(intArrayOf(0,1,1), intArrayOf(1,1,0)) // S
        6 -> arrayOf(intArrayOf(1,1,0), intArrayOf(0,1,1)) // Z
        else -> arrayOf()
    }
    val color = when (type) {
        0 -> Color.CYAN
        1 -> Color.YELLOW
        2 -> Color.MAGENTA
        3 -> Color.parseColor("#FFA500")
        4 -> Color.BLUE
        5 -> Color.GREEN
        6 -> Color.RED
        else -> Color.WHITE
    }

    fun rotate() {
        val newShape = Array(shape[0].size) { IntArray(shape.size) }
        for (y in shape.indices) {
            for (x in shape[0].indices) {
                newShape[x][shape.size - 1 - y] = shape[y][x]
            }
        }
        shape = newShape
    }
}
