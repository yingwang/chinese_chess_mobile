# 中国象棋 v1.1.0 - Release Notes

## 新功能 ✨

### 开局库系统
- 添加了经典中国象棋开局定式
- 包含中炮、马局等常见开局
- AI在开局阶段会使用专业棋谱，下法更合理

### 改进的界面提示
- **可移动位置指示**：所有可走位置都用虚线框标识
  - 蓝色虚线框：普通移动
  - 红色虚线框：吃子移动
  - 即使目标位置有棋子也会显示提示
- **选中棋子高亮**：选中的棋子有明显的绿色光晕效果
- **时间显示优化**：超过1小时的对局显示为"小时:分钟:秒"格式

### 全新应用图标
- 采用中国传统美学设计
- 青灰、古铜、象牙色调，古朴内敛
- 以"象"字为主体，更具文化韵味
- 水墨画风格，告别俗艳

## 问题修复 🐛

### 关键修复
- ✅ 修复了主程序编译错误
- ✅ 修复了开局库坐标系统错误（之前AI开局走法不正确）
- ✅ 修复了图标编译错误

### 其他修复
- 移动提示现在在所有情况下都能正确显示
- 开局走法现在符合象棋定式

## 技术改进 🔧

- 使用矢量图标替代位图，APK体积更小
- 优化了代码结构和可维护性
- 改进了开局库的注释和文档

---

## New Features ✨

### Opening Book System
- Added classic Chinese chess opening repertoire
- Includes center cannon, horse openings, and more
- AI now plays professional opening moves

### Enhanced UI Feedback
- **Move Indicators**: All legal moves shown with dashed borders
  - Blue dashed border: normal moves
  - Red dashed border: capture moves
  - Indicators now show even when destination has a piece
- **Selected Piece Highlight**: Selected pieces now have a prominent green glow
- **Time Display**: Games over 1 hour show "hours:minutes:seconds" format

### Redesigned App Icon
- Classical Chinese aesthetic
- Slate gray, bronze, and ivory color palette
- Features "象" (Elephant) character
- Ink wash painting inspired design

## Bug Fixes 🐛

### Critical Fixes
- ✅ Fixed MainActivity compilation errors
- ✅ Fixed opening book coordinate system (AI now makes correct opening moves)
- ✅ Fixed icon compilation errors

### Other Fixes
- Move indicators now display correctly in all cases
- Opening moves now follow proper chess strategy

## Technical Improvements 🔧

- Vector-based icons for smaller APK size
- Improved code structure and maintainability
- Enhanced opening book documentation

---

## 安装说明

### 从源码构建
```bash
git clone https://github.com/yingwang/chinese_chess_mobile.git
cd chinese_chess_mobile
git checkout v1.1.0
./gradlew assembleRelease
```

### 系统要求
- Android 7.0 (API 24) 或更高版本
- 约 10MB 存储空间

## 下载

- APK: [chinese_chess_v1.1.0.apk](releases/v1.1.0)
- 源代码: [Source code (zip)](archive/v1.1.0.zip)
- 源代码: [Source code (tar.gz)](archive/v1.1.0.tar.gz)

## 反馈

如有问题或建议，请在 [GitHub Issues](https://github.com/yingwang/chinese_chess_mobile/issues) 提交。

---

**完整变更日志**: [CHANGELOG.md](CHANGELOG.md)
