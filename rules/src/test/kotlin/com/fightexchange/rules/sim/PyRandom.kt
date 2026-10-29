package com.fightexchange.rules.sim

/**
 * Bit-exact port of CPython's `random.Random` (MT19937) for the parts sim.py uses:
 * seeding with a small non-negative int, `random()` and `shuffle()`.
 */
class PyRandom(seed: Int) {
    private val mt = IntArray(N)
    private var mti = N + 1

    init {
        require(seed >= 0)
        initByArray(intArrayOf(seed))
    }

    private fun initGenrand(s: Int) {
        mt[0] = s
        for (i in 1 until N) {
            mt[i] = 1812433253 * (mt[i - 1] xor (mt[i - 1] ushr 30)) + i
        }
        mti = N
    }

    private fun initByArray(key: IntArray) {
        initGenrand(19650218)
        var i = 1
        var j = 0
        var k = maxOf(N, key.size)
        while (k > 0) {
            mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1664525)) + key[j] + j
            i++
            j++
            if (i >= N) {
                mt[0] = mt[N - 1]
                i = 1
            }
            if (j >= key.size) j = 0
            k--
        }
        k = N - 1
        while (k > 0) {
            mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1566083941)) - i
            i++
            if (i >= N) {
                mt[0] = mt[N - 1]
                i = 1
            }
            k--
        }
        mt[0] = UPPER_MASK
    }

    private fun nextInt(): Int {
        if (mti >= N) twist()
        var y = mt[mti++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680.toInt())
        y = y xor ((y shl 15) and 0xefc60000.toInt())
        y = y xor (y ushr 18)
        return y
    }

    private fun twist() {
        fun mix(a: Int, b: Int, c: Int): Int {
            val y = (a and UPPER_MASK) or (b and LOWER_MASK)
            return c xor (y ushr 1) xor (if (y and 1 != 0) MATRIX_A else 0)
        }
        for (kk in 0 until N - M) mt[kk] = mix(mt[kk], mt[kk + 1], mt[kk + M])
        for (kk in N - M until N - 1) mt[kk] = mix(mt[kk], mt[kk + 1], mt[kk + (M - N)])
        mt[N - 1] = mix(mt[N - 1], mt[0], mt[M - 1])
        mti = 0
    }

    fun random(): Double {
        val a = (nextInt() ushr 5).toLong()
        val b = (nextInt() ushr 6).toLong()
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0)
    }

    private fun getrandbits(k: Int): Long = (nextInt().toLong() and 0xffffffffL) ushr (32 - k)

    private fun randbelow(n: Int): Int {
        val k = 32 - Integer.numberOfLeadingZeros(n)
        var r = getrandbits(k)
        while (r >= n) r = getrandbits(k)
        return r.toInt()
    }

    fun <T> shuffle(x: MutableList<T>) {
        for (i in x.size - 1 downTo 1) {
            val j = randbelow(i + 1)
            val t = x[i]
            x[i] = x[j]
            x[j] = t
        }
    }

    private companion object {
        const val N = 624
        const val M = 397
        const val MATRIX_A = 0x9908b0df.toInt()
        const val UPPER_MASK = 0x80000000.toInt()
        const val LOWER_MASK = 0x7fffffff
    }
}
