"""
Supervised pre-training for Chinese Chess neural network.

Generates training data by having an alpha-beta engine play games against
itself, then trains the neural network to mimic the engine's move choices
(policy) and predict game outcomes (value).

This is "knowledge distillation" — the neural network learns to approximate
the hand-crafted evaluation + search, providing a strong starting point for
subsequent self-play (AlphaZero-style) fine-tuning.

Usage::

    python pretrain.py --num-games 1000 --search-depth 4 --epochs 20
"""

from __future__ import annotations

import argparse
import logging
import os
import random
import time
from dataclasses import dataclass, field
from typing import List, Optional, Tuple

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
import torch.optim as optim
from torch.utils.data import Dataset, DataLoader

from model import ChineseChessNet, create_model
from encoding import (
    board_to_tensor, move_to_index, NUM_ACTIONS, COLS, ROWS,
)
from game import ChineseChessGame, Color, Move, PieceType
from alphabeta import AlphaBetaEngine

logger = logging.getLogger(__name__)

# Maximum moves per game before declaring a draw
MAX_GAME_MOVES = 200

# Repetition limit — declare draw after N repeated positions
MAX_REPETITIONS = 3


# ======================================================================
# Data structures
# ======================================================================

@dataclass
class PositionRecord:
    """A single training position extracted from an engine game."""
    board_tensor: np.ndarray   # (15, 10, 9) float32
    policy_target: np.ndarray  # (NUM_ACTIONS,) float32 — one-hot at engine move
    value_target: float        # +1 red win, -1 black win, 0 draw
    current_player: int        # 0=Red, 1=Black (who was to move)


class PretrainDataset(Dataset):
    """PyTorch Dataset wrapping a list of PositionRecords."""

    def __init__(self, records: List[PositionRecord]) -> None:
        self.records = records

    def __len__(self) -> int:
        return len(self.records)

    def __getitem__(self, idx: int) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        rec = self.records[idx]
        board = torch.from_numpy(rec.board_tensor)
        policy = torch.from_numpy(rec.policy_target)
        # Value target from current player's perspective:
        # If current_player was Red and Red won -> +1
        # If current_player was Red and Red lost -> -1
        # Flip sign when current player was Black
        if rec.current_player == 0:
            value = rec.value_target
        else:
            value = -rec.value_target
        value_t = torch.tensor(value, dtype=torch.float32)
        return board, policy, value_t


# ======================================================================
# Game generation
# ======================================================================

def _game_to_board_state(game: ChineseChessGame) -> dict:
    """Convert game board to encoding.board_to_tensor format."""
    board_state = {}
    for (row, col), piece in game.board.items():
        flat = row * COLS + col
        board_state[flat] = (int(piece.color), int(piece.type))
    return board_state


def _move_to_action_index(move: Move) -> int:
    """Convert a game Move to policy action index."""
    fr, fc = move.from_pos
    tr, tc = move.to_pos
    return move_to_index(fr * COLS + fc, tr * COLS + tc)


def _add_opening_noise(game: ChineseChessGame, num_random_moves: int) -> None:
    """Play a few random legal moves at the start to diversify openings."""
    for _ in range(num_random_moves):
        moves = game.get_legal_moves()
        if not moves:
            break
        game.make_move(random.choice(moves))
        over, _ = game.is_game_over()
        if over:
            break


def generate_game(engine: AlphaBetaEngine,
                  random_opening_moves: int = 0) -> Tuple[List[PositionRecord], Optional[int]]:
    """Play one full game with the alpha-beta engine on both sides.

    Args:
        engine: the alpha-beta search engine.
        random_opening_moves: number of random moves at the start for
            opening diversification (0 to disable).

    Returns:
        (records, winner) where winner is 0=Red, 1=Black, or None for draw.
    """
    game = ChineseChessGame()

    # Optional random opening diversification
    if random_opening_moves > 0:
        n = random.randint(0, random_opening_moves)
        _add_opening_noise(game, n)

    positions: List[Tuple[np.ndarray, int, int]] = []  # (tensor, action_idx, player)
    position_counts: dict[tuple, int] = {}
    move_count = 0

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

        # Encode current position
        board_state = _game_to_board_state(game)
        tensor = board_to_tensor(board_state, int(game.current_player))
        tensor_np = tensor.numpy()

        # Engine selects move
        best_move = engine.search(game)
        if best_move is None:
            break

        action_idx = _move_to_action_index(best_move)
        positions.append((tensor_np, action_idx, int(game.current_player)))

        game.make_move(best_move)
        move_count += 1

    # Determine game result
    if winner is None and move_count >= MAX_GAME_MOVES:
        # Timeout draw
        winner_int = None
    elif winner is not None:
        winner_int = int(winner)
    else:
        winner_int = None

    # Build PositionRecords with the final game result
    # value_target is from RED's perspective: +1 if Red won, -1 if Black won, 0 draw
    if winner_int == 0:
        result_value = 1.0    # Red won
    elif winner_int == 1:
        result_value = -1.0   # Black won
    else:
        result_value = 0.0    # Draw

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


