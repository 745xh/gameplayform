package com.gameplatform;

import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setWebViewClient(new WebViewClient());

        // 默认加载服务器地址 - 连接远程游戏服务器
        // 改为你的服务器地址（局域网IP或云服务器域名）
        String serverUrl = getIntent().getStringExtra("SERVER_URL");
        if (serverUrl == null) {
            // 默认地址：修改为你的服务器IP
            // 局域网：http://192.168.x.x:5000
            // 云端：https://xxx.onrender.com
            serverUrl = "http://10.0.2.2:5000"; // Android模拟器访问本机
        }
        webView.loadUrl(serverUrl);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
