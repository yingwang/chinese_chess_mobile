"""
Hand-crafted evaluation function for Chinese Chess (象棋).

Ported from the Kotlin Evaluator.kt in the mobile app.
Evaluates positions from RED's perspective (positive = RED advantage).
"""

from __future__ import annotations

from game import ChineseChessGame, Color, PieceType, Piece

# Base material values (from PieceType.kt)
BASE_VALUES = {
    PieceType.GENERAL: 10000,
    PieceType.ADVISOR: 120,
    PieceType.ELEPHANT: 120,
    PieceType.HORSE: 400,
    PieceType.CHARIOT: 900,
    PieceType.CANNON: 450,
    PieceType.SOLDIER: 100,
}

# Piece-square tables — from BLACK's perspective (row 0 = black side).
# For RED pieces, the row index is mirrored: use (9 - row).

GENERAL_TABLE = [
    [0, 0, 0, 8, 9, 8, 0, 0, 0],
    [0, 0, 0, 9, 9, 9, 0, 0, 0],
    [0, 0, 0, 8, 9, 8, 0, 0, 0],
    [0, 0, 0, 0, 0, 0, 0, 0, 0],
    [0, 0, 0, 0, 0, 0, 0, 0, 0],
    [0, 0, 0, 0, 0, 0, 0, 0, 0],
    [0, 0, 0, 0, 0, 0, 0, 0, 0],
    [0, 0, 0, 8, 9, 8, 0, 0, 0],
    [0, 0, 0, 9, 9, 9, 0, 0, 0],
    [0, 0, 0, 8, 9, 8, 0, 0, 0],
]

ADVISOR_TABLE = [
    [0, 0, 0, 20, 0, 20, 0, 0, 0],
    [0, 0, 0, 0, 23, 0, 0, 0, 0],
    [0, 0, 0, 20, 0, 20, 0, 0, 0],
    [0, 0, 0, 0, 0, 0, 0, 0, 0],
    [0, 0, 0, 0, 0, 0, 0, 0, 0],
    [0, 0, 0, 0, 0, 0, 0, 0, 0],
    [0, 0, 0, 0, 0, 0, 0, 0, 0],
    [0, 0, 0, 20, 0, 20, 0, 0, 0],
    [0, 0, 0, 0, 23, 0, 0, 0, 0],
    [0, 0, 0, 20, 0, 20, 0, 0, 0],
]

ELEPHANT_TABLE = [
    [0, 0, 20, 0, 0, 0, 20, 0, 0],
    [0, 0, 0, 0, 0, 0, 0, 0, 0],
    [18, 0, 0, 0, 23, 0, 0, 0, 18],
    [0, 0, 0, 0, 0, 0, 0, 0, 0],
    [0, 0, 20, 0, 0, 0, 20, 0, 0],
    [0, 0, 20, 0, 0, 0, 20, 0, 0],
    [0, 0, 0, 0, 0, 0, 0, 0, 0],
    [18, 0, 0, 0, 23, 0, 0, 0, 18],
    [0, 0, 0, 0, 0, 0, 0, 0, 0],
    [0, 0, 20, 0, 0, 0, 20, 0, 0],
]

HORSE_TABLE = [
    [90, 90, 90, 96, 90, 96, 90, 90, 90],
    [90, 96, 103, 97, 94, 97, 103, 96, 90],
    [92, 98, 99, 103, 99, 103, 99, 98, 92],
    [93, 108, 100, 107, 100, 107, 100, 108, 93],
    [90, 100, 99, 103, 104, 103, 99, 100, 90],
    [90, 98, 101, 102, 103, 102, 101, 98, 90],
    [92, 94, 98, 95, 98, 95, 98, 94, 92],
    [93, 92, 94, 95, 92, 95, 94, 92, 93],
    [85, 90, 92, 93, 78, 93, 92, 90, 85],
    [88, 85, 90, 88, 90, 88, 90, 85, 88],
]

