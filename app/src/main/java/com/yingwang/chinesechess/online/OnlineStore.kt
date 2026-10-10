package com.yingwang.chinesechess.online

import android.content.Context
import com.yingwang.chinesechess.model.PieceColor

/**
 * The online game this phone is in, kept so the app can go back to the room after it was in the
 * background or its process was ended. The moves themselves live in the database; the anonymous
 * account that holds the seat is kept by Firebase.
 */
object OnlineStore {
    private const val PREFS = "online_game"
    private const val KEY_CODE = "code"
    private const val KEY_COLOR = "color"

    data class Saved(val code: String, val color: PieceColor)

    fun save(context: Context, code: String, color: PieceColor) {
        prefs(context).edit()
            .putString(KEY_CODE, code)
            .putString(KEY_COLOR, OnlineProtocol.colorKey(color))
            .apply()
    }

    fun load(context: Context): Saved? {
        val p = prefs(context)
        val code = p.getString(KEY_CODE, null)?.takeIf { OnlineProtocol.isValidCode(it) } ?: return null
        val color = OnlineProtocol.parseColor(p.getString(KEY_COLOR, null)) ?: return null
        return Saved(code, color)
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
