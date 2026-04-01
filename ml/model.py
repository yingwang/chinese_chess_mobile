"""
Neural network model for Chinese Chess (象棋).

Lightweight ResNet architecture suitable for mobile inference via ONNX export.
"""

import torch
import torch.nn as nn
import torch.nn.functional as F
from typing import Tuple

from encoding import NUM_INPUT_CHANNELS, NUM_ACTIONS, ROWS, COLS


class ResidualBlock(nn.Module):
    """Single residual block: conv3x3 -> BN -> ReLU -> conv3x3 -> BN -> skip -> ReLU."""

    def __init__(self, channels: int):
        super().__init__()
        self.conv1 = nn.Conv2d(channels, channels, 3, padding=1, bias=False)
        self.bn1 = nn.BatchNorm2d(channels)
        self.conv2 = nn.Conv2d(channels, channels, 3, padding=1, bias=False)
        self.bn2 = nn.BatchNorm2d(channels)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        residual = x
        out = F.relu(self.bn1(self.conv1(x)))
        out = self.bn2(self.conv2(out))
        out = F.relu(out + residual)
        return out


class ChineseChessNet(nn.Module):
    """
    Dual-headed ResNet for Chinese Chess.

    Architecture:
        - Input: (batch, 15, 10, 9) feature planes
        - Initial conv: 15 -> 128, 3x3
        - 6 residual blocks, 128 filters each
        - Policy head: conv 1x1 -> BN -> ReLU -> flatten -> FC -> 2086
        - Value head: conv 1x1 -> BN -> ReLU -> flatten -> FC(128) -> ReLU -> FC(1) -> tanh
    """

    def __init__(self, num_filters: int = 128, num_blocks: int = 6):
        super().__init__()

        self.num_filters = num_filters
        self.board_size = ROWS * COLS  # 90

        # Initial convolution
        self.conv_init = nn.Conv2d(NUM_INPUT_CHANNELS, num_filters, 3, padding=1, bias=False)
        self.bn_init = nn.BatchNorm2d(num_filters)

        # Residual tower
        self.res_blocks = nn.Sequential(
            *[ResidualBlock(num_filters) for _ in range(num_blocks)]
        )

        # Policy head
        self.policy_conv = nn.Conv2d(num_filters, 2, 1, bias=False)
        self.policy_bn = nn.BatchNorm2d(2)
        self.policy_fc = nn.Linear(2 * self.board_size, NUM_ACTIONS)

        # Value head
        self.value_conv = nn.Conv2d(num_filters, 1, 1, bias=False)
        self.value_bn = nn.BatchNorm2d(1)
        self.value_fc1 = nn.Linear(self.board_size, 128)
        self.value_fc2 = nn.Linear(128, 1)

        self._init_weights()

    def _init_weights(self):
        """Kaiming initialization for conv layers, zero-init for BN and biases."""
        for module in self.modules():
            if isinstance(module, nn.Conv2d):
                nn.init.kaiming_normal_(module.weight, mode='fan_out', nonlinearity='relu')
            elif isinstance(module, nn.BatchNorm2d):
                nn.init.constant_(module.weight, 1)
                nn.init.constant_(module.bias, 0)
            elif isinstance(module, nn.Linear):
                nn.init.kaiming_normal_(module.weight, mode='fan_in', nonlinearity='relu')
                if module.bias is not None:
                    nn.init.constant_(module.bias, 0)

    def forward(self, x: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        """
        Forward pass.

        Args:
            x: input tensor of shape (batch, 15, 10, 9)

        Returns:
            (policy_logits, value) where:
                policy_logits: (batch, 2086) raw logits over the action space
                value: (batch, 1) board evaluation in [-1, 1]
        """
        # Shared trunk
        out = F.relu(self.bn_init(self.conv_init(x)))
        out = self.res_blocks(out)

        # Policy head
        p = F.relu(self.policy_bn(self.policy_conv(out)))
        p = p.view(p.size(0), -1)
        p = self.policy_fc(p)

        # Value head
        v = F.relu(self.value_bn(self.value_conv(out)))
        v = v.view(v.size(0), -1)
        v = F.relu(self.value_fc1(v))
        v = torch.tanh(self.value_fc2(v))

        return p, v

    def predict(self, board_tensor: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        """
        Run inference on a single board position or batch.

        Args:
            board_tensor: tensor of shape (15, 10, 9) or (batch, 15, 10, 9)

        Returns:
            (policy_logits, value) — policy as raw logits, value as scalar(s).
        """
        was_training = self.training
        self.eval()

        if board_tensor.dim() == 3:
            board_tensor = board_tensor.unsqueeze(0)

        with torch.no_grad():
            policy_logits, value = self.forward(board_tensor)

        if was_training:
            self.train()

        return policy_logits, value

    def export_to_onnx(self, path: str):
        """
        Export model to ONNX format for mobile inference.

        Args:
            path: file path for the .onnx output (e.g., "model.onnx")
        """
        self.eval()
        dummy_input = torch.zeros(1, NUM_INPUT_CHANNELS, ROWS, COLS)
        torch.onnx.export(
            self,
            dummy_input,
            path,
            input_names=["board"],
            output_names=["policy", "value"],
            dynamic_axes={
                "board": {0: "batch"},
                "policy": {0: "batch"},
                "value": {0: "batch"},
            },
            opset_version=13,
        )


def create_model(num_filters: int = 128, num_blocks: int = 6) -> ChineseChessNet:
    """Create a new Chinese Chess neural network."""
    return ChineseChessNet(num_filters=num_filters, num_blocks=num_blocks)
