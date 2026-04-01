"""
Self-play game generation for Chinese Chess AlphaZero-style training.

Uses MCTS + neural network to play games against itself and generate
(board_state, mcts_policy, game_result) training triples.
"""

from __future__ import annotations

import logging
from concurrent.futures import ProcessPoolExecutor, as_completed
from dataclasses import dataclass, field
from typing import List, Tuple, Optional, Dict

import numpy as np
import torch

from game import ChineseChessGame, Color, Move
from mcts import MCTS, _game_to_board_state, _move_to_action_index
from encoding import board_to_tensor, NUM_ACTIONS, COLS
from model import ChineseChessNet

logger = logging.getLogger(__name__)


@dataclass
class TrainingExample:
    """A single training position from self-play."""
    board_tensor: np.ndarray   # (15, 10, 9) float32
    policy_target: np.ndarray  # (NUM_ACTIONS,) float32 — MCTS visit distribution
    value_target: float        # +1 win, -1 loss, 0 draw (from current player's view)


@dataclass
class GameResult:
    """Summary of a completed self-play game."""
    examples: List[TrainingExample]
    winner: Optional[int]  # 0=Red, 1=Black, None=draw
    num_moves: int


class SelfPlayWorker:
    """Generates one self-play game using MCTS.

    Each position is stored from the current player's perspective, so the
    value target is always relative to the player at that position.
    """

    def __init__(
        self,
        model: ChineseChessNet,
        num_simulations: int = 200,
        c_puct: float = 1.5,
        device: Optional[torch.device] = None,
    ) -> None:
        self.model = model
        self.num_simulations = num_simulations
        self.c_puct = c_puct
        self.device = device or torch.device('cpu')

    def play_game(
        self,
        temp_threshold: int = 30,
        temp_high: float = 1.0,
        temp_low: float = 0.1,
        resign_threshold: float = -0.95,
        resign_count: int = 5,
        max_moves: int = 200,
    ) -> GameResult:
        """Play a complete self-play game and collect training data.

        Args:
            temp_threshold: use temp_high for the first N moves, then temp_low.
            temp_high: exploration temperature for early moves.
            temp_low: exploitation temperature for later moves.
            resign_threshold: value below which a position is considered lost.
            resign_count: resign after this many consecutive below-threshold values.
            max_moves: declare draw if game exceeds this many moves.

        Returns:
            GameResult with training examples and outcome.
        """
        game = ChineseChessGame()
        mcts = MCTS(
            model=self.model,
            num_simulations=self.num_simulations,
            c_puct=self.c_puct,
            device=self.device,
        )

        positions: List[Tuple[np.ndarray, np.ndarray, int]] = []
        consecutive_low_value = 0
        move_count = 0

        while move_count < max_moves:
            # Check game over
            game_over, winner = game.is_game_over()
            if game_over:
                break

            # Temperature schedule
            temperature = temp_high if move_count < temp_threshold else temp_low

            # Run MCTS
            moves, probs = mcts.get_action_probs(game, temperature)
            if len(moves) == 0:
                break

            # Store position: board tensor from current player's perspective
            board_state = _game_to_board_state(game)
            current_player_int = 0 if game.current_player == Color.RED else 1
            board_tensor = board_to_tensor(board_state, current_player_int).numpy()

            # Build full policy vector (sparse -> dense)
            policy_target = np.zeros(NUM_ACTIONS, dtype=np.float32)
            for move, prob in zip(moves, probs):
                try:
                    idx = _move_to_action_index(move)
                    policy_target[idx] = prob
                except KeyError:
                    pass

            positions.append((board_tensor, policy_target, current_player_int))

            # Check resign condition using MCTS value estimate
            # The root value after search approximates the position evaluation
            # We can estimate it from the MCTS policy distribution shape
            # For a proper resign check, we'd need the value from the network
            board_t = torch.from_numpy(board_tensor).to(self.device)
            _, value_t = self.model.predict(board_t)
            value = value_t.item()

            if value < resign_threshold:
                consecutive_low_value += 1
                if consecutive_low_value >= resign_count:
                    # Current player resigns — opponent wins
                    winner = game.current_player.opposite()
                    game_over = True
                    break
            else:
                consecutive_low_value = 0

            # Sample move from probability distribution
            move_idx = np.random.choice(len(moves), p=probs)
            chosen_move = moves[move_idx]
            game.make_move(chosen_move)
            move_count += 1

        # Determine outcome
        if not game_over:
            # Max moves reached — draw
            winner = None
        elif winner is None:
            # is_game_over returned True with no winner — draw/stalemate
            pass

        # Assign value targets based on game outcome
        if winner is None:
            result_value = 0.0
            winner_int = None
        else:
            winner_int = int(winner)
            result_value = 1.0  # will be applied per-position below

        examples: List[TrainingExample] = []
        for board_tensor, policy_target, player_int in positions:
            if winner is None:
                v = 0.0
            elif player_int == winner_int:
                v = 1.0
            else:
                v = -1.0

            examples.append(TrainingExample(
                board_tensor=board_tensor,
                policy_target=policy_target,
                value_target=v,
            ))

        return GameResult(
            examples=examples,
            winner=winner_int,
            num_moves=move_count,
        )


