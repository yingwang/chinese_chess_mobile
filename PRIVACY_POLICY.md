# Privacy Policy for Chinese Chess (中国象棋)

**Last Updated: October 10, 2026**

## English Version

### Introduction

This privacy policy describes how Chinese Chess ("the App", "we", "our") handles information when you use our mobile application.

### Information Collection and Use

**We do not collect your name, email address, phone number or any other personal details, and we do not sell or share data with third parties.**

Chinese Chess:
- Works offline, except for the optional "Play a friend online" mode described below
- Does NOT require an account or sign-up
- Does NOT ask for your name, email address or phone number
- Does NOT track user behavior
- Does NOT use analytics services
- Does NOT display advertisements
- Does NOT sell data or share it with third parties

### Playing a Friend Online

The App works offline, except for the optional "Play a friend online" mode. When you use it, the App signs in to Google Firebase with an anonymous account (a random ID, with no name, email or phone number) and stores the game in Firebase's Realtime Database: the six-character room code, which side each player took, the moves with the server's time of each, the result, and whether each player is currently connected.

Only the two players seated in a room can read or write its game. Anyone else who has the code can see only the room's status (waiting, being played or finished), which side its host took, and when it was created and started. Everything is sent encrypted (HTTPS/TLS).

A room is deleted when its host cancels it, and when the last player leaves a finished game. If both players close the App without leaving the room, the room stays in the database with only the data listed above until it is deleted on request.

We use this data only to run the game between you and your friend; we do not sell it, use it for advertising or analytics, or share it with anyone else. Firebase processes it on our behalf under Google's terms (https://firebase.google.com/support/privacy). To have a game deleted, email nickelyw@gmail.com with its room code.

### Local Data Storage

The App may store the following data locally on your device:
- Game settings and preferences
- Game statistics (scores, play time)
- Audio preferences (sound on/off)
- During an online game, its room code and your side, so the App can return to the room

This data is stored only on your device and is not sent to any server or third party.

### Children's Privacy

Our App does not knowingly collect personal information from children under the age of 13. The App is suitable for all ages. Online play stores only an anonymous ID and the moves of the game; players cannot send each other messages, and no name or contact details are asked for.

### Permissions

The App may request the following permissions:
- **Storage**: To save game preferences and statistics locally on your device
- **Audio**: To play game sounds and background music
- **Internet and network state**: Used only by "Play a friend online", to reach Firebase and to notice when the connection drops

These permissions are used solely for the functionality of the App. Apart from online play, nothing in the App uses the network.

### Data Security

An online game is sent encrypted (HTTPS/TLS) and stored in Firebase, where the database rules let only the two players in a room read or change its game. Everything else the App keeps stays on your device.

### Changes to This Privacy Policy

We may update our Privacy Policy from time to time. We will notify you of any changes by posting the new Privacy Policy on this page and updating the "Last Updated" date.

### Contact Us

If you have any questions about this Privacy Policy, please contact us at:
- Email: nickelyw@gmail.com
- GitHub: https://github.com/yingwang/chinese_chess_mobile

---

## 中文版

### 简介

本隐私政策描述了中国象棋（"本应用"、"我们"）在您使用我们的移动应用程序时如何处理信息。

### 信息收集和使用

**我们不收集您的姓名、邮箱、电话或任何其他个人信息，也不出售数据或与第三方共享数据。**

中国象棋：
- 除了可选的「和朋友联机」（见下文），所有功能都离线运行
- 不需要账号，也不需要注册
- 不询问您的姓名、邮箱或电话
- 不跟踪用户行为
- 不使用分析服务
- 不显示广告
- 不出售数据，也不与第三方共享数据

### 和朋友联机

除了可选的「和朋友联机」，本应用的所有功能都离线运行。使用联机时，应用会以匿名账号登录 Google Firebase（只是一个随机 ID，不含姓名、邮箱或电话），并把这盘棋存在 Firebase 实时数据库中：6 位房间号、双方各执哪一方、每一步棋及服务器记录的时间、对局结果，以及双方当前是否在线。

只有坐进这个房间的两位棋手能读写这盘棋。其他知道房间号的人只能看到房间的状态（等人、对局中或已结束）、房主执哪一方，以及建房和开局的时间。所有数据都加密传输（HTTPS/TLS）。