def generate_games(num_games: int, search_depth: int,
                   random_opening_moves: int = 4) -> List[PositionRecord]:
    """Generate training data from alpha-beta self-play.

    Args:
        num_games: number of games to play.
        search_depth: alpha-beta search depth.
        random_opening_moves: max random moves at the start of each game.

    Returns:
        List of all PositionRecords collected.
    """
    engine = AlphaBetaEngine(depth=search_depth, quiescence_depth=2,
                             time_limit=30.0)
    all_records: List[PositionRecord] = []
    red_wins = 0
    black_wins = 0
    draws = 0

    t0 = time.time()
    for game_idx in range(num_games):
        gt0 = time.time()
        records, winner = generate_game(engine, random_opening_moves)
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

        # Clear TT periodically to avoid stale entries dominating
        if (game_idx + 1) % 50 == 0:
            engine.clear_cache()

        logger.info(
            "Game %d/%d: %d moves, %s wins, %.1fs (%d positions total)",
            game_idx + 1, num_games, len(records), result_str,
            elapsed, len(all_records),
        )

    total_time = time.time() - t0
    logger.info(
        "Generation complete: %d games in %.1fs (%.1fs/game), "
        "Red=%d Black=%d Draw=%d, %d total positions",
        num_games, total_time, total_time / max(1, num_games),
        red_wins, black_wins, draws, len(all_records),
    )
    return all_records


# ======================================================================
# Training
# ======================================================================

class SupervisedTrainer:
    """Trains the neural network to mimic the alpha-beta engine.

    Uses cross-entropy loss for policy and MSE for value, matching the
    same loss functions used in AlphaZero self-play training (train.py).
    """

    def __init__(
        self,
        model: ChineseChessNet,
        lr: float = 0.001,
        weight_decay: float = 1e-4,
        batch_size: int = 128,
        device: Optional[torch.device] = None,
    ) -> None:
        self.model = model
        self.batch_size = batch_size
        self.device = device or torch.device('cpu')
        self.model.to(self.device)

        self.optimizer = optim.Adam(
            model.parameters(),
            lr=lr,
            weight_decay=weight_decay,
        )
        # Learning rate scheduler: reduce on plateau
        self.scheduler = optim.lr_scheduler.ReduceLROnPlateau(
            self.optimizer, mode='min', factor=0.5, patience=3,
        )

    def train_epoch(self, dataset: PretrainDataset,
                    shuffle: bool = True) -> dict:
        """Train for one epoch over the dataset.

        Returns:
            Dict with 'total_loss', 'policy_loss', 'value_loss',
            'policy_accuracy'.
        """
        self.model.train()
        loader = DataLoader(
            dataset, batch_size=self.batch_size, shuffle=shuffle,
            num_workers=0, pin_memory=(self.device.type == 'cuda'),
        )

        total_policy_loss = 0.0
        total_value_loss = 0.0
        total_correct = 0
        total_samples = 0
        num_batches = 0

        for boards, policies, values in loader:
            boards = boards.to(self.device)
            policies = policies.to(self.device)
            values = values.to(self.device)

            policy_logits, pred_values = self.model(boards)
            pred_values = pred_values.squeeze(-1)

            # Policy loss: cross-entropy with one-hot target
            log_probs = F.log_softmax(policy_logits, dim=1)
            policy_loss = -torch.sum(policies * log_probs, dim=1).mean()

            # Value loss: MSE
            value_loss = F.mse_loss(pred_values, values)

            total_loss = policy_loss + value_loss

            self.optimizer.zero_grad()
            total_loss.backward()
            # Gradient clipping for stability
            torch.nn.utils.clip_grad_norm_(self.model.parameters(), 1.0)
            self.optimizer.step()

            total_policy_loss += policy_loss.item()
            total_value_loss += value_loss.item()
            num_batches += 1
            total_samples += boards.size(0)

            # Policy accuracy: check if top prediction matches target
            pred_actions = policy_logits.argmax(dim=1)
            target_actions = policies.argmax(dim=1)
            total_correct += (pred_actions == target_actions).sum().item()

        avg_policy = total_policy_loss / max(1, num_batches)
        avg_value = total_value_loss / max(1, num_batches)
        accuracy = total_correct / max(1, total_samples)

        return {
            'total_loss': avg_policy + avg_value,
            'policy_loss': avg_policy,
            'value_loss': avg_value,
            'policy_accuracy': accuracy,
        }


