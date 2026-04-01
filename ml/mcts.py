"""
Monte Carlo Tree Search for Chinese Chess self-play training.

Uses the neural network (model.py) for policy priors and value estimation,
and the game logic (game.py) for move generation and state transitions.
"""

from __future__ import annotations

import math
from typing import Optional, List, Tuple, Dict

import numpy as np
import torch
import torch.nn.functional as F

from game import ChineseChessGame, Move, Color, PieceType, Piece
from encoding import (
    board_to_tensor, move_to_index, index_to_move,
    NUM_ACTIONS, ROWS, COLS,
)


def _game_to_board_state(game: ChineseChessGame) -> Dict[int, Tuple[int, int]]:
    """Convert game board dict to encoding.board_to_tensor format.

    Returns:
        Dict mapping flat position (row*9+col) to (color_int, piece_type_int).
    """
    board_state = {}
    for (row, col), piece in game.board.items():
        flat = row * COLS + col
        board_state[flat] = (int(piece.color), int(piece.type))
    return board_state


def _move_to_action_index(move: Move) -> int:
    """Convert a game Move to a policy action index."""
    fr, fc = move.from_pos
    tr, tc = move.to_pos
    return move_to_index(fr * COLS + fc, tr * COLS + tc)


class MCTSNode:
    """A single node in the MCTS tree."""

    __slots__ = (
        'parent', 'children', 'move',
        'visit_count', 'total_value', 'prior_probability',
    )

    def __init__(
        self,
        parent: Optional[MCTSNode] = None,
        move: Optional[Move] = None,
        prior: float = 0.0,
    ) -> None:
        self.parent = parent
        self.children: Dict[int, MCTSNode] = {}  # action_index -> child
        self.move = move
        self.visit_count: int = 0
        self.total_value: float = 0.0
        self.prior_probability: float = prior

    @property
    def q_value(self) -> float:
        """Average value (W / N). Returns 0 if unvisited."""
        if self.visit_count == 0:
            return 0.0
        return self.total_value / self.visit_count

    def ucb_score(self, c_puct: float) -> float:
        """Upper confidence bound score for selection."""
        parent_n = self.parent.visit_count if self.parent else 1
        exploration = c_puct * self.prior_probability * math.sqrt(parent_n) / (1 + self.visit_count)
        return self.q_value + exploration

    @property
    def is_leaf(self) -> bool:
        return len(self.children) == 0


def add_dirichlet_noise(
    node: MCTSNode,
    alpha: float = 0.3,
    epsilon: float = 0.25,
) -> None:
    """Mix Dirichlet noise into the prior probabilities at the root for exploration."""
    if not node.children:
        return
    actions = list(node.children.keys())
    noise = np.random.dirichlet([alpha] * len(actions))
    for i, action_idx in enumerate(actions):
        child = node.children[action_idx]
        child.prior_probability = (
            (1 - epsilon) * child.prior_probability + epsilon * noise[i]
        )


