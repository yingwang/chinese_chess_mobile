"""
AlphaZero-style training loop for Chinese Chess.

Orchestrates self-play data generation, neural network training, and model
evaluation in an iterative loop.
"""

from __future__ import annotations

import argparse
import logging
import os
import random
import time
from collections import deque
from dataclasses import dataclass, field
from typing import List, Optional, Tuple, Dict

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
import torch.optim as optim
from torch.utils.data import Dataset, DataLoader

from model import ChineseChessNet, create_model
from encoding import NUM_ACTIONS
from self_play import SelfPlayManager, SelfPlayWorker, TrainingExample
from game import ChineseChessGame, Color
from mcts import MCTS

logger = logging.getLogger(__name__)


class ReplayBuffer:
    """Fixed-capacity ring buffer for training examples."""

    def __init__(self, capacity: int = 500_000) -> None:
        self.buffer: deque[TrainingExample] = deque(maxlen=capacity)

    def add(self, examples: List[TrainingExample]) -> None:
        self.buffer.extend(examples)

    def sample(self, batch_size: int) -> List[TrainingExample]:
        return random.sample(list(self.buffer), min(batch_size, len(self.buffer)))

    def __len__(self) -> int:
        return len(self.buffer)


class TrainingDataset(Dataset):
    """Wraps a list of TrainingExamples as a PyTorch Dataset."""

    def __init__(self, examples: List[TrainingExample]) -> None:
        self.examples = examples

    def __len__(self) -> int:
        return len(self.examples)

    def __getitem__(self, idx: int) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        ex = self.examples[idx]
        board = torch.from_numpy(ex.board_tensor)
        policy = torch.from_numpy(ex.policy_target)
        value = torch.tensor(ex.value_target, dtype=torch.float32)
        return board, policy, value


