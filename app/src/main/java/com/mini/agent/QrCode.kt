package com.mini.agent

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max as kmax

/**
 * 二维码生成（字节模式，版本 1-10，纠错等级 M）。
 *
 * 为什么自己写：ZXing 是几 MB 的依赖，一个二维码不值当。
 * 这个实现覆盖实际够用的范围——网址、短文本，最长约 200 字节。
 *
 * 参照 ISO/IEC 18004 的关键步骤：
 *   数据编码 → 纠错码（Reed-Solomon）→ 交织 → 布点 → 遮罩 → 补齐格式信息
 */
object QrCode {

    /** 纠错等级 M：约 15% 容错，够用 */
    private const val ECC_M = 0

    /** 版本 1-10 的纠错块结构：[每块纠错码数, 组1块数, 组1数据码数, 组2块数, 组2数据码数] */
    private val ECC_TABLE = arrayOf(
        // ver 1  M: 10 纠错码字, 1 块 × 16 数据码字
        intArrayOf(10, 1, 16, 0, 0),
        // ver 2  M: 16, 1 × 28
        intArrayOf(16, 1, 28, 0, 0),
        // ver 3  M: 26, 1 × 44
        intArrayOf(26, 1, 44, 0, 0),
        // ver 4  M: 18, 2 × 32
        intArrayOf(18, 2, 32, 0, 0),
        // ver 5  M: 24, 2 × 43
        intArrayOf(24, 2, 43, 0, 0),
        // ver 6  M: 16, 4 × 27
        intArrayOf(16, 4, 27, 0, 0),
        // ver 7  M: 18, 4 × 31
        intArrayOf(18, 4, 31, 0, 0),
        // ver 8  M: 22, 2 × 38 + 2 × 39
        intArrayOf(22, 2, 38, 2, 39),
        // ver 9  M: 22, 3 × 36 + 2 × 37
        intArrayOf(22, 3, 36, 2, 37),
        // ver 10 M: 26, 4 × 43 + 1 × 44
        intArrayOf(26, 4, 43, 1, 44)
    )

    /** 版本 1-10 每级的总数据码字数（纠错等级 M） */
    private val TOTAL_DATA_CODEWORDS = intArrayOf(16, 28, 44, 64, 86, 108, 124, 154, 182, 216)

    /**
     * 对齐图案的中心坐标（版本 1-10）。
     * 下标 = 版本号 - 1。这张表错一行就会让整个数据区错位，
     * 而且图形区看着完全正常，只有扫不出来——极难发现。
     */
    private val ALIGN_POS = arrayOf(
        intArrayOf(),           // v1
        intArrayOf(6, 18),      // v2
        intArrayOf(6, 22),      // v3
        intArrayOf(6, 26),      // v4
        intArrayOf(6, 30),      // v5
        intArrayOf(6, 34),      // v6
        intArrayOf(6, 22, 38),  // v7
        intArrayOf(6, 24, 42),  // v8
        intArrayOf(6, 26, 46),  // v9
        intArrayOf(6, 28, 50)   // v10
    )

    /**
     * GF(256) 乘法，本原多项式 0x11D。
     *
     * 用直接乘法而不是对数表：QR 一年生成不了几次，
     * 256 次循环的代价可以忽略，换来的是「绝不会算错」。
     * 之前用对数表版本时 GF_EXP/GF_LOG 表初始化有坑，越界过一次——
     * 这种算法宁可慢，不能有「有时对有时错」。
     */
    private fun gfMul(a: Int, b: Int): Int {
        var x = a and 0xFF
        var y = b and 0xFF
        var p = 0
        for (i in 0 until 8) {
            if ((y and 1) != 0) p = p xor x
            val hi = x and 0x80
            x = (x shl 1) and 0xFF
            // 关键：异或本原多项式后必须再截断到 8 位。
            // 0x11D 是 9 位，不截断的话结果会带进第 9 位（多出 0x100），
            // 纠错码就会整片偏移——这个 bug 极难肉眼看出来。
            if (hi != 0) x = (x xor 0x11D) and 0xFF
            y = y shr 1
        }
        return p
    }

