"""
Alpha-beta search engine for Chinese Chess (象棋).

Simplified Python port of the Kotlin ChessAI.kt. Designed to generate
high-quality training data for supervised pre-training of the neural network.

Key features:
  - Alpha-beta pruning with negamax-style implementation
  - Move ordering: captures (MVV-LVA) > killer moves > history heuristic
  - Quiescence search (captures only, limited depth)
  - Null-move pruning
  - Transposition table (simple dictionary)
"""

from __future__ import annotations

import time
from typing import Optional, Tuple, Dict

from game import ChineseChessGame, Color, PieceType, Move
from evaluator import evaluate, BASE_VALUES, CHECKMATE_SCORE

INFINITY = 1_000_000
NULL_MOVE_REDUCTION = 2
LMR_THRESHOLD = 4

# Transposition table entry types
TT_EXACT = 0
TT_LOWER = 1   # score >= beta (fail-high)
TT_UPPER = 2   # score <= alpha (fail-low)


class _TTEntry:
    __slots__ = ('depth', 'score', 'flag', 'best_move')

    def __init__(self, depth: int, score: int, flag: int,
                 best_move: Optional[Move]) -> None:
        self.depth = depth
        self.score = score
        self.flag = flag
        self.best_move = best_move


def _mvv_lva_score(move: Move) -> int:
    """Most Valuable Victim - Least Valuable Attacker ordering score."""
    if move.captured_piece is None:
        return 0
    victim_val = BASE_VALUES.get(move.captured_piece.type, 0)
    # Use the moving piece's type from the board; we get it from from_pos
    # but it's not stored in Move directly — use captured piece for MVV
    # and a small constant for LVA (we don't have attacker type in Move).
    return victim_val * 10


