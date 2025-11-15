# 中国象棋 (Chinese Chess) - Android

A professional-level Chinese Chess (Xiangqi) game for Android with advanced AI.

## Features

### Game Modes
- **Player vs AI** - Play against professional-level AI
- **Player vs Player** - Play with a friend on the same device
- **AI vs AI** - Watch AI play against itself

### Professional AI Engine

The AI uses state-of-the-art game engine techniques:

1. **Alpha-Beta Pruning** - Efficient tree search algorithm that dramatically reduces the number of positions evaluated
2. **Iterative Deepening** - Progressive depth search for better time management
3. **Transposition Tables** - Caches evaluated positions to avoid redundant calculations
4. **Move Ordering** - Prioritizes promising moves to improve pruning efficiency
5. **Quiescence Search** - Evaluates capture sequences to avoid horizon effect
6. **Advanced Evaluation Function** - Uses piece-square tables and positional analysis

### AI Difficulty Levels

- **Beginner** (Depth 2, 1s) - Good for learning
- **Intermediate** (Depth 4, 3s) - Casual play
- **Advanced** (Depth 5, 5s) - Challenging opponent
- **Professional** (Depth 6, 8s) - Strong amateur level
- **Master** (Depth 7, 15s) - Near professional level

## Technical Details

### Architecture

```
app/
├── model/          # Game logic and rules
│   ├── Board.kt    # Board representation and move validation
│   ├── Piece.kt    # Piece movement rules
│   ├── Position.kt # Board positions
│   └── Move.kt     # Move representation
├── ai/             # AI engine
│   ├── ChessAI.kt  # Main AI with alpha-beta search
│   ├── Evaluator.kt # Position evaluation
│   └── TranspositionTable.kt # Position caching
├── ui/             # User interface
│   └── BoardView.kt # Custom board rendering
└── GameController.kt # Game flow control
```

### Game Rules

The implementation follows standard Chinese Chess (Xiangqi) rules:

- **General (将/帅)** - Moves one step orthogonally within palace
- **Advisor (士/仕)** - Moves one step diagonally within palace
- **Elephant (象/相)** - Moves two steps diagonally, cannot cross river
- **Horse (马/馬)** - Moves in L-shape, can be blocked
- **Chariot (车/車)** - Moves any distance orthogonally
- **Cannon (炮/砲)** - Moves like chariot, captures by jumping
- **Soldier (兵/卒)** - Moves forward, sideways after crossing river

Special rules:
- **Flying General** - Generals cannot face each other directly
- **Check and Checkmate** - Standard chess-like rules
- **Stalemate** - No legal moves results in draw

## Building

### Requirements
- Android Studio Arctic Fox or later
- Gradle 8.2+
- Kotlin 1.9.20+
- Min SDK: 24 (Android 7.0)
- Target SDK: 34 (Android 14)

### Build Instructions

```bash
# Clone the repository
git clone https://github.com/yingwang/chinese_chess_mobile.git
cd chinese_chess_mobile

# Build the app
./gradlew assembleDebug

# Install on device
./gradlew installDebug

# Run tests (if added)
./gradlew test
```

## How to Play

1. **Starting a Game**
   - Tap "新游戏" (New Game) to select game mode
   - Choose AI difficulty if playing against AI
   - Red side moves first

2. **Making Moves**
   - Tap a piece to select it
   - Legal moves are shown with blue dots
   - Tap destination to move
   - Selected piece is highlighted in green

3. **Game Controls**
   - **悔棋 (Undo)** - Take back the last move(s)
   - **Menu → 关于 (About)** - View AI statistics

## AI Performance

The AI typically searches:
- **Professional level**: 50,000 - 500,000 positions per move
- **Master level**: 200,000 - 2,000,000 positions per move

Search depth increases dramatically with iterative deepening and transposition table hits.

## Credits

Inspired by the [yingwang/chinese-chess](https://github.com/yingwang/chinese-chess) project.

This implementation features a completely rewritten AI engine with modern techniques for professional-level play.

## License

GPL-3.0 License - see LICENSE file for details

## Future Enhancements

Potential improvements:
- Opening book for known strong openings
- Endgame tablebase
- Machine learning evaluation
- Online multiplayer
- Game analysis and hints
- Save/load games
- Position setup mode
- Replay games with annotations