def _play_single_game(
    model_state_dict: dict,
    num_filters: int,
    num_blocks: int,
    num_simulations: int,
    c_puct: float,
    temp_threshold: int,
    temp_high: float,
    temp_low: float,
    resign_threshold: float,
    resign_count: int,
    max_moves: int,
) -> GameResult:
    """Standalone function for multiprocessing — creates model in worker process."""
    from model import ChineseChessNet
    model = ChineseChessNet(num_filters=num_filters, num_blocks=num_blocks)
    model.load_state_dict(model_state_dict)
    model.eval()

    worker = SelfPlayWorker(
        model=model,
        num_simulations=num_simulations,
        c_puct=c_puct,
        device=torch.device('cpu'),
    )
    return worker.play_game(
        temp_threshold=temp_threshold,
        temp_high=temp_high,
        temp_low=temp_low,
        resign_threshold=resign_threshold,
        resign_count=resign_count,
        max_moves=max_moves,
    )


class SelfPlayManager:
    """Manages parallel self-play game generation."""

    def __init__(
        self,
        model: ChineseChessNet,
        num_simulations: int = 200,
        c_puct: float = 1.5,
        temp_threshold: int = 30,
        temp_high: float = 1.0,
        temp_low: float = 0.1,
        resign_threshold: float = -0.95,
        resign_count: int = 5,
        max_moves: int = 200,
    ) -> None:
        self.model = model
        self.num_simulations = num_simulations
        self.c_puct = c_puct
        self.temp_threshold = temp_threshold
        self.temp_high = temp_high
        self.temp_low = temp_low
        self.resign_threshold = resign_threshold
        self.resign_count = resign_count
        self.max_moves = max_moves

    def generate_games(
        self,
        num_games: int,
        num_workers: int = 4,
    ) -> Tuple[List[TrainingExample], Dict[str, int]]:
        """Generate multiple self-play games, optionally in parallel.

        Args:
            num_games: total number of games to play.
            num_workers: number of parallel workers (1 = sequential).

        Returns:
            (all_examples, stats) where stats has keys 'red_wins', 'black_wins', 'draws'.
        """
        all_examples: List[TrainingExample] = []
        stats = {'red_wins': 0, 'black_wins': 0, 'draws': 0, 'total_moves': 0}

        if num_workers <= 1:
            # Sequential mode — simpler, useful for debugging
            worker = SelfPlayWorker(
                model=self.model,
                num_simulations=self.num_simulations,
                c_puct=self.c_puct,
            )
            for i in range(num_games):
                result = worker.play_game(
                    temp_threshold=self.temp_threshold,
                    temp_high=self.temp_high,
                    temp_low=self.temp_low,
                    resign_threshold=self.resign_threshold,
                    resign_count=self.resign_count,
                    max_moves=self.max_moves,
                )
                all_examples.extend(result.examples)
                self._update_stats(stats, result)
                logger.info(
                    "Game %d/%d: %d moves, winner=%s",
                    i + 1, num_games, result.num_moves,
                    self._winner_str(result.winner),
                )
        else:
            # Parallel mode using ProcessPoolExecutor
            model_state = self.model.state_dict()
            # Detect model config from model instance
            num_filters = self.model.num_filters
            num_blocks = len(self.model.res_blocks)

            futures = []
            with ProcessPoolExecutor(max_workers=num_workers) as executor:
                for _ in range(num_games):
                    future = executor.submit(
                        _play_single_game,
                        model_state,
                        num_filters,
                        num_blocks,
                        self.num_simulations,
                        self.c_puct,
                        self.temp_threshold,
                        self.temp_high,
                        self.temp_low,
                        self.resign_threshold,
                        self.resign_count,
                        self.max_moves,
                    )
                    futures.append(future)

                for i, future in enumerate(as_completed(futures)):
                    result = future.result()
                    all_examples.extend(result.examples)
                    self._update_stats(stats, result)
                    logger.info(
                        "Game %d/%d: %d moves, winner=%s",
                        i + 1, num_games, result.num_moves,
                        self._winner_str(result.winner),
                    )

        return all_examples, stats

    @staticmethod
    def _update_stats(stats: Dict[str, int], result: GameResult) -> None:
        stats['total_moves'] += result.num_moves
        if result.winner is None:
            stats['draws'] += 1
        elif result.winner == 0:
            stats['red_wins'] += 1
        else:
            stats['black_wins'] += 1

    @staticmethod
    def _winner_str(winner: Optional[int]) -> str:
        if winner is None:
            return "draw"
        return "Red" if winner == 0 else "Black"
