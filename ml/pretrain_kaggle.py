"""
Supervised pre-training using Kaggle xiangqi dataset (10,000 games).

Parses WXF notation moves from the Kaggle Online Xiangqi dataset,
replays each game, and generates (board_state, policy, value) training triples.

Usage::
    python pretrain_kaggle.py --data-dir /Users/ying/Downloads/archive --epochs 30
"""

from __future__ import annotations

import argparse
import csv
import logging
import os
import random
from collections import defaultdict
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

import numpy as np
import torch

from model import create_model
from encoding import board_to_tensor, move_to_index, NUM_ACTIONS, COLS, ROWS
from game import ChineseChessGame, Color, Move, PieceType
from pretrain import PositionRecord, train_supervised

logger = logging.getLogger(__name__)

# WXF piece letter to PieceType
WXF_PIECES = {
    'K': PieceType.GENERAL, 'k': PieceType.GENERAL,
    'A': PieceType.ADVISOR, 'a': PieceType.ADVISOR,
    'E': PieceType.ELEPHANT, 'e': PieceType.ELEPHANT,
    'B': PieceType.ELEPHANT, 'b': PieceType.ELEPHANT,  # Bishop = Elephant
    'H': PieceType.HORSE, 'h': PieceType.HORSE,
    'N': PieceType.HORSE, 'n': PieceType.HORSE,  # Knight = Horse
    'R': PieceType.CHARIOT, 'r': PieceType.CHARIOT,
    'C': PieceType.CANNON, 'c': PieceType.CANNON,
    'P': PieceType.SOLDIER, 'p': PieceType.SOLDIER,
}


def wxf_col_to_board_col(wxf_col: int, color: Color) -> int:
    """Convert WXF column number (1-9) to board column (0-8).

    Red counts from right: Red col 1 = board col 8, Red col 9 = board col 0.
    Black counts from right (their right): Black col 1 = board col 0, Black col 9 = board col 8.
    """
    if color == Color.RED:
        return 9 - wxf_col
    else:
        return wxf_col - 1


def find_piece_on_col(game: ChineseChessGame, piece_type: PieceType,
                      color: Color, board_col: int) -> Optional[Tuple[int, int]]:
    """Find a piece of given type and color on the given column.

    If multiple pieces of same type on same column, returns None (ambiguous).
    """
    candidates = []
    positions = game._red_pieces if color == Color.RED else game._black_pieces
    for pos in positions:
        piece = game.board[pos]
        if piece.type == piece_type and pos[1] == board_col:
            candidates.append(pos)

    if len(candidates) == 1:
        return candidates[0]
    elif len(candidates) == 2:
        # Ambiguous — caller needs front/rear logic
        return None
    return None


def find_piece_front_rear(game: ChineseChessGame, piece_type: PieceType,
                          color: Color, board_col: int,
                          is_front: bool) -> Optional[Tuple[int, int]]:
    """Find front or rear piece of given type on a column.

    'Front' means closer to the opponent's side:
    - For Red: smaller row number is front
    - For Black: larger row number is front
    """
    candidates = []
    positions = game._red_pieces if color == Color.RED else game._black_pieces
    for pos in positions:
        piece = game.board[pos]
        if piece.type == piece_type and pos[1] == board_col:
            candidates.append(pos)

    if len(candidates) < 2:
        return candidates[0] if candidates else None

    # Sort by row
    candidates.sort(key=lambda p: p[0])

    if color == Color.RED:
        # Red front = smaller row (closer to row 0 = black side)
        return candidates[0] if is_front else candidates[-1]
    else:
        # Black front = larger row (closer to row 9 = red side)
        return candidates[-1] if is_front else candidates[0]


