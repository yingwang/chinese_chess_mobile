"""
Encoding utilities for Chinese Chess (象棋) neural network.

Handles board-to-tensor conversion and move indexing for the policy head.
The move space covers all geometrically possible moves for all piece types
on a 10x9 board.
"""

import torch
import numpy as np
from typing import Dict, List, Tuple, Optional

ROWS = 10
COLS = 9
NUM_SQUARES = ROWS * COLS  # 90

# Piece type indices (0-6)
PIECE_GENERAL = 0
PIECE_ADVISOR = 1
PIECE_ELEPHANT = 2
PIECE_HORSE = 3
PIECE_CHARIOT = 4
PIECE_CANNON = 5
PIECE_SOLDIER = 6

NUM_PIECE_TYPES = 7

# Feature plane layout:
# 0-6:   Red pieces (General, Advisor, Elephant, Horse, Chariot, Cannon, Soldier)
# 7-13:  Black pieces (same order)
# 14:    Current player to move (all 1s = Red, all 0s = Black)
NUM_INPUT_CHANNELS = 15

# Piece character mapping for board parsing
PIECE_CHARS = {
    'K': (0, PIECE_GENERAL),   # Red General
    'A': (0, PIECE_ADVISOR),   # Red Advisor
    'B': (0, PIECE_ELEPHANT),  # Red Elephant (Bishop)
    'N': (0, PIECE_HORSE),     # Red Horse (Knight)
    'R': (0, PIECE_CHARIOT),   # Red Chariot (Rook)
    'C': (0, PIECE_CANNON),    # Red Cannon
    'P': (0, PIECE_SOLDIER),   # Red Soldier (Pawn)
    'k': (1, PIECE_GENERAL),   # Black General
    'a': (1, PIECE_ADVISOR),   # Black Advisor
    'b': (1, PIECE_ELEPHANT),  # Black Elephant
    'n': (1, PIECE_HORSE),     # Black Horse
    'r': (1, PIECE_CHARIOT),   # Black Chariot
    'c': (1, PIECE_CANNON),    # Black Cannon
    'p': (1, PIECE_SOLDIER),   # Black Soldier
}


def _pos_to_rc(pos: int) -> Tuple[int, int]:
    """Convert flat position (0-89) to (row, col)."""
    return pos // COLS, pos % COLS


def _rc_to_pos(row: int, col: int) -> int:
    """Convert (row, col) to flat position (0-89)."""
    return row * COLS + col


def _in_bounds(r: int, c: int) -> bool:
    return 0 <= r < ROWS and 0 <= c < COLS


def _generate_all_possible_moves() -> Tuple[List[Tuple[int, int]], Dict[Tuple[int, int], int]]:
    """
    Enumerate all possible (from_pos, to_pos) moves in Chinese Chess.

    The action space is the union of:
    1. Cardinal moves (Chariot, Cannon, General, Soldier): slide along rank/file
       from any square, 1 to max(ROWS-1, COLS-1) steps in 4 directions.
    2. Horse/Knight moves: 8 L-shaped jumps from any square.
    3. Advisor moves: 1-step diagonal, constrained to the 5 palace squares per
       side where advisors can actually stand, targeting only palace squares.
    4. Elephant moves: 2-step diagonal, constrained to the 7 valid positions per
       side, targeting only same-half valid elephant positions.

    This yields exactly 2086 unique moves.
    """
    moves_set = set()

    for r in range(ROWS):
        for c in range(COLS):
            from_pos = _rc_to_pos(r, c)

            # Cardinal moves: Chariot, Cannon, General, Soldier
            for dr, dc in [(0, 1), (0, -1), (1, 0), (-1, 0)]:
                for step in range(1, max(ROWS, COLS)):
                    nr, nc = r + dr * step, c + dc * step
                    if _in_bounds(nr, nc):
                        moves_set.add((from_pos, _rc_to_pos(nr, nc)))

            # Horse/Knight: L-shaped jumps
            for dr, dc in [(-2, -1), (-2, 1), (-1, -2), (-1, 2),
                           (1, -2), (1, 2), (2, -1), (2, 1)]:
                nr, nc = r + dr, c + dc
                if _in_bounds(nr, nc):
                    moves_set.add((from_pos, _rc_to_pos(nr, nc)))

    # Advisor moves: 1-step diagonal within palace only.
    # Valid advisor squares: corners and center of each 3x3 palace.
    _advisor_squares = [
        (0, 3), (0, 5), (1, 4), (2, 3), (2, 5),   # Black palace
        (7, 3), (7, 5), (8, 4), (9, 3), (9, 5),   # Red palace
    ]
    for r, c in _advisor_squares:
        from_pos = _rc_to_pos(r, c)
        for dr, dc in [(-1, -1), (-1, 1), (1, -1), (1, 1)]:
            nr, nc = r + dr, c + dc
            if _in_bounds(nr, nc):
                in_palace = ((0 <= nr <= 2 and 3 <= nc <= 5)
                             or (7 <= nr <= 9 and 3 <= nc <= 5))
                if in_palace:
                    moves_set.add((from_pos, _rc_to_pos(nr, nc)))

    # Elephant moves: 2-step diagonal, constrained to valid elephant positions
    # and same half of the board.
    _elephant_squares = [
        (0, 2), (0, 6), (2, 0), (2, 4), (2, 8), (4, 2), (4, 6),   # Black
        (5, 2), (5, 6), (7, 0), (7, 4), (7, 8), (9, 2), (9, 6),   # Red
    ]
    for r, c in _elephant_squares:
        from_pos = _rc_to_pos(r, c)
        for dr, dc in [(-2, -2), (-2, 2), (2, -2), (2, 2)]:
            nr, nc = r + dr, c + dc
            if _in_bounds(nr, nc):
                same_half = (r <= 4 and nr <= 4) or (r >= 5 and nr >= 5)
                if same_half:
                    moves_set.add((from_pos, _rc_to_pos(nr, nc)))

    # Sort for deterministic indexing
    moves_list = sorted(moves_set)
    move_to_idx = {move: idx for idx, move in enumerate(moves_list)}

    return moves_list, move_to_idx


