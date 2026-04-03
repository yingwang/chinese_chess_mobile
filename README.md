# 中国象棋 Chinese Chess

<p align="center">
  <img src="docs/screenshot.png" alt="Chinese Chess" width="300"/>
</p>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.yingwang.chinesechess">
    <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" width="200"/>
  </a>
</p>

<p align="center">
  <strong><a href="https://yingwang.github.io/chinese_chess_mobile/">Website</a></strong> ·
  <strong><a href="https://play.google.com/store/apps/details?id=com.yingwang.chinesechess">Google Play</a></strong> ·
  <strong><a href="PRIVACY_POLICY.md">Privacy Policy</a></strong>
</p>

A feature-rich Chinese Chess (Xiangqi / 象棋) game for Android with a strong AI engine, classical UI, and endgame puzzles.

## Features

### Game Modes
- **Player vs AI** — 5 difficulty levels from beginner to master + neural network AI
- **Player vs Player** — Same-device local multiplayer
- **AI vs AI** — Watch the engine play itself
- **Endgame Puzzles** — 8 classic endgame positions (重炮杀, 铁门栓, 天地炮, 马后炮, etc.)

### AI Engine

The AI uses modern game engine techniques:

| Technique | Description |
|-----------|-------------|
| Alpha-Beta Pruning | Efficient minimax search with pruning |
| Null Move Pruning | Skip-turn heuristic for fast cutoffs |
| Late Move Reductions | Search late moves at reduced depth |
| Iterative Deepening | Progressive depth with aspiration windows |
| Zobrist Hashing | Fast position identification |
| Transposition Tables | Depth-preferred replacement strategy |
| Killer Move Heuristic | Remember refutation moves per depth |
| History Heuristic | Track historically successful moves |
| Quiescence Search | Resolve captures to avoid horizon effect |
| MVV-LVA Ordering | Most Valuable Victim – Least Valuable Attacker |
| Opening Book | 30+ classic opening lines |

#### Difficulty Levels

| Level | Search Depth | Time Limit | Quiescence |
|-------|-------------|------------|------------|
| Beginner | 2 | 1s | Off |
| Intermediate | 3 | 2s | Depth 2 |
| Advanced | 4 | 3s | Depth 3 |
| Professional | 5 | 5s | Depth 4 |
| Master | 7 | 10s | Depth 5 |

### Neural Network AI

An AlphaZero-style neural network engine is available as an additional difficulty level:

| Component | Details |
|-----------|---------|
| Architecture | ResNet (128 filters, 6 residual blocks) with dual policy + value heads |
| Input | 15 feature planes (10x9): 7 per side + current player |
| Policy Head | 2086 possible moves |
| Value Head | Position evaluation in [-1, 1] |
| Search | MCTS with 200 simulations |
| Training Data | 40,000+ master game records (supervised learning) |
| Inference | TensorFlow Lite (float16) on device |

Training notebooks for Google Colab and Kaggle are in the `ml/` directory.

#### Evaluation Function

- Piece-square tables for all 7 piece types
- King safety (advisor/elephant guard assessment)
- Crossed-river bonuses (soldiers, horses)
- Chariot activity (open file bonus, development penalty)
- Horse coordination (connected horses bonus)
- Cannon endgame adjustment (fewer jump targets)
- Threat detection (卧槽马, deep chariot penetration)

### User Interface

- Classical wood-grain board with gradient textures
- 3D convex pieces with radial gradient, specular highlight, and drop shadow
- Inner decorative ring on each piece (traditional xiangqi style)
- Warm gold selection glow with refined move indicators
- Smooth 200ms piece movement animation
- Haptic feedback on selection and placement
- Pulsing dots AI thinking indicator

### Additional Features

- **Save/Load** — Auto-saves on exit, offers to resume on launch
- **Hint System** — AI suggests the best move for the current position
- **Game Replay** — Step through move history forward/backward
- **Undo with Confirmation** — Prevents accidental undo
- **Captured Pieces Display** — Shows eaten pieces above/below the board
- **Turn Indicator** — Color dot shows whose turn it is
- **Move History** — Chinese notation (e.g., 車五進九)
- **Foldable Support** — Handles screen configuration changes gracefully

## Screenshots

<p align="center">
  <em>Screenshots coming soon</em>
</p>

## Building

### Requirements
- Android Studio Hedgehog or later
- JDK 17+
- Gradle 8.2+
- Kotlin 1.9.20+
- Min SDK 24 (Android 7.0) · Target SDK 35 (Android 15)

### Build & Install

```bash
git clone https://github.com/yingwang/chinese_chess_mobile.git
cd chinese_chess_mobile
./gradlew assembleDebug
./gradlew installDebug
```

## Architecture

```
app/src/main/java/com/yingwang/chinesechess/
├── model/                    # Game logic
│   ├── Board.kt              # Board state, move validation
│   ├── Piece.kt              # Piece movement rules (7 types)
│   ├── Move.kt               # Move representation
│   ├── Position.kt           # Board coordinates
│   ├── PieceType.kt          # Piece types with Chinese names
│   └── PieceColor.kt         # RED / BLACK
├── ai/                       # AI engine
│   ├── ChessAI.kt            # Search (alpha-beta, NMP, LMR)
│   ├── Evaluator.kt          # Position evaluation
│   ├── TranspositionTable.kt # TT with depth-preferred replacement
│   ├── ZobristHash.kt        # Zobrist position hashing
│   ├── OpeningBook.kt        # Opening book (30+ lines)
│   └── ml/                   # Neural network AI
│       ├── MLChessAI.kt      # MCTS + neural network search
│       ├── TFLiteModel.kt    # TFLite inference wrapper
│       ├── MoveEncoding.kt   # Board/move encoding (2086 actions)
│       └── MCTSNode.kt       # Monte Carlo tree search nodes
├── ui/
│   └── BoardView.kt          # Custom Canvas board rendering
├── audio/
│   └── GameAudioManager.kt   # Sound effects & background music
├── GameController.kt         # Game flow, save/load, replay
├── EndgamePositions.kt       # 8 classic endgame puzzles
├── SoundManager.kt           # Fallback tone generator
└── MainActivity.kt           # UI wiring
```

## Game Rules

| Piece | Red | Black | Movement |
|-------|-----|-------|----------|
| General | 帅 | 将 | 1 step orthogonal, within palace |
| Advisor | 仕 | 士 | 1 step diagonal, within palace |
| Elephant | 相 | 象 | 2 steps diagonal, blocked by eye, no river crossing |
| Horse | 馬 | 马 | L-shape, blocked by adjacent piece |
| Chariot | 車 | 车 | Any distance orthogonal |
| Cannon | 炮 | 炮 | Moves like chariot, captures by jumping over 1 piece |
| Soldier | 兵 | 卒 | Forward only; forward + sideways after crossing river |

Special: **Flying General** rule — generals cannot face each other on an open file.

## Privacy

This app does not collect any personal data. No internet connection required. No tracking, analytics, or ads. All game data is stored locally. See [Privacy Policy](PRIVACY_POLICY.md).

## License

[GPL-3.0](LICENSE)

## Credits

Built with Kotlin and Android Canvas. Inspired by [yingwang/chinese-chess](https://github.com/yingwang/chinese-chess) (web version).