def parse_wxf_move(game: ChineseChessGame, wxf: str,
                   color: Color) -> Optional[Move]:
    """Parse a WXF notation move and find the corresponding legal move.

    WXF format: PieceCol Action Dest
    Examples: C2.5 (Cannon at col 2 moves horizontally to col 5)
              H2+3 (Horse at col 2 moves forward to col 3)
              P7+1 (Pawn at col 7 moves forward 1 step)
              +R+3 (Front Chariot moves forward 3 steps)

    Args:
        game: current game state
        wxf: WXF notation string (e.g., "C2.5")
        color: which player is moving

    Returns:
        Matching legal Move, or None if parsing fails.
    """
    wxf = wxf.strip()
    if len(wxf) != 4:
        return None

    piece_char = wxf[0]
    col_or_pos = wxf[1]
    action = wxf[2]
    dest = wxf[3]

    # Handle front/rear notation in two formats:
    # Format 1: +C.5 or -C.5 (前/后 prefix)
    # Format 2: C+.5 or C-.5 (前/后 in column position)
    is_front_rear = False

    if piece_char in ('+', '-'):
        # Format 1: +C.5
        is_front_rear = True
        is_front = (piece_char == '+')
        actual_piece_char = col_or_pos
        if actual_piece_char not in WXF_PIECES:
            return None
        piece_type = WXF_PIECES[actual_piece_char]
        action = wxf[2]
        dest = wxf[3]
    elif col_or_pos in ('+', '-') and piece_char in WXF_PIECES:
        # Format 2: C+.5 or C-.5
        is_front_rear = True
        is_front = (col_or_pos == '+')
        piece_type = WXF_PIECES[piece_char]
        action = wxf[2]
        dest = wxf[3]

    if is_front_rear:

        # Need to find which column has two of this piece type
        positions = game._red_pieces if color == Color.RED else game._black_pieces
        col_counts: Dict[int, list] = defaultdict(list)
        for pos in positions:
            p = game.board[pos]
            if p.type == piece_type:
                col_counts[pos[1]].append(pos)

        from_pos = None
        for col, pieces in col_counts.items():
            if len(pieces) >= 2:
                from_pos = find_piece_front_rear(game, piece_type, color, col, is_front)
                break

        if from_pos is None:
            return None
    else:
        if piece_char not in WXF_PIECES:
            return None
        piece_type = WXF_PIECES[piece_char]

        try:
            wxf_col = int(col_or_pos)
        except ValueError:
            return None

        board_col = wxf_col_to_board_col(wxf_col, color)
        from_pos = find_piece_on_col(game, piece_type, color, board_col)

        if from_pos is None:
            # Try finding among multiple pieces on same column
            # Sometimes the notation is unambiguous because only one can make the move
            positions = game._red_pieces if color == Color.RED else game._black_pieces
            candidates = []
            for pos in positions:
                p = game.board[pos]
                if p.type == piece_type and pos[1] == board_col:
                    candidates.append(pos)
            if not candidates:
                return None
            # Try each candidate and see which one can make a valid move
            from_pos = None
            for cand in candidates:
                test_to = _compute_destination(cand, piece_type, color, action, dest)
                if test_to is not None:
                    move = _find_legal_move(game, cand, test_to)
                    if move is not None:
                        return move
            return None

    if action not in ('.', '+', '-'):
        return None

    try:
        dest_num = int(dest)
    except ValueError:
        return None

    to_pos = _compute_destination(from_pos, piece_type, color, action, dest)
    if to_pos is None:
        return None

    return _find_legal_move(game, from_pos, to_pos)


def _compute_destination(from_pos: Tuple[int, int], piece_type: PieceType,
                         color: Color, action: str,
                         dest: str) -> Optional[Tuple[int, int]]:
    """Compute destination position from WXF action and destination."""
    try:
        dest_num = int(dest)
    except ValueError:
        return None

    from_row, from_col = from_pos

    if action == '.':
        # Horizontal move — dest_num is the destination column in WXF
        to_col = wxf_col_to_board_col(dest_num, color)
        return (from_row, to_col)

    # Forward (+) or backward (-)
    # Direction depends on color
    if color == Color.RED:
        direction = -1 if action == '+' else 1  # Red forward = row decreasing
    else:
        direction = 1 if action == '+' else -1   # Black forward = row increasing

    if piece_type in (PieceType.CHARIOT, PieceType.CANNON, PieceType.GENERAL, PieceType.SOLDIER):
        # Straight-line pieces: dest_num = number of steps
        to_row = from_row + direction * dest_num
        return (to_row, from_col)

    elif piece_type == PieceType.HORSE:
        # Horse: dest_num = destination column in WXF
        to_col = wxf_col_to_board_col(dest_num, color)
        col_diff = abs(to_col - from_col)
        if col_diff == 1:
            row_diff = 2
        elif col_diff == 2:
            row_diff = 1
        else:
            return None
        to_row = from_row + direction * row_diff
        return (to_row, to_col)

    elif piece_type == PieceType.ELEPHANT:
        # Elephant: dest_num = destination column, always moves 2 rows
        to_col = wxf_col_to_board_col(dest_num, color)
        to_row = from_row + direction * 2
        return (to_row, to_col)

    elif piece_type == PieceType.ADVISOR:
        # Advisor: dest_num = destination column, always moves 1 row
        to_col = wxf_col_to_board_col(dest_num, color)
        to_row = from_row + direction * 1
        return (to_row, to_col)

    return None