class MCTS:
    """Monte Carlo Tree Search using a neural network for evaluation and priors."""

    def __init__(
        self,
        model: torch.nn.Module,
        num_simulations: int = 800,
        c_puct: float = 1.5,
        temperature: float = 1.0,
        device: Optional[torch.device] = None,
    ) -> None:
        self.model = model
        self.num_simulations = num_simulations
        self.c_puct = c_puct
        self.temperature = temperature
        self.device = device or next(model.parameters()).device

    def search(self, game: ChineseChessGame) -> Tuple[List[Move], np.ndarray]:
        """Run MCTS from the current position and return move probabilities.

        Args:
            game: current game state (not modified).

        Returns:
            (moves, probs): list of legal moves and their visit-count probabilities.
        """
        return self.get_action_probs(game, self.temperature)

    def get_action_probs(
        self,
        game: ChineseChessGame,
        temperature: float = 1.0,
    ) -> Tuple[List[Move], np.ndarray]:
        """Run MCTS simulations and return move probabilities.

        Args:
            game: current game state (not modified).
            temperature: controls exploration vs exploitation.
                1.0 = proportional to visit counts.
                ~0 = argmax (pick best move).

        Returns:
            (moves, probs): legal moves and corresponding probabilities.
        """
        root = MCTSNode()

        # Expand root
        legal_moves = game.get_legal_moves()
        if not legal_moves:
            return [], np.array([])

        # Get neural network evaluation for root
        policy, value = self._evaluate(game)
        self._expand_node(root, legal_moves, policy)

        # Add Dirichlet noise at root for exploration
        add_dirichlet_noise(root, alpha=0.3, epsilon=0.25)

        # Root gets a virtual visit with the network value
        root.visit_count = 1
        root.total_value = value

        # Run simulations
        for _ in range(self.num_simulations):
            self._simulate(root, game)

        # Collect visit counts for legal moves
        moves = []
        visit_counts = []
        for action_idx, child in root.children.items():
            moves.append(child.move)
            visit_counts.append(child.visit_count)

        visit_counts = np.array(visit_counts, dtype=np.float64)

        if temperature < 1e-4:
            # Near-zero temperature: pick the most visited move
            probs = np.zeros_like(visit_counts)
            probs[np.argmax(visit_counts)] = 1.0
        else:
            # Proportional to visit_count^(1/temperature)
            counts_t = visit_counts ** (1.0 / temperature)
            total = counts_t.sum()
            if total > 0:
                probs = counts_t / total
            else:
                probs = np.ones_like(counts_t) / len(counts_t)

        return moves, probs

    def _simulate(self, root: MCTSNode, game: ChineseChessGame) -> None:
        """Run a single MCTS simulation: select -> expand -> evaluate -> backpropagate."""
        node = root
        scratch_game = game.clone()
        search_path: List[MCTSNode] = [node]

        # SELECT: traverse tree using UCB until we reach a leaf
        while not node.is_leaf:
            node = self._select(node)
            search_path.append(node)
            scratch_game.make_move(node.move)

        # Check terminal state
        game_over, winner = scratch_game.is_game_over()
        if game_over:
            # Terminal value from perspective of the player who just moved
            # (the parent of this leaf). The player who just moved is the
            # opposite of scratch_game.current_player.
            if winner is None:
                value = 0.0
            else:
                # winner is the color that won
                just_moved = scratch_game.current_player.opposite()
                value = 1.0 if winner == just_moved else -1.0
        else:
            # EXPAND and EVALUATE
            legal_moves = scratch_game.get_legal_moves()
            if not legal_moves:
                # No legal moves but not detected as game over by is_game_over
                # (shouldn't happen, but be safe)
                value = 0.0
            else:
                policy, value = self._evaluate(scratch_game)
                self._expand_node(node, legal_moves, policy)
                # value is from the perspective of scratch_game.current_player
                # We need to negate it because backprop expects value from the
                # perspective of the player who just moved (node's parent's player)
                value = -value

        # BACKPROPAGATE
        self._backpropagate(search_path, value)

    def _select(self, node: MCTSNode) -> MCTSNode:
        """Select the child with highest UCB score."""
        best_score = -float('inf')
        best_child = None
        c_puct = self.c_puct

        for child in node.children.values():
            score = child.ucb_score(c_puct)
            if score > best_score:
                best_score = score
                best_child = child

        return best_child

    def _expand_node(
        self,
        node: MCTSNode,
        legal_moves: List[Move],
        policy: np.ndarray,
    ) -> None:
        """Create child nodes for all legal moves with policy priors.

        Args:
            node: leaf node to expand.
            legal_moves: legal moves from this position.
            policy: softmax policy vector of size NUM_ACTIONS.
        """
        # Gather priors for legal moves and renormalize
        action_indices = []
        priors = []
        for move in legal_moves:
            try:
                idx = _move_to_action_index(move)
            except KeyError:
                # Move not in action space (shouldn't happen with correct encoding)
                continue
            action_indices.append(idx)
            priors.append(policy[idx])

        # Renormalize priors over legal moves
        prior_sum = sum(priors)
        if prior_sum > 1e-8:
            priors = [p / prior_sum for p in priors]
        else:
            # Uniform fallback
            n = len(priors)
            priors = [1.0 / n] * n if n > 0 else []

        for i, (action_idx, move) in enumerate(zip(action_indices, legal_moves)):
            child = MCTSNode(parent=node, move=move, prior=priors[i])
            node.children[action_idx] = child

    def _backpropagate(self, search_path: List[MCTSNode], value: float) -> None:
        """Update visit counts and values along the search path.

        The value alternates sign at each level because players alternate turns.
        value is from the perspective of the player who made the move leading to
        the last node in search_path.
        """
        # The last node in search_path is the leaf. The value is from the
        # perspective of the player who just moved to reach that leaf.
        # As we go back up, we flip signs because each level represents
        # the other player's turn.
        for node in reversed(search_path):
            node.visit_count += 1
            node.total_value += value
            value = -value

    def _evaluate(self, game: ChineseChessGame) -> Tuple[np.ndarray, float]:
        """Use the neural network to evaluate a position.

        Args:
            game: game state to evaluate.

        Returns:
            (policy, value) where policy is a numpy array of shape (NUM_ACTIONS,)
            with softmax probabilities, and value is a float in [-1, 1] from
            the perspective of game.current_player.
        """
        board_state = _game_to_board_state(game)
        current_player_int = 0 if game.current_player == Color.RED else 1
        tensor = board_to_tensor(board_state, current_player_int)
        tensor = tensor.to(self.device)

        policy_logits, value_tensor = self.model.predict(tensor)

        # policy_logits: (1, NUM_ACTIONS), value_tensor: (1, 1)
        policy_logits = policy_logits.squeeze(0)  # (NUM_ACTIONS,)

        # Mask illegal moves: set logits of illegal actions to -inf
        legal_moves = game.get_legal_moves()
        legal_indices = set()
        for move in legal_moves:
            try:
                legal_indices.add(_move_to_action_index(move))
            except KeyError:
                pass

        mask = torch.full((NUM_ACTIONS,), float('-inf'), device=self.device)
        for idx in legal_indices:
            mask[idx] = 0.0
        policy_logits = policy_logits + mask

        # Softmax to get probabilities
        policy_probs = F.softmax(policy_logits, dim=0).cpu().numpy()

        value = value_tensor.item()
        return policy_probs, value
