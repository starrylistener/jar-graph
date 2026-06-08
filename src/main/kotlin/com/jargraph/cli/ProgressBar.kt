package com.jargraph.cli

/**
 * 终端单行进度条（仿照 codegraph shimmer progress）
 *
 * 阶段驱动：每个阶段独占一行，完成后显示 done 并换行
 */
class ProgressBar {

    companion object {
        private const val RST = "[0m"
        private const val DM = "[2m"
        private const val GRN = "[32m"
        private const val BOLD = "[1m"
        private const val CYAN = "[36m"

        private val SPINNER = arrayOf("◐", "◓", "◑", "◒")
        private const val BAR_WIDTH = 25
        private const val FILLED = "█"
        private const val EMPTY = "░"
        private const val RAIL = "┃"

        fun formatNumber(n: Int): String {
            return n.toString().reversed().chunked(3).joinToString(",").reversed()
        }
    }

    private var spinnerIdx = 0
    private var lastLine = ""
    private var currentPhase = ""

    /** 开始一个新阶段 */
    fun start(phase: String) {
        if (currentPhase.isNotBlank() && currentPhase != phase) {
            finish(currentPhase)
        }
        currentPhase = phase
        update(0, 0)
    }

    /** 更新当前阶段进度（覆盖同一行） */
    fun update(current: Int, total: Int) {
        val glyph = SPINNER[spinnerIdx % SPINNER.size]
        spinnerIdx++

        val percent = if (total > 0) {
            (current.toDouble() / total * 100).toInt()
        } else {
            0
        }

        val filled = (BAR_WIDTH * percent / 100).coerceIn(0, BAR_WIDTH)
        val empty = BAR_WIDTH - filled

        val bar = "$BOLD$CYAN${FILLED.repeat(filled)}$RST$DM${EMPTY.repeat(empty)}$RST"
        val countStr = if (total > 0) " ${formatNumber(current)}/${formatNumber(total)}" else ""

        val line = "$DM$RAIL$RST  $BOLD$CYAN$glyph$RST  $currentPhase$countStr  $bar  $percent%"
        render(line)
    }

    /** 完成当前阶段，换行显示 done */
    fun finish(phase: String = currentPhase, detail: String = "") {
        clearLine()
        val doneLine = "$DM$RAIL$RST  $GRN✓$RST  $phase${if (detail.isNotBlank()) " $DM—$RST $detail" else ""}"
        println(doneLine)
        lastLine = ""
        if (phase == currentPhase) {
            currentPhase = ""
        }
    }

    /** 清除当前行 */
    fun clear() {
        clearLine()
        lastLine = ""
    }

    private fun render(line: String) {
        if (line != lastLine) {
            print("\r[K$line")
            System.out.flush()
            lastLine = line
        }
    }

    private fun clearLine() {
        print("\r[K")
        System.out.flush()
    }
}