# Pre-compute the move space at module load time
ALL_MOVES, MOVE_TO_INDEX = _generate_all_possible_moves()
NUM_ACTIONS = len(ALL_MOVES)  # Should be 2086


def get_all_possible_moves() -> List[Tuple[int, int]]:
    """Return list of all (from_pos, to_pos) pairs in the action space."""
    return ALL_MOVES


def move_to_index(from_pos: int, to_pos: int) -> int:
    """
    Convert a move (from_pos, to_pos) to a policy index.

    Args:
        from_pos: source square (0-89), encoded as row*9 + col
        to_pos: destination square (0-89)

    Returns:
        Index into the policy output vector.

    Raises:
        KeyError: if the move is not in the action space.
    """
    return MOVE_TO_INDEX[(from_pos, to_pos)]


def index_to_move(index: int) -> Tuple[int, int]:
    """
    Convert a policy index back to (from_pos, to_pos).

    Args:
        index: index into the policy output vector

    Returns:
        (from_pos, to_pos) tuple with flat square indices.
    """
    return ALL_MOVES[index]


def board_to_tensor(
    board_state: Dict[int, Tuple[int, int]],
    current_player: int = 0,
) -> torch.Tensor:
    """
    Convert a board state dictionary to a feature tensor.

    Args:
        board_state: mapping from flat position (0-89) to (color, piece_type)
            where color is 0=Red, 1=Black and piece_type is 0-6 per the
            PIECE_* constants.
        current_player: 0 for Red to move, 1 for Black to move.

    Returns:
        Tensor of shape (15, 10, 9) with float32 values.
    """
    tensor = torch.zeros(NUM_INPUT_CHANNELS, ROWS, COLS, dtype=torch.float32)

    for pos, (color, piece_type) in board_state.items():
        row, col = _pos_to_rc(pos)
        channel = color * NUM_PIECE_TYPES + piece_type
        tensor[channel, row, col] = 1.0

    # Current player plane
    if current_player == 0:  # Red to move
        tensor[14, :, :] = 1.0
    # Black to move: plane stays all zeros

    return tensor


def fen_to_board_state(fen: str) -> Tuple[Dict[int, Tuple[int, int]], int]:
    """
    Parse a Chinese Chess FEN string into board_state and current_player.

    FEN format: piece_placement current_player
    Example: "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w"

    Rows are listed top to bottom (Black side first, row 0 to row 9).

    Args:
        fen: FEN string for Chinese Chess position.

    Returns:
        (board_state, current_player) where current_player is 0=Red, 1=Black.
    """
    parts = fen.strip().split()
    placement = parts[0]
    current_player = 0 if len(parts) < 2 or parts[1] == 'w' else 1

    board_state = {}
    row = 0
    col = 0

    for ch in placement:
        if ch == '/':
            row += 1
            col = 0
        elif ch.isdigit():
            col += int(ch)
        elif ch in PIECE_CHARS:
            pos = _rc_to_pos(row, col)
            board_state[pos] = PIECE_CHARS[ch]
            col += 1

    return board_state, current_player


def board_state_to_fen(
    board_state: Dict[int, Tuple[int, int]],
    current_player: int = 0,
) -> str:
    """Convert board_state dict back to a FEN string."""
    # Reverse lookup for piece chars
    char_lookup = {}
    for ch, (color, ptype) in PIECE_CHARS.items():
        char_lookup[(color, ptype)] = ch

    rows = []
    for r in range(ROWS):
        empty = 0
        row_str = ""
        for c in range(COLS):
            pos = _rc_to_pos(r, c)
            if pos in board_state:
                if empty > 0:
                    row_str += str(empty)
                    empty = 0
                row_str += char_lookup[board_state[pos]]
            else:
                empty += 1
        if empty > 0:
            row_str += str(empty)
        rows.append(row_str)

    placement = "/".join(rows)
    player_char = "w" if current_player == 0 else "b"
    return f"{placement} {player_char}"
