"""
Chinese Chess (Xiangqi) game logic for self-play training.

Mirrors the rules from the Kotlin mobile app implementation.
Board: 10 rows x 9 columns. Row 0 is top (BLACK), row 9 is bottom (RED).
River is between rows 4 and 5.
"""

from __future__ import annotations

import copy
from dataclasses import dataclass, field
from enum import IntEnum, auto
from typing import Optional


class PieceType(IntEnum):
    GENERAL = 0
    ADVISOR = 1
    ELEPHANT = 2
    HORSE = 3
    CHARIOT = 4
    CANNON = 5
    SOLDIER = 6


class Color(IntEnum):
    RED = 0
    BLACK = 1

    def opposite(self) -> Color:
        return Color.BLACK if self == Color.RED else Color.RED


@dataclass
class Piece:
    type: PieceType
    color: Color


@dataclass
class Move:
    from_pos: tuple[int, int]
    to_pos: tuple[int, int]
    captured_piece: Optional[Piece] = None


# Pre-computed constants
_ORTHOGONAL = ((-1, 0), (1, 0), (0, -1), (0, 1))
_DIAGONAL = ((-1, -1), (-1, 1), (1, -1), (1, 1))

# Horse: (dest_row_offset, dest_col_offset, blocking_row_offset, blocking_col_offset)
_HORSE_MOVES = (
    (-2, -1, -1, 0),
    (-2, 1, -1, 0),
    (-1, -2, 0, -1),
    (-1, 2, 0, 1),
    (1, -2, 0, -1),
    (1, 2, 0, 1),
    (2, -1, 1, 0),
    (2, 1, 1, 0),
)

# Elephant: (dest_row_offset, dest_col_offset, blocking_row_offset, blocking_col_offset)
_ELEPHANT_MOVES = (
    (-2, -2, -1, -1),
    (-2, 2, -1, 1),
    (2, -2, 1, -1),
    (2, 2, 1, 1),
)

# Initial board setup: (row, col, PieceType, Color)
_INITIAL_PIECES = (
    # BLACK back rank
    (0, 0, PieceType.CHARIOT, Color.BLACK),
    (0, 1, PieceType.HORSE, Color.BLACK),
    (0, 2, PieceType.ELEPHANT, Color.BLACK),
    (0, 3, PieceType.ADVISOR, Color.BLACK),
    (0, 4, PieceType.GENERAL, Color.BLACK),
    (0, 5, PieceType.ADVISOR, Color.BLACK),
    (0, 6, PieceType.ELEPHANT, Color.BLACK),
    (0, 7, PieceType.HORSE, Color.BLACK),
    (0, 8, PieceType.CHARIOT, Color.BLACK),
    # BLACK cannons
    (2, 1, PieceType.CANNON, Color.BLACK),
    (2, 7, PieceType.CANNON, Color.BLACK),
    # BLACK soldiers
    (3, 0, PieceType.SOLDIER, Color.BLACK),
    (3, 2, PieceType.SOLDIER, Color.BLACK),
    (3, 4, PieceType.SOLDIER, Color.BLACK),
    (3, 6, PieceType.SOLDIER, Color.BLACK),
    (3, 8, PieceType.SOLDIER, Color.BLACK),
    # RED soldiers
    (6, 0, PieceType.SOLDIER, Color.RED),
    (6, 2, PieceType.SOLDIER, Color.RED),
    (6, 4, PieceType.SOLDIER, Color.RED),
    (6, 6, PieceType.SOLDIER, Color.RED),
    (6, 8, PieceType.SOLDIER, Color.RED),
    # RED cannons
    (7, 1, PieceType.CANNON, Color.RED),
    (7, 7, PieceType.CANNON, Color.RED),
    # RED back rank
    (9, 0, PieceType.CHARIOT, Color.RED),
    (9, 1, PieceType.HORSE, Color.RED),
    (9, 2, PieceType.ELEPHANT, Color.RED),
    (9, 3, PieceType.ADVISOR, Color.RED),
    (9, 4, PieceType.GENERAL, Color.RED),
    (9, 5, PieceType.ADVISOR, Color.RED),
    (9, 6, PieceType.ELEPHANT, Color.RED),
    (9, 7, PieceType.HORSE, Color.RED),
    (9, 8, PieceType.CHARIOT, Color.RED),
)