CHARIOT_TABLE = [
    [206, 208, 207, 213, 214, 213, 207, 208, 206],
    [206, 212, 209, 216, 233, 216, 209, 212, 206],
    [206, 208, 207, 214, 216, 214, 207, 208, 206],
    [206, 213, 213, 216, 216, 216, 213, 213, 206],
    [208, 211, 211, 214, 215, 214, 211, 211, 208],
    [208, 212, 212, 214, 215, 214, 212, 212, 208],
    [204, 209, 204, 212, 214, 212, 204, 209, 204],
    [198, 208, 204, 212, 212, 212, 204, 208, 198],
    [200, 208, 206, 212, 200, 212, 206, 208, 200],
    [194, 206, 204, 212, 200, 212, 204, 206, 194],
]

CANNON_TABLE = [
    [100, 100, 96, 91, 90, 91, 96, 100, 100],
    [98, 98, 96, 92, 89, 92, 96, 98, 98],
    [97, 97, 96, 91, 92, 91, 96, 97, 97],
    [96, 99, 99, 98, 100, 98, 99, 99, 96],
    [96, 96, 96, 96, 100, 96, 96, 96, 96],
    [95, 96, 99, 96, 100, 96, 99, 96, 95],
    [96, 96, 96, 96, 96, 96, 96, 96, 96],
    [97, 96, 100, 99, 101, 99, 100, 96, 97],
    [96, 97, 98, 98, 98, 98, 98, 97, 96],
    [96, 96, 97, 99, 99, 99, 97, 96, 96],
]

SOLDIER_TABLE = [
    [9, 9, 9, 11, 13, 11, 9, 9, 9],
    [19, 24, 34, 42, 44, 42, 34, 24, 19],
    [19, 24, 32, 37, 37, 37, 32, 24, 19],
    [19, 23, 27, 29, 30, 29, 27, 23, 19],
    [14, 18, 20, 27, 29, 27, 20, 18, 14],
    [7, 0, 13, 0, 16, 0, 13, 0, 7],
    [7, 0, 7, 0, 15, 0, 7, 0, 7],
    [0, 0, 0, 0, 0, 0, 0, 0, 0],
    [0, 0, 0, 0, 0, 0, 0, 0, 0],
    [0, 0, 0, 0, 0, 0, 0, 0, 0],
]

PST = {
    PieceType.GENERAL: GENERAL_TABLE,
    PieceType.ADVISOR: ADVISOR_TABLE,
    PieceType.ELEPHANT: ELEPHANT_TABLE,
    PieceType.HORSE: HORSE_TABLE,
    PieceType.CHARIOT: CHARIOT_TABLE,
    PieceType.CANNON: CANNON_TABLE,
    PieceType.SOLDIER: SOLDIER_TABLE,
}

# Checkmate / stalemate sentinel scores
CHECKMATE_SCORE = 90000


def _positional_value(piece_type: PieceType, color: Color,
                      row: int, col: int) -> int:
    """Look up the piece-square table value for a piece.

    Tables are from BLACK's perspective. For RED pieces, mirror the row.
    """
    table = PST[piece_type]
    r = row if color == Color.BLACK else 9 - row
    return table[r][col]


def _piece_value(piece_type: PieceType, color: Color,
                 row: int, col: int) -> int:
    """Total piece value = base material + positional bonus."""
    return BASE_VALUES[piece_type] + _positional_value(piece_type, color, row, col)


def _evaluate_king_safety(game: ChineseChessGame) -> int:
    """Penalize exposed king (missing advisors/elephants).

    Returns score contribution from RED's perspective.
    """
    score = 0
    for color in (Color.RED, Color.BLACK):
        pieces = game.get_all_pieces(color)
        advisor_count = sum(1 for _, p in pieces if p.type == PieceType.ADVISOR)
        elephant_count = sum(1 for _, p in pieces if p.type == PieceType.ELEPHANT)

        safety = 0
        if advisor_count == 0:
            safety -= 40
        elif advisor_count == 1:
            safety -= 15
        if elephant_count == 0:
            safety -= 25
        elif elephant_count == 1:
            safety -= 10

        # Bonus for general in centre column
        general_pos = game._general_pos.get(color)
        if general_pos is not None and general_pos[1] == 4:
            safety += 10

        if color == Color.RED:
            score += safety
        else:
            score -= safety

    return score