class AlphaBetaEngine:
    """Alpha-beta search engine for game data generation.

    Usage::

        engine = AlphaBetaEngine(depth=4)
        move = engine.search(game)
    """

    def __init__(self, depth: int = 4, quiescence_depth: int = 2,
                 time_limit: float = 30.0) -> None:
        self.max_depth = depth
        self.quiescence_depth = quiescence_depth
        self.time_limit = time_limit

        # Transposition table: state_key -> _TTEntry
        self._tt: Dict[tuple, _TTEntry] = {}
        # Killer moves: 2 slots per depth
        self._killers: list[list[Optional[Move]]] = [[None, None] for _ in range(30)]
        # History heuristic: [color][from_flat][to_flat]
        self._history = [[[0] * 90 for _ in range(90)] for _ in range(2)]
        self._nodes = 0
        self._start_time = 0.0
        self._stopped = False

    def search(self, game: ChineseChessGame) -> Optional[Move]:
        """Find the best move for the current player using iterative deepening.

        Returns:
            The best Move found, or None if no legal moves exist.
        """
        self._nodes = 0
        self._start_time = time.time()
        self._stopped = False
        # Decay history instead of clearing
        for c in range(2):
            for f in range(90):
                for t in range(90):
                    self._history[c][f][t] //= 2

        best_move: Optional[Move] = None
        best_score = 0

        for depth in range(1, self.max_depth + 1):
            if self._stopped:
                break
            result = self._search_root(game, depth)
            if result is not None and not self._stopped:
                best_move, best_score = result
                if abs(best_score) > CHECKMATE_SCORE - 100:
                    break
            if time.time() - self._start_time > self.time_limit:
                self._stopped = True

        return best_move

    def search_with_score(self, game: ChineseChessGame) -> Tuple[Optional[Move], int]:
        """Like search(), but also returns the evaluation score."""
        self._nodes = 0
        self._start_time = time.time()
        self._stopped = False
        for c in range(2):
            for f in range(90):
                for t in range(90):
                    self._history[c][f][t] //= 2

        best_move: Optional[Move] = None
        best_score = 0

        for depth in range(1, self.max_depth + 1):
            if self._stopped:
                break
            result = self._search_root(game, depth)
            if result is not None and not self._stopped:
                best_move, best_score = result
                if abs(best_score) > CHECKMATE_SCORE - 100:
                    break
            if time.time() - self._start_time > self.time_limit:
                self._stopped = True

        return best_move, best_score

    def clear_cache(self) -> None:
        """Clear transposition table and history."""
        self._tt.clear()
        self._history = [[[0] * 90 for _ in range(90)] for _ in range(2)]
        self._killers = [[None, None] for _ in range(30)]

    # ------------------------------------------------------------------
    # Root search
    # ------------------------------------------------------------------

    def _search_root(self, game: ChineseChessGame,
                     depth: int) -> Optional[Tuple[Move, int]]:
        is_maximizing = (game.current_player == Color.RED)
        best_move: Optional[Move] = None
        best_score = -INFINITY if is_maximizing else INFINITY
        alpha = -INFINITY
        beta = INFINITY

        state_key = game.get_state_key()
        tt_entry = self._tt.get(state_key)
        tt_best = tt_entry.best_move if tt_entry else None

        moves = self._order_moves(game, game.get_legal_moves(), tt_best, depth)
        if not moves:
            return None

        for move in moves:
            if self._stopped:
                break
            game.make_move(move)
            score = self._alpha_beta(game, depth - 1, alpha, beta,
                                     not is_maximizing, True)
            game.undo_move()

            if is_maximizing:
                if score > best_score:
                    best_score = score
                    best_move = move
                alpha = max(alpha, score)
            else:
                if score < best_score:
                    best_score = score
                    best_move = move
                beta = min(beta, score)

        if best_move is not None:
            return best_move, best_score
        return None

    # ------------------------------------------------------------------
    # Core alpha-beta
    # ------------------------------------------------------------------

    def _alpha_beta(self, game: ChineseChessGame, depth: int,
                    alpha: int, beta: int,
                    maximizing: bool, allow_null: bool) -> int:
        self._nodes += 1

        if self._nodes % 4096 == 0:
            if time.time() - self._start_time > self.time_limit:
                self._stopped = True
        if self._stopped:
            return 0

        # Transposition table probe
        state_key = game.get_state_key()
        tt_entry = self._tt.get(state_key)
        if tt_entry is not None and tt_entry.depth >= depth:
            if tt_entry.flag == TT_EXACT:
                return tt_entry.score
            elif tt_entry.flag == TT_LOWER and tt_entry.score >= beta:
                return tt_entry.score
            elif tt_entry.flag == TT_UPPER and tt_entry.score <= alpha:
                return tt_entry.score

        # Leaf — quiescence or static eval
        if depth <= 0:
            if self.quiescence_depth == 0:
                return evaluate(game)
            return self._quiescence(game, alpha, beta, maximizing,
                                    self.quiescence_depth)

        # Terminal checks
        current = game.current_player
        if game.is_checkmate(current):
            # Current player is checkmated — bad for them
            if maximizing:
                return -CHECKMATE_SCORE + (self.max_depth - depth)
            else:
                return CHECKMATE_SCORE - (self.max_depth - depth)
        if game.is_stalemate(current):
            # Stalemate is a loss in xiangqi
            if maximizing:
                return -CHECKMATE_SCORE + (self.max_depth - depth)
            else:
                return CHECKMATE_SCORE - (self.max_depth - depth)

        # Null-move pruning
        if allow_null and depth >= 3 and not game.is_in_check(current):
            # Pass: switch player without moving
            game.current_player = current.opposite()
            null_score = self._alpha_beta(game, depth - 1 - NULL_MOVE_REDUCTION,
                                          alpha, beta, not maximizing, False)
            game.current_player = current  # restore
            if maximizing and null_score >= beta:
                return beta
            if not maximizing and null_score <= alpha:
                return alpha

        tt_best = tt_entry.best_move if tt_entry else None
        moves = self._order_moves(game, game.get_legal_moves(), tt_best, depth)
        if not moves:
            return evaluate(game)

        in_check = game.is_in_check(current)
        best_move: Optional[Move] = None

        if maximizing:
            best_score = -INFINITY
            for idx, move in enumerate(moves):
                if self._stopped:
                    break
                game.make_move(move)

                # Late move reduction
                reduction = 0
                if (idx >= LMR_THRESHOLD and depth >= 3
                        and not in_check and move.captured_piece is None):
                    reduction = 1

                score = self._alpha_beta(game, depth - 1 - reduction,
                                         alpha, beta, False, True)
                if reduction > 0 and score > alpha:
                    score = self._alpha_beta(game, depth - 1,
                                             alpha, beta, False, True)
                game.undo_move()

                if score > best_score:
                    best_score = score
                    best_move = move
                alpha = max(alpha, score)
                if beta <= alpha:
                    if move.captured_piece is None:
                        self._update_killers(depth, move)
                        self._update_history(move, depth, current)
                    self._tt[state_key] = _TTEntry(depth, best_score,
                                                   TT_LOWER, best_move)
                    return best_score
        else:
            best_score = INFINITY
            for idx, move in enumerate(moves):
                if self._stopped:
                    break
                game.make_move(move)

                reduction = 0
                if (idx >= LMR_THRESHOLD and depth >= 3
                        and not in_check and move.captured_piece is None):
                    reduction = 1

                score = self._alpha_beta(game, depth - 1 - reduction,
                                         alpha, beta, True, True)
                if reduction > 0 and score < beta:
                    score = self._alpha_beta(game, depth - 1,
                                             alpha, beta, True, True)
                game.undo_move()

                if score < best_score:
                    best_score = score
                    best_move = move
                beta = min(beta, score)
                if beta <= alpha:
                    if move.captured_piece is None:
                        self._update_killers(depth, move)
                        self._update_history(move, depth, current)
                    self._tt[state_key] = _TTEntry(depth, best_score,
                                                   TT_UPPER, best_move)
                    return best_score

        self._tt[state_key] = _TTEntry(depth, best_score, TT_EXACT, best_move)
        return best_score

    # ------------------------------------------------------------------
    # Quiescence search
    # ------------------------------------------------------------------

    def _quiescence(self, game: ChineseChessGame,
                    alpha: int, beta: int,
                    maximizing: bool, depth: int) -> int:
        self._nodes += 1
        if depth == 0:
            return evaluate(game)

        stand_pat = evaluate(game)

        if maximizing:
            if stand_pat >= beta:
                return beta
            current_alpha = max(alpha, stand_pat)
            captures = [m for m in game.get_legal_moves()
                        if m.captured_piece is not None]
            captures.sort(key=_mvv_lva_score, reverse=True)
            for move in captures:
                game.make_move(move)
                score = self._quiescence(game, current_alpha, beta,
                                         False, depth - 1)
                game.undo_move()
                current_alpha = max(current_alpha, score)
                if current_alpha >= beta:
                    return beta
            return current_alpha
        else:
            if stand_pat <= alpha:
                return alpha
            current_beta = min(beta, stand_pat)
            captures = [m for m in game.get_legal_moves()
                        if m.captured_piece is not None]
            captures.sort(key=_mvv_lva_score, reverse=True)
            for move in captures:
                game.make_move(move)
                score = self._quiescence(game, alpha, current_beta,
                                         True, depth - 1)
                game.undo_move()
                current_beta = min(current_beta, score)
                if current_beta <= alpha:
                    return alpha
            return current_beta

    # ------------------------------------------------------------------
    # Move ordering
    # ------------------------------------------------------------------

    def _order_moves(self, game: ChineseChessGame, moves: list[Move],
                     tt_move: Optional[Move] = None,
                     depth: int = -1) -> list[Move]:
        """Order moves: TT best > captures (MVV-LVA) > killers > history."""

        def _score(move: Move) -> int:
            # TT move gets highest priority
            if tt_move is not None:
                if (move.from_pos == tt_move.from_pos
                        and move.to_pos == tt_move.to_pos):
                    return 100000

            # Killer moves
            if 0 <= depth < len(self._killers):
                k0, k1 = self._killers[depth]
                if k0 is not None and move.from_pos == k0.from_pos and move.to_pos == k0.to_pos:
                    return 50000
                if k1 is not None and move.from_pos == k1.from_pos and move.to_pos == k1.to_pos:
                    return 45000

            s = 0
            # Captures: MVV-LVA
            if move.captured_piece is not None:
                s += 10000 + BASE_VALUES.get(move.captured_piece.type, 0) * 10

            # History heuristic for quiet moves
            if move.captured_piece is None:
                color_idx = int(game.current_player)
                from_flat = move.from_pos[0] * 9 + move.from_pos[1]
                to_flat = move.to_pos[0] * 9 + move.to_pos[1]
                s += self._history[color_idx][from_flat][to_flat]

            return s

        moves.sort(key=_score, reverse=True)
        return moves

    def _update_killers(self, depth: int, move: Move) -> None:
        if 0 <= depth < len(self._killers):
            k0 = self._killers[depth][0]
            if k0 is None or k0.from_pos != move.from_pos or k0.to_pos != move.to_pos:
                self._killers[depth][1] = self._killers[depth][0]
                self._killers[depth][0] = move

    def _update_history(self, move: Move, depth: int, color: Color) -> None:
        c = int(color)
        f = move.from_pos[0] * 9 + move.from_pos[1]
        t = move.to_pos[0] * 9 + move.to_pos[1]
        self._history[c][f][t] += depth * depth