def _find_legal_move(game: ChineseChessGame, from_pos: Tuple[int, int],
                     to_pos: Tuple[int, int]) -> Optional[Move]:
    """Find a legal move matching from_pos -> to_pos."""
    for move in game.get_legal_moves():
        if move.from_pos == from_pos and move.to_pos == to_pos:
            return move
    return None


def _board_state(game: ChineseChessGame) -> dict:
    return {r * COLS + c: (int(p.color), int(p.type)) for (r, c), p in game.board.items()}


def load_kaggle_data(data_dir: str) -> Tuple[Dict[int, dict], Dict[int, List]]:
    """Load gameinfo and moves from Kaggle CSV files.

    Returns:
        (game_info, game_moves) where:
        - game_info[gameID] = {'winner': 'red'/'black'/'draw', 'redELO': int, ...}
        - game_moves[gameID] = [(turn, side, wxf_move), ...]
    """
    game_info = {}
    info_path = os.path.join(data_dir, 'gameinfo.csv')
    with open(info_path, 'r') as f:
        reader = csv.DictReader(f)
        for row in reader:
            gid = int(row['gameID'])
            game_info[gid] = {
                'winner': row['winner'],
                'redELO': int(row['redELO']),
                'blackELO': int(row['blackELO']),
            }

    game_moves: Dict[int, List] = defaultdict(list)
    moves_path = os.path.join(data_dir, 'moves.csv')
    with open(moves_path, 'r') as f:
        reader = csv.DictReader(f)
        for row in reader:
            gid = int(row['gameID'])
            turn = int(row['turn'])
            side = row['side']
            move = row['move']
            game_moves[gid].append((turn, side, move))

    return game_info, game_moves


def interleave_moves(moves_list: List[Tuple[int, str, str]]) -> List[Tuple[str, str]]:
    """Interleave red and black moves by turn number.

    Input moves are grouped by side. Output is alternating (side, wxf) pairs.
    """
    red_moves = sorted([(t, m) for t, s, m in moves_list if s == 'red'])
    black_moves = sorted([(t, m) for t, s, m in moves_list if s == 'black'])

    result = []
    ri = bi = 0
    while ri < len(red_moves) or bi < len(black_moves):
        if ri < len(red_moves):
            result.append(('red', red_moves[ri][1]))
            ri += 1
        if bi < len(black_moves):
            result.append(('black', black_moves[bi][1]))
            bi += 1

    return result


def replay_game(game_id: int, moves: List[Tuple[str, str]],
                winner: str) -> List[PositionRecord]:
    """Replay a game and generate training records.

    Args:
        game_id: for logging
        moves: list of (side, wxf_notation) in play order
        winner: 'red', 'black', or 'draw'

    Returns:
        List of PositionRecords from this game.
    """
    game = ChineseChessGame()
    positions = []

    for side, wxf in moves:
        color = Color.RED if side == 'red' else Color.BLACK

        # Verify it's this side's turn
        if game.current_player != color:
            # Sometimes data has issues — skip
            break

        # Encode current position
        bs = _board_state(game)
        tensor = board_to_tensor(bs, int(game.current_player)).numpy()

        # Parse and apply move
        move = parse_wxf_move(game, wxf, color)
        if move is None:
            logger.debug("Game %d: failed to parse '%s' for %s", game_id, wxf, side)
            break

        # Record position
        try:
            action_idx = move_to_index(
                move.from_pos[0] * COLS + move.from_pos[1],
                move.to_pos[0] * COLS + move.to_pos[1],
            )
        except KeyError:
            break

        positions.append((tensor, action_idx, int(color)))
        game.make_move(move)

        # Check if game is over
        over, _ = game.is_game_over()
        if over:
            break

    # Value target from RED's perspective
    if winner == 'red':
        result_value = 1.0
    elif winner == 'black':
        result_value = -1.0
    else:
        result_value = 0.0

    records = []
    for tensor, action_idx, player in positions:
        policy = np.zeros(NUM_ACTIONS, dtype=np.float32)
        policy[action_idx] = 1.0
        records.append(PositionRecord(
            board_tensor=tensor,
            policy_target=policy,
            value_target=result_value,
            current_player=player,
        ))

    return records