class ChineseChessGame:
    """Complete Chinese Chess game with move generation, validation, and state management."""

    __slots__ = ('board', 'current_player', 'move_history',
                 '_red_pieces', '_black_pieces', '_general_pos')

    def __init__(self) -> None:
        self.board: dict[tuple[int, int], Piece] = {}
        self.current_player: Color = Color.RED
        self.move_history: list[Move] = []
        # Fast lookup caches
        self._red_pieces: set[tuple[int, int]] = set()
        self._black_pieces: set[tuple[int, int]] = set()
        self._general_pos: dict[Color, tuple[int, int]] = {}
        self.reset()

    def reset(self) -> None:
        """Set up the initial board position."""
        self.board.clear()
        self._red_pieces.clear()
        self._black_pieces.clear()
        self._general_pos.clear()
        self.current_player = Color.RED
        self.move_history.clear()

        for row, col, ptype, color in _INITIAL_PIECES:
            pos = (row, col)
            self.board[pos] = Piece(ptype, color)
            if color == Color.RED:
                self._red_pieces.add(pos)
            else:
                self._black_pieces.add(pos)
            if ptype == PieceType.GENERAL:
                self._general_pos[color] = pos

    def get_piece(self, row: int, col: int) -> Optional[Piece]:
        """Get piece at position, or None."""
        return self.board.get((row, col))

    def get_all_pieces(self, color: Color) -> list[tuple[tuple[int, int], Piece]]:
        """Get all (position, piece) pairs for a color."""
        positions = self._red_pieces if color == Color.RED else self._black_pieces
        board = self.board
        return [(pos, board[pos]) for pos in positions]

    def _pieces_set(self, color: Color) -> set[tuple[int, int]]:
        return self._red_pieces if color == Color.RED else self._black_pieces

    # ----------------------------------------------------------------
    # Move generation
    # ----------------------------------------------------------------

    def get_legal_moves(self, color: Color | None = None) -> list[Move]:
        """Generate all legal moves for the given color (default: current player).

        A move is legal if:
        1. It is a valid piece move.
        2. It does not leave own general in check.
        3. It does not result in generals facing each other.
        """
        if color is None:
            color = self.current_player

        pseudo_moves: list[Move] = []
        positions = self._red_pieces if color == Color.RED else self._black_pieces
        board = self.board
        for pos in positions:
            piece = board[pos]
            self._get_piece_moves(pos[0], pos[1], piece, pseudo_moves)

        # Filter illegal moves
        legal: list[Move] = []
        for move in pseudo_moves:
            self._do_move(move)
            if not self._is_in_check_fast(color) and not self._generals_facing_fast():
                legal.append(move)
            self._undo_move(move)

        return legal

    def _get_piece_moves(self, row: int, col: int, piece: Piece,
                         moves: list[Move]) -> None:
        """Dispatch to piece-specific move generator."""
        ptype = piece.type
        if ptype == PieceType.GENERAL:
            self._general_moves(row, col, piece.color, moves)
        elif ptype == PieceType.ADVISOR:
            self._advisor_moves(row, col, piece.color, moves)
        elif ptype == PieceType.ELEPHANT:
            self._elephant_moves(row, col, piece.color, moves)
        elif ptype == PieceType.HORSE:
            self._horse_moves(row, col, piece.color, moves)
        elif ptype == PieceType.CHARIOT:
            self._chariot_moves(row, col, piece.color, moves)
        elif ptype == PieceType.CANNON:
            self._cannon_moves(row, col, piece.color, moves)
        elif ptype == PieceType.SOLDIER:
            self._soldier_moves(row, col, piece.color, moves)

    def _general_moves(self, row: int, col: int, color: Color,
                       moves: list[Move]) -> None:
        palace_row_min = 7 if color == Color.RED else 0
        palace_row_max = 9 if color == Color.RED else 2
        board = self.board
        from_pos = (row, col)

        for dr, dc in _ORTHOGONAL:
            nr, nc = row + dr, col + dc
            if palace_row_min <= nr <= palace_row_max and 3 <= nc <= 5:
                target = board.get((nr, nc))
                if target is None or target.color != color:
                    moves.append(Move(from_pos, (nr, nc), target))

    def _advisor_moves(self, row: int, col: int, color: Color,
                       moves: list[Move]) -> None:
        palace_row_min = 7 if color == Color.RED else 0
        palace_row_max = 9 if color == Color.RED else 2
        board = self.board
        from_pos = (row, col)

        for dr, dc in _DIAGONAL:
            nr, nc = row + dr, col + dc
            if palace_row_min <= nr <= palace_row_max and 3 <= nc <= 5:
                target = board.get((nr, nc))
                if target is None or target.color != color:
                    moves.append(Move(from_pos, (nr, nc), target))

    def _elephant_moves(self, row: int, col: int, color: Color,
                        moves: list[Move]) -> None:
        # Elephants cannot cross the river
        allowed_min = 5 if color == Color.RED else 0
        allowed_max = 9 if color == Color.RED else 4
        board = self.board
        from_pos = (row, col)

        for dr, dc, br, bc in _ELEPHANT_MOVES:
            nr, nc = row + dr, col + dc
            if 0 <= nr <= 9 and 0 <= nc <= 8 and allowed_min <= nr <= allowed_max:
                # Check elephant eye (blocking piece)
                if board.get((row + br, col + bc)) is None:
                    target = board.get((nr, nc))
                    if target is None or target.color != color:
                        moves.append(Move(from_pos, (nr, nc), target))

    def _horse_moves(self, row: int, col: int, color: Color,
                     moves: list[Move]) -> None:
        board = self.board
        from_pos = (row, col)

        for dr, dc, br, bc in _HORSE_MOVES:
            nr, nc = row + dr, col + dc
            if 0 <= nr <= 9 and 0 <= nc <= 8:
                # Check horse leg (blocking piece)
                if board.get((row + br, col + bc)) is None:
                    target = board.get((nr, nc))
                    if target is None or target.color != color:
                        moves.append(Move(from_pos, (nr, nc), target))

    def _chariot_moves(self, row: int, col: int, color: Color,
                       moves: list[Move]) -> None:
        board = self.board
        from_pos = (row, col)

        for dr, dc in _ORTHOGONAL:
            nr, nc = row + dr, col + dc
            while 0 <= nr <= 9 and 0 <= nc <= 8:
                target = board.get((nr, nc))
                if target is None:
                    moves.append(Move(from_pos, (nr, nc), None))
                else:
                    if target.color != color:
                        moves.append(Move(from_pos, (nr, nc), target))
                    break
                nr += dr
                nc += dc

    def _cannon_moves(self, row: int, col: int, color: Color,
                      moves: list[Move]) -> None:
        board = self.board
        from_pos = (row, col)

        for dr, dc in _ORTHOGONAL:
            nr, nc = row + dr, col + dc
            jumped = False
            while 0 <= nr <= 9 and 0 <= nc <= 8:
                target = board.get((nr, nc))
                if not jumped:
                    if target is None:
                        moves.append(Move(from_pos, (nr, nc), None))
                    else:
                        jumped = True
                else:
                    if target is not None:
                        if target.color != color:
                            moves.append(Move(from_pos, (nr, nc), target))
                        break
                nr += dr
                nc += dc

    def _soldier_moves(self, row: int, col: int, color: Color,
                       moves: list[Move]) -> None:
        board = self.board
        from_pos = (row, col)
        forward = -1 if color == Color.RED else 1
        crossed_river = (row <= 4) if color == Color.RED else (row >= 5)

        # Forward
        nr = row + forward
        if 0 <= nr <= 9:
            target = board.get((nr, col))
            if target is None or target.color != color:
                moves.append(Move(from_pos, (nr, col), target))

        # Sideways (only after crossing river)
        if crossed_river:
            for dc in (-1, 1):
                nc = col + dc
                if 0 <= nc <= 8:
                    target = board.get((row, nc))
                    if target is None or target.color != color:
                        moves.append(Move(from_pos, (row, nc), target))

    # ----------------------------------------------------------------
    # Move execution
    # ----------------------------------------------------------------

    def make_move(self, move: Move) -> None:
        """Execute a move, update board state, and switch player."""
        self._do_move(move)
        self.move_history.append(move)
        self.current_player = self.current_player.opposite()

    def _do_move(self, move: Move) -> None:
        """Internal: apply move to board without history/player switch."""
        fr, fc = move.from_pos
        tr, tc = move.to_pos
        piece = self.board[move.from_pos]

        # Remove from old position
        del self.board[move.from_pos]
        piece_set = self._pieces_set(piece.color)
        piece_set.discard(move.from_pos)

        # Handle capture
        if move.captured_piece is not None:
            opp_set = self._pieces_set(move.captured_piece.color)
            opp_set.discard(move.to_pos)

        # Place at new position
        self.board[move.to_pos] = piece
        piece_set.add(move.to_pos)

        # Update general position cache
        if piece.type == PieceType.GENERAL:
            self._general_pos[piece.color] = move.to_pos

    def undo_move(self) -> None:
        """Undo the last move."""
        if not self.move_history:
            return
        move = self.move_history.pop()
        self._undo_move(move)
        self.current_player = self.current_player.opposite()

    def _undo_move(self, move: Move) -> None:
        """Internal: reverse a move on the board without history/player switch."""
        piece = self.board[move.to_pos]
        piece_set = self._pieces_set(piece.color)

        # Remove from destination
        del self.board[move.to_pos]
        piece_set.discard(move.to_pos)

        # Restore to origin
        self.board[move.from_pos] = piece
        piece_set.add(move.from_pos)

        # Restore captured piece
        if move.captured_piece is not None:
            self.board[move.to_pos] = move.captured_piece
            opp_set = self._pieces_set(move.captured_piece.color)
            opp_set.add(move.to_pos)

        # Update general position cache
        if piece.type == PieceType.GENERAL:
            self._general_pos[piece.color] = move.from_pos

    # ----------------------------------------------------------------
    # Check / checkmate / stalemate
    # ----------------------------------------------------------------

    def is_in_check(self, color: Color) -> bool:
        """Check if the given color's general is under attack."""
        return self._is_in_check_fast(color)

    def _is_in_check_fast(self, color: Color) -> bool:
        """Efficient check detection: see if any opponent piece attacks the general."""
        general_pos = self._general_pos.get(color)
        if general_pos is None:
            return True  # General captured

        gr, gc = general_pos
        opp = color.opposite()
        board = self.board

        # Check attacks from chariots/generals along orthogonal lines
        # and cannons (jumping one piece)
        for dr, dc in _ORTHOGONAL:
            nr, nc = gr + dr, gc + dc
            jumped = False
            while 0 <= nr <= 9 and 0 <= nc <= 8:
                p = board.get((nr, nc))
                if p is not None:
                    if not jumped:
                        if p.color == opp:
                            if p.type == PieceType.CHARIOT:
                                return True
                            # General facing (handled separately but also useful here)
                            if p.type == PieceType.GENERAL:
                                return True
                        jumped = True
                    else:
                        # After one jump, only cannon can capture
                        if p.color == opp and p.type == PieceType.CANNON:
                            return True
                        break
                nr += dr
                nc += dc

        # Check attacks from horses
        for dr, dc, br, bc in _HORSE_MOVES:
            nr, nc = gr + dr, gc + dc
            if 0 <= nr <= 9 and 0 <= nc <= 8:
                p = board.get((nr, nc))
                if p is not None and p.color == opp and p.type == PieceType.HORSE:
                    # Horse blocking check: the block is relative to the HORSE position,
                    # not relative to the general. We need to check if the horse at (nr,nc)
                    # can reach (gr,gc). The horse at (nr,nc) moves by (-dr,-dc) to reach
                    # (gr,gc), so its blocking square is (nr+(-br), nc+(-bc)) = (nr-br, nc-bc).
                    # But actually the block offsets in _HORSE_MOVES are relative to the
                    # source piece. If the horse is at (nr, nc) and wants to move to (gr, gc),
                    # the delta from horse's perspective is (-dr, -dc). We need to find the
                    # corresponding blocking position for that delta.
                    # The blocking position for a horse move (dr2, dc2) is the orthogonal
                    # step component: if |dr2|==2, block is (sign(dr2),0); if |dc2|==2,
                    # block is (0,sign(dc2)).
                    # From horse at (nr,nc) moving by (-dr,-dc):
                    if abs(dr) == 2:
                        block_r, block_c = nr + (-1 if dr > 0 else 1), nc
                    else:
                        block_r, block_c = nr, nc + (-1 if dc > 0 else 1)
                    if board.get((block_r, block_c)) is None:
                        return True

        # Check attacks from soldiers
        # Opponent soldiers that can reach (gr, gc):
        # For a BLACK soldier (moves downward, row+1 forward, sideways after crossing),
        # it attacks (gr,gc) if it's at (gr-1,gc) [forward] or (gr,gc-1)/(gr,gc+1) [side, if crossed].
        # For a RED soldier (moves upward, row-1 forward, sideways after crossing),
        # it attacks (gr,gc) if it's at (gr+1,gc) [forward] or (gr,gc-1)/(gr,gc+1) [side, if crossed].
        if opp == Color.BLACK:
            # Black soldier attacks downward
            p = board.get((gr - 1, gc))
            if p is not None and p.color == opp and p.type == PieceType.SOLDIER:
                return True
            # Sideways attacks (soldier must have crossed river: row >= 5)
            for dc2 in (-1, 1):
                nc2 = gc + dc2
                if 0 <= nc2 <= 8:
                    p = board.get((gr, nc2))
                    if (p is not None and p.color == opp and p.type == PieceType.SOLDIER
                            and gr >= 5):  # soldier at gr must have crossed
                        return True
        else:
            # Red soldier attacks upward
            p = board.get((gr + 1, gc))
            if p is not None and p.color == opp and p.type == PieceType.SOLDIER:
                return True
            # Sideways attacks (soldier must have crossed river: row <= 4)
            for dc2 in (-1, 1):
                nc2 = gc + dc2
                if 0 <= nc2 <= 8:
                    p = board.get((gr, nc2))
                    if (p is not None and p.color == opp and p.type == PieceType.SOLDIER
                            and gr <= 4):
                        return True

        return False

    def generals_facing(self) -> bool:
        """Check if both generals are on the same column with no pieces between."""
        return self._generals_facing_fast()

    def _generals_facing_fast(self) -> bool:
        red_pos = self._general_pos.get(Color.RED)
        black_pos = self._general_pos.get(Color.BLACK)
        if red_pos is None or black_pos is None:
            return False
        if red_pos[1] != black_pos[1]:
            return False

        col = red_pos[1]
        min_row = min(red_pos[0], black_pos[0])
        max_row = max(red_pos[0], black_pos[0])
        board = self.board

        for r in range(min_row + 1, max_row):
            if (r, col) in board:
                return False
        return True

    def is_checkmate(self, color: Color) -> bool:
        """True if color is in check and has no legal moves."""
        return self.is_in_check(color) and len(self.get_legal_moves(color)) == 0

    def is_stalemate(self, color: Color) -> bool:
        """True if color is NOT in check but has no legal moves."""
        return not self.is_in_check(color) and len(self.get_legal_moves(color)) == 0

    def is_game_over(self) -> tuple[bool, Optional[Color]]:
        """Check if the game is over.

        Returns:
            (is_over, winner) where winner is None for stalemate/draw.
            For checkmate, winner is the side that delivered checkmate.
            For stalemate, the side with no moves loses (returns opponent as winner).
        """
        if self.is_checkmate(self.current_player):
            return True, self.current_player.opposite()
        if self.is_stalemate(self.current_player):
            # In Chinese chess, stalemate is a loss for the stalemated player
            return True, self.current_player.opposite()
        # Check if generals are facing (the side that just moved caused it, so they lose)
        if self.generals_facing():
            return True, self.current_player  # current player wins (opponent just moved)
        return False, None

    # ----------------------------------------------------------------
    # Utility
    # ----------------------------------------------------------------

    def clone(self) -> ChineseChessGame:
        """Deep copy of the game state."""
        new_game = ChineseChessGame.__new__(ChineseChessGame)
        new_game.board = {pos: Piece(p.type, p.color) for pos, p in self.board.items()}
        new_game.current_player = self.current_player
        new_game.move_history = [
            Move(m.from_pos, m.to_pos,
                 Piece(m.captured_piece.type, m.captured_piece.color) if m.captured_piece else None)
            for m in self.move_history
        ]
        new_game._red_pieces = set(self._red_pieces)
        new_game._black_pieces = set(self._black_pieces)
        new_game._general_pos = dict(self._general_pos)
        return new_game

    def get_state_key(self) -> tuple:
        """Hashable representation of the current board state for repetition detection.

        Returns a tuple of (current_player, frozenset of (row, col, piece_type, color)).
        """
        pieces = tuple(
            sorted(
                (pos[0], pos[1], p.type, p.color)
                for pos, p in self.board.items()
            )
        )
        return (self.current_player, pieces)

    def __repr__(self) -> str:
        symbols = {
            (PieceType.GENERAL, Color.RED): 'K',
            (PieceType.ADVISOR, Color.RED): 'A',
            (PieceType.ELEPHANT, Color.RED): 'E',
            (PieceType.HORSE, Color.RED): 'H',
            (PieceType.CHARIOT, Color.RED): 'R',
            (PieceType.CANNON, Color.RED): 'C',
            (PieceType.SOLDIER, Color.RED): 'P',
            (PieceType.GENERAL, Color.BLACK): 'k',
            (PieceType.ADVISOR, Color.BLACK): 'a',
            (PieceType.ELEPHANT, Color.BLACK): 'e',
            (PieceType.HORSE, Color.BLACK): 'h',
            (PieceType.CHARIOT, Color.BLACK): 'r',
            (PieceType.CANNON, Color.BLACK): 'c',
            (PieceType.SOLDIER, Color.BLACK): 'p',
        }
        lines = []
        for row in range(10):
            chars = []
            for col in range(9):
                p = self.board.get((row, col))
                chars.append(symbols[(p.type, p.color)] if p else '.')
            lines.append(' '.join(chars))
            if row == 4:
                lines.append('  ---- river ----')
        return '\n'.join(lines)