def train_supervised(
    model: ChineseChessNet,
    records: List[PositionRecord],
    epochs: int = 20,
    batch_size: int = 128,
    lr: float = 0.001,
    device: Optional[torch.device] = None,
    checkpoint_dir: str = "./checkpoints",
) -> ChineseChessNet:
    """Run the full supervised training loop.

    Args:
        model: neural network to train.
        records: training data from engine games.
        epochs: number of training epochs.
        batch_size: mini-batch size.
        lr: initial learning rate.
        device: torch device.
        checkpoint_dir: directory for saving checkpoints.

    Returns:
        The trained model.
    """
    device = device or torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    os.makedirs(checkpoint_dir, exist_ok=True)

    # Shuffle and split into train/val (90/10)
    random.shuffle(records)
    split = int(0.9 * len(records))
    train_records = records[:split]
    val_records = records[split:]

    logger.info("Training on %d positions, validating on %d",
                len(train_records), len(val_records))

    train_dataset = PretrainDataset(train_records)
    val_dataset = PretrainDataset(val_records)

    trainer = SupervisedTrainer(
        model=model, lr=lr, batch_size=batch_size, device=device,
    )

    best_val_loss = float('inf')

    for epoch in range(1, epochs + 1):
        t0 = time.time()

        # Train
        train_metrics = trainer.train_epoch(train_dataset)

        # Validate
        val_metrics = _validate(model, val_dataset, batch_size, device)

        elapsed = time.time() - t0

        logger.info(
            "Epoch %d/%d (%.1fs): "
            "train_loss=%.4f (policy=%.4f value=%.4f acc=%.2f%%) | "
            "val_loss=%.4f (policy=%.4f value=%.4f acc=%.2f%%)",
            epoch, epochs, elapsed,
            train_metrics['total_loss'],
            train_metrics['policy_loss'],
            train_metrics['value_loss'],
            train_metrics['policy_accuracy'] * 100,
            val_metrics['total_loss'],
            val_metrics['policy_loss'],
            val_metrics['value_loss'],
            val_metrics['policy_accuracy'] * 100,
        )

        # Step the LR scheduler on validation loss
        trainer.scheduler.step(val_metrics['total_loss'])

        # Save checkpoint if validation improved
        if val_metrics['total_loss'] < best_val_loss:
            best_val_loss = val_metrics['total_loss']
            path = os.path.join(checkpoint_dir, "pretrained_best.pt")
            torch.save({
                'epoch': epoch,
                'model_state_dict': model.state_dict(),
                'optimizer_state_dict': trainer.optimizer.state_dict(),
                'val_loss': best_val_loss,
                'train_metrics': train_metrics,
                'val_metrics': val_metrics,
            }, path)
            logger.info("Best checkpoint saved: %s (val_loss=%.4f)",
                        path, best_val_loss)

        # Periodic checkpoint
        if epoch % 5 == 0:
            path = os.path.join(checkpoint_dir, f"pretrained_epoch_{epoch:03d}.pt")
            torch.save({
                'epoch': epoch,
                'model_state_dict': model.state_dict(),
                'optimizer_state_dict': trainer.optimizer.state_dict(),
                'val_loss': val_metrics['total_loss'],
            }, path)

    # Save final model
    final_path = os.path.join(checkpoint_dir, "pretrained_final.pt")
    torch.save({
        'epoch': epochs,
        'model_state_dict': model.state_dict(),
    }, final_path)
    logger.info("Final model saved: %s", final_path)

    # Export ONNX
    try:
        model.cpu()
        onnx_path = os.path.join(checkpoint_dir, "pretrained_best.onnx")
        model.export_to_onnx(onnx_path)
        model.to(device)
        logger.info("ONNX exported: %s", onnx_path)
    except Exception as e:
        model.to(device)
        logger.warning("ONNX export failed: %s", e)

    return model