class Trainer:
    """Handles a single training epoch over the replay buffer."""

    def __init__(
        self,
        model: ChineseChessNet,
        lr: float = 0.001,
        weight_decay: float = 1e-4,
        batch_size: int = 256,
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

    def train_step(
        self,
        boards: torch.Tensor,
        target_policies: torch.Tensor,
        target_values: torch.Tensor,
    ) -> Dict[str, float]:
        """Single training step on a batch.

        Args:
            boards: (B, 15, 10, 9)
            target_policies: (B, NUM_ACTIONS) — MCTS visit distributions
            target_values: (B,) — game outcomes in [-1, 1]

        Returns:
            Dict with 'total_loss', 'policy_loss', 'value_loss'.
        """
        self.model.train()
        boards = boards.to(self.device)
        target_policies = target_policies.to(self.device)
        target_values = target_values.to(self.device)

        policy_logits, pred_values = self.model(boards)
        pred_values = pred_values.squeeze(-1)

        # Policy loss: cross-entropy with MCTS visit distribution
        log_probs = F.log_softmax(policy_logits, dim=1)
        policy_loss = -torch.sum(target_policies * log_probs, dim=1).mean()

        # Value loss: MSE
        value_loss = F.mse_loss(pred_values, target_values)

        total_loss = policy_loss + value_loss

        self.optimizer.zero_grad()
        total_loss.backward()
        self.optimizer.step()

        return {
            'total_loss': total_loss.item(),
            'policy_loss': policy_loss.item(),
            'value_loss': value_loss.item(),
        }

    def train_epoch(
        self,
        replay_buffer: ReplayBuffer,
        num_batches: Optional[int] = None,
    ) -> Dict[str, float]:
        """Train for one epoch over the replay buffer.

        Args:
            replay_buffer: source of training examples.
            num_batches: if set, limit to this many batches per epoch.
                Defaults to len(buffer) // batch_size.

        Returns:
            Dict with average 'total_loss', 'policy_loss', 'value_loss'.
        """
        if len(replay_buffer) < self.batch_size:
            logger.warning("Replay buffer (%d) smaller than batch size (%d), skipping",
                           len(replay_buffer), self.batch_size)
            return {'total_loss': 0.0, 'policy_loss': 0.0, 'value_loss': 0.0}

        if num_batches is None:
            num_batches = max(1, len(replay_buffer) // self.batch_size)

        total_losses = {'total_loss': 0.0, 'policy_loss': 0.0, 'value_loss': 0.0}

        for _ in range(num_batches):
            examples = replay_buffer.sample(self.batch_size)
            boards = torch.stack([torch.from_numpy(e.board_tensor) for e in examples])
            policies = torch.stack([torch.from_numpy(e.policy_target) for e in examples])
            values = torch.tensor([e.value_target for e in examples], dtype=torch.float32)

            losses = self.train_step(boards, policies, values)
            for k in total_losses:
                total_losses[k] += losses[k]

        for k in total_losses:
            total_losses[k] /= num_batches

        return total_losses

    def set_lr(self, lr: float) -> None:
        for param_group in self.optimizer.param_groups:
            param_group['lr'] = lr


def evaluate_models(
    model_new: ChineseChessNet,
    model_old: ChineseChessNet,
    num_games: int = 20,
    num_simulations: int = 100,
    c_puct: float = 1.5,
    device: Optional[torch.device] = None,
) -> Tuple[int, int, int]:
    """Pit two models against each other and count wins.

    Each model plays both as Red and Black for fairness.

    Args:
        model_new: candidate model.
        model_old: current best model.
        num_games: total games to play (split evenly by color).
        num_simulations: MCTS simulations per move during evaluation.
        c_puct: MCTS exploration constant.
        device: torch device.

    Returns:
        (new_wins, old_wins, draws)
    """
    device = device or torch.device('cpu')
    model_new.eval()
    model_old.eval()

    new_wins = 0
    old_wins = 0
    draws = 0

    half = num_games // 2

    for game_idx in range(num_games):
        # Alternate who plays Red
        if game_idx < half:
            red_model, black_model = model_new, model_old
            new_is_red = True
        else:
            red_model, black_model = model_old, model_new
            new_is_red = False

        mcts_red = MCTS(red_model, num_simulations=num_simulations,
                        c_puct=c_puct, device=device)
        mcts_black = MCTS(black_model, num_simulations=num_simulations,
                          c_puct=c_puct, device=device)

        game = ChineseChessGame()
        move_count = 0

        while move_count < 200:
            game_over, winner = game.is_game_over()
            if game_over:
                break

            if game.current_player == Color.RED:
                moves, probs = mcts_red.get_action_probs(game, temperature=0.1)
            else:
                moves, probs = mcts_black.get_action_probs(game, temperature=0.1)

            if len(moves) == 0:
                break

            best_idx = int(np.argmax(probs))
            game.make_move(moves[best_idx])
            move_count += 1

        game_over, winner = game.is_game_over()
        if not game_over or winner is None:
            draws += 1
        else:
            winner_is_red = (winner == Color.RED)
            if (winner_is_red and new_is_red) or (not winner_is_red and not new_is_red):
                new_wins += 1
            else:
                old_wins += 1

        logger.info(
            "Eval game %d/%d: %d moves, winner=%s (new=%s)",
            game_idx + 1, num_games, move_count,
            "Red" if winner == Color.RED else ("Black" if winner == Color.BLACK else "draw"),
            "Red" if new_is_red else "Black",
        )

    return new_wins, old_wins, draws


@dataclass
class AlphaZeroConfig:
    """All hyperparameters for the training loop."""
    num_iterations: int = 100
    games_per_iteration: int = 100
    num_simulations: int = 200
    num_workers: int = 4
    batch_size: int = 256
    lr: float = 0.001
    weight_decay: float = 1e-4
    epochs_per_iteration: int = 10
    replay_buffer_capacity: int = 500_000
    eval_games: int = 20
    eval_simulations: int = 100
    eval_interval: int = 5
    win_threshold: float = 0.55
    checkpoint_dir: str = "./checkpoints"
    c_puct: float = 1.5
    temp_threshold: int = 30
    num_filters: int = 128
    num_blocks: int = 6
    resign_threshold: float = -0.95
    resign_count: int = 5
    max_game_length: int = 200


class AlphaZeroTrainer:
    """Main AlphaZero training orchestration.

    Loop:
        1. Self-play: generate N games with current model.
        2. Add training data to replay buffer.
        3. Train network for K epochs on replay buffer.
        4. Every M iterations, evaluate new model vs best model.
        5. If new model wins >55%, accept it as the new best.
    """

    def __init__(self, config: AlphaZeroConfig) -> None:
        self.config = config
        self.device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')

        # Create models
        self.current_model = create_model(
            num_filters=config.num_filters,
            num_blocks=config.num_blocks,
        ).to(self.device)
        self.best_model = create_model(
            num_filters=config.num_filters,
            num_blocks=config.num_blocks,
        ).to(self.device)
        self.best_model.load_state_dict(self.current_model.state_dict())

        self.replay_buffer = ReplayBuffer(capacity=config.replay_buffer_capacity)
        self.trainer = Trainer(
            model=self.current_model,
            lr=config.lr,
            weight_decay=config.weight_decay,
            batch_size=config.batch_size,
            device=self.device,
        )

        os.makedirs(config.checkpoint_dir, exist_ok=True)

    def run(self, num_iterations: Optional[int] = None) -> None:
        """Main training loop.

        Args:
            num_iterations: override config.num_iterations if set.
        """
        iterations = num_iterations or self.config.num_iterations
        cfg = self.config

        for iteration in range(1, iterations + 1):
            t0 = time.time()
            logger.info("=== Iteration %d/%d ===", iteration, iterations)

            # --- 1. Self-play ---
            logger.info("Self-play: generating %d games...", cfg.games_per_iteration)
            self.current_model.eval()
            sp_manager = SelfPlayManager(
                model=self.current_model,
                num_simulations=cfg.num_simulations,
                c_puct=cfg.c_puct,
                temp_threshold=cfg.temp_threshold,
                resign_threshold=cfg.resign_threshold,
                resign_count=cfg.resign_count,
                max_moves=cfg.max_game_length,
            )
            examples, sp_stats = sp_manager.generate_games(
                num_games=cfg.games_per_iteration,
                num_workers=cfg.num_workers,
            )

            logger.info(
                "Self-play done: %d examples, R=%d B=%d D=%d, avg_moves=%.1f",
                len(examples),
                sp_stats['red_wins'], sp_stats['black_wins'], sp_stats['draws'],
                sp_stats['total_moves'] / max(1, cfg.games_per_iteration),
            )

            # --- 2. Add to replay buffer ---
            self.replay_buffer.add(examples)
            logger.info("Replay buffer size: %d", len(self.replay_buffer))

            # --- 3. Train ---
            logger.info("Training for %d epochs...", cfg.epochs_per_iteration)
            for epoch in range(1, cfg.epochs_per_iteration + 1):
                losses = self.trainer.train_epoch(self.replay_buffer)
                if epoch % max(1, cfg.epochs_per_iteration // 3) == 0 or epoch == 1:
                    logger.info(
                        "  Epoch %d: total=%.4f policy=%.4f value=%.4f",
                        epoch, losses['total_loss'],
                        losses['policy_loss'], losses['value_loss'],
                    )

            # --- 4. Evaluate and possibly accept new model ---
            if iteration % cfg.eval_interval == 0:
                logger.info("Evaluating new model vs best (%d games)...", cfg.eval_games)
                new_wins, old_wins, eval_draws = evaluate_models(
                    model_new=self.current_model,
                    model_old=self.best_model,
                    num_games=cfg.eval_games,
                    num_simulations=cfg.eval_simulations,
                    c_puct=cfg.c_puct,
                    device=self.device,
                )
                total_decisive = new_wins + old_wins
                win_rate = new_wins / max(1, total_decisive)
                logger.info(
                    "Eval result: new=%d old=%d draws=%d win_rate=%.2f",
                    new_wins, old_wins, eval_draws, win_rate,
                )

                if win_rate >= cfg.win_threshold:
                    logger.info("New model accepted! (%.1f%% > %.1f%%)",
                                win_rate * 100, cfg.win_threshold * 100)
                    self.best_model.load_state_dict(self.current_model.state_dict())
                    self._save_checkpoint(iteration, is_best=True)
                else:
                    logger.info("New model rejected. Reverting to best model.")
                    self.current_model.load_state_dict(self.best_model.state_dict())
                    # Re-create optimizer for the reverted model
                    self.trainer = Trainer(
                        model=self.current_model,
                        lr=cfg.lr,
                        weight_decay=cfg.weight_decay,
                        batch_size=cfg.batch_size,
                        device=self.device,
                    )
            else:
                self._save_checkpoint(iteration, is_best=False)

            elapsed = time.time() - t0
            logger.info("Iteration %d completed in %.1fs", iteration, elapsed)

    def _save_checkpoint(self, iteration: int, is_best: bool) -> None:
        """Save model checkpoint and optionally export to ONNX."""
        cfg = self.config
        path = os.path.join(cfg.checkpoint_dir, f"model_iter_{iteration:04d}.pt")
        torch.save({
            'iteration': iteration,
            'model_state_dict': self.current_model.state_dict(),
            'optimizer_state_dict': self.trainer.optimizer.state_dict(),
            'replay_buffer_size': len(self.replay_buffer),
        }, path)
        logger.info("Checkpoint saved: %s", path)

        if is_best:
            best_path = os.path.join(cfg.checkpoint_dir, "best_model.pt")
            torch.save(self.best_model.state_dict(), best_path)

            # Export ONNX
            onnx_path = os.path.join(cfg.checkpoint_dir, "best_model.onnx")
            try:
                self.best_model.cpu()
                self.best_model.export_to_onnx(onnx_path)
                self.best_model.to(self.device)
                logger.info("ONNX exported: %s", onnx_path)
            except Exception as e:
                self.best_model.to(self.device)
                logger.warning("ONNX export failed: %s", e)

            # Attempt TFLite conversion
            tflite_path = os.path.join(cfg.checkpoint_dir, "best_model.tflite")
            self._try_tflite_export(onnx_path, tflite_path)

    @staticmethod
    def _try_tflite_export(onnx_path: str, tflite_path: str) -> None:
        """Attempt ONNX -> TFLite conversion. Logs warning on failure."""
        try:
            import onnx
            from onnx_tf.backend import prepare
            import tensorflow as tf

            onnx_model = onnx.load(onnx_path)
            tf_rep = prepare(onnx_model)

            # Save as SavedModel then convert
            saved_model_dir = onnx_path.replace('.onnx', '_savedmodel')
            tf_rep.export_graph(saved_model_dir)

            converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)
            converter.optimizations = [tf.lite.Optimize.DEFAULT]
            tflite_model = converter.convert()

            with open(tflite_path, 'wb') as f:
                f.write(tflite_model)
            logger.info("TFLite exported: %s", tflite_path)

        except ImportError:
            logger.warning(
                "TFLite export skipped — install onnx-tf and tensorflow: "
                "pip install onnx-tf tensorflow"
            )
        except Exception as e:
            logger.warning("TFLite export failed: %s", e)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="AlphaZero-style training for Chinese Chess"
    )
    parser.add_argument("--iterations", type=int, default=100,
                        help="Number of training iterations")
    parser.add_argument("--games-per-iteration", type=int, default=100,
                        help="Self-play games per iteration")
    parser.add_argument("--simulations", type=int, default=200,
                        help="MCTS simulations per move")
    parser.add_argument("--batch-size", type=int, default=256,
                        help="Training batch size")
    parser.add_argument("--lr", type=float, default=0.001,
                        help="Learning rate")
    parser.add_argument("--checkpoint-dir", type=str, default="./checkpoints",
                        help="Directory for model checkpoints")
    parser.add_argument("--num-workers", type=int, default=4,
                        help="Number of parallel self-play workers")
    parser.add_argument("--epochs", type=int, default=10,
                        help="Training epochs per iteration")
    parser.add_argument("--eval-games", type=int, default=20,
                        help="Number of evaluation games")
    parser.add_argument("--eval-interval", type=int, default=5,
                        help="Evaluate every N iterations")
    parser.add_argument("--num-filters", type=int, default=128,
                        help="Number of conv filters in the model")
    parser.add_argument("--num-blocks", type=int, default=6,
                        help="Number of residual blocks")
    parser.add_argument("--buffer-capacity", type=int, default=500_000,
                        help="Replay buffer capacity")
    parser.add_argument("--log-level", type=str, default="INFO",
                        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
                        help="Logging level")

    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    config = AlphaZeroConfig(
        num_iterations=args.iterations,
        games_per_iteration=args.games_per_iteration,
        num_simulations=args.simulations,
        batch_size=args.batch_size,
        lr=args.lr,
        checkpoint_dir=args.checkpoint_dir,
        num_workers=args.num_workers,
        epochs_per_iteration=args.epochs,
        eval_games=args.eval_games,
        eval_interval=args.eval_interval,
        num_filters=args.num_filters,
        num_blocks=args.num_blocks,
        replay_buffer_capacity=args.buffer_capacity,
    )

    logger.info("Starting AlphaZero training with config: %s", config)
    trainer = AlphaZeroTrainer(config)
    trainer.run()


if __name__ == "__main__":
    main()
