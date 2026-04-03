"""
Supervised pre-training using ChessDB cloud database as teacher.

Instead of using a weak local alpha-beta engine, queries chessdb.cn API
for the best move at each position. This gives grandmaster+ quality
training data for free.

Usage::

    python pretrain_cloud.py --num-games 500 --epochs 20
"""

from __future__ import annotations

import argparse
import logging
import os
import random
import time
from dataclasses import dataclass
from typing import List, Optional, Tuple

import numpy as np
import requests
import torch

from model import ChineseChessNet, create_model
from encoding import board_to_tensor, move_to_index, NUM_ACTIONS, COLS, ROWS
from game import ChineseChessGame, Color, Move, PieceType
from pretrain import (
    PositionRecord, PretrainDataset, SupervisedTrainer,
    train_supervised, _validate,
)

logger = logging.getLogger(__name__)

MAX_GAME_MOVES = 150
MAX_REPETITIONS = 3
API_URL = "http://www.chessdb.cn/chessdb.php"
REQUEST_DELAY = 0.3  # seconds between API calls to be polite


def game_to_fen(game: ChineseChessGame) -> str:
    """Convert current game state to FEN string for API query."""
    rows_str = []
    for row in range(ROWS):
        empty = 0
        row_str = ""
        for col in range(COLS):
            piece = game.board.get((row, col))
            if piece is None:
                empty += 1
            else:
                if empty > 0:
                    row_str += str(empty)
                    empty = 0
                # Map piece to FEN character
                c = _piece_to_fen_char(piece.type, piece.color)
                row_str += c
        if empty > 0:
            row_str += str(empty)
        rows_str.append(row_str)

    board_fen = "/".join(rows_str)
    side = "w" if game.current_player == Color.RED else "b"
    # Simplified FEN — no castling/en-passant in xiangqi
    return f"{board_fen} {side} - - 0 1"


def _piece_to_fen_char(piece_type: PieceType, color: Color) -> str:
    """Map piece type + color to FEN character."""
    mapping = {
        PieceType.GENERAL: 'k',
        PieceType.ADVISOR: 'a',
        PieceType.ELEPHANT: 'b',
        PieceType.HORSE: 'n',
        PieceType.CHARIOT: 'r',
        PieceType.CANNON: 'c',
        PieceType.SOLDIER: 'p',
    }
    c = mapping[piece_type]
    if color == Color.RED:
        c = c.upper()
    return c


def parse_api_move(move_str: str) -> Optional[Tuple[Tuple[int, int], Tuple[int, int]]]:
    """Parse ICCS move string like 'b0c2' into ((row, col), (row, col))."""
    move_str = move_str.strip().strip('\x00')
    if len(move_str) != 4:
        return None
    try:
        fc = ord(move_str[0]) - ord('a')
        fr = int(move_str[1])
        tc = ord(move_str[2]) - ord('a')
        tr = int(move_str[3])
        # API uses rank 0=bottom for red, but our board has row 0=top
        # FEN row 0 = top of board = rank 9
        fr = 9 - fr
        tr = 9 - tr
        return ((fr, fc), (tr, tc))
    except (ValueError, IndexError):
        return None


def query_best_move(fen: str, retries: int = 2) -> Optional[str]:
    """Query chessdb.cn for the best move given a FEN position.

    Returns the move in ICCS format (e.g., 'b0c2') or None if not found.
    """
    for attempt in range(retries + 1):
        try:
            resp = requests.get(
                API_URL,
                params={"action": "querybest", "board": fen},
                timeout=10,
            )
            text = resp.text.strip().strip('\x00')

            if text.startswith("move:"):
                return text[5:].strip().strip('\x00')
            elif text.startswith("egtb:"):
                return text[5:].strip().strip('\x00')
            elif text in ("unknown", "nobestmove", "invalid board"):
                return None
            else:
                # Might be rate limited or other issue
                if attempt < retries:
                    time.sleep(1)
                    continue
                return None
        except Exception as e:
            if attempt < retries:
                time.sleep(1)
                continue
            logger.debug("API error: %s", e)
            return None

    return None


