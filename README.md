## 启动方法

```npm
npm install
npm run dev
```

安装yk.apk到安卓模拟器

## 写在前面

此仓库为复现+原理分析，不包含原款app，但是复现了远控功能（仅限于localhost），使用Node.js + 安卓模拟器

本人最近在网上冲浪，被诈骗犯尝试钓鱼，发了个黄色APP安装包过来

兴趣使然，搞了台安卓机进行逆向分析后，发现有点恐怖，因为它能绕过你的 `银行APP` 和 `加密货币钱包` 的录屏检测

<img width="746" height="472" alt="image" src="https://github.com/user-attachments/assets/397bfca5-c53f-4db4-a818-2acc9d8cda8f" />

* 无需 Root 权限。

* 基于 Android 原生 API 实现设备控制。

* 使用 WebSocket 进行双向实时通信。

* 支持屏幕图像和界面文本独立传输。

* 支持远程点击、滑动、文字输入和系统导航。

* 支持断线自动重连。

| 绝对不是本人想看

## 流程

1. 通过“引导”下载色情APP，在下载之前，会让你注册一个账号，并且填入支付密码（重要）

2. 在安装完APK后，会给你跳2次人脸，这人脸是过不去的，所以你会输入手机解锁密码（重要）

3. 获得你的支付密码和手机解锁密码后，让你开启无障碍模式（重要）

## 原理分析

AccessibilityService（无障碍服务）：读取界面结构、执行点击、滑动、输入文字等操作。

### 1. 远程点击、滑动与导航

核心技术：AccessibilityService.dispatchGesture()

Android 无障碍服务具有模拟用户手势的能力。

例如电脑端希望点击屏幕上的某个按钮：

1. 网页点击
   
2. JSON 指令
   
3. Android 手势

示例：点击屏幕坐标 (450, 800)

```json
{
  "type": "control",
  "action": "tap",
  "x": 450,
  "y": 800
}
```

执行时，Android 创建一个 Path，把点击位置转换成持续约 70 毫秒的触摸手势，再交给系统执行。

这里有一个重要特点：控制发生在 Android 系统层，而不是某个特定 App 的内部。

因此，在权限允许且目标界面支持的情况下，可以操作不同 App，包括浏览器、聊天软件和系统设置。

### 2. 读取屏幕文字（绕过录屏检测）

这是整套系统中比较有价值的功能。

你并不是单纯使用 OCR 识别屏幕，而是直接读取 Android 的 Accessibility UI Tree（无障碍节点树）。

例如手机上显示：

<img width="209" height="198" alt="image" src="https://github.com/user-attachments/assets/9cf31d9b-3ee2-4684-8135-3ed2fd0f1f53" />

无障碍服务读取结果（示意）

```json
{
  "type": "screen_text",
  "app": "com.example.chat",
  "items": [
    {"text": "你好，今天有时间吗？"},
    {"text": "有的，怎么了？"}
  ]
}
```

实际传输的每个文本节点还会包含位置、边界及是否可点击等信息。

代码中包含
```java
AccessibilityNodeInfo root =
    getRootInActiveWindow();
```

然后递归遍历节点：
```json
node.getText();
node.getContentDescription();
node.getBoundsInScreen(bounds);
```

因此，它不仅能读取文本，还能知道文本在屏幕上的位置。

例如

```json
{
  "text": "发送",
  "x": 0.89,
  "y": 0.93,
  "clickable": true
}
```

这意味着电脑端可以根据 UI 节点信息定位按钮，而不必完全依赖截图识别。

并且，银行和钱包软件都采用了防录屏（黑屏），但是防不了这种读取，知道你屏幕有什么文字，按钮在哪里，那就是想点哪点哪，随便进你GMAIL拿验证码。

