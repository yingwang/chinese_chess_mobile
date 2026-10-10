# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.5.0] - 2026-10-10

### Added
- Play a friend online (和朋友联机), in More and in the New game list: create a room as red, black or a random side and send its six-character code with the share sheet, or join a room by typing its code. Rooms are shared with the web version, so a phone can play a browser and the other way round. The board turns round when you play black; you move only your own pieces; there is no engine, no hint and no take-back while the game is on. The hint and undo buttons become Resign and Leave; leaving a game still being played resigns it
- Each side's clock in an online game comes from the server's time of every move, so both players see the same times
- The friend's card says whether they are online, and the status line says so too when they are not; a lost connection shows as reconnecting
- The app lets go of the connection while it is out of sight and picks the game up again when it is back, also after its process was ended: it goes straight back to the room
- After an online game it can be reviewed with the engine like any other

### Changed
- The app now asks for network access (INTERNET, ACCESS_NETWORK_STATE), used only by online play; everything else still works offline
- The privacy policy (PRIVACY_POLICY.md and .html, the short version, the site, which now carries it as privacy.html, and the README) and the store listings no longer call the app completely offline: they say what an online game stores in Firebase, who can read it, when a room is deleted and how to ask for one to be deleted

## [2.4.8] - 2026-10-10

### Changed
- Each side has its own clock in its card, beside the round, instead of one clock for the whole game in the middle. Only the side to move is charged, the AI's thinking included; the running clock is in the accent and bold, the other quieter. No time limits
- The exported record gives each side's time and the total

### Fixed
- The game clock kept running while the app was in the background and jumped when it came back, yet after the app was closed and the game resumed it carried on from the saved time without the gap. The clocks now stop whenever the app is out of sight and pick up exactly where they were, they stop when the game ends and during a replay or review, and both are saved with the game. They count on the phone's monotonic clock, so changing the time of day cannot move them
- Taking a move back rewinds the position, not the clocks: time spent stays with the side that spent it, so nothing is given back twice and nothing counted twice
- A game saved by an earlier version, which kept only one total, resumes with both clocks at zero

## [2.4.7] - 2026-10-04

### Changed
- The dark theme is smoky ink grey with moon-white text and pale gold lines instead of night green, and its pieces are a warmer white
- The app opens dark unless the player picks otherwise in 界面主题; it no longer follows the system by default, since the app has always been dark

### Fixed
- The board shifted at the start of a game: with the evaluation shown, its number was gone until the first reading arrived, and the row grew when it did; the captured-pieces line was a little taller with text in it, and taller still once it wrapped. The number now keeps its place, and the captured line is one line of fixed height whose text shrinks as captures mount up
- On the dark board the legal-move dots were dark brown and could hardly be seen. The move dots, capture marks and selection ring now take their colours from the theme: light dots, a red capture mark and a saturated gold selection ring at night, the old colours by day; the selection ring is thicker in both

## [2.4.6] - 2026-10-04

### Fixed
- The AI could sit on 思考中 without ever moving: a move made while the engine was still starting cancelled the start half way, which left a 230 MB engine process running and started another, and on a loaded phone the next search waited behind them. The engine start now runs to the end whoever asked for it
- After a take-back the last-move marks stayed on the move that was taken back

## [2.4.5] - 2026-10-04

### Added
- A dark theme, 墨玉夜山: the ink landscape tinted night green, a dark jade board with gold lines, and the same light pieces with cinnabar and ink characters
- 界面主题 in 更多: light, dark, or following the system (the default). Switching keeps the game, a review in progress and the AI's thinking; the activity rebuilds its views itself instead of restarting

## [2.4.4] - 2026-10-04

### Fixed
- Leaving a replay left the last-move marks on the move the replay stopped on, and resuming a saved game showed none; both now mark the game's last move

## [2.4.3] - 2026-10-04

