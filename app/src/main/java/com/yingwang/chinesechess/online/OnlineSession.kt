package com.yingwang.chinesechess.online

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.yingwang.chinesechess.model.PieceColor
import com.yingwang.chinesechess.model.Position
import com.yingwang.chinesechess.online.OnlineProtocol.WireMove
import com.yingwang.chinesechess.online.OnlineProtocol.WireResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * One online game, seen from this player's seat: the Firebase side of playing a friend, in the
 * room layout of [OnlineProtocol] that the web version also reads and writes.
 *
 * Make one with [create], [join] or [resume]; then [start] attaches the listeners, which report
 * on the main thread to [listener]. The database rules (database.rules.json in the web repo) let
 * only the two seated players read the room, so every listener here is attached once seated.
 */
class OnlineSession private constructor(
    val code: String,
    val myColor: PieceColor,
    private val uid: String
) {
    /** Why a room could not be created, joined or picked up again. */
    class OnlineException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause)

    enum class Reason { NETWORK, NOT_FOUND, FULL, FINISHED }

    interface Listener {
        /** The friend took the other seat (also reported on [start] if they already had). */
        fun onOpponentJoined()
        /** Every move of the game so far, in order, each time the list changes. */
        fun onMoves(moves: List<WireMove>)
        /** The friend's connection: null before anyone has taken the other seat. */
        fun onOpponentConnected(connected: Boolean?)
        /** This device's own connection to the database. */
        fun onConnectionChanged(connected: Boolean)
        fun onResult(result: WireResult)
        /** When the game started, by the server's clock (null until both seats are taken). */
        fun onStartedAt(serverTime: Long?)
        /** The room is gone: cancelled, or cleared away after the game. */
        fun onRoomClosed()
    }

    var listener: Listener? = null

    val opponentColor: PieceColor = myColor.opposite()
    private val database = FirebaseDatabase.getInstance()
    private val game: DatabaseReference = database.getReference(ROOMS).child(code)
    private val mySeat: DatabaseReference = game.child("players").child(OnlineProtocol.colorKey(myColor))
    private val opponentSeat: DatabaseReference = game.child("players").child(OnlineProtocol.colorKey(opponentColor))

    /** The server's clock minus this phone's, from Firebase; for the running side's time. */
    private var serverOffset = 0L
    fun serverNow(): Long = System.currentTimeMillis() + serverOffset

    var opponentJoined = false
        private set
    var connected = false
        private set
    var status: String? = null
        private set
    private val attached = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()

    private fun listen(ref: DatabaseReference, onData: (DataSnapshot) -> Unit) {
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onData(snapshot)
            override fun onCancelled(error: DatabaseError) {
                // Reads are refused once the room is deleted (the seats that grant them are gone);
                // the meta listener, readable by anyone signed in, reports that as a closed room.
                Log.w(TAG, "listener on ${ref.path} cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(l)
        attached += ref to l
    }

    /** Attaches the listeners; call once, when this player has a seat. */
    fun start() {
        if (attached.isNotEmpty()) return
        listen(database.getReference(".info/serverTimeOffset")) { snap ->
            serverOffset = (snap.value as? Number)?.toLong() ?: 0L
        }
        listen(database.getReference(".info/connected")) { snap ->
            connected = snap.value == true
            if (connected) {
                // Presence, as the web does it: online now, offline as soon as the server loses us.
                mySeat.child("connected").onDisconnect().setValue(false)
                mySeat.child("connected").setValue(true)
            }
            listener?.onConnectionChanged(connected)
        }
        listen(game.child("meta")) { snap ->
            if (!snap.exists()) {
                listener?.onRoomClosed()
                return@listen
            }
            status = snap.child("status").value as? String
            listener?.onStartedAt((snap.child("startedAt").value as? Number)?.toLong())
        }
        listen(opponentSeat) { snap ->
            val joined = snap.child("uid").value is String
            if (joined && !opponentJoined) {
                opponentJoined = true
                // The joiner starts the game; the host says so too in case the joiner was cut
                // off in between (the web host does the same).
                if (status == OnlineProtocol.STATUS_WAITING) {
                    game.child("meta/status").setValue(OnlineProtocol.STATUS_PLAYING)
                }
                listener?.onOpponentJoined()
            }
            listener?.onOpponentConnected(if (joined) snap.child("connected").value == true else null)
        }
        listen(game.child("moves")) { snap -> listener?.onMoves(OnlineProtocol.decodeMoves(snap.value)) }
        listen(game.child("result")) { snap ->
            OnlineProtocol.decodeResult(snap.value)?.let { listener?.onResult(it) }
        }
    }

    /** Detaches every listener; the room and the seat stay as they are. */
    fun stop() {
        for ((ref, l) in attached) ref.removeEventListener(l)
        attached.clear()
    }

    /**
     * Writes move [index] with the server's time on it. The database turns down a move that
     * is not this player's to make; the move listener then shows the list without it.
     */
    fun sendMove(index: Int, from: Position, to: Position, onFailure: () -> Unit) {
        val data = OnlineProtocol.encodeMove(from, to)
        data["t"] = ServerValue.TIMESTAMP
        game.child("moves").child(index.toString()).setValue(data)
            .addOnFailureListener { e ->
                Log.w(TAG, "move $index refused", e)
                onFailure()
            }
    }

    /**
     * The result, and the room marked finished, in one write. If the other side already wrote
     * the same result this is a no-op; a different one is refused and theirs stands.
     */
    fun sendResult(result: WireResult) {
        val data = OnlineProtocol.encodeResult(result).toMutableMap()
        if (result.type == OnlineProtocol.ResultType.RESIGN) data["t"] = ServerValue.TIMESTAMP
        game.updateChildren(
            mapOf(
                "result" to data,
                "meta/status" to OnlineProtocol.STATUS_FINISHED
            )
        ).addOnFailureListener { e -> Log.w(TAG, "result not written", e) }
    }

    fun resign() = sendResult(WireResult(OnlineProtocol.ResultType.RESIGN, opponentColor))

    /**
     * Leaves the room: no longer present, listeners off. A room still waiting for the friend is
     * deleted. A finished one is cleared away by whoever leaves last, i.e. when the friend has
     * already gone; the presence write that would otherwise follow the deletion is cancelled first.
     */
    suspend fun leave(roomFinished: Boolean, opponentGone: Boolean) {
        stop()
        try {
            withTimeout(NETWORK_TIMEOUT_MS) {
                mySeat.child("connected").onDisconnect().cancel().await()
                if (!opponentJoined || (roomFinished && opponentGone)) {
                    game.removeValue().await()
                } else {
                    mySeat.child("connected").setValue(false).await()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "leaving room $code", e)
        }
    }

    /** The state of the room when it was picked up again. */
    class Snapshot(val session: OnlineSession, val status: String?, val moves: List<WireMove>, val result: WireResult?)

    companion object {
        private const val TAG = "OnlineSession"
        private const val ROOMS = "games"
        private const val NETWORK_TIMEOUT_MS = 20_000L

        private var screensInSight = 0
        private var used = false

        /**
         * Each screen's onStart (true) and onStop (false). While none is in sight the connection
         * is dropped and the friend sees this player offline; the moves made meanwhile arrive when
         * the app is back. Counted, so a screen stopping behind a newer one does not cut the
         * newer one off; and nothing connects until the player has gone online at all.
         */
        fun screenInSight(inSight: Boolean) {
            screensInSight = (screensInSight + if (inSight) 1 else -1).coerceAtLeast(0)
            if (!used) return
            val database = FirebaseDatabase.getInstance()
            if (screensInSight > 0) database.goOnline() else database.goOffline()
        }

        private suspend fun <T> network(block: suspend () -> T): T = try {
            if (!used) {
                used = true
                FirebaseDatabase.getInstance().goOnline()
            }
            withTimeout(NETWORK_TIMEOUT_MS) { block() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "timed out", e)
            throw OnlineException(Reason.NETWORK, e)
        } catch (e: OnlineException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "network call failed", e)
            throw OnlineException(Reason.NETWORK, e)
        }

        private fun isPermissionDenied(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.any { it.message?.contains("ermission denied", ignoreCase = true) == true }

        /** The anonymous account the web version also uses: no name, no e-mail, just an id. */
        suspend fun signIn(): String = network {
            val auth = FirebaseAuth.getInstance()
            auth.currentUser?.uid ?: auth.signInAnonymously().await().user?.uid
                ?: throw OnlineException(Reason.NETWORK)
        }

        private fun rooms() = FirebaseDatabase.getInstance().getReference(ROOMS)

        /** Opens a room with this player on [color] and a fresh code; the friend joins with the code. */
        suspend fun create(color: PieceColor): OnlineSession {
            val uid = signIn()
            return network {
                var code: String
                do {
                    code = OnlineProtocol.generateCode()
                } while (rooms().child(code).child("meta").get().await().exists())
                val colorKey = OnlineProtocol.colorKey(color)
                rooms().child(code).setValue(
                    mapOf(
                        "meta" to mapOf(
                            "createdAt" to ServerValue.TIMESTAMP,
                            "status" to OnlineProtocol.STATUS_WAITING,
                            "gameCode" to code,
                            "hostColor" to colorKey
                        ),
                        "players" to mapOf(colorKey to mapOf("uid" to uid, "connected" to true))
                    )
                ).await()
                OnlineSession(code, color, uid)
            }
        }

        /**
         * Takes the free seat in room [code]. Only the room's meta can be read before that (it
         * says whether the room is waiting and which side the host took); a room already playing
         * is open only to its two players, so this one is either in it already or turned away.
         */
        suspend fun join(code: String): OnlineSession {
            val uid = signIn()
            return network {
                val meta = rooms().child(code).child("meta").get().await()
                if (!meta.exists()) throw OnlineException(Reason.NOT_FOUND)
                when (meta.child("status").value as? String) {
                    OnlineProtocol.STATUS_WAITING -> {}
                    OnlineProtocol.STATUS_FINISHED -> throw OnlineException(Reason.FINISHED)
                    else -> return@network findOwnSeat(code, uid) ?: throw OnlineException(Reason.FULL)
                }
                val hostColor = OnlineProtocol.parseColor(meta.child("hostColor").value)
                val seat = hostColor?.opposite() ?: PieceColor.BLACK
                try {
                    rooms().child(code).child("players").child(OnlineProtocol.colorKey(seat))
                        .setValue(mapOf("uid" to uid, "connected" to true)).await()
                } catch (e: Exception) {
                    // Taken a moment ago (or by this player, from another device).
                    if (!isPermissionDenied(e)) throw e
                    return@network findOwnSeat(code, uid) ?: throw OnlineException(Reason.FULL, e)
                }
                rooms().child(code).child("meta").updateChildren(
                    mapOf("status" to OnlineProtocol.STATUS_PLAYING, "startedAt" to ServerValue.TIMESTAMP)
                ).await()
                OnlineSession(code, seat, uid)
            }
        }

        /** This player's seat in [code], if they have one (they can read the room only then). */
        private suspend fun findOwnSeat(code: String, uid: String): OnlineSession? {
            val room = try {
                rooms().child(code).get().await()
            } catch (e: Exception) {
                return null
            }
            for (color in PieceColor.values()) {
                if (room.child("players").child(OnlineProtocol.colorKey(color)).child("uid").value == uid) {
                    return OnlineSession(code, color, uid)
                }
            }
            return null
        }

        /**
         * Picks up the game in room [code] on [color] after the app was away: the room as it is
         * now, or null if it is gone or no longer this player's (signed in afresh, say).
         */
        suspend fun resume(code: String, color: PieceColor): Snapshot? {
            val uid = signIn()
            // Refused means the room is gone or no longer has this player in it; anything else
            // (offline, say) is worth another try, and is thrown as NETWORK.
            val room = network {
                try {
                    rooms().child(code).get().await()
                } catch (e: Exception) {
                    if (isPermissionDenied(e)) null else throw e
                }
            } ?: return null
            if (!room.exists()) return null
            if (room.child("players").child(OnlineProtocol.colorKey(color)).child("uid").value != uid) return null
            val session = OnlineSession(code, color, uid)
            return Snapshot(
                session,
                room.child("meta/status").value as? String,
                OnlineProtocol.decodeMoves(room.child("moves").value),
                OnlineProtocol.decodeResult(room.child("result").value)
            )
        }
    }
}