def _evaluate_threats(game: ChineseChessGame) -> int:
    """Tactical bonuses for pieces in advantageous positions."""
    score = 0
    total_pieces = len(game.board)

    for (row, col), piece in game.board.items():
        is_red = piece.color == Color.RED

        if piece.type == PieceType.SOLDIER:
            crossed = (row <= 4) if is_red else (row >= 5)
            if crossed:
                bonus = 20
                score += bonus if is_red else -bonus
                if 3 <= col <= 5:
                    score += 15 if is_red else -15

        elif piece.type == PieceType.HORSE:
            in_enemy = (row <= 4) if is_red else (row >= 5)
            if in_enemy:
                score += 15 if is_red else -15
            near_general = (row <= 2) if is_red else (row >= 7)
            if near_general and 2 <= col <= 6:
                score += 25 if is_red else -25

        elif piece.type == PieceType.CHARIOT:
            deep = (row <= 2) if is_red else (row >= 7)
            if deep:
                score += 20 if is_red else -20

        elif piece.type == PieceType.CANNON:
            if total_pieces <= 10:
                score += -30 if is_red else 30

    return score


def _evaluate_chariot_activity(game: ChineseChessGame) -> int:
    """Open-file bonus for chariots, penalty for undeveloped chariot."""
    score = 0
    for (row, col), piece in game.board.items():
        if piece.type != PieceType.CHARIOT:
            continue
        is_red = piece.color == Color.RED

        # Count own soldiers on same column
        own_soldiers_on_file = 0
        positions = game._red_pieces if is_red else game._black_pieces
        for pos in positions:
            p = game.board.get(pos)
            if p is not None and p.type == PieceType.SOLDIER and pos[1] == col:
                own_soldiers_on_file += 1

        if own_soldiers_on_file == 0:
            score += 15 if is_red else -15

        on_home_rank = (row == 9) if is_red else (row == 0)
        if on_home_rank:
            score += -20 if is_red else 20

    return score


def _evaluate_horse_coordination(game: ChineseChessGame) -> int:
    """Connected / protected horses bonus."""
    score = 0
    for color in (Color.RED, Color.BLACK):
        horses = [(pos, p) for pos, p in game.get_all_pieces(color)
                  if p.type == PieceType.HORSE]
        if len(horses) == 2:
            h1 = horses[0][0]
            h2 = horses[1][0]
            dist = abs(h1[0] - h2[0]) + abs(h1[1] - h2[1])
            if 2 <= dist <= 4:
                score += 10 if color == Color.RED else -10
    return score


def evaluate(game: ChineseChessGame) -> int:
    """Evaluate board from RED's perspective.

    Positive = RED advantage, negative = BLACK advantage.

    Args:
        game: current game state.

    Returns:
        Integer evaluation score.
    """
    # Quick terminal checks
    if game.is_checkmate(game.current_player):
        return -CHECKMATE_SCORE if game.current_player == Color.RED else CHECKMATE_SCORE
    if game.is_stalemate(game.current_player):
        # In xiangqi stalemate is a loss for the stalemated side
        return -CHECKMATE_SCORE if game.current_player == Color.RED else CHECKMATE_SCORE

    score = 0

    # Material + positional (piece-square tables)
    for (row, col), piece in game.board.items():
        val = _piece_value(piece.type, piece.color, row, col)
        if piece.color == Color.RED:
            score += val
        else:
            score -= val

    # Additional evaluation terms
    score += _evaluate_king_safety(game)
    score += _evaluate_threats(game)
    score += _evaluate_chariot_activity(game)
    score += _evaluate_horse_coordination(game)

    # Check bonus (tempo)
    if game.is_in_check(Color.RED):
        score -= 30
    if game.is_in_check(Color.BLACK):
        score += 30

    return score