### Changed
- A new look, 青玉: moon-white paper and an ink-wash landscape behind a pale jade board with faint mineral veins and a champagne-gold inlay; panels and cards are lit from above; the system bars are light
- Pieces are flat jade and cream discs with the characters in cinnabar red and ink black; the pieces, the river and a page title (对弈, or 复盘 in a review) are set in a Kai subset of LXGW WenKai (SIL OFL 1.1, renamed Jade Chess Kai; licence in `assets/licenses/`)
- New game has moved from the bottom bar into 更多, as its first item
- The background bamboo is kept faint so it does not sit under the status bar

## [2.4.2] - 2026-10-04

### Added
- The game is analysed while it is played: while the player thinks, the engine looks at the positions so far at depth 12, newest first, so a review afterwards is mostly ready and deeper. The AI's turn, a hint, a replay and leaving the app stop the search in hand at once
- In a review, the position the player stops on gets a two-second look, as a hint does, and its move replaces the shallow one (marked as the longer look)

### Changed
- Mistakes and blunders are named only when a depth-12 search before and after the move agrees; other moves get the engine's move, not a verdict. 最佳 and 缓着 are gone
- The review shows how many positions it has searched, and checked
- Leaving a review stops its analysis, and the analysis takes the engine one position at a time, so a game picked up again never waits for a whole review
- 上一处失误 / 下一处失误 in the review panel jump between the marked moves
- Captured pieces are listed on each card as one line of text grouped by kind (車×2  炮  卒×3), in the colour of the side they belonged to
- A review of a game still in progress ends on 当前局面 rather than 终局

## [2.4.1] - 2026-10-03

### Changed
- The review is a panel under the board instead of a dialog: drag along the graph to step through the game, or use the replay buttons; the bottom bar steps aside while it is open
- Every position in the review shows the engine's move as an arrow, and the line above the graph names the move played, judges it (best, inaccuracy, mistake, blunder) and gives the engine's choice when it differed
- The graph is drawn in pawns on a scale that fits the game, labelled 红+5 / 0 / 黑+5; mistakes and blunders are coloured on the curve with a dot where they were played
- The costliest move is the one that lost the most of the player's winning chances, not the most centipawns, so moves played after the game was already lost are no longer picked
- The header cards drop the 已吃子 label and wrap captured pieces onto two rows, so all sixteen fit

### Fixed
- A replayed position kept the last-move marks at the start and a piece selected before the replay
- The suggestion arrow and the mistake note stayed on screen while stepping through other moves
- An AI move in flight, or an AI-vs-AI game, went on while the game was being replayed

## [2.4.0] - 2026-10-03

### Added
- Two levels below 初级, 新手 and 入门, for people who have just learnt the moves: the engine looks at its six best moves and picks among them by a weighted draw (never missing a mate or walking into one), which beat 初级 in 0% and 13% of 30 calibration games
- On first launch the app asks how well you play and starts at 初级 unless told otherwise; it used to start everyone at 专业
- Practice and challenge modes: challenge games count towards the rating and allow no hints or take-backs, practice games allow both and leave the rating alone
- After a game: play again, or review it as a graph of how the position went, with the player's costliest move marked and the better move drawn on the board; tapping the graph opens the replay at that move
- An English interface (`values-en`), with moves written in WXF notation (C2=5, H8+7); the pieces keep their characters
- Thirty endgame studies from the classical manuals (适情雅趣, 烂柯神机, 梦入神机, 韬略元机, 橘中秘), mates in 2 to 12, each verified by Pikafish and by a unit test that plays the solution to mate under the app's rules; a study has a move limit, can be retried or shown solved, is ticked once solved and is saved like any game (see `ENDGAMES.md`)
- Unit tests for FEN, both notations, the weak levels and every endgame solution

### Changed
- The running evaluation is hidden by default and can be shown from 更多; when shown it is one reading next to the bar
- Replay runs from a bar under the board instead of a dialog; the move strip shows the latest round with the full list a tap away
- The header cards show the pieces each side has taken under 已吃子; the board is quieter, the last move is marked with corner brackets and the selection with a ring
- New game is drawn quieter than the other buttons and asks before ending an unfinished game
- The header counts rounds, as the move strip and the exported record do, instead of single moves

### Removed
- The eight hand-made endgame positions: seven of them were not legal positions

### Fixed
- Item names in the level and endgame lists were near-black on the dark dialog

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