def _validate(model: ChineseChessNet, dataset: PretrainDataset,
              batch_size: int,
              device: torch.device) -> dict:
    """Run validation and return metrics."""
    model.eval()
    loader = DataLoader(dataset, batch_size=batch_size, shuffle=False,
                        num_workers=0)

    total_policy_loss = 0.0
    total_value_loss = 0.0
    total_correct = 0
    total_samples = 0
    num_batches = 0

    with torch.no_grad():
        for boards, policies, values in loader:
            boards = boards.to(device)
            policies = policies.to(device)
            values = values.to(device)

            policy_logits, pred_values = model(boards)
            pred_values = pred_values.squeeze(-1)

            log_probs = F.log_softmax(policy_logits, dim=1)
            policy_loss = -torch.sum(policies * log_probs, dim=1).mean()
            value_loss = F.mse_loss(pred_values, values)

            total_policy_loss += policy_loss.item()
            total_value_loss += value_loss.item()
            num_batches += 1
            total_samples += boards.size(0)

            pred_actions = policy_logits.argmax(dim=1)
            target_actions = policies.argmax(dim=1)
            total_correct += (pred_actions == target_actions).sum().item()

    avg_policy = total_policy_loss / max(1, num_batches)
    avg_value = total_value_loss / max(1, num_batches)
    accuracy = total_correct / max(1, total_samples)

    model.train()
    return {
        'total_loss': avg_policy + avg_value,
        'policy_loss': avg_policy,
        'value_loss': avg_value,
        'policy_accuracy': accuracy,
    }


# ======================================================================
# CLI entry point
# ======================================================================

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Supervised pre-training for Chinese Chess neural network "
                    "(knowledge distillation from alpha-beta engine)"
    )
    parser.add_argument("--num-games", type=int, default=1000,
                        help="Number of alpha-beta self-play games to generate "
                             "(default: 1000)")
    parser.add_argument("--search-depth", type=int, default=4,
                        help="Alpha-beta search depth (default: 4)")
    parser.add_argument("--epochs", type=int, default=20,
                        help="Training epochs (default: 20)")
    parser.add_argument("--batch-size", type=int, default=128,
                        help="Mini-batch size (default: 128)")
    parser.add_argument("--lr", type=float, default=0.001,
                        help="Initial learning rate (default: 0.001)")
    parser.add_argument("--checkpoint-dir", type=str, default="./checkpoints",
                        help="Directory for model checkpoints "
                             "(default: ./checkpoints)")
    parser.add_argument("--model-path", type=str, default=None,
                        help="Path to existing model checkpoint to continue "
                             "training from")
    parser.add_argument("--num-filters", type=int, default=128,
                        help="Number of conv filters in the model (default: 128)")
    parser.add_argument("--num-blocks", type=int, default=6,
                        help="Number of residual blocks (default: 6)")
    parser.add_argument("--random-opening-moves", type=int, default=4,
                        help="Max random moves at game start for opening "
                             "diversification (default: 4)")
    parser.add_argument("--log-level", type=str, default="INFO",
                        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
                        help="Logging level (default: INFO)")

    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    # Select device
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    logger.info("Using device: %s", device)

    # Create or load model
    model = create_model(num_filters=args.num_filters,
                         num_blocks=args.num_blocks)

    if args.model_path is not None:
        logger.info("Loading model from %s", args.model_path)
        checkpoint = torch.load(args.model_path, map_location='cpu',
                                weights_only=False)
        if 'model_state_dict' in checkpoint:
            model.load_state_dict(checkpoint['model_state_dict'])
        else:
            model.load_state_dict(checkpoint)
        logger.info("Model loaded successfully")

    # --- Phase 1: Generate training data ---
    logger.info("=" * 60)
    logger.info("Phase 1: Generating %d games (depth=%d, random_opening=%d)",
                args.num_games, args.search_depth,
                args.random_opening_moves)
    logger.info("=" * 60)

    records = generate_games(
        num_games=args.num_games,
        search_depth=args.search_depth,
        random_opening_moves=args.random_opening_moves,
    )

    if len(records) < 100:
        logger.error("Too few training positions (%d). Something went wrong.",
                     len(records))
        return

    # --- Phase 2: Supervised training ---
    logger.info("=" * 60)
    logger.info("Phase 2: Supervised training (%d epochs, %d positions)",
                args.epochs, len(records))
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

    logger.info("=" * 60)
    logger.info("Pre-training complete!")
    logger.info(
        "Next steps: use the pretrained model as starting point for "
        "AlphaZero self-play training:\n"
        "  python train.py --checkpoint-dir %s "
        "--model-path %s/pretrained_best.pt",
        args.checkpoint_dir, args.checkpoint_dir,
    )
    logger.info("=" * 60)


if __name__ == "__main__":
    main()