    /** α^i（i 可超过 255，α 的阶是 255） */
    private fun gfExp(i: Int): Int {
        var x = 1
        repeat(i % 255) {
            val hi = x and 0x80
            x = (x shl 1) and 0xFF
            if (hi != 0) x = (x xor 0x11D) and 0xFF
        }
        return x
    }

    /**
     * 生成多项式：∏(x + α^i)，i = 0..degree-1。
     *
     * 系数从高次到低次排列，长度 degree+1（最高次系数为 1）。
     */
    private fun rsGeneratorPoly(degree: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until degree) {
            g = polyMul(g, intArrayOf(1, gfExp(i)))
        }
        return g
    }

    /** 多项式相乘，系数高次在前 */
    private fun polyMul(a: IntArray, b: IntArray): IntArray {
        val out = IntArray(a.size + b.size - 1)
        for (i in a.indices) {
            for (j in b.indices) {
                out[i + j] = out[i + j] xor gfMul(a[i], b[j])
            }
        }
        return out
    }

    /**
     * 多项式取余（长除法）。
     * 系数高次在前，b[0] 必须是 1；返回余数（长度 b.size-1）。
     */
    private fun polyMod(a: IntArray, b: IntArray): IntArray {
        val rem = ArrayList<Int>(a.toList())
        while (rem.size >= b.size) {
            val factor = rem[0]
            if (factor != 0) {
                for (j in b.indices) rem[j] = rem[j] xor gfMul(b[j], factor)
            }
            rem.removeAt(0)
        }
        return rem.toIntArray()
    }

    /**
     * 多项式取余，得到纠错码字。
     */
    private fun rsEncode(data: IntArray, ecCount: Int): IntArray {
        val gen = rsGeneratorPoly(ecCount)
        val raw = IntArray(data.size + ecCount)
        for (i in data.indices) raw[i] = data[i]
        val rem = polyMod(raw, gen)
        return rem
    }

    /** 把文本编码成完整的码字流（数据码字 + 纠错码字，已交织） */
    private fun buildCodewords(bytes: ByteArray, version: Int): IntArray {
        val ecc = ECC_TABLE[version - 1]
        val ecPerBlock = ecc[0]
        val g1Blocks = ecc[1]
        val g1Data = ecc[2]
        val g2Blocks = ecc[3]
        val g2Data = ecc[4]
        val totalData = TOTAL_DATA_CODEWORDS[version - 1]

        // 字符计数位：版本 1-9 是 8 位，版本 10-26 是 16 位
        val countBits = if (version <= 9) 8 else 16

        val bits = ArrayList<Int>()
        // 模式指示符：字节模式 = 0100，占 4 位
        // （写成 0x4 直接 add 只会进 1 位，码字流整体错位——这个坑非常隐蔽）
        for (i in 3 downTo 0) bits.add((0x4 shr i) and 1)
        for (i in countBits - 1 downTo 0) {
            bits.add((bytes.size shr i) and 1)
        }
        for (b in bytes) {
            for (i in 7 downTo 0) bits.add((b.toInt() shr i) and 1)
        }

        // 终止符：最多 4 个 0，且不能超过容量
        val capacity = totalData * 8
        var t = 0
        while (bits.size < capacity && t < 4) {
            bits.add(0)
            t++
        }
        // 补到字节边界
        while (bits.size % 8 != 0) bits.add(0)

        // 交替填充字节 0xEC / 0x11 直到填满
        var padByte = 0xEC
        while (bits.size < capacity) {
            for (i in 7 downTo 0) bits.add((padByte shr i) and 1)
            padByte = if (padByte == 0xEC) 0x11 else 0xEC
        }

        // 数据码字
        val dataCw = IntArray(totalData)
        var bitPos = 0
        for (cw in dataCw.indices) {
            var b = 0
            for (k in 0 until 8) {
                b = (b shl 1) or bits[bitPos]
                bitPos++
            }
            dataCw[cw] = b
        }

        // 分块算纠错
        val blocks = ArrayList<IntArray>()
        val ecBlocks = ArrayList<IntArray>()
        var offset = 0
        repeat(g1Blocks) {
            val blk = dataCw.copyOfRange(offset, offset + g1Data)
            offset += g1Data
            blocks.add(blk)
            ecBlocks.add(rsEncode(blk, ecPerBlock))
        }
        repeat(g2Blocks) {
            val blk = dataCw.copyOfRange(offset, offset + g2Data)
            offset += g2Data
            blocks.add(blk)
            ecBlocks.add(rsEncode(blk, ecPerBlock))
        }

        // 交织：先按列取所有块的数据，再按列取所有块的纠错码
        val result = IntArray(totalData + ecPerBlock * (g1Blocks + g2Blocks))
        var r = 0
        var maxData = maxOf(g1Data, g2Data)
        for (col in 0 until maxData) {
            for (blk in blocks) {
                if (col < blk.size) result[r++] = blk[col]
            }
        }
        for (col in 0 until ecPerBlock) {
            for (blk in ecBlocks) {
                result[r++] = blk[col]
            }
        }
        return result
    }

    /**
     * 生成二维码位矩阵。
     *
     * 顺序很关键，一步都不能错：
     *   1. 画定位图案（含分隔符）
     *   2. 画校正图案（中心已被定位图案占用的跳过）
     *   3. 画定时图案（已被占用的跳过）
     *   4. 固定黑块 + 格式信息区，全部标记为保留区
     *   5. 之字形填数据（只填非保留区）
     *   6. 选遮罩并应用（只作用于非保留区）
     *   7. 最后写格式信息（它自己不算在遮罩里）
     *
     * 坐标一律用 (row, col) 表述，和标准文档一致；
     * 内部数组索引才是 [row * size + col]。
     */
    private fun buildMatrix(version: Int, codewords: IntArray): BooleanArray {
        val size = version * 4 + 17
        val m = BooleanArray(size * size)       // true = 黑
        val occ = BooleanArray(size * size)      // true = 已被功能图形/格式信息占用

        fun put(row: Int, col: Int, black: Boolean) {
            if (row < 0 || col < 0 || row >= size || col >= size) return
            m[row * size + col] = black
            occ[row * size + col] = true
        }

        // 1. 定位图案（7x7 + 一圈浅色分隔符，共 9x9 区域）
        fun finder(top: Int, left: Int) {
            for (r in -1..7) {
                for (c in -1..7) {
                    if (top + r < 0 || left + c < 0 || top + r >= size || left + c >= size) continue
                    val black = (r in 0..6 && (c == 0 || c == 6)) ||
                            (c in 0..6 && (r == 0 || r == 6)) ||
                            (r in 2..4 && c in 2..4)
                    put(top + r, left + c, black)
                }
            }
        }
        finder(0, 0)
        finder(0, size - 7)
        finder(size - 7, 0)

        // 2. 校正图案：中心坐标两两组合，中心已被定位图案占用的跳过
        val align = ALIGN_POS[version - 1]
        for (ar in align) {
            for (ac in align) {
                if (occ[ar * size + ac]) continue
                for (dr in -2..2) {
                    for (dc in -2..2) {
                        val black = dr == -2 || dr == 2 || dc == -2 || dc == 2 || (dr == 0 && dc == 0)
                        put(ar + dr, ac + dc, black)
                    }
                }
            }
        }

        // 3. 定时图案：行 6 和列 6 上的交替黑白，已被占用的跳过
        for (r in 8 until size - 8) {
            if (!occ[r * size + 6]) put(r, 6, r % 2 == 0)
        }
        for (c in 8 until size - 8) {
            if (!occ[6 * size + c]) put(6, c, c % 2 == 0)
        }

        // 4. 固定黑块（版本 1 就有，永远存在）
        put(size - 8, 8, true)

        // 5. 格式信息区：两份副本，全部标记为保留区（数据不能写进去）
        for ((row, col) in formatCells(size)) put(row, col, false)

        // 6. 之字形填数据：从右下角开始，两列一组，向上向下交替
        var bitIdx = 0
        var inc = -1
        var row = size - 1
        var col = size - 1
        while (col > 0) {
            if (col == 6) col--          // 跳过垂直定时列
            while (true) {
                for (c in 0..1) {
                    val x = col - c
                    if (!occ[row * size + x]) {
                        val bit = if (bitIdx < codewords.size * 8) {
                            (codewords[bitIdx / 8] shr (7 - bitIdx % 8)) and 1
                        } else 0
                        m[row * size + x] = (bit == 1)
                        bitIdx++
                    }
                }
                row += inc
                if (row < 0 || row >= size) {
                    row -= inc
                    inc = -inc
                    break
                }
            }
            col -= 2
        }

        // 7. 选遮罩：8 种都试一遍，惩罚分最低的胜出
        var bestMask = 0
        var bestScore = Int.MAX_VALUE
        for (mask in 0 until 8) {
            val test = m.copyOf()
            applyMask(test, occ, size, mask)
            val score = penalty(test, size)
            if (score < bestScore) {
                bestScore = score
                bestMask = mask
            }
        }
        applyMask(m, occ, size, bestMask)

        // 8. 写格式信息（在遮罩之后）
        writeFormat(m, size, ECC_M, bestMask)

        return m
    }

    // ---------- 遮罩 ----------

    /**
     * 应用遮罩。只作用于非保留区——功能图形和格式信息不该被遮罩改掉。
     */
    private fun applyMask(m: BooleanArray, reserved: BooleanArray, size: Int, mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (reserved[y * size + x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (y / 2 + x / 3) % 2 == 0
                    5 -> (x * y) % 2 + (x * y) % 3 == 0
                    6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
                    else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
                }
                if (invert) m[y * size + x] = !m[y * size + x]
            }
        }
    }

    // ---------- 格式信息 ----------

    /**
     * 格式信息占用的格子（两份副本，共 30 格）。
     *
     * 位置必须和标准完全一致，写错一个格子扫码就失败。
     * 这里统一列出来：画保留区、写格式信息都用它，保证两处永远同步。
     */
    private fun formatCells(size: Int): List<Pair<Int, Int>> {
        val cells = ArrayList<Pair<Int, Int>>(30)
        // 第一份：竖着那份，沿第 8 列
        //   row 0..5, 7, 8，以及 row size-7..size-1
        for (i in 0..5) cells.add(Pair(i, 8))
        cells.add(Pair(7, 8))
        cells.add(Pair(8, 8))
        for (i in 8..14) cells.add(Pair(size - 15 + i, 8))
        // 第二份：横着那份，沿第 8 行
        //   col size-1..size-8，然后 col 7，最后 col 5..0
        for (i in 0..7) cells.add(Pair(8, size - 1 - i))
        cells.add(Pair(8, 7))
        for (i in 9..14) cells.add(Pair(8, 15 - i - 1))
        return cells
    }

    /**
     * 写格式信息：纠错等级 + 遮罩编号，15 位 BCH(15,5) 码。
     */
    private fun writeFormat(m: BooleanArray, size: Int, ecc: Int, mask: Int) {
        val data = (ecc shl 3) or mask
        val bits = bchTypeInfo(data)

        fun bit(i: Int) = ((bits shr i) and 1) == 1

        // 第一份：竖着那份，沿第 8 列
        for (i in 0..5) m[i * size + 8] = bit(i)             // (col=8, row=0..5)
        m[7 * size + 8] = bit(6)                             // (col=8, row=7)
        m[8 * size + 8] = bit(7)                             // (col=8, row=8)
        for (i in 8..14) m[(size - 15 + i) * size + 8] = bit(i)

        // 第二份：横着那份，沿第 8 行
        for (i in 0..7) m[8 * size + (size - 1 - i)] = bit(i)
        m[8 * size + 7] = bit(8)
        for (i in 9..14) m[8 * size + (15 - i - 1)] = bit(i)
    }

    /**
     * BCH(15,5) 编码，返回 15 位格式信息（含 0x5412 掩码）。
     *
     * 生成多项式 G15 = 0x537，掩码 0x5412。
     */
    private fun bchTypeInfo(data: Int): Int {
        var d = data shl 10
        while (bitLength(d) - bitLength(0x537) >= 0) {
            d = d xor (0x537 shl (bitLength(d) - bitLength(0x537)))
        }
        return ((data shl 10) or d) xor 0x5412
    }

    private fun bitLength(v: Int): Int {
        var n = 0
        var x = v
        while (x != 0) {
            n++
            x = x shr 1
        }
        return n
    }

    // ---------- 遮罩惩罚分 ----------

    /**
     * 四条规则之和，越小越好：
     * 1. 同行/列连续同色 5 格以上
     * 2. 2x2 同色块
     * 3. 类似定位图案的 1:1:3:1:1 组合
     * 4. 黑白比例偏离 50%
     */
    private fun penalty(m: BooleanArray, size: Int): Int {
        var score = 0

        // 规则 1
        for (y in 0 until size) {
            var run = 1
            for (x in 1 until size) {
                if (m[y * size + x] == m[y * size + x - 1]) {
                    run++
                    if (run == 5) score += 3 else if (run > 5) score += 1
                } else run = 1
            }
        }
        for (x in 0 until size) {
            var run = 1
            for (y in 1 until size) {
                if (m[y * size + x] == m[(y - 1) * size + x]) {
                    run++
                    if (run == 5) score += 3 else if (run > 5) score += 1
                } else run = 1
            }
        }

        // 规则 2
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val v = m[y * size + x]
                if (v == m[y * size + x + 1] && v == m[(y + 1) * size + x] &&
                    v == m[(y + 1) * size + x + 1]
                ) score += 3
            }
        }

        // 规则 3：两种方向的黑白模式
        val p1 = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        val p2 = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)
        for (y in 0 until size) {
            for (x in 0..size - 11) {
                var m1 = true
                var m2 = true
                for (k in 0 until 11) {
                    if (m[y * size + x + k] != p1[k]) m1 = false
                    if (m[y * size + x + k] != p2[k]) m2 = false
                }
                if (m1 || m2) score += 40
            }
        }

        // 规则 4
        var dark = 0
        for (v in m) if (v) dark++
        val percent = dark * 100 / (size * size)
        val k = when {
            percent < 45 -> 0
            percent < 50 -> percent - 45
            percent < 55 -> 50 - percent
            else -> percent - 55
        }
        score += k * 10

        return score
    }

    /** 生成位图；size 是模块边长（像素/模块） */
    fun encode(content: String, sizePx: Int): Bitmap {
        val bytes = content.toByteArray(Charsets.UTF_8)

        // 选版本：按字节模式的真实容量算
        // 容量 = (总数据码字 * 8 - 4位模式 - countBits位长度) / 8
        var version = 0
        for (v in 1..10) {
            val countBits = if (v <= 9) 8 else 16
            val capacity = (TOTAL_DATA_CODEWORDS[v - 1] * 8 - 4 - countBits) / 8
            if (bytes.size <= capacity) {
                version = v
                break
            }
        }
        if (version == 0) {
            throw IllegalArgumentException("内容太长（${bytes.size} 字节），二维码放不下")
        }

        val codewords = buildCodewords(bytes, version)
        val matrix = buildMatrix(version, codewords)
        val size = version * 4 + 17

        // 模块尺寸：整体留白 4 模块（quiet zone），凑成整数
        val quiet = 4
        val total = size + quiet * 2
        val moduleSize = kmax(1, sizePx / total)
        val dim = total * moduleSize

        val bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
        for (y in 0 until dim) {
            for (x in 0 until dim) {
                val mx = x / moduleSize - quiet
                val my = y / moduleSize - quiet
                val on = mx in 0 until size && my in 0 until size &&
                        matrix[my * size + mx]
                bmp.setPixel(x, y, if (on) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
