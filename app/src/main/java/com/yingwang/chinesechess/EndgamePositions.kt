package com.yingwang.chinesechess

import com.yingwang.chinesechess.model.*

data class EndgamePosition(
    val name: String,
    val description: String,
    val pieces: List<Piece>,
    val firstPlayer: PieceColor = PieceColor.RED
)

object EndgamePositions {
    val positions = listOf(
        // 1. 重炮杀 (Double Cannon Checkmate)
        EndgamePosition(
            name = "重炮杀",
            description = "红方两炮配合将杀",
            pieces = listOf(
                Piece(PieceType.GENERAL, PieceColor.RED, Position(9, 4)),
                Piece(PieceType.CANNON, PieceColor.RED, Position(5, 4)),
                Piece(PieceType.CANNON, PieceColor.RED, Position(3, 3)),
                Piece(PieceType.GENERAL, PieceColor.BLACK, Position(0, 4)),
                Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 3)),
                Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 5))
            )
        ),
        // 2. 铁门栓 (Iron Door Bolt)
        EndgamePosition(
            name = "铁门栓",
            description = "车炮配合，铁门栓杀法",
            pieces = listOf(
                Piece(PieceType.GENERAL, PieceColor.RED, Position(9, 4)),
                Piece(PieceType.CHARIOT, PieceColor.RED, Position(0, 0)),
                Piece(PieceType.CANNON, PieceColor.RED, Position(2, 4)),
                Piece(PieceType.GENERAL, PieceColor.BLACK, Position(0, 4)),
                Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 3)),
                Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(1, 4))
            )
        ),
        // 3. 天地炮 (Heaven-Earth Cannons)
        EndgamePosition(
            name = "天地炮",
            description = "双炮一上一下配合攻杀",
            pieces = listOf(
                Piece(PieceType.GENERAL, PieceColor.RED, Position(9, 4)),
                Piece(PieceType.CANNON, PieceColor.RED, Position(0, 3)),
                Piece(PieceType.CANNON, PieceColor.RED, Position(9, 3)),
                Piece(PieceType.CHARIOT, PieceColor.RED, Position(5, 0)),
                Piece(PieceType.GENERAL, PieceColor.BLACK, Position(0, 4)),
                Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 5)),
                Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(1, 4)),
                Piece(PieceType.ELEPHANT, PieceColor.BLACK, Position(2, 6))
            )
        ),
        // 4. 马后炮 (Horse-Rear Cannon)
        EndgamePosition(
            name = "马后炮",
            description = "经典马后炮杀法",
            pieces = listOf(
                Piece(PieceType.GENERAL, PieceColor.RED, Position(9, 4)),
                Piece(PieceType.HORSE, PieceColor.RED, Position(2, 5)),
                Piece(PieceType.CANNON, PieceColor.RED, Position(5, 4)),
                Piece(PieceType.GENERAL, PieceColor.BLACK, Position(0, 4)),
                Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 3)),
                Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 5))
            )
        ),
        // 5. 双车错 (Double Chariot Interlock)
        EndgamePosition(
            name = "双车错",
            description = "双车配合将杀",
            pieces = listOf(
                Piece(PieceType.GENERAL, PieceColor.RED, Position(9, 4)),
                Piece(PieceType.CHARIOT, PieceColor.RED, Position(3, 0)),
                Piece(PieceType.CHARIOT, PieceColor.RED, Position(3, 8)),
                Piece(PieceType.GENERAL, PieceColor.BLACK, Position(0, 4)),
                Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 3)),
                Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 5)),
                Piece(PieceType.ELEPHANT, PieceColor.BLACK, Position(0, 2))
            )
        ),
        // 6. 车马冷着 (Chariot-Horse Cold Move)
        EndgamePosition(
            name = "车马冷着",
            description = "车马配合巧妙入局",
            pieces = listOf(
                Piece(PieceType.GENERAL, PieceColor.RED, Position(9, 4)),
                Piece(PieceType.CHARIOT, PieceColor.RED, Position(4, 8)),
                Piece(PieceType.HORSE, PieceColor.RED, Position(4, 5)),
                Piece(PieceType.GENERAL, PieceColor.BLACK, Position(0, 3)),
                Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 4)),
                Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(1, 3)),
                Piece(PieceType.SOLDIER, PieceColor.BLACK, Position(3, 4))
            )
        ),
        // 7. 大胆穿心 (Bold Heart Pierce)
        EndgamePosition(
            name = "大胆穿心",
            description = "弃子穿心攻杀",
            pieces = listOf(
                Piece(PieceType.GENERAL, PieceColor.RED, Position(9, 4)),
                Piece(PieceType.CHARIOT, PieceColor.RED, Position(5, 4)),
                Piece(PieceType.CANNON, PieceColor.RED, Position(7, 4)),
                Piece(PieceType.HORSE, PieceColor.RED, Position(5, 2)),
                Piece(PieceType.GENERAL, PieceColor.BLACK, Position(0, 4)),
                Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 3)),
                Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 5)),
                Piece(PieceType.CHARIOT, PieceColor.BLACK, Position(2, 0)),
                Piece(PieceType.ELEPHANT, PieceColor.BLACK, Position(0, 2))
            )
        ),
        // 8. 卧槽马 (Crouching Horse)
        EndgamePosition(
            name = "卧槽马",
            description = "马占卧槽位将杀",
            pieces = listOf(
                Piece(PieceType.GENERAL, PieceColor.RED, Position(9, 4)),
                Piece(PieceType.HORSE, PieceColor.RED, Position(2, 3)),
                Piece(PieceType.CHARIOT, PieceColor.RED, Position(4, 0)),
                Piece(PieceType.GENERAL, PieceColor.BLACK, Position(0, 4)),
                Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 3)),
                Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 5))
            )
        )
    )
}