def process_dataset(data_dir: str, min_elo: int = 0) -> List[PositionRecord]:
    """Process the full Kaggle dataset into training records.

    Args:
        data_dir: directory containing gameinfo.csv and moves.csv
        min_elo: filter games where both players have >= this ELO

    Returns:
        List of all PositionRecords.
    """
    logger.info("Loading data from %s...", data_dir)
    game_info, game_moves = load_kaggle_data(data_dir)
    logger.info("Loaded %d games, %d move entries",
                len(game_info), sum(len(v) for v in game_moves.values()))

    all_records = []
    success = 0
    failed = 0
    skipped = 0

    for gid, info in sorted(game_info.items()):
        # Filter by ELO
        if min_elo > 0:
            if info['redELO'] < min_elo or info['blackELO'] < min_elo:
                skipped += 1
                continue

        if gid not in game_moves:
            skipped += 1
            continue

        moves = interleave_moves(game_moves[gid])
        if not moves:
            skipped += 1
            continue

        records = replay_game(gid, moves, info['winner'])

        if len(records) > 5:  # Need at least some moves
            all_records.extend(records)
            success += 1
        else:
            failed += 1

        if (success + failed) % 1000 == 0:
            logger.info("Progress: %d success, %d failed, %d skipped, %d positions",
                        success, failed, skipped, len(all_records))

    logger.info("Done: %d success, %d failed, %d skipped, %d total positions",
                success, failed, skipped, len(all_records))
    return all_records


def main():
    parser = argparse.ArgumentParser(
        description="Pre-train xiangqi model using Kaggle game records"
    )
    parser.add_argument("--data-dir", type=str,
                        default="/Users/ying/Downloads/archive",
                        help="Directory containing gameinfo.csv and moves.csv")
    parser.add_argument("--min-elo", type=int, default=0,
                        help="Filter games by minimum ELO (default: 0 = no filter)")
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--batch-size", type=int, default=256)
    parser.add_argument("--lr", type=float, default=0.001)
    parser.add_argument("--checkpoint-dir", type=str, default="./checkpoints")
    parser.add_argument("--model-path", type=str, default=None,
                        help="Continue training from existing model")
    parser.add_argument("--num-filters", type=int, default=128)
    parser.add_argument("--num-blocks", type=int, default=6)
    parser.add_argument("--log-level", type=str, default="INFO")

    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    # Device
    if torch.cuda.is_available():
        device = torch.device('cuda')
    elif torch.backends.mps.is_available():
        device = torch.device('mps')
    else:
        device = torch.device('cpu')
    logger.info("Device: %s", device)

    # Model
    model = create_model(num_filters=args.num_filters, num_blocks=args.num_blocks)
    if args.model_path:
        ckpt = torch.load(args.model_path, map_location='cpu', weights_only=False)
        if 'model_state_dict' in ckpt:
            model.load_state_dict(ckpt['model_state_dict'])
        else:
            model.load_state_dict(ckpt)
        logger.info("Loaded model from %s", args.model_path)

    # Process dataset
    records = process_dataset(args.data_dir, min_elo=args.min_elo)

    if len(records) < 100:
        logger.error("Too few positions (%d). Check data parsing.", len(records))
        return

    # Train
    model = train_supervised(
        model=model,
        records=records,
        epochs=args.epochs,
        batch_size=args.batch_size,
        lr=args.lr,
        device=device,
        checkpoint_dir=args.checkpoint_dir,
    )

    logger.info("Kaggle pre-training complete!")


if __name__ == "__main__":
    main()