def _game_to_board_state(game: ChineseChessGame) -> dict:
    """Convert game board to encoding format."""
    board_state = {}
    for (row, col), piece in game.board.items():
        flat = row * COLS + col
        board_state[flat] = (int(piece.color), int(piece.type))
    return board_state


def _move_to_action_index(from_pos: Tuple[int, int], to_pos: Tuple[int, int]) -> int:
    """Convert move positions to action index."""
    fr, fc = from_pos
    tr, tc = to_pos
    return move_to_index(fr * COLS + fc, tr * COLS + tc)


def find_matching_move(game: ChineseChessGame,
                       from_pos: Tuple[int, int],
                       to_pos: Tuple[int, int]) -> Optional[Move]:
    """Find the legal move matching the given positions."""
    legal_moves = game.get_legal_moves()
    for move in legal_moves:
        if move.from_pos == from_pos and move.to_pos == to_pos:
            return move
    return None


def generate_game_cloud(random_opening_moves: int = 4,
                        ) -> Tuple[List[PositionRecord], Optional[int]]:
    """Play one game using cloud database as the engine for both sides.

    Returns:
        (records, winner) where winner is 0=Red, 1=Black, or None for draw.
    """
    game = ChineseChessGame()

    # Random opening diversification
    if random_opening_moves > 0:
        n = random.randint(0, random_opening_moves)
        for _ in range(n):
            moves = game.get_legal_moves()
            if not moves:
                break
            game.make_move(random.choice(moves))
            over, _ = game.is_game_over()
            if over:
                break

    positions: List[Tuple[np.ndarray, int, int]] = []  # (tensor, action_idx, player)
    position_counts: dict = {}
    move_count = 0
    consecutive_misses = 0
    winner = None

    while move_count < MAX_GAME_MOVES:
        over, winner = game.is_game_over()
        if over:
            break

        # Repetition detection
        state_key = game.get_state_key()
        position_counts[state_key] = position_counts.get(state_key, 0) + 1
        if position_counts[state_key] >= MAX_REPETITIONS:
            winner = None
            break

        # Encode position
        board_state = _game_to_board_state(game)
        tensor = board_to_tensor(board_state, int(game.current_player))
        tensor_np = tensor.numpy()

        # Query cloud database
        fen = game_to_fen(game)
        best_move_str = query_best_move(fen)
        time.sleep(REQUEST_DELAY)

        if best_move_str is None:
            consecutive_misses += 1
            if consecutive_misses >= 5:
                # Cloud doesn't know this position, stop game
                break
            # Fall back to random legal move (don't record as training data)
            legal = game.get_legal_moves()
            if not legal:
                break
            game.make_move(random.choice(legal))
            move_count += 1
            continue

        consecutive_misses = 0

        # Parse the move
        parsed = parse_api_move(best_move_str)
        if parsed is None:
            legal = game.get_legal_moves()
            if not legal:
                break
            game.make_move(random.choice(legal))
            move_count += 1
            continue

        from_pos, to_pos = parsed
        move = find_matching_move(game, from_pos, to_pos)
        if move is None:
            # Move not legal — skip
            legal = game.get_legal_moves()
            if not legal:
                break
            game.make_move(random.choice(legal))
            move_count += 1
            continue

        # Record position with cloud's best move
        action_idx = _move_to_action_index(from_pos, to_pos)
        positions.append((tensor_np, action_idx, int(game.current_player)))

        game.make_move(move)
        move_count += 1

    # Determine result
    if winner is None and move_count >= MAX_GAME_MOVES:
        winner_int = None
    elif winner is not None:
        winner_int = int(winner)
    else:
        winner_int = None

    # Value target from RED's perspective
    if winner_int == 0:
        result_value = 1.0
    elif winner_int == 1:
        result_value = -1.0
    else:
        result_value = 0.0

    records: List[PositionRecord] = []
    for tensor_np, action_idx, player in positions:
        policy = np.zeros(NUM_ACTIONS, dtype=np.float32)
        policy[action_idx] = 1.0
        records.append(PositionRecord(
            board_tensor=tensor_np,
            policy_target=policy,
            value_target=result_value,
            current_player=player,
        ))

    return records, winner_int


