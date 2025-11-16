# 中国象棋游戏音频系统

## 功能概述

本项目已集成完整的音频系统，包括：

1. **下子音效** - 清脆的棋子落盘声
2. **吃子音效** - 更有力的棋子碰撞声
3. **背景音乐** - 悠扬的五声音阶旋律（自动循环）

## 已实现的功能

### 音频管理器 (GameAudioManager)

位置：`app/src/main/java/com/yingwang/chinesechess/audio/GameAudioManager.kt`

提供以下功能：
- ✅ 音效播放（使用 SoundPool 实现低延迟）
- ✅ 背景音乐循环播放（使用 MediaPlayer）
- ✅ 独立的音效/音乐开关
- ✅ 音量控制
- ✅ 自动资源管理

### 集成点

1. **MainActivity.onCreate()** - 初始化音频管理器
2. **MainActivity.onResume()** - 恢复背景音乐
3. **MainActivity.onPause()** - 暂停背景音乐
4. **MainActivity.onDestroy()** - 释放音频资源
5. **GameController.onMoveCompleted** - 播放相应音效

## 音频文件

### 当前音频文件

位置：`app/src/main/res/raw/`

- `move_piece.wav` - 下子音效（13KB，150ms）
- `capture_piece.wav` - 吃子音效（18KB，200ms）
- `background_music.wav` - 背景音乐（2.6MB，30秒循环）

### 音频规格

- 格式：WAV（PCM）
- 采样率：44.1kHz
- 位深：16-bit
- 声道：单声道（Mono）

## 如何替换音频文件

### 方法一：使用自己的音频文件

1. 准备你的音频文件（MP3、WAV、OGG等格式都支持）
2. 将文件重命名为：
   - `move_piece.mp3` (或 .wav, .ogg)
   - `capture_piece.mp3` (或 .wav, .ogg)
   - `background_music.mp3` (或 .wav, .ogg)
3. 将文件放入 `app/src/main/res/raw/` 目录
4. 删除旧的 `.wav` 文件
5. 重新构建项目

### 方法二：重新生成音频

运行生成脚本：
```bash
python3 generate_audio.py
```

这将生成新的合成音频文件。

## 推荐的免费音频资源

### 音效资源

1. **Pixabay Sound Effects**
   - https://pixabay.com/sound-effects/search/chess/
   - 完全免费，无需署名

2. **Freesound**
   - https://freesound.org/
   - 搜索 "chess click" 或 "wood click"

### 背景音乐资源

1. **Free Stock Music**
   - https://www.free-stock-music.com/chinese.html
   - 中国传统音乐，CC-BY许可

2. **Chosic**
   - https://www.chosic.com/free-music/chinese/
   - 免费中国音乐，多种许可选项

3. **Fesliyan Studios**
   - https://www.fesliyanstudios.com/royalty-free-music/downloads-c/chinese-music/61
   - 完全免费音乐

## 音频控制 API

### 在代码中使用

```kotlin
// 获取音频管理器实例（在 MainActivity 中）
private lateinit var audioManager: GameAudioManager

// 播放音效
audioManager.playMoveSound()       // 下子音效
audioManager.playCaptureSound()    // 吃子音效

// 背景音乐控制
audioManager.startBackgroundMusic() // 开始/恢复播放
audioManager.pauseBackgroundMusic() // 暂停
audioManager.stopBackgroundMusic()  // 停止

// 音量控制（0.0 - 1.0）
audioManager.setSoundVolume(0.7f)   // 音效音量
audioManager.setMusicVolume(0.3f)   // 背景音乐音量

// 开关控制
audioManager.setSoundEnabled(true)  // 启用/禁用音效
audioManager.setMusicEnabled(true)  // 启用/禁用背景音乐

// 释放资源
audioManager.release()
```

## 性能考虑

1. **SoundPool** 用于短音效
   - 预加载到内存
   - 低延迟播放
   - 适合频繁播放的短音效

2. **MediaPlayer** 用于背景音乐
   - 流式播放，不占用大量内存
   - 支持循环播放
   - 适合长时间的背景音乐

3. **音量平衡**
   - 音效音量：70%（前景）
   - 背景音乐音量：30%（背景）
   - 确保音效清晰可辨，音乐不干扰

## 未来改进建议

1. **添加设置界面**
   - 音效开关
   - 背景音乐开关
   - 音量滑块

2. **更多音效**
   - 将军提示音
   - 游戏胜利音效
   - 游戏失败音效
   - UI点击音效

3. **更多音乐**
   - 多首背景音乐可选
   - 根据游戏进度切换音乐风格

4. **音频压缩**
   - 使用 MP3 或 OGG 格式减小 APK 体积
   - 当前 WAV 文件总大小：约 2.6MB

## 故障排除

### 音频不播放

1. 检查设备音量
2. 检查音频文件是否正确放置在 `app/src/main/res/raw/`
3. 检查文件名是否正确（小写，下划线分隔）
4. 查看 Logcat 错误信息

### 构建错误

如果出现资源未找到错误：
1. 清理项目：`./gradlew clean`
2. 重新构建：`./gradlew assembleDebug`

### 音频延迟

- 音效使用 SoundPool，延迟应该很小
- 如果仍有延迟，尝试调整 `SoundPool` 的 `maxStreams` 参数

## 许可说明

当前生成的音频文件是使用 Python 脚本合成的，可自由使用。

如果使用第三方音频资源，请：
1. 确认使用许可
2. 如需要，添加署名信息
3. 遵守相应的使用条款

---

**祝你游戏愉快！** 🎵🎮
