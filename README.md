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
  <strong><a href="https://yingwang.github.io/chinese_chess/">Web Version / 网页版</a></strong> ·
  <strong><a href="PRIVACY_POLICY.md">Privacy Policy</a></strong>
</p>

A feature-rich Chinese Chess (Xiangqi) game for Android with a strong AI engine, neural network AI, classical UI, and endgame puzzles.

一款功能丰富的 Android 中国象棋游戏，拥有强力 AI 引擎、神经网络 AI、经典界面和残局练习。

## Features / 功能

### Game Modes / 游戏模式
- **Player vs AI / 人机对弈** — 6 difficulty levels powered by Pikafish engine (3000+ Elo) / 6 个难度级别，Pikafish 引擎驱动（3000+ Elo）
- **Player vs Player / 双人对弈** — Same-device local multiplayer / 同设备本地双人
- **AI vs AI / AI 对弈** — Watch the engine play itself / 观看 AI 自我对弈
- **Endgame Puzzles / 残局练习** — 8 classic positions / 8 个经典残局 (重炮杀, 铁门栓, 天地炮, 马后炮, etc.)

### AI Engine / AI 引擎 — Pikafish

Powered by [Pikafish](https://github.com/official-pikafish/Pikafish), one of the strongest xiangqi engines in the world (3000+ Elo). Pikafish is a Stockfish fork rewritten for xiangqi rules, using NNUE (efficiently updatable neural network) evaluation.

由 [Pikafish](https://github.com/official-pikafish/Pikafish) 驱动，目前世界最强的象棋引擎之一（3000+ Elo）。Pikafish 基于 Stockfish 改写，使用 NNUE 神经网络评估。

The engine runs as a native ARM64 binary on device, communicating via UCI protocol.

引擎以原生 ARM64 二进制文件在设备上运行，通过 UCI 协议通信。

#### Difficulty Levels / 难度级别

| Level / 级别 | Pikafish Depth / 搜索深度 | Estimated Elo |
|-------|-------------|------------|
| Beginner / 初级 | 3 | ~1600 |
| Intermediate / 中级 | 6 | ~2000 |
| Advanced / 高级 | 10 | ~2400 |
| Professional / 专业 | 15 | ~2600 |
| Master / 大师 | 20 | ~2800 |
| Grandmaster / 棋圣 | Unlimited / 无限 | 3000+ |

### User Interface / 用户界面

- Classical wood-grain board / 经典木纹棋盘
- 3D convex pieces with radial gradient and drop shadow / 3D 凸面棋子，径向渐变和投影
- Inner decorative ring (traditional xiangqi style) / 内圈装饰环（传统象棋风格）
- Warm gold selection glow with move indicators / 暖金色选中光效和走法提示
- Smooth 200ms piece movement animation / 流畅的 200ms 走子动画
- Haptic feedback on selection and placement / 选子和落子时触觉反馈
- Pulsing dots AI thinking indicator / AI 思考脉冲点指示器

### Rules Enforcement / 规则检测

- **Perpetual check detection / 长将检测** — Perpetual check (same position 3 times with check) results in loss for the checking side / 同一局面出现3次且处于将军状态，长将方判负
- **Threefold repetition draw / 三次重复和棋** — Same position 3 times without check is a draw / 同一局面出现3次（无将军）判和棋
- **AI avoidance / AI 规避** — AI proactively avoids moves that would cause repetition penalties / AI 主动规避会导致判负的重复走法

### Additional Features / 其他功能

- **Save/Load / 存档读档** — Auto-saves on exit, resume on launch / 退出自动保存，启动时恢复
- **Hint System / 提示系统** — AI suggests the best move / AI 推荐最佳走法
- **Game Replay / 棋局回放** — Step through move history / 逐步浏览走棋历史
- **Undo / 悔棋** — Undo with confirmation / 确认后悔棋
- **Captured Pieces / 吃子显示** — Shows captured pieces above/below board / 棋盘上下显示被吃棋子
- **Turn Indicator / 回合指示** — Color dot shows whose turn / 颜色圆点显示当前回合
- **Move History / 走棋记录** — Chinese notation (e.g., 車五進九) / 中文记谱法
- **Foldable Support / 折叠屏支持** — Handles screen changes gracefully / 优雅处理屏幕变化

## Screenshots / 截图

<p align="center">
  <em>Screenshots coming soon / 截图即将添加</em>
</p>

## Building / 构建

### Requirements / 环境要求
- Android Studio Hedgehog or later / Android Studio Hedgehog 或更高版本
- JDK 17+
- Gradle 8.9+
- Kotlin 2.0+
- Min SDK 24 (Android 7.0) · Target SDK 35 (Android 15)

### Build & Install / 构建安装

```bash
git clone https://github.com/yingwang/chinese_chess_mobile.git
cd chinese_chess_mobile
./gradlew assembleDebug
./gradlew installDebug
```

## Architecture / 项目结构

```
app/src/main/java/com/yingwang/chinesechess/
├── model/                    # Game logic / 游戏逻辑
│   ├── Board.kt              # Board state, move validation / 棋盘状态、走法验证
│   ├── Piece.kt              # Piece movement rules / 棋子移动规则
│   ├── Move.kt               # Move representation / 走法表示
│   ├── Position.kt           # Board coordinates / 棋盘坐标
│   ├── PieceType.kt          # Piece types / 棋子类型
│   └── PieceColor.kt         # RED / BLACK / 红 / 黑
├── ai/                       # AI engine / AI 引擎
│   ├── PikafishEngine.kt     # Pikafish UCI wrapper / Pikafish UCI 通信
│   ├── ChessAI.kt            # Fallback Alpha-Beta / 备用 Alpha-Beta 搜索
│   ├── Evaluator.kt          # Position evaluation / 局面评估
│   ├── TranspositionTable.kt # Transposition table / 置换表
│   ├── ZobristHash.kt        # Zobrist hashing / Zobrist 哈希
│   └── OpeningBook.kt        # Opening book / 开局库
├── ui/
│   └── BoardView.kt          # Board rendering / 棋盘渲染
├── audio/
│   └── GameAudioManager.kt   # Sound effects / 音效
├── GameController.kt         # Game flow / 游戏流程控制
├── EndgamePositions.kt       # Endgame puzzles / 残局练习
├── SoundManager.kt           # Fallback tone generator / 备用音调生成
└── MainActivity.kt           # UI wiring / UI 绑定
```

## Game Rules / 游戏规则

| Piece / 棋子 | Red / 红 | Black / 黑 | Movement / 走法 |
|-------|-----|-------|----------|
| General / 将帅 | 帅 | 将 | 1 step orthogonal, within palace / 九宫内直走一步 |
| Advisor / 仕士 | 仕 | 士 | 1 step diagonal, within palace / 九宫内斜走一步 |
| Elephant / 相象 | 相 | 象 | 2 steps diagonal, blocked by eye, no river / 田字斜走，塞象眼，不过河 |
| Horse / 马 | 馬 | 马 | L-shape, blocked by adjacent piece / 日字走，蹩马腿 |
| Chariot / 车 | 車 | 车 | Any distance orthogonal / 直线任意距离 |
| Cannon / 炮 | 炮 | 炮 | Moves like chariot, captures by jumping / 直走如车，隔子吃子 |
| Soldier / 兵卒 | 兵 | 卒 | Forward; forward + sideways after river / 前进；过河后可横走 |

Special: **Flying General** rule — generals cannot face each other on an open file.

特殊规则：**将帅照面** — 将帅不能在同一列无遮挡对面。

## Privacy / 隐私

This app does not collect any personal data. No internet connection required. No tracking, analytics, or ads. All game data is stored locally. See [Privacy Policy](PRIVACY_POLICY.md).

本应用不收集任何个人数据。无需网络连接，无追踪、分析或广告。所有数据存储在本地。详见 [隐私政策](PRIVACY_POLICY.md)。

## License / 许可

[GPL-3.0](LICENSE)

## About / 关于

Built with Kotlin and Android Canvas, powered by [Pikafish](https://github.com/official-pikafish/Pikafish) engine. Also available as a [web version](https://github.com/yingwang/chinese_chess).

使用 Kotlin 和 Android Canvas 构建，搭载 [Pikafish](https://github.com/official-pikafish/Pikafish) 引擎。另有[网页版](https://github.com/yingwang/chinese_chess)。