def generate_games_cloud(num_games: int,
                         random_opening_moves: int = 4,
                         ) -> List[PositionRecord]:
    """Generate training data using cloud database."""
    all_records: List[PositionRecord] = []
    red_wins = 0
    black_wins = 0
    draws = 0

    t0 = time.time()
    for game_idx in range(num_games):
        gt0 = time.time()
        records, winner = generate_game_cloud(random_opening_moves)
        elapsed = time.time() - gt0
        all_records.extend(records)

        if winner == 0:
            red_wins += 1
            result_str = "Red"
        elif winner == 1:
            black_wins += 1
            result_str = "Black"
        else:
            draws += 1
            result_str = "Draw"

        logger.info(
            "Game %d/%d: %d positions, %s, %.1fs (%d total)",
            game_idx + 1, num_games, len(records), result_str,
            elapsed, len(all_records),
        )

    total_time = time.time() - t0
    logger.info(
        "Done: %d games in %.1fs, R=%d B=%d D=%d, %d positions",
        num_games, total_time, red_wins, black_wins, draws, len(all_records),
    )
    return all_records


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Supervised pre-training using ChessDB cloud database"
    )
    parser.add_argument("--num-games", type=int, default=500,
                        help="Number of games to generate (default: 500)")
    parser.add_argument("--epochs", type=int, default=20,
                        help="Training epochs (default: 20)")
    parser.add_argument("--batch-size", type=int, default=128,
                        help="Mini-batch size (default: 128)")
    parser.add_argument("--lr", type=float, default=0.001,
                        help="Initial learning rate (default: 0.001)")
    parser.add_argument("--checkpoint-dir", type=str, default="./checkpoints",
                        help="Directory for model checkpoints")
    parser.add_argument("--model-path", type=str, default=None,
                        help="Path to existing model to continue training from")
    parser.add_argument("--num-filters", type=int, default=128,
                        help="Conv filters (default: 128)")
    parser.add_argument("--num-blocks", type=int, default=6,
                        help="Residual blocks (default: 6)")
    parser.add_argument("--random-opening-moves", type=int, default=6,
                        help="Max random opening moves (default: 6)")
    parser.add_argument("--log-level", type=str, default="INFO",
                        choices=["DEBUG", "INFO", "WARNING", "ERROR"])

    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    if torch.cuda.is_available():
        device = torch.device('cuda')
    elif torch.backends.mps.is_available():
        device = torch.device('mps')
    else:
        device = torch.device('cpu')
    logger.info("Using device: %s", device)

    model = create_model(num_filters=args.num_filters,
                         num_blocks=args.num_blocks)

    if args.model_path:
        logger.info("Loading model from %s", args.model_path)
        ckpt = torch.load(args.model_path, map_location='cpu', weights_only=False)
        if 'model_state_dict' in ckpt:
            model.load_state_dict(ckpt['model_state_dict'])
        else:
            model.load_state_dict(ckpt)

    # Phase 1: Generate data from cloud
    logger.info("=" * 60)
    logger.info("Phase 1: Generating %d games from ChessDB cloud", args.num_games)
    logger.info("=" * 60)

    records = generate_games_cloud(
        num_games=args.num_games,
        random_opening_moves=args.random_opening_moves,
    )

    if len(records) < 50:
        logger.error("Too few positions (%d). Check network/API.", len(records))
        return

    # Phase 2: Train
    logger.info("=" * 60)
    logger.info("Phase 2: Training (%d epochs, %d positions)", args.epochs, len(records))
    logger.info("=" * 60)

    model = train_supervised(
        model=model,
        records=records,
        epochs=args.epochs,
        batch_size=args.batch_size,
        lr=args.lr,
        device=device,
        checkpoint_dir=args.checkpoint_dir,
    )

    logger.info("Cloud pre-training complete!")


if __name__ == "__main__":
    main()
