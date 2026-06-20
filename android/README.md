# Android APK 打包指南

将游戏平台打包为 Android APK。

## 快速打包（5分钟）

### 1. 创建 Android 项目
- Android Studio → New Project → Empty Views Activity
- 项目名: `MiniGamePlatform`，语言: Java，Min SDK: API 24

### 2. 修改 `activity_main.xml`
```xml
<WebView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/webview"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

### 3. 修改 `MainActivity.java`
```java
public class MainActivity extends AppCompatActivity {
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        WebView wv = findViewById(R.id.webview);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.getSettings().setDomStorageEnabled(true);
        wv.loadUrl("http://服务器IP:5000");
    }
}
```

### 4. 权限
在 `AndroidManifest.xml` 添加：
```xml
<uses-permission android:name="android.permission.INTERNET" />
```
并在 `<application>` 标签加 `android:usesCleartextTraffic="true"`

### 5. 生成 APK
Build → Build APK(s) → 在 `app/build/outputs/apk/debug/` 找到 APK

## 嵌入 Java 服务器（主机模式）
如需一部手机当主机，在 Activity 启动时运行服务器：
```java
new Thread(() -> { try { GameServer.main(new String[0]); } catch (Exception e) {} }).start();
```

## 云端部署（无需服务器、远程联机）

### Render.com 免费方案
1. 注册 https://render.com
2. New Web Service → 连接 GitHub
3. 构建命令: `javac -encoding UTF-8 GameServer.java`
4. 启动命令: `java GameServer`
5. 部署后得到 `https://xxx.onrender.com` 地址
6. 将 Android App 中的 URL 改为该地址

## 项目文件说明
| 文件 | 说明 |
|------|------|
| GameServer.java | Java HTTP 服务器（零依赖，仅需 JDK） |
| public/index.html | 游戏界面 |
| public/css/style.css | 圆桌样式 |
| public/js/app.js | 客户端逻辑（EventSource + Fetch） |
| start_server.bat | Windows 一键启动 |
