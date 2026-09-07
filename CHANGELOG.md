# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.3.2] - 2026-09-07

### Changed
- The AI no longer walks a piece back and forth: a move that revisits a position or reverses its own last move is re-searched with those moves excluded, and the alternative is played when it is within 60 cp (never giving up or walking into a mate); a third occurrence of a position is avoided regardless
- Mates farther than three moves away show as 胜势/败势 instead of the count, so the evaluation does not give the tactic away

## [2.3.1] - 2026-09-07

### Added
- Engine evaluation replaces the material count: each card shows the position from its own side in pawns (or moves to mate), and a bar above the board shows red's share; the score comes from Pikafish's search (a quick depth-10 look after moves it did not search itself). Without the engine the material count stands in.

## [2.3.0] - 2026-09-07

### Changed
- New look: a slate ground with celadon accents, one card per side in the header (role, score, captured pieces, whose move it is), a status pill, and an icon bar at the bottom; every colour lives in `values/colors.xml`
- The board is now drawn in code: framed honey-coloured wood with a light grain, double border, position marks and file numbers; the walnut photograph (and the AI-generated logo baked into its corner) is gone
- The AI never answers instantly any more; each of its moves takes at least 0.9 s so the reply is visible
- Hint marks the move on the board and shows a snackbar instead of a dialog covering the board
- Move list fills the space between the board and the buttons instead of a fixed 100dp
- AI difficulty can be changed from 更多 without going through a new game
- All user-facing text moved from Kotlin into `strings.xml`
- Release signing reads `keystore.properties` or environment variables; the passwords are no longer in `build.gradle.kts`

### Fixed
- Chinese notation: diagonal pieces (馬, 相/象, 仕/士) name the destination file after 进/退, and two pieces of a kind on one file are written 前/后
- Avoiding a threefold repetition re-runs the engine with the repeating moves excluded instead of playing the first legal move it finds
- Endgame studies are no longer auto-saved; resuming one replayed its moves onto the standard opening
- Resuming a saved game keeps its difficulty and its clock instead of resetting both
- Mute is remembered across launches
- One audio manager instead of two loading the same samples; the dead options menu is removed

## [1.1.0] - 2025-11-16

### Added
- Opening book with classic Chinese chess openings (center cannon, horse openings, etc.)
- Dashed border indicators for all legal move destinations
- Enhanced visual feedback for selected pieces with outer glow effect
- Time display now shows hours:minutes:seconds format for games longer than 1 hour
- Vector-based app icon for all screen densities (reduces APK size)

### Fixed
- **Critical**: Fixed MainActivity compilation errors (missing closing brace, type mismatches)
- **Critical**: Fixed opening book coordinate system (was using incorrect positions for first moves)
- Move destination indicators now show even when target square is occupied
- Icon compilation errors by migrating from corrupted PNG to vector drawables
- Opening moves now follow proper Chinese chess strategy

### Changed
- **App Icon**: Redesigned with classical Chinese aesthetic
  - Changed from bright red/gold to elegant slate gray/bronze/ivory palette
  - Changed character from "将" to "象" for better recognition
  - Inspired by traditional ink wash paintings and aged chess pieces
  - More refined and timeless appearance
- **UI Improvements**: Enhanced move highlighting system
  - Blue dashed circles for normal moves
  - Red dashed circles for capture moves
  - Brighter green border with glow effect for selected pieces
  - Small dots at center of destination squares for better visibility
- Opening book now uses proper board coordinates (Row 0-2 is BLACK, Row 7-9 is RED)

### Technical
- Removed corrupted PNG icon files (mipmap-*/ic_launcher.png)
- Added vector-based icons for API 24+ and API 26+ (adaptive icons)
- Improved code structure by properly closing setupGameController() function

## [1.0.0] - 2025-11-15

### Initial Release
- Professional-level Chinese Chess game for Android
- Advanced AI with Alpha-Beta pruning, iterative deepening, and transposition tables
- 5 difficulty levels (Beginner to Master)
- Three game modes: Player vs AI, Player vs Player, AI vs AI
- Custom board rendering with traditional Chinese chess aesthetics
- Sound effects for moves, captures, and check
- Move history display with Chinese notation
- Undo functionality
- Game statistics tracking (time, move count, material advantage)

[2.3.2]: https://github.com/yingwang/chinese_chess_mobile/compare/v2.3.1...v2.3.2
[2.3.1]: https://github.com/yingwang/chinese_chess_mobile/compare/v2.3.0...v2.3.1
[2.3.0]: https://github.com/yingwang/chinese_chess_mobile/compare/v2.2.1...v2.3.0
[1.1.0]: https://github.com/yingwang/chinese_chess_mobile/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/yingwang/chinese_chess_mobile/releases/tag/v1.0.0
