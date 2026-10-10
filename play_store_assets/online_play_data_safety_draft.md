# 2.5.0「和朋友联机」：Data safety 与隐私政策草稿

草稿，未提交到 Play Console，也未改动线上的隐私政策。2.5.0 送审之前要做的事：在 Play Console 里按下面填好 Data safety，把隐私政策段落放进 PRIVACY_POLICY.md（以及网站和 PRIVACY_POLICY_SHORT.txt），再把商店介绍里「不联网」的说法改掉（见最后一节）。

## 联机功能实际传了什么

只有玩家主动点「和朋友联机」之后才会联网；人机、双人、残局、复盘都和以前一样完全离线。联机时，应用通过 Google Firebase（项目 xiangqi-online-28fa1，Realtime Database 加匿名登录，网页版用的也是同一个项目）读写这些内容：

- 一个匿名账号 ID：Firebase 匿名登录生成的随机字符串，不含姓名、邮箱、电话，也不和设备标识或广告 ID 关联。它用来占住房间里的座位，数据库规则据此只让同一局的两位棋手读写这盘棋。
- 房间数据：6 位房间号、建房时间、房主执哪一方、对局状态、开局时间。
- 对局内容：每一步的起止格子和服务器记下的时间，以及结果（将死、困毙、长将、三次重复、认输，和谁赢）。
- 在线状态：这位棋手此刻是否连着服务器，用来给对方显示「在线 / 离线」。

不收集：姓名、邮箱、电话、通讯录、位置、照片、设备标识、广告 ID，也没有统计、崩溃上报或广告 SDK。分享房间号走的是系统分享面板，由玩家自己选择发给谁，应用本身不读取聊天对象。所有连接都经 TLS 加密（HTTPS / WSS）。

数据的保留：房间等人时取消会立刻删除；一盘下完，最后离开的一方会把整个房间删掉。两边都直接关掉应用而不点「离开」时，房间会留在数据库里，里面只有上面这些匿名数据。

## Play Console：Data safety 表单建议填法

**Data collection and security**

- Does your app collect or share any of the required user data types? **Yes**
- Is all of the user data collected by your app encrypted in transit? **Yes**
- Do you provide a way for users to request that their data is deleted? **Yes**（通过邮件 nickelyw@gmail.com 申请；表单里可同时说明房间在双方离开后会自动删除）

**Data types**（只勾这两项）

1. **App activity → Other actions**（Play 对这一项的解释里点名了 gameplay）
   - Collected: Yes；Shared: **No**（Firebase 是替你处理数据的服务商，按 Play 的定义不算「分享」）
   - Processed ephemerally: **No**（对局存在数据库里，直到房间被删除）
   - Required or optional: **Optional**（只有使用联机对战时才收集，用户可以不用）
   - Purposes: **App functionality**
2. **Device or other IDs**（Firebase 匿名登录的账号 ID）
   - Collected: Yes；Shared: **No**
   - Processed ephemerally: **No**
   - Required or optional: **Optional**
   - Purposes: **App functionality**（另可勾 **Fraud prevention, security, and compliance**：数据库规则靠它只让两位棋手读写自己的对局）

不需要勾：Personal info、Location、Financial info、Messages、Photos、Contacts、App info and performance（没有崩溃或诊断上报）。

两点说明，供你判断：Firebase 的服务器在连接时会看到 IP 地址，但应用不用它推断位置，Google 的 Firebase 说明里也不要求因此勾「Approximate location」。匿名账号 ID 是否算作「Device or other IDs」各家写法不一，这里按「与某个应用安装相关、但不指向具体个人的标识」归到这一类；如果想更保守，也可以改归「Personal info → User IDs」，两种都说得通，但不要两处都不勾。

**其他会变的地方**

- 新增权限：`INTERNET`、`ACCESS_NETWORK_STATE`（Firebase SDK 另外合并进来一条 `com.google.android.providers.gsf.permission.READ_GSERVICES`，普通权限，不弹窗）。
- 内容分级问卷里如果有「用户之间能否互动」一题：能，但只交换棋步，没有聊天、没有文字或图片；房间要靠房间号进入，陌生人搜不到。

## 隐私政策段落（放进 PRIVACY_POLICY.md 两个语言版本）

**English**

> **Playing a friend online.** The app works offline, except for the optional "Play a friend online" mode. When you use it, the app signs in to Google Firebase with an anonymous account (a random ID, with no name, email or phone number) and stores the game in Firebase's Realtime Database: the six-character room code, which side each player took, the moves with the server's time of each, the result, and whether each player is currently connected. Only the two players seated in a room can read or write its game; anyone else can see no more than whether a room with that code is waiting for a player. Everything is sent encrypted (HTTPS/TLS). A room is deleted when its host cancels it, and when the last player leaves a finished game. We use this data only to run the game between you and your friend; we do not sell it, use it for advertising or analytics, or share it with anyone else. Firebase processes it on our behalf under Google's terms (https://firebase.google.com/support/privacy). To have a game deleted, email nickelyw@gmail.com with its room code.

**中文**

> **和朋友联机。** 除了可选的「和朋友联机」，本应用的所有功能都离线运行。使用联机时，应用会以匿名账号登录 Google Firebase（只是一个随机 ID，不含姓名、邮箱或电话），并把这盘棋存在 Firebase 实时数据库中：6 位房间号、双方各执哪一方、每一步棋及服务器记录的时间、对局结果，以及双方当前是否在线。只有坐进这个房间的两位棋手能读写这盘棋，其他人最多只能看到这个房间号是否在等人。所有数据都加密传输（HTTPS/TLS）。房主取消等人时房间即被删除；一盘下完，最后离开的一方会删除房间。这些数据只用于让你和朋友对弈，我们不出售、不用于广告或统计，也不提供给任何第三方。Firebase 依照 Google 的条款代为处理（https://firebase.google.com/support/privacy）。如需删除某盘棋，请把房间号发到 nickelyw@gmail.com。

同时要改掉的旧说法：英文版「Chinese Chess is a completely offline game that: Does NOT require internet connection」和「no data is transmitted off your device」，中文版「中国象棋是一个完全离线的游戏」「不需要互联网连接」「不会将任何数据传输到您的设备之外」，以及文末 Your Rights 一节和 Summary。PRIVACY_POLICY_SHORT.txt、docs/index.html 的 Privacy 一段、README 的「Privacy / 隐私」一段也是同样的说法。「Last Updated」改成发布当天。

## 商店介绍里要改的句子

- en-US：「PRIVATE BY DESIGN / The app requests no permissions at all, not even network access. No ads, no tracking, no analytics. Every game stays on your phone.」建议改为：「PRIVATE BY DESIGN / Everything but playing a friend online works without a connection. No ads, no tracking, no analytics, no sign-up. An online game is stored only for you and your friend, under an anonymous ID.」开头那句「there is no account to create」仍然成立。
- zh-CN：「隐私 / 这个应用没有申请任何权限，连联网都没有。没有广告，没有追踪，没有统计。所有棋局都留在自己的手机上。」建议改为：「隐私 / 除了和朋友联机，其他功能都不需要联网。没有广告，没有追踪，没有统计，也不用注册。联机的棋局只给你和朋友两个人看，用的是匿名身份。」第二段「不需要注册，也不需要联网」建议改成「不需要注册，人机对弈也不需要联网」。
- 两种语言的「对局方式」列表可以加一行：「和朋友联机：发个房间号，手机和网页版都能一起下」/「Play a friend online with a room code, phone or web」。