房主取消等人时房间即被删除；一盘下完，最后离开的一方会删除房间。如果双方都没有离开房间就直接关掉应用，房间会留在数据库中，里面只有上面列出的这些数据，直到按申请删除。

这些数据只用于让您和朋友对弈，我们不出售、不用于广告或统计，也不提供给任何第三方。Firebase 依照 Google 的条款代为处理（https://firebase.google.com/support/privacy）。如需删除某盘棋，请把房间号发到 nickelyw@gmail.com。

### 本地数据存储

本应用可能会在您的设备上本地存储以下数据：
- 游戏设置和偏好
- 游戏统计（得分、游戏时间）
- 音频偏好（声音开/关）
- 联机对局进行中时，这盘棋的房间号和您执哪一方，以便应用回到房间

这些数据仅存储在您的设备上，不会发送到任何服务器或第三方。

### 儿童隐私

我们的应用不会有意收集13岁以下儿童的个人信息。本应用适合所有年龄段。联机对局只保存一个匿名 ID 和这盘棋的棋步；棋手之间不能互发消息，应用也不询问姓名或联系方式。

### 权限

本应用可能请求以下权限：
- **存储**：用于在您的设备上本地保存游戏偏好和统计数据
- **音频**：用于播放游戏音效和背景音乐
- **网络访问与网络状态**：仅供「和朋友联机」使用，用于连接 Firebase，并在连接中断时察觉

这些权限仅用于应用的功能。除了联机对局，应用的其他部分都不使用网络。

### 数据安全

联机对局加密传输（HTTPS/TLS），存储在 Firebase 中，数据库规则只允许房间里的两位棋手读取或改动这盘棋。应用保存的其他数据都留在您的设备上。

### 隐私政策的变更

我们可能会不时更新我们的隐私政策。我们将通过在此页面上发布新的隐私政策并更新"最后更新"日期来通知您任何更改。

### 联系我们

如果您对本隐私政策有任何问题，请通过以下方式联系我们：
- 电子邮件：nickelyw@gmail.com
- GitHub：https://github.com/yingwang/chinese_chess_mobile

---

## Legal Compliance

This privacy policy complies with:
- General Data Protection Regulation (GDPR)
- California Consumer Privacy Act (CCPA)
- Children's Online Privacy Protection Act (COPPA)
- Google Play Store Privacy Policy Requirements

## Your Rights

Everything the App keeps on your device (settings, statistics and saved games) can be deleted by clearing the App's data or uninstalling it. An online game is kept under an anonymous ID, with no name or contact details attached, and its room is deleted when the host cancels it or the last player leaves a finished game. To have an online game deleted, or to ask what is held for it, email nickelyw@gmail.com with its room code (see "Deleting your data" below).

## Deleting your data / 删除数据

**Chinese Chess (中国象棋), by Ying Wang**

Offline play (against the engine, two players on one phone, endgames, review) stores nothing off your device. Uninstalling the App, or clearing its data, removes it all.

An online game is stored in Firebase only while it is needed: its room is deleted when the host cancels it, and when the last player leaves a finished game.

To have any remaining online game deleted:
1. Email nickelyw@gmail.com.
2. Give the room code and roughly when the game was played.
3. The room will be deleted within 30 days.

What is deleted: the whole room, that is its code, the anonymous IDs of its two seats, the moves with their times, the result and the online status. Nothing else is kept.

**中国象棋（Chinese Chess），开发者 Ying Wang**

离线下棋（人机、双人、残局、复盘）不会在您的设备以外保存任何数据，卸载应用或清除应用数据即可全部删除。

联机对局只在需要时存在 Firebase 中：房主取消等人时房间即被删除；一盘下完，最后离开的一方会删除房间。

如需删除仍留在服务器上的联机对局：
1. 发邮件到 nickelyw@gmail.com。
2. 写明房间号，以及大约是什么时候下的。
3. 我们会在 30 天内删除这个房间。

删除的内容：整个房间，即房间号、两个座位的匿名 ID、每一步棋及其时间、对局结果和在线状态。除此之外不保存任何数据。

---

**Summary**: Chinese Chess is a privacy-friendly game. Everything except the optional "Play a friend online" works offline; an online game is stored under an anonymous ID, only for you and your friend. No personal details, ads, tracking or analytics.
