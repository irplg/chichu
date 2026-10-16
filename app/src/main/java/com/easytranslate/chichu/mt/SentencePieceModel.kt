package com.easytranslate.chichu.mt

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 轻译 · SentencePiece 模型解析（ Marian 专用子集）
 *
 * 只解析 .spm protobuf 中的 pieces（piece 字符串 + score），
 * 用于 Viterbi 切分（无需完整分词器）。
 *
 * SentencePiece proto 结构（仅需要的字段）：
 *   ModelProto { repeated SentencePiece pieces = 1; }
 *   SentencePiece { optional string piece = 1; optional float score = 2; optional Type type = 3; }
 */
class SentencePieceModel(spmFile: File) {

    class Piece(val text: String, val score: Float, val type: Int)

    val pieces: List<Piece>
    val pieceToScore: Map<String, Float>
    val maxPieceLen: Int   // 最长 piece 的字符数（Viterbi 窗口上界）

    init {
        val data = spmFile.readBytes()
        pieces = parsePieces(data)
        pieceToScore = HashMap<String, Float>(pieces.size * 2).apply {
            for (p in pieces) put(p.text, p.score)
        }
        maxPieceLen = pieces.maxOf { it.text.length }
    }

    companion object {
        private fun parsePieces(data: ByteArray): List<Piece> {
            val result = ArrayList<Piece>()
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            while (buf.hasRemaining()) {
                val tag = readVarint(buf)
                val field = (tag ushr 3).toInt()
                val wire = (tag and 0x7).toInt()
                if (field == 1 && wire == 2) {
                    val len = readVarint(buf).toInt()
                    val start = buf.position()
                    val piece = parseSentencePiece(data, start, len)
                    if (piece != null) result.add(piece)
                    buf.position(start + len)
                } else {
                    skipField(buf, wire)
                }
            }
            return result
        }

        private fun parseSentencePiece(data: ByteArray, start: Int, len: Int): Piece? {
            var text: String? = null
            var score = 0f
            var type = 1
            val buf = ByteBuffer.wrap(data, start, len).order(ByteOrder.LITTLE_ENDIAN)
            while (buf.hasRemaining()) {
                val tag = readVarint(buf)
                val field = (tag ushr 3).toInt()
                val wire = (tag and 0x7).toInt()
                when {
                    field == 1 && wire == 2 -> {
                        val l = readVarint(buf).toInt()
                        text = String(data, buf.position(), l, Charsets.UTF_8)
                        buf.position(buf.position() + l)
                    }
                    field == 2 && wire == 5 -> {
                        score = buf.float
                    }
                    field == 3 && wire == 0 -> {
                        type = readVarint(buf).toInt()
                    }
                    else -> skipField(buf, wire)
                }
            }
            return text?.let { Piece(it, score, type) }
        }

        private fun readVarint(buf: ByteBuffer): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buf.get().toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }

        private fun skipField(buf: ByteBuffer, wire: Int) {
            when (wire) {
                0 -> readVarint(buf)
                1 -> buf.position(buf.position() + 8)
                2 -> {
                    val len = readVarint(buf).toInt()
                    buf.position(buf.position() + len)
                }
                5 -> buf.position(buf.position() + 4)
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
        }
    }
}
