# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[1.1.0]: https://github.com/yingwang/chinese_chess_mobile/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/yingwang/chinese_chess_mobile/releases/tag/v1.0.0
