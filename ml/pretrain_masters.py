"""
Supervised pre-training using 40k+ master game records (UCI PGN format).

Usage::
    python pretrain_masters.py --pgn data/xqdb_masters_40711_UCI_games.pgn --epochs 30
"""
from __future__ import annotations

import argparse, logging, os, random, re
from typing import List, Optional, Tuple

import numpy as np
import torch

from model import create_model
from encoding import board_to_tensor, move_to_index, NUM_ACTIONS, COLS, ROWS
from game import ChineseChessGame, Color, Move
from pretrain import PositionRecord, train_supervised

logger = logging.getLogger(__name__)


def parse_uci_pgn(filepath: str) -> list:
    """Parse UCI PGN file into list of games with metadata and moves."""
    games = []
    current = {}
    with open(filepath) as f:
        for line in f:
            line = line.strip()
            if line.startswith('['):
                m = re.match(r'\[(\w+)\s+"(.*?)"\]', line)
                if m:
                    current[m.group(1)] = m.group(2)
            elif line:
                moves_str = re.sub(r'\d+\.', '', line)
                moves = [m for m in moves_str.split()
                         if m not in ('1-0', '0-1', '1/2-1/2', '*') and len(m) == 4]
                if moves:
                    current['moves'] = moves
                    games.append(current)
                    current = {}
    return games


def uci_to_positions(move_str: str) -> Tuple[Tuple[int, int], Tuple[int, int]]:
    """Convert UCI move like 'h2e2' to board positions."""
    fc = ord(move_str[0]) - ord('a')
    fr = 9 - int(move_str[1])
    tc = ord(move_str[2]) - ord('a')
    tr = 9 - int(move_str[3])
    return (fr, fc), (tr, tc)


def find_legal_move(game: ChineseChessGame,
                    from_pos: Tuple[int, int],
                    to_pos: Tuple[int, int]) -> Optional[Move]:
    for m in game.get_legal_moves():
        if m.from_pos == from_pos and m.to_pos == to_pos:
            return m
    return None


def replay_uci_game(moves: List[str]) -> Tuple[List[PositionRecord], int]:
    """Replay a game from UCI moves and extract training positions.

    Returns (records, num_moves_parsed).
    """
    game = ChineseChessGame()
    positions = []

    for uci_move in moves:
        try:
            from_pos, to_pos = uci_to_positions(uci_move)
        except (ValueError, IndexError):
            break

        # Encode position before making the move
        bs = {r * COLS + c: (int(p.color), int(p.type))
              for (r, c), p in game.board.items()}
        tensor = board_to_tensor(bs, int(game.current_player)).numpy()

        # Find and validate the move
        move = find_legal_move(game, from_pos, to_pos)
        if move is None:
            break

        try:
            action_idx = move_to_index(
                from_pos[0] * COLS + from_pos[1],
                to_pos[0] * COLS + to_pos[1],
            )
        except KeyError:
            break

        positions.append((tensor, action_idx, int(game.current_player)))
        game.make_move(move)

        if game.is_game_over()[0]:
            break

    return positions, len(positions)


def process_pgn(filepath: str) -> List[PositionRecord]:
    """Process entire PGN file into training records."""
    logger.info("Parsing %s...", filepath)
    games = parse_uci_pgn(filepath)
    logger.info("Found %d games", len(games))

    all_records = []
    success = 0
    failed = 0

    for i, game_data in enumerate(games):
        moves = game_data.get('moves', [])
        if not moves:
            failed += 1
            continue

        positions, n_parsed = replay_uci_game(moves)

        if n_parsed < 10:
            failed += 1
            continue

        # Determine winner from result or last position
        result = game_data.get('Result', '*')
        if result == '1-0':
            value = 1.0  # Red wins
        elif result == '0-1':
            value = -1.0  # Black wins
        else:
            value = 0.0  # Draw or unknown

        for tensor, action_idx, player in positions:
            policy = np.zeros(NUM_ACTIONS, dtype=np.float32)
            policy[action_idx] = 1.0
            all_records.append(PositionRecord(
                board_tensor=tensor,
                policy_target=policy,
                value_target=value,
                current_player=player,
            ))

        success += 1
        if (success + failed) % 5000 == 0:
            logger.info("Progress: %d ok, %d fail, %d positions",
                        success, failed, len(all_records))

    logger.info("Done: %d games ok, %d failed, %d total positions",
                success, failed, len(all_records))
    return all_records


def main():
    parser = argparse.ArgumentParser(
        description="Pre-train on 40k+ master game records"
    )
    parser.add_argument("--pgn", type=str,
                        default="data/xqdb_masters_40711_UCI_games.pgn")
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--batch-size", type=int, default=256)
    parser.add_argument("--lr", type=float, default=0.001)
    parser.add_argument("--checkpoint-dir", type=str, default="./checkpoints")
    parser.add_argument("--model-path", type=str, default=None)
    parser.add_argument("--num-filters", type=int, default=128)
    parser.add_argument("--num-blocks", type=int, default=6)
    parser.add_argument("--log-level", type=str, default="INFO")

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
    logger.info("Device: %s", device)

    model = create_model(num_filters=args.num_filters, num_blocks=args.num_blocks)
    if args.model_path:
        ckpt = torch.load(args.model_path, map_location='cpu', weights_only=False)
        sd = ckpt.get('model_state_dict', ckpt)
        model.load_state_dict(sd)
        logger.info("Loaded model from %s", args.model_path)

    records = process_pgn(args.pgn)
    if len(records) < 100:
        logger.error("Too few positions (%d)", len(records))
        return

    model = train_supervised(
        model=model,
        records=records,
        epochs=args.epochs,
        batch_size=args.batch_size,
        lr=args.lr,
        device=device,
        checkpoint_dir=args.checkpoint_dir,
    )
    logger.info("Master pre-training complete!")


if __name__ == "__main__":
    main()
