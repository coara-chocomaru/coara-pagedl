package com.coara.pagedl;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class pagedl extends AppCompatActivity {
    private static final String TAG = "pagedl";
    private static final long MIN_STORAGE_THRESHOLD = 512L * 1024L * 1024L;
    private static final String PC_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String ACCEPT_HEADER = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8";
    private static final String ACCEPT_LANGUAGE = "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7";
    private static final String ACCEPT_ENCODING = "gzip, deflate";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int BUFFER_SIZE = 32768;
    private static final int REQUEST_CODE_POST_NOTIFICATIONS = 1;
    private static final long LOAD_WAIT_MS = 10000;
    private EditText urlInput;
    private Switch jsSwitch;
    private Switch resourceSwitch;
    private Switch pcUaSwitch;
    private Button saveButton;
    private WebView webView;
    private ProgressDialog progressDialog;

    public static final String ACTION_DOWNLOAD_STARTED = "com.coara.pagedl.ACTION_DOWNLOAD_STARTED";
    public static final String ACTION_DOWNLOAD_PROGRESS = "com.coara.pagedl.ACTION_DOWNLOAD_PROGRESS";
    public static final String ACTION_DOWNLOAD_COMPLETE = "com.coara.pagedl.ACTION_DOWNLOAD_COMPLETE";
    public static final String ACTION_DOWNLOAD_ERROR = "com.coara.pagedl.ACTION_DOWNLOAD_ERROR";

    public static final String EXTRA_SESSION_ID = "extra_session_id";

    private Set<String> loadedResources = new LinkedHashSet<>();
    private volatile AtomicBoolean isSaving = new AtomicBoolean(false);
    private Handler handler = new Handler(Looper.getMainLooper());

    private long currentSessionId = -1L;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (ACTION_DOWNLOAD_PROGRESS.equals(action)) {
                long sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L);
                if (sessionId != currentSessionId) {
                    return;
                }

                final String msg = intent.getStringExtra("message");
                final boolean isResourceProgress = intent.getBooleanExtra("resource_progress", false);

                handler.post(() -> {
                    if (progressDialog == null) return;
                    if (msg == null) return;

                    boolean resourceSwitchChecked = (resourceSwitch != null && resourceSwitch.isChecked());
                    boolean resourceSwitchEnabled = (resourceSwitch != null && resourceSwitch.isEnabled());

                    if (isResourceProgress && (!resourceSwitchChecked || !resourceSwitchEnabled)) {
                        progressDialog.setMessage("ダウンロード中です…");
                    } else {
                        progressDialog.setMessage(msg);
                    }
                });

            } else if (ACTION_DOWNLOAD_COMPLETE.equals(action)) {
                long sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L);
                if (sessionId != currentSessionId) return;

                final String path = intent.getStringExtra("outputPath");
                runOnUiThread(() -> {
                    if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
                    Toast.makeText(pagedl.this, "保存完了：\n" + path, Toast.LENGTH_LONG).show();
                    clearCacheAndCookies();
                });
            } else if (ACTION_DOWNLOAD_ERROR.equals(action)) {
                long sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L);
                if (sessionId != currentSessionId) return;

                final String err = intent.getStringExtra("error");
                runOnUiThread(() -> {
                    if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
                    showCancellationNotification(err);
                    handler.postDelayed(() -> {
                        finishAffinity();
                        System.exit(0);
                    }, 2200);
                });
            }
        }
    };

    private void showCancellationNotification(String message) {
        final String channelId = "pagedl_cancel_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            final NotificationChannel ch = new NotificationChannel(channelId, "Page Download Cancellation", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("ページ保存キャンセルの通知");
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }

        final NotificationCompat.Builder nb = new NotificationCompat.Builder(this, channelId)
            .setContentTitle("ダウンロードキャンセル")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(1900L);
        final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(0x1454, nb.build());
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pgdl);

        urlInput = findViewById(R.id.urlInput);
        jsSwitch = findViewById(R.id.jsSwitch);
        resourceSwitch = findViewById(R.id.resourceZipSwitch);
        pcUaSwitch = findViewById(R.id.pcUaSwitch);
        saveButton = findViewById(R.id.saveButton);
        webView = findViewById(R.id.webView);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        jsSwitch.setChecked(true);
        final WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(jsSwitch.isChecked());
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setBlockNetworkLoads(false);
        webSettings.setSupportMultipleWindows(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        updateUserAgent(webSettings);

        jsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            webSettings.setJavaScriptEnabled(isChecked);
            boolean hasStorage = getAvailableStorage() >= MIN_STORAGE_THRESHOLD;
            resourceSwitch.setEnabled(isChecked && hasStorage);
            if (!isChecked && resourceSwitch.isChecked()) {
                resourceSwitch.setChecked(false);
            }
        });

        pcUaSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> updateUserAgent(webSettings));

        resourceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!jsSwitch.isChecked()) {
                    resourceSwitch.setChecked(false);
                    Toast.makeText(this, "JavaScriptが無効なため、リソース保存は利用できません", Toast.LENGTH_LONG).show();
                    return;
                }
                if (getAvailableStorage() < MIN_STORAGE_THRESHOLD) {
                    resourceSwitch.setChecked(false);
                    Toast.makeText(this, "ストレージ容量が512MB未満のため、リソース保存を無効にします", Toast.LENGTH_LONG).show();
                }
            }
        });

        saveButton.setOnClickListener(v -> handleSaveButtonClick());

        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("ダウンロード中です…");
        progressDialog.setCancelable(false);

        checkStorageAndUpdateUI();

        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_DOWNLOAD_PROGRESS);
        filter.addAction(ACTION_DOWNLOAD_COMPLETE);
        filter.addAction(ACTION_DOWNLOAD_ERROR);
        registerReceiver(receiver, filter);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isSaving.get()) {
                    Toast.makeText(pagedl.this, "保存中はバックキーが無効です", Toast.LENGTH_SHORT).show();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        checkNotificationPermission();
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE_POST_NOTIFICATIONS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "通知権限が拒否されました。通知が表示されない可能性があります。", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(receiver);
        } catch (final Exception ignored) {
        }
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        webView.destroy();
    }

    private void checkStorageAndUpdateUI() {
        boolean hasStorage = getAvailableStorage() >= MIN_STORAGE_THRESHOLD;
        if (!hasStorage) {
            resourceSwitch.setEnabled(false);
            if (resourceSwitch.isChecked()) resourceSwitch.setChecked(false);
            Toast.makeText(this, "ストレージ容量が512MB未満のため、リソース保存を無効にします", Toast.LENGTH_LONG).show();
        } else {
            resourceSwitch.setEnabled(jsSwitch.isChecked());
        }
    }

    private long getAvailableStorage() {
        final File path = Environment.getExternalStorageDirectory();
        return path.getFreeSpace();
    }

    private void updateUserAgent(final WebSettings webSettings) {
        if (pcUaSwitch.isChecked()) {
            webSettings.setUserAgentString(PC_USER_AGENT);
        } else {
            webSettings.setUserAgentString(WebSettings.getDefaultUserAgent(this));
        }
    }

    private void handleSaveButtonClick() {
        checkStorageAndUpdateUI();

        if (!isSaving.compareAndSet(false, true)) {
            Toast.makeText(this, "現在保存処理中です", Toast.LENGTH_SHORT).show();
            return;
        }

        final String urlString = urlInput.getText().toString().trim();
        if (urlString.isEmpty()) {
            Toast.makeText(this, "URLを入力してください", Toast.LENGTH_SHORT).show();
            isSaving.set(false);
            return;
        }
        if (urlString.startsWith("blob:")) {
            Toast.makeText(this, "blob: URLはサポートされていません", Toast.LENGTH_LONG).show();
            isSaving.set(false);
            return;
        }

        currentSessionId = System.currentTimeMillis();

        final String siteName = urlString.replaceAll("[^a-zA-Z0-9]", "_");

        progressDialog.setMessage("ダウンロード中です…");
        progressDialog.show();

        final File outputDir = createOutputDirectory(siteName);
        if (outputDir == null) {
            if (progressDialog.isShowing()) progressDialog.dismiss();
            isSaving.set(false);
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> saveSiteInfo(urlString, outputDir));

        if (urlString.startsWith("data:")) {
            Executors.newSingleThreadExecutor().execute(() -> saveDataUrlContent(urlString, outputDir, new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date())));
            isSaving.set(false);
            return;
        }

        final boolean saveResources = resourceSwitch.isChecked();
        final boolean jsEnabled = jsSwitch.isChecked();

        if (saveResources || !jsEnabled) {
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    final byte[] htmlData = downloadResourceBytes(urlString, pcUaSwitch.isChecked(), urlString);
                    if (htmlData == null) {
                        throw new IOException("HTMLダウンロード失敗");
                    }
                    final File htmlFile = new File(outputDir, "page.html");
                    try (final FileOutputStream fos = new FileOutputStream(htmlFile)) {
                        fos.write(htmlData);
                    }

                    if (!saveResources && !jsEnabled) {
                        runOnUiThread(() -> {
                            if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
                            Toast.makeText(pagedl.this, "保存完了：\n" + htmlFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                            clearCacheAndCookies();
                        });
                    }
                } catch (final Exception e) {
                    runOnUiThread(() -> {
                        if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
                        Toast.makeText(pagedl.this, "HTML保存エラー: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                } finally {
                    if (!saveResources) {
                        isSaving.set(false);
                    }
                }
            });
        }

        if (jsEnabled) {
            loadedResources.clear();
            webView.setWebViewClient(new CustomWebViewClient(urlString, siteName, outputDir));
            webView.loadUrl(urlString);
        }
    }

    private class CustomWebViewClient extends WebViewClient {
        private final String originalUrl;
        private final String siteName;
        private final File outputDir;
        private boolean pageLoaded = false;

        public CustomWebViewClient(String originalUrl, String siteName, File outputDir) {
            this.originalUrl = originalUrl;
            this.siteName = siteName;
            this.outputDir = outputDir;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (pageLoaded) return;
            pageLoaded = true;
            String idleScript = "(function() {"
                    + "  var idleTime = 0;"
                    + "  var idleInterval = setInterval(function() {"
                    + "    idleTime++;"
                    + "    if (idleTime > 3) { "
                    + "      clearInterval(idleInterval);"
                    + "      window.onGrokIdle = true;"
                    + "    }"
                    + "  }, 1000);"
                    + "  document.addEventListener('mousemove', function(){ idleTime = 0; });"
                    + "  document.addEventListener('keypress', function(){ idleTime = 0; });"
                    + "  document.addEventListener('scroll', function(){ idleTime = 0; });"
                    + "  if (document.readyState === 'complete') {"
                    + "    idleTime = 4;"
                    + "  }"
                    + "})();";
            view.evaluateJavascript(idleScript, null);
            handler.postDelayed(() -> checkAndSaveArchive(view, siteName, originalUrl, outputDir), LOAD_WAIT_MS);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                String resUrl = request.getUrl().toString();
                if (!request.isForMainFrame()) {
                    if (resUrl.startsWith("http://") || resUrl.startsWith("https://")) {
                        loadedResources.add(resUrl);
                    }
                }
            } catch (Exception ignored) {
            }
            return super.shouldInterceptRequest(view, request);
        }
    }

    private void checkAndSaveArchive(WebView view, String siteName, String urlString, File outputDir) {
        view.evaluateJavascript("window.onGrokIdle", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if ("true".equals(value)) {
                    handlerDelayedSaveArchive(siteName, urlString, outputDir);
                } else {
                    handler.postDelayed(() -> checkAndSaveArchive(view, siteName, urlString, outputDir), 2000);
                }
            }
        });
    }

    private void handlerDelayedSaveArchive(final String siteName, final String urlString, final File outputDir) {
        try {
            final String archivePath = new File(outputDir, "page.mht").getAbsolutePath();

            final String cssScript = "(function() {"
                    + "  var out = [];"
                    + "  try {"
                    + "    var sheets = document.styleSheets;"
                    + "    for (var i = 0; i < sheets.length; i++) {"
                    + "      var s = sheets[i];"
                    + "      if (s.href) out.push(s.href);"
                    + "      try {"
                    + "        var rules = s.cssRules || s.rules;"
                    + "        if (rules) {"
                    + "          for (var j = 0; j < rules.length; j++) {"
                    + "            try { out.push(rules[j].cssText); } catch (e) {}"
                    + "          }"
                    + "        }"
                    + "      } catch (e) {}"
                    + "    }"
                    + "  } catch (e) {}"
                    + "  var styles = document.querySelectorAll('style');"
                    + "  for (var k = 0; k < styles.length; k++) {"
                    + "    try { out.push(styles[k].innerHTML); } catch (e) {}"
                    + "  }"
                    + "  return JSON.stringify(out);"
                    + "})()";
            webView.evaluateJavascript(cssScript, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (value != null && !"null".equals(value)) {
                        try {
                            JSONArray array = new JSONArray(value);
                            for (int i = 0; i < array.length(); i++) {
                                String cssItem = array.getString(i);
                                if (cssItem == null) continue;
                                if (cssItem.startsWith("http://") || cssItem.startsWith("https://")) {
                                    loadedResources.add(cssItem);
                                } else {
                                    Set<String> inlineUrls = Utils.extractResourcesFromCss(cssItem, urlString);
                                    loadedResources.addAll(inlineUrls);
                                    Set<String> imported = Utils.extractImportsFromCss(cssItem, urlString);
                                    loadedResources.addAll(imported);
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to parse CSS URLs", e);
                        }
                    }

                    String domResourceScript = Utils.buildDomResourceScript();
                    webView.evaluateJavascript(domResourceScript, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String domValue) {
                            if (domValue != null && !"null".equals(domValue)) {
                                try {
                                    JSONArray domArray = new JSONArray(domValue);
                                    for (int i = 0; i < domArray.length(); i++) {
                                        String res = domArray.getString(i);
                                        if (res == null || res.isEmpty()) continue;
                                        if (res.startsWith("//")) res = "https:" + res;
                                        if (res.startsWith("http://") || res.startsWith("https://")) {
                                            loadedResources.add(res);
                                        } else {
                                            try {
                                                String resolved = Utils.resolveUrl(res, urlString);
                                                if (resolved != null) loadedResources.add(resolved);
                                            } catch (Exception ignored) {
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    Log.w(TAG, "Failed to parse DOM resources", e);
                                }
                            }

                            String inlineStyleScript = Utils.buildInlineStyleScript();
                            webView.evaluateJavascript(inlineStyleScript, new ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String styleValue) {
                                    if (styleValue != null && !"null".equals(styleValue)) {
                                        try {
                                            JSONArray arr = new JSONArray(styleValue);
                                            for (int i = 0; i < arr.length(); i++) {
                                                String s = arr.getString(i);
                                                if (s == null) continue;
                                                Set<String> urls = Utils.extractResourcesFromCss(s, urlString);
                                                loadedResources.addAll(urls);
                                            }
                                        } catch (Exception ignored) {
                                        }
                                    }

                                    String scriptSrcScript = Utils.buildScriptSrcScript();
                                    webView.evaluateJavascript(scriptSrcScript, new ValueCallback<String>() {
                                        @Override
                                        public void onReceiveValue(String jsValue) {
                                            if (jsValue != null && !"null".equals(jsValue)) {
                                                try {
                                                    JSONArray arr = new JSONArray(jsValue);
                                                    for (int i = 0; i < arr.length(); i++) {
                                                        String s = arr.getString(i);
                                                        if (s == null) continue;
                                                        Set<String> urls = Utils.extractResourcesFromJs(s, urlString);
                                                        loadedResources.addAll(urls);
                                                    }
                                                } catch (Exception ignored) {
                                                }
                                            }

                                            webView.saveWebArchive(archivePath, false, archiveValue -> {
                                                if (archiveValue == null) {
                                                    captureManualHtml(urlString, outputDir);
                                                    return;
                                                }

                                                final boolean saveResources = resourceSwitch.isChecked();
                                                if (saveResources) {
                                                    final Intent svc = new Intent(pagedl.this, DownloadService.class);
                                                    svc.putExtra(DownloadService.EXTRA_ARCHIVE_PATH, archiveValue);
                                                    svc.putExtra(DownloadService.EXTRA_HTML_PATH, new File(outputDir, "page.html").getAbsolutePath());
                                                    svc.putExtra(DownloadService.EXTRA_OUTPUT_DIR, outputDir.getAbsolutePath());
                                                    svc.putExtra(DownloadService.EXTRA_SAVE_RESOURCES, true);
                                                    svc.putExtra(DownloadService.EXTRA_PC_UA, pcUaSwitch.isChecked());
                                                    svc.putExtra(DownloadService.EXTRA_BASE_URL, urlString);
                                                    svc.putExtra(DownloadService.EXTRA_REFERER, urlString);
                                                    svc.putExtra(DownloadService.EXTRA_JS_ENABLED, jsSwitch.isChecked());
                                                    svc.putStringArrayListExtra(DownloadService.EXTRA_LOADED_RESOURCES, new ArrayList<>(loadedResources));
                                                    svc.putExtra(EXTRA_SESSION_ID, currentSessionId);
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                        startForegroundService(svc);
                                                    } else {
                                                        startService(svc);
                                                    }
                                                } else {
                                                    runOnUiThread(() -> {
                                                        if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
                                                        Toast.makeText(pagedl.this, "保存完了：\n" + archiveValue, Toast.LENGTH_LONG).show();
                                                        clearCacheAndCookies();
                                                    });
                                                    isSaving.set(false);
                                                }
                                            });
                                        }
                                    });
                                }
                            });
                        }
                    });
                }
            });
        } catch (final Exception e) {
            if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
            Toast.makeText(this, "初期化エラー: " + e.getMessage(), Toast.LENGTH_LONG).show();
            isSaving.set(false);
        }
    }

    private void captureManualHtml(String urlString, File outputDir) {
        webView.evaluateJavascript("(function() { return ('<html>'+document.getElementsByTagName('html')[0].innerHTML+'</html>'); })();", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String html) {
                if (html != null && !"null".equals(html)) {
                    html = html.replaceAll("^\"|\"$", "");
                    File htmlFile = new File(outputDir, "page_manual.html");
                    try {
                        Utils.writeStringToFile(htmlFile, html);

                        final Intent svc = new Intent(pagedl.this, DownloadService.class);
                        svc.putExtra(DownloadService.EXTRA_ARCHIVE_PATH, (String) null);
                        svc.putExtra(DownloadService.EXTRA_HTML_PATH, htmlFile.getAbsolutePath());
                        svc.putExtra(DownloadService.EXTRA_OUTPUT_DIR, outputDir.getAbsolutePath());
                        svc.putExtra(DownloadService.EXTRA_SAVE_RESOURCES, resourceSwitch.isChecked());
                        svc.putExtra(DownloadService.EXTRA_PC_UA, pcUaSwitch.isChecked());
                        svc.putExtra(DownloadService.EXTRA_BASE_URL, urlString);
                        svc.putExtra(DownloadService.EXTRA_REFERER, urlString);
                        svc.putExtra(DownloadService.EXTRA_JS_ENABLED, jsSwitch.isChecked());
                        svc.putStringArrayListExtra(DownloadService.EXTRA_LOADED_RESOURCES, new ArrayList<>(loadedResources));
                        svc.putExtra(EXTRA_SESSION_ID, currentSessionId);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(svc);
                        } else {
                            startService(svc);
                        }

                        if (!resourceSwitch.isChecked()) {
                            handler.post(() -> {
                                if (progressDialog != null) progressDialog.setMessage("ダウンロード中です…");
                            });
                            isSaving.set(false);
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Manual HTML save failed", e);
                        runOnUiThread(() -> {
                            if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
                            Toast.makeText(pagedl.this, "Manual HTML 保存失敗", Toast.LENGTH_LONG).show();
                        });
                        isSaving.set(false);
                    }
                } else {
                    runOnUiThread(() -> {
                        if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
                        Toast.makeText(pagedl.this, "HTML キャプチャ失敗", Toast.LENGTH_LONG).show();
                    });
                    isSaving.set(false);
                }
            }
        });
    }

    private File createOutputDirectory(final String siteName) {
        final String datetime = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        final File baseDir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "page");
        final File outputDir = new File(baseDir, datetime + "_" + siteName);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            runOnUiThread(() -> Toast.makeText(pagedl.this, "フォルダ作成失敗: " + outputDir.getAbsolutePath(), Toast.LENGTH_LONG).show());
            return null;
        }
        return outputDir;
    }

    private void saveSiteInfo(final String urlString, final File outputDir) {
        try {
            final URL url = new URL(urlString);
            final StringBuilder info = new StringBuilder();
            info.append("URL: ").append(urlString).append("\n");
            info.append("Timestamp: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).append("\n");

            info.append("JavaScript Enabled: ").append(jsSwitch.isChecked()).append("\n");
            info.append("Resource Saving: ").append(resourceSwitch.isChecked()).append("\n");
            info.append("PC User Agent: ").append(pcUaSwitch.isChecked()).append("\n");

            try {
                final InetAddress[] addresses = InetAddress.getAllByName(url.getHost());
                info.append("IP Addresses:\n");
                for (final InetAddress address : addresses) {
                    info.append("  - ").append(address.getHostAddress()).append("\n");
                }
            } catch (final UnknownHostException e) {
                info.append("IP Addresses: Unable to resolve\n");
            }

            try {
                final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", pcUaSwitch.isChecked() ? PC_USER_AGENT : WebSettings.getDefaultUserAgent(this));
                conn.setRequestProperty("Accept", ACCEPT_HEADER);
                conn.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE);
                conn.connect();
                info.append("HTTP Headers:\n");
                for (final Map.Entry<String, java.util.List<String>> header : conn.getHeaderFields().entrySet()) {
                    if (header.getKey() != null) {
                        info.append("  ").append(header.getKey()).append(": ").append(header.getValue()).append("\n");
                    }
                }
                conn.disconnect();
            } catch (final IOException e) {
                info.append("HTTP Headers: Unable to retrieve\n");
            }

            info.append("Open Ports:\n");
            final int[] commonPorts = {21, 22, 80, 443, 8080, 3306, 5432};
            for (final int port : commonPorts) {
                try (final Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(url.getHost(), port), 2000);
                    info.append("  - Port ").append(port).append(": Open\n");
                } catch (final IOException e) {
                    info.append("  - Port ").append(port).append(": Closed\n");
                }
            }

            try {
                info.append("Host Name: ").append(url.getHost()).append("\n");
                info.append("Protocol: ").append(url.getProtocol()).append("\n");
                info.append("Port: ").append(url.getPort() == -1 ? "Default" : String.valueOf(url.getPort())).append("\n");
            } catch (Exception e) {
                info.append("Additional URL Info: Unable to retrieve\n");
            }

            final File infoFile = new File(outputDir, new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + "_info.txt");
            Utils.writeStringToFile(infoFile, info.toString());
        } catch (final Exception e) {
            Log.w(TAG, "Failed to save site info", e);
        }
    }

    private void saveDataUrlContent(final String dataUrl, final File outputDir, final String baseName) {
        try {
            if (getAvailableStorage() < MIN_STORAGE_THRESHOLD) {
                throw new IOException("ストレージ容量不足");
            }
            final int commaIndex = dataUrl.indexOf(",");
            if (commaIndex == -1) {
                throw new IOException("data URL の形式が不正です");
            }
            final String header = dataUrl.substring(0, commaIndex);
            String payload = dataUrl.substring(commaIndex + 1);
            String mimeType = "application/octet-stream";
            boolean isBase64 = false;
            final Pattern pattern = Pattern.compile("data:([^;,]+)(;base64)?");
            final Matcher matcher = pattern.matcher(header);
            if (matcher.find()) {
                mimeType = matcher.group(1);
                isBase64 = matcher.group(2) != null;
            }
            final String extension = getExtensionForMimeType(mimeType);
            final String fileName = baseName + extension;
            final File outFile = new File(outputDir, fileName);
            byte[] data;
            if (isBase64) {
                data = Base64.decode(payload, Base64.DEFAULT);
            } else {
                data = URLDecoder.decode(payload, "UTF-8").getBytes(StandardCharsets.UTF_8);
            }
            try (final FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(data);
            }
            runOnUiThread(() -> {
                if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
                Toast.makeText(pagedl.this, "保存完了：\n" + outFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                clearCacheAndCookies();
            });
        } catch (final Exception e) {
            runOnUiThread(() -> {
                if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
                Toast.makeText(pagedl.this, "Data URL 保存エラー: " + e.getMessage(), Toast.LENGTH_LONG).show();
            });
        } finally {
            isSaving.set(false);
        }
    }

    private String getExtensionForMimeType(final String mimeType) {
        if (mimeType == null) return "";
        switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "text/html":
                return ".html";
            case "text/css":
                return ".css";
            case "application/javascript":
            case "text/javascript":
                return ".js";
            case "application/json":
                return ".json";
            case "image/jpeg":
            case "image/jpg":
                return ".jpg";
            case "image/png":
                return ".png";
            case "image/gif":
                return ".gif";
            case "image/webp":
                return ".webp";
            case "image/svg+xml":
                return ".svg";
            case "application/pdf":
                return ".pdf";
            default:
                return "";
        }
    }

    private void clearCacheAndCookies() {
        runOnUiThread(() -> {
            webView.clearCache(true);
            final CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.removeAllCookies(null);
            cookieManager.flush();
        });
    }

    public static class DownloadService extends Service {
        public static final String EXTRA_ARCHIVE_PATH = "extra_archive_path";
        public static final String EXTRA_HTML_PATH = "extra_html_path";
        public static final String EXTRA_OUTPUT_DIR = "extra_output_dir";
        public static final String EXTRA_SAVE_RESOURCES = "extra_save_resources";
        public static final String EXTRA_PC_UA = "extra_pc_ua";
        public static final String EXTRA_BASE_URL = "extra_base_url";
        public static final String EXTRA_REFERER = "extra_referer";
        public static final String EXTRA_JS_ENABLED = "extra_js_enabled";
        public static final String EXTRA_LOADED_RESOURCES = "extra_loaded_resources";
        public static final String ACTION_STOP = "com.coara.pagedl.ACTION_STOP";

        private static final String CHANNEL_ID = "pagedl_download_channel";
        private static final int NOTIF_ID = 0x1453;
        private static final int MAX_RETRIES = 3;
        private static final long RETRY_BACKOFF_MS = 600L;
        private final ExecutorService executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private Future<?> currentTask;

        private boolean pcUa;
        private String referer;
        private long serviceSessionId = -1L;
        private final Map<String, String> urlToLocalPath = new ConcurrentHashMap<>();

        @Override
        public void onCreate() {
            super.onCreate();
            createNotificationChannel();
        }

        @Override
        public int onStartCommand(final Intent intent, final int flags, final int startId) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    sendError("通知権限がありません");
                    stopSelf();
                    return START_NOT_STICKY;
                }
            }
            if (intent == null) {
                return START_NOT_STICKY;
            }
            final String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopped.set(true);
                if (currentTask != null && !currentTask.isDone()) {
                    currentTask.cancel(true);
                }
                final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(NOTIF_ID);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
                executor.shutdownNow();
                sendError("処理がキャンセルされました\nアプリを終了します。");
                stopSelf();
                return START_NOT_STICKY;
            }

            final String archivePath = intent.getStringExtra(EXTRA_ARCHIVE_PATH);
            final String htmlPath = intent.getStringExtra(EXTRA_HTML_PATH);
            final String outputDir = intent.getStringExtra(EXTRA_OUTPUT_DIR);
            final boolean saveResources = intent.getBooleanExtra(EXTRA_SAVE_RESOURCES, false);
            this.pcUa = intent.getBooleanExtra(EXTRA_PC_UA, false);
            final boolean jsEnabled = intent.getBooleanExtra(EXTRA_JS_ENABLED, true);
            final String baseUrl = intent.getStringExtra(EXTRA_BASE_URL);
            this.referer = intent.getStringExtra(EXTRA_REFERER);
            final ArrayList<String> loadedResourcesList = intent.getStringArrayListExtra(EXTRA_LOADED_RESOURCES);

            serviceSessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L);

            final File outDirFile = new File(outputDir != null ? outputDir : getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) + "/page/default");
            final Intent stopIntent = new Intent(this, DownloadService.class);
            stopIntent.setAction(ACTION_STOP);
            final int pendingFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
            final PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent, pendingFlags);

            final NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("ページ保存処理を実行中")
                    .setContentText(outDirFile.getAbsolutePath())
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .addAction(new NotificationCompat.Action(android.R.drawable.ic_delete, "終了", stopPending))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);

            startForeground(NOTIF_ID, nb.build());

            currentTask = executor.submit(() -> {
                try {
                    sendProgress("処理開始...", false);
                    String mainHtmlNoJs = null;
                    String mainHtmlJs = null;
                    final Set<String> allResources = new LinkedHashSet<>();

                    if (archivePath != null) {
                        sendProgress("アーカイブを抽出中...", false);
                        final File archiveFile = new File(archivePath);
                        final String mhtContent = Utils.readFileToString(archiveFile);
                        mainHtmlJs = MimeParser.getMainHtml(mhtContent);
                        if (mainHtmlJs == null || mainHtmlJs.isEmpty()) {
                            throw new IOException("Main HTML extraction failed");
                        }
                        final File jsHtmlFile = new File(outDirFile, "page_js.html");
                        Utils.writeStringToFile(jsHtmlFile, mainHtmlJs);

                        allResources.addAll(Utils.extractResources(mainHtmlJs, baseUrl));

                        final Map<String, byte[]> mhtResources = MimeParser.extractResources(mhtContent, outDirFile);
                        final File mhtResDir = new File(outDirFile, "mht_resources");
                        if (!mhtResources.isEmpty() && (mhtResDir.exists() || mhtResDir.mkdirs())) {
                            for (Map.Entry<String, byte[]> entry : mhtResources.entrySet()) {
                                try {
                                    File f = new File(mhtResDir, entry.getKey());
                                    try (FileOutputStream fos = new FileOutputStream(f)) {
                                        fos.write(entry.getValue());
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }

                    if (htmlPath != null) {
                        File htmlFile = new File(htmlPath);
                        if (htmlFile.exists()) {
                            mainHtmlNoJs = Utils.readFileToString(htmlFile);
                            allResources.addAll(Utils.extractResources(mainHtmlNoJs, baseUrl));
                        }
                    }

                    if (loadedResourcesList != null) {
                        for (String s : loadedResourcesList) {
                            if (s == null || s.isEmpty()) continue;
                            try {
                                String resolved = Utils.resolveUrl(s, baseUrl);
                                if (resolved != null) allResources.add(resolved);
                            } catch (Exception ignored) {
                            }
                        }
                    }

                    if (saveResources && !stopped.get()) {
                        sendProgress("リソース抽出・ダウンロード中...", true);
                        Set<String> additional = recursiveExtractFromResources(allResources, baseUrl, new HashSet<>());
                        allResources.addAll(additional);

                        Set<String> resolvedResources = new LinkedHashSet<>();
                        for (String res : allResources) {
                            try {
                                String resolved = Utils.resolveUrl(res, baseUrl);
                                if (resolved != null) {
                                    resolvedResources.add(resolved);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        saveResourcesToFolderFromSet(resolvedResources, outDirFile, baseUrl);

                        writeResourceIndex(outDirFile);
                    }

                    sendComplete(outDirFile.getAbsolutePath());
                } catch (final Exception e) {
                    if (!stopped.get()) {
                        Log.e(TAG, "Service error", e);
                        sendError(e.getMessage());
                    }
                } finally {
                    final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.cancel(NOTIF_ID);
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(Service.STOP_FOREGROUND_REMOVE);
                    } else {
                        stopForeground(true);
                    }
                    executor.shutdownNow();
                    stopSelf();
                }
            });

            return START_NOT_STICKY;
        }

        private void writeResourceIndex(final File outputDir) {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("{\n");
                boolean first = true;
                for (Map.Entry<String, String> e : urlToLocalPath.entrySet()) {
                    if (!first) sb.append(",\n");
                    first = false;
                    sb.append("  ").append(jsonEscape(e.getKey())).append(": ").append(jsonEscape(e.getValue()));
                }
                sb.append("\n}\n");
                Utils.writeStringToFile(new File(outputDir, "resource_index.json"), sb.toString());
            } catch (Exception ignored) {
            }
        }

        private String jsonEscape(String s) {
            if (s == null) return "null";
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append("\"");
            return sb.toString();
        }

        @Nullable
        @Override
        public IBinder onBind(final Intent intent) {
            return new Binder();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            stopped.set(true);
            if (!executor.isShutdown()) {
                executor.shutdownNow();
            }
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    Log.w(TAG, "Executor did not terminate in time");
                }
            } catch (final InterruptedException ignored) {
            }
            final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIF_ID);
            }
        }

        private void createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                final NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Page Download", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("ページ保存の進行状況を表示します");
                if (nm != null) {
                    nm.createNotificationChannel(ch);
                }
            }
        }

        private void sendProgress(final String message, final boolean isResourceProgress) {
            if (stopped.get()) {
                return;
            }
            final Intent i = new Intent(ACTION_DOWNLOAD_PROGRESS);
            i.putExtra("message", message);
            i.putExtra("resource_progress", isResourceProgress);
            i.putExtra(EXTRA_SESSION_ID, serviceSessionId);
            sendBroadcast(i);

            final Intent stopIntent = new Intent(this, DownloadService.class);
            stopIntent.setAction(ACTION_STOP);
            final int pendingFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
            final PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent, pendingFlags);
            final NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("ページ保存中")
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .addAction(new NotificationCompat.Action(android.R.drawable.ic_delete, "終了", stopPending))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, nb.build());
            }
        }

        private void sendComplete(final String path) {
            final Intent i = new Intent(ACTION_DOWNLOAD_COMPLETE);
            i.putExtra("outputPath", path);
            i.putExtra(EXTRA_SESSION_ID, serviceSessionId);
            sendBroadcast(i);
            final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIF_ID);
            }
        }

        private void sendError(final String err) {
            final Intent i = new Intent(ACTION_DOWNLOAD_ERROR);
            i.putExtra("error", err);
            i.putExtra(EXTRA_SESSION_ID, serviceSessionId);
            sendBroadcast(i);
            final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIF_ID);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            executor.shutdownNow();
            stopSelf();
        }

        private Set<String> recursiveExtractFromResources(final Set<String> initialResources, final String baseUrl, final Set<String> visited) {
            final Set<String> additional = new LinkedHashSet<>();
            for (final String resUrl : initialResources) {
                if (visited.contains(resUrl) || stopped.get()) {
                    continue;
                }
                visited.add(resUrl);
                if (!checkStorage()) {
                    return new LinkedHashSet<>();
                }
                final byte[] data = downloadResourceBytes(resUrl);
                if (data == null) {
                    continue;
                }
                if (Utils.isBinaryByMagic(data, 0, data.length)) {
                    continue;
                }
                String contentType = getContentTypeFromUrl(resUrl);
                String charset = Utils.detectCharset(data, contentType);
                String content;
                try {
                    content = new String(data, Charset.forName(charset));
                } catch (Exception e) {
                    content = new String(data, StandardCharsets.UTF_8);
                }
                Set<String> subResources = new LinkedHashSet<>();
                final String lowerUrl = resUrl.toLowerCase(Locale.ROOT);
                final String lowerCt = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
                final boolean isCss = lowerCt.contains("css") || lowerUrl.contains(".css");
                final boolean isJs = lowerCt.contains("javascript") || lowerCt.contains("ecmascript") || lowerUrl.endsWith(".js") || lowerUrl.contains(".js?");
                final boolean isHtml = lowerCt.contains("html") || lowerUrl.endsWith(".html") || lowerUrl.endsWith(".htm") || lowerUrl.endsWith(".xhtml");
                final boolean isJson = lowerCt.contains("json") || lowerUrl.endsWith(".json");
                final boolean isM3u8 = lowerCt.contains("mpegurl") || lowerUrl.endsWith(".m3u8");
                final boolean isMpd = lowerCt.contains("dash+xml") || lowerUrl.endsWith(".mpd");
                final boolean isSvg = lowerCt.contains("svg") || lowerUrl.endsWith(".svg");
                final boolean isXml = lowerCt.contains("xml") || lowerUrl.endsWith(".xml");

                if (isCss) {
                    subResources = Utils.extractResourcesFromCss(content, resUrl);
                } else if (isJs) {
                    subResources = Utils.extractResourcesFromJs(content, resUrl);
                    String sourceMapUrl = Utils.extractSourceMappingUrl(content, resUrl);
                    if (sourceMapUrl != null) subResources.add(sourceMapUrl);
                } else if (isHtml || isSvg || isXml) {
                    subResources = Utils.extractResources(content, resUrl);
                } else if (isJson) {
                    subResources = Utils.extractResourcesFromJson(content, resUrl);
                } else if (isM3u8) {
                    subResources = Utils.extractResourcesFromM3u8(content, resUrl);
                } else if (isMpd) {
                    subResources = Utils.extractResourcesFromMpd(content, resUrl);
                } else if (lowerCt.isEmpty() && !lowerUrl.contains(".")) {
                    subResources = Utils.extractResourcesFromJs(content, resUrl);
                }

                additional.addAll(subResources);
                additional.addAll(recursiveExtractFromResources(subResources, baseUrl, visited));
            }
            return additional;
        }

        private String getContentTypeFromUrl(final String urlString) {
            HttpURLConnection conn = null;
            try {
                final URL url = new URL(urlString);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("HEAD");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", pcUa ? PC_USER_AGENT : WebSettings.getDefaultUserAgent(this));
                conn.setRequestProperty("Accept", ACCEPT_HEADER);
                conn.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE);
                if (referer != null) conn.setRequestProperty("Referer", referer);
                conn.connect();
                final String contentType = conn.getContentType();
                return contentType != null ? contentType : "";
            } catch (final Exception e) {
                return "";
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private boolean checkStorage() {
            final File path = Environment.getExternalStorageDirectory();
            return path.getFreeSpace() >= MIN_STORAGE_THRESHOLD;
        }

        private void saveResourcesToFolderFromSet(final Set<String> resources, final File outputDir, final String baseUrl) {
            if (resources.isEmpty()) {
                return;
            }
            final String datetime = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            final File resourceDir = new File(outputDir, datetime + "_resources_" + baseUrl.replaceAll("[^a-zA-Z0-9]", "_"));
            if (!resourceDir.mkdirs() && !resourceDir.exists()) {
                sendError("リソースフォルダ作成失敗");
                return;
            }
            sendProgress("リソース保存中です", true);

            final Map<String, Integer> fileNameCounts = new HashMap<>();
            ArrayList<Future<?>> downloadFutures = new ArrayList<>();
            for (final String resUrl : resources) {
                if (stopped.get() || !checkStorage()) {
                    for (Future<?> future : downloadFutures) {
                        future.cancel(true);
                    }
                    cleanupIncompleteFiles(resourceDir);
                    return;
                }
                final Future<?> future = executor.submit(() -> {
                    try {
                        URL resourceUrl = new URL(resUrl);
                        String path = resourceUrl.getPath();
                        if (path == null || path.isEmpty()) path = "/";
                        String decoded = path;
                        try {
                            decoded = URLDecoder.decode(path, "UTF-8");
                        } catch (Exception ignored) {
                        }
                        String fileName = new File(decoded).getName();
                        if (fileName.isEmpty()) {
                            fileName = "index_" + Math.abs(resUrl.hashCode());
                        }
                        fileName = fileName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
                        if (fileName.length() > 180) {
                            fileName = fileName.substring(0, 180);
                        }
                        String baseName = fileName;
                        if (!baseName.contains(".")) {
                            String ct = getContentTypeFromUrl(resUrl);
                            String ext = Utils.getExtensionFromContentType(ct);
                            if (ext != null && !ext.isEmpty()) {
                                fileName = fileName + ext;
                            }
                        }
                        synchronized (fileNameCounts) {
                            Integer count = fileNameCounts.get(fileName);
                            if (count == null) {
                                count = 0;
                            }
                            count++;
                            fileNameCounts.put(fileName, count);
                            if (count > 1) {
                                fileName = count + "_" + fileName;
                            }
                        }
                        final File destFile = new File(resourceDir, fileName);
                        downloadResource(resUrl, destFile);
                        if (destFile.exists() && destFile.length() > 0) {
                            urlToLocalPath.put(resUrl, destFile.getName());
                        }
                    } catch (final Exception e) {
                        Log.w(TAG, "リソースダウンロードエラー: " + resUrl, e);
                    }
                });
                downloadFutures.add(future);
            }

            for (Future<?> future : downloadFutures) {
                try {
                    future.get();
                } catch (Exception e) {
                    Log.w(TAG, "Download future error", e);
                }
            }
        }

        private void cleanupIncompleteFiles(final File directory) {
            if (directory.exists() && directory.isDirectory()) {
                final File[] files = directory.listFiles();
                if (files != null) {
                    for (final File file : files) {
                        if (file.isDirectory()) {
                            cleanupIncompleteFiles(file);
                        } else if (!file.delete()) {
                            Log.w(TAG, "Failed to delete incomplete file: " + file.getAbsolutePath());
                        }
                    }
                }
            }
        }

        private byte[] downloadResourceBytes(final String resourceUrl) {
            return downloadResourceBytes(resourceUrl, pcUa, referer, MAX_RETRIES);
        }

        private byte[] downloadResourceBytes(final String resourceUrl, final boolean usePcUa, final String ref, int retries) {
            for (int attempt = 0; attempt <= retries; attempt++) {
                HttpURLConnection conn = null;
                try {
                    final URL url = new URL(resourceUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    conn.setRequestProperty("User-Agent", usePcUa ? PC_USER_AGENT : WebSettings.getDefaultUserAgent(this));
                    conn.setRequestProperty("Accept", ACCEPT_HEADER);
                    conn.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE);
                    conn.setRequestProperty("Accept-Encoding", ACCEPT_ENCODING);
                    if (ref != null) conn.setRequestProperty("Referer", ref);
                    final int responseCode = conn.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_NOT_FOUND ||
                            responseCode == HttpURLConnection.HTTP_FORBIDDEN ||
                            responseCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
                            responseCode == HttpURLConnection.HTTP_GONE) {
                        return null;
                    }
                    if (responseCode >= 500 && attempt < retries) {
                        conn.disconnect();
                        conn = null;
                        sleepQuiet(RETRY_BACKOFF_MS * (attempt + 1));
                        continue;
                    }
                    if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                        return null;
                    }
                    final String encoding = conn.getContentEncoding();
                    final InputStream in = wrapStream(conn.getInputStream(), encoding);
                    try (final InputStream input = in;
                         final ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        final byte[] buffer = new byte[BUFFER_SIZE];
                        int bytesRead;
                        while ((bytesRead = input.read(buffer)) != -1) {
                            if (stopped.get()) {
                                throw new ClosedByInterruptException();
                            }
                            if (!checkStorage()) {
                                return null;
                            }
                            baos.write(buffer, 0, bytesRead);
                        }
                        return baos.toByteArray();
                    }
                } catch (final ClosedByInterruptException e) {
                    Log.w(TAG, "Download interrupted: " + resourceUrl);
                    return null;
                } catch (final Exception e) {
                    if (attempt >= retries) {
                        Log.w(TAG, "ダウンロードエラー: " + resourceUrl, e);
                        return null;
                    }
                    sleepQuiet(RETRY_BACKOFF_MS * (attempt + 1));
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
            return null;
        }

        private InputStream wrapStream(final InputStream in, final String encoding) throws IOException {
            if (encoding == null) return in;
            final String enc = encoding.toLowerCase(Locale.ROOT);
            if (enc.contains("gzip")) return new GZIPInputStream(in);
            if (enc.contains("deflate")) return new InflaterInputStream(in);
            return in;
        }

        private void sleepQuiet(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void downloadResource(final String resourceUrl, final File destination) throws IOException {
            if (destination.exists() && destination.length() > 0) {
                return;
            }
            if (stopped.get() || !checkStorage()) {
                return;
            }
            final File parent = destination.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Failed to create directory: " + parent);
            }
            final File tempFile = new File(destination.getAbsolutePath() + ".tmp");
            HttpURLConnection conn = null;
            int attempts = 0;
            IOException lastError = null;
            while (attempts <= MAX_RETRIES) {
                try {
                    final URL url = new URL(resourceUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    conn.setRequestProperty("User-Agent", pcUa ? PC_USER_AGENT : WebSettings.getDefaultUserAgent(this));
                    conn.setRequestProperty("Accept", ACCEPT_HEADER);
                    conn.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE);
                    conn.setRequestProperty("Accept-Encoding", ACCEPT_ENCODING);
                    if (referer != null) conn.setRequestProperty("Referer", referer);
                    final int code = conn.getResponseCode();
                    if (code == HttpURLConnection.HTTP_NOT_FOUND ||
                            code == HttpURLConnection.HTTP_FORBIDDEN ||
                            code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                            code == HttpURLConnection.HTTP_GONE) {
                        return;
                    }
                    if (code >= 500) {
                        throw new IOException("Server error: " + code);
                    }
                    if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                        return;
                    }
                    InputStream in = wrapStream(conn.getInputStream(), conn.getContentEncoding());
                    try (final OutputStream out = new FileOutputStream(tempFile)) {
                        final byte[] buffer = new byte[BUFFER_SIZE];
                        int bytesRead;
                        long totalBytes = 0;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            if (stopped.get()) {
                                throw new ClosedByInterruptException();
                            }
                            totalBytes += bytesRead;
                            if (totalBytes > MIN_STORAGE_THRESHOLD / 2) {
                                if (!checkStorage()) {
                                    throw new IOException("ストレージ容量不足");
                                }
                                totalBytes = 0;
                            }
                            out.write(buffer, 0, bytesRead);
                        }
                    } finally {
                        try { in.close(); } catch (Exception ignored) {}
                    }
                    if (!tempFile.renameTo(destination)) {
                        if (destination.exists()) {
                            destination.delete();
                        }
                        if (!tempFile.renameTo(destination)) {
                            throw new IOException("rename failed");
                        }
                    }
                    return;
                } catch (final ClosedByInterruptException e) {
                    if (tempFile.exists() && !tempFile.delete()) {
                        Log.w(TAG, "Failed to delete temp file: " + tempFile.getAbsolutePath());
                    }
                    throw e;
                } catch (final IOException e) {
                    lastError = e;
                    attempts++;
                    if (attempts > MAX_RETRIES) break;
                    sleepQuiet(RETRY_BACKOFF_MS * attempts);
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
            if (tempFile.exists() && !tempFile.delete()) {
                Log.w(TAG, "Failed to delete temp file: " + tempFile.getAbsolutePath());
            }
            if (lastError != null) {
                Log.w(TAG, "Download failed after retries: " + resourceUrl + " - " + lastError.getMessage());
            }
        }
    }

    static class Utils {
        public static String readFileToString(final File file) throws IOException {
            final byte[] buffer = new byte[(int) file.length()];
            try (final FileInputStream fis = new FileInputStream(file)) {
                int offset = 0;
                int read;
                while (offset < buffer.length && (read = fis.read(buffer, offset, buffer.length - offset)) != -1) {
                    offset += read;
                }
                if (offset != buffer.length) {
                    throw new IOException("ファイル全体の読み込みに失敗しました");
                }
            }
            String charset = detectCharset(buffer, "");
            try {
                return new String(buffer, Charset.forName(charset));
            } catch (Exception e) {
                return new String(buffer, StandardCharsets.UTF_8);
            }
        }

        public static void writeStringToFile(final File file, final String content) throws IOException {
            final File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("parent mkdirs failed");
            }
            try (final FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        }

        public static String detectCharset(byte[] data, String contentType) {
            if (contentType != null) {
                Matcher m = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([a-zA-Z0-9_\\-]+)").matcher(contentType);
                if (m.find()) {
                    try {
                        Charset.forName(m.group(1));
                        return m.group(1);
                    } catch (Exception ignored) {
                    }
                }
            }
            if (data != null && data.length >= 3) {
                if ((data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
                    return "UTF-8";
                }
                if ((data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
                    return "UTF-16BE";
                }
                if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
                    return "UTF-16LE";
                }
            }
            if (data != null && data.length > 0) {
                int scan = Math.min(data.length, 4096);
                String head = new String(data, 0, scan, StandardCharsets.ISO_8859_1);
                Matcher m = Pattern.compile("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([a-zA-Z0-9_\\-]+)").matcher(head);
                if (m.find()) {
                    try {
                        Charset.forName(m.group(1));
                        return m.group(1);
                    } catch (Exception ignored) {
                    }
                }
                Matcher m2 = Pattern.compile("(?i)<\\?xml[^>]+encoding\\s*=\\s*[\"']([a-zA-Z0-9_\\-]+)").matcher(head);
                if (m2.find()) {
                    try {
                        Charset.forName(m2.group(1));
                        return m2.group(1);
                    } catch (Exception ignored) {
                    }
                }
            }
            return "UTF-8";
        }

        public static boolean isBinaryByMagic(byte[] data, int offset, int length) {
            if (data == null || length < 4) return false;
            int b0 = data[offset] & 0xFF;
            int b1 = data[offset + 1] & 0xFF;
            int b2 = data[offset + 2] & 0xFF;
            int b3 = data[offset + 3] & 0xFF;
            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true;
            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return true;
            if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return true;
            if (b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46) return true;
            if (b0 == 0x50 && b1 == 0x4B && (b2 == 0x03 || b2 == 0x05 || b2 == 0x07)) return true;
            if (b0 == 0x1F && b1 == 0x8B) return true;
            if (b0 == 0x42 && b1 == 0x5A && b2 == 0x68) return true;
            if (b0 == 0x37 && b1 == 0x7A && b2 == 0xBC && b3 == 0xAF) return true;
            if (b0 == 0xFD && b1 == 0x37 && b2 == 0x7A && b3 == 0x58) return true;
            if (b0 == 0x00 && b1 == 0x00 && b2 == 0x01 && b3 == 0x00) return true;
            if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) return true;
            if (b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53) return true;
            if (b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43) return true;
            if (b0 == 0x49 && b1 == 0x44 && b2 == 0x33) return true;
            return false;
        }

        public static String resolveUrl(String originalUrl, String baseUrl) {
            if (originalUrl == null || originalUrl.isEmpty()) return null;
            String trimmed = originalUrl.trim();
            if (trimmed.isEmpty()) return null;
            if (trimmed.startsWith("data:") || trimmed.startsWith("blob:") ||
                    trimmed.startsWith("javascript:") || trimmed.startsWith("mailto:") ||
                    trimmed.startsWith("tel:") || trimmed.startsWith("about:") ||
                    trimmed.startsWith("#")) {
                return null;
            }
            if (trimmed.startsWith("//")) {
                try {
                    URL base = new URL(baseUrl);
                    return base.getProtocol() + ":" + trimmed;
                } catch (Exception e) {
                    return "https:" + trimmed;
                }
            }
            try {
                URL base = new URL(baseUrl);
                URL resolved = new URL(base, trimmed);
                return resolved.toString();
            } catch (Exception e) {
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    return trimmed;
                }
                return null;
            }
        }

        public static void addResourceIfValid(final Set<String> resources, final String originalUrl, final String baseUrl) {
            if (originalUrl == null || originalUrl.isEmpty()) return;
            String trimmed = originalUrl.trim();
            if (trimmed.isEmpty()) return;
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("data:") || lower.startsWith("blob:") ||
                    lower.startsWith("#") || lower.startsWith("javascript:") ||
                    lower.startsWith("mailto:") || lower.startsWith("tel:") ||
                    lower.startsWith("about:") || lower.startsWith("chrome:") ||
                    lower.startsWith("file:") || lower.startsWith("ws:") ||
                    lower.startsWith("wss:") || lower.startsWith("ftp:")) {
                return;
            }
            String resolved = resolveUrl(trimmed, baseUrl);
            if (resolved == null) return;
            String rl = resolved.toLowerCase(Locale.ROOT);
            if (!rl.startsWith("http://") && !rl.startsWith("https://")) return;
            resources.add(resolved);
        }

        public static boolean isResourceType(final String url) {
            if (url == null) return false;
            String path = url;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            int h = path.indexOf('#');
            if (h >= 0) path = path.substring(0, h);
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            String lower = name.toLowerCase(Locale.ROOT);
            int dot = lower.lastIndexOf('.');
            if (dot < 0) return false;
            String ext = lower.substring(dot + 1);
            switch (ext) {
                case "js":
                case "mjs":
                case "cjs":
                case "jsx":
                case "tsx":
                case "css":
                case "scss":
                case "sass":
                case "less":
                case "html":
                case "htm":
                case "xhtml":
                case "shtml":
                case "php":
                case "asp":
                case "aspx":
                case "jsp":
                case "png":
                case "jpg":
                case "jpeg":
                case "gif":
                case "webp":
                case "avif":
                case "bmp":
                case "ico":
                case "svg":
                case "svgz":
                case "tif":
                case "tiff":
                case "heic":
                case "heif":
                case "jxl":
                case "mp3":
                case "wav":
                case "ogg":
                case "oga":
                case "flac":
                case "aac":
                case "m4a":
                case "opus":
                case "weba":
                case "mp4":
                case "m4v":
                case "webm":
                case "mkv":
                case "mov":
                case "avi":
                case "ogv":
                case "ts":
                case "m4s":
                case "mpd":
                case "m3u8":
                case "m3u":
                case "pdf":
                case "doc":
                case "docx":
                case "xls":
                case "xlsx":
                case "ppt":
                case "pptx":
                case "json":
                case "xml":
                case "rss":
                case "atom":
                case "xsl":
                case "xslt":
                case "ini":
                case "conf":
                case "cfg":
                case "toml":
                case "yaml":
                case "yml":
                case "txt":
                case "md":
                case "markdown":
                case "py":
                case "rb":
                case "go":
                case "rs":
                case "c":
                case "cpp":
                case "h":
                case "hpp":
                case "java":
                case "kt":
                case "swift":
                case "cs":
                case "sh":
                case "bat":
                case "ps1":
                case "pl":
                case "lua":
                case "m":
                case "mm":
                case "ttf":
                case "otf":
                case "woff":
                case "woff2":
                case "eot":
                case "wasm":
                case "map":
                case "apk":
                case "aab":
                case "ipa":
                case "exe":
                case "dmg":
                case "deb":
                case "rpm":
                case "zip":
                case "rar":
                case "7z":
                case "tar":
                case "gz":
                case "bz2":
                case "xz":
                case "zst":
                case "vtt":
                case "srt":
                case "ass":
                case "sub":
                case "ics":
                case "vcf":
                case "torrent":
                case "webmanifest":
                case "appcache":
                case "manifest":
                    return true;
                default:
                    return false;
            }
        }

        public static String getExtensionFromContentType(String contentType) {
            if (contentType == null) return null;
            String ct = contentType.toLowerCase(Locale.ROOT);
            int semi = ct.indexOf(';');
            if (semi >= 0) ct = ct.substring(0, semi).trim();
            switch (ct) {
                case "text/html": return ".html";
                case "application/xhtml+xml": return ".xhtml";
                case "text/css": return ".css";
                case "application/javascript":
                case "text/javascript":
                case "application/x-javascript":
                case "application/ecmascript":
                case "text/ecmascript": return ".js";
                case "application/json":
                case "text/json": return ".json";
                case "application/xml":
                case "text/xml": return ".xml";
                case "image/jpeg": return ".jpg";
                case "image/png": return ".png";
                case "image/gif": return ".gif";
                case "image/webp": return ".webp";
                case "image/avif": return ".avif";
                case "image/svg+xml": return ".svg";
                case "image/x-icon":
                case "image/vnd.microsoft.icon": return ".ico";
                case "image/bmp": return ".bmp";
                case "image/tiff": return ".tiff";
                case "audio/mpeg": return ".mp3";
                case "audio/wav":
                case "audio/x-wav":
                case "audio/wave": return ".wav";
                case "audio/ogg": return ".ogg";
                case "audio/aac": return ".aac";
                case "audio/flac": return ".flac";
                case "audio/mp4":
                case "audio/x-m4a": return ".m4a";
                case "video/mp4": return ".mp4";
                case "video/webm": return ".webm";
                case "video/ogg": return ".ogv";
                case "video/x-matroska": return ".mkv";
                case "video/quicktime": return ".mov";
                case "video/mp2t": return ".ts";
                case "application/vnd.apple.mpegurl":
                case "application/x-mpegurl": return ".m3u8";
                case "application/dash+xml": return ".mpd";
                case "application/pdf": return ".pdf";
                case "application/zip": return ".zip";
                case "application/gzip": return ".gz";
                case "application/x-tar": return ".tar";
                case "application/x-7z-compressed": return ".7z";
                case "application/wasm": return ".wasm";
                case "font/ttf":
                case "application/x-font-ttf": return ".ttf";
                case "font/otf":
                case "application/x-font-opentype": return ".otf";
                case "font/woff":
                case "application/font-woff": return ".woff";
                case "font/woff2": return ".woff2";
                case "application/vnd.ms-fontobject": return ".eot";
                case "text/vtt": return ".vtt";
                case "application/manifest+json":
                case "application/webmanifest": return ".webmanifest";
                case "text/plain": return ".txt";
                default: return null;
            }
        }

        public static Set<String> extractResources(final String html, final String baseUrl) {
            final Set<String> resources = new LinkedHashSet<>();
            if (html == null) return resources;

            String effectiveBase = baseUrl;
            try {
                Matcher baseM = Pattern.compile("(?is)<base\\s+[^>]*href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").matcher(html);
                if (baseM.find()) {
                    String bh = baseM.group(1) != null ? baseM.group(1) : (baseM.group(2) != null ? baseM.group(2) : baseM.group(3));
                    String r = resolveUrl(bh, baseUrl);
                    if (r != null) effectiveBase = r;
                }
            } catch (Exception ignored) {
            }

            final String base = effectiveBase;

            final Pattern attrPattern = Pattern.compile(
                    "(?is)(src|href|data-src|data-lazy-src|data-lazy|data-original|data-srcset|data-lazy-srcset|data-original-set|data-bg|data-background|data-background-image|data-image|data-image-src|data-url|data-href|data-poster|data-video|data-thumb|data-thumbnail|poster|srcset|imagesrcset|background|content|xlink:href|xlinkHref)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))"
            );
            final Matcher matcher = attrPattern.matcher(html);
            while (matcher.find()) {
                String attr = matcher.group(1).toLowerCase(Locale.ROOT);
                String val = matcher.group(2);
                if (val == null) val = matcher.group(3);
                if (val == null) val = matcher.group(4);
                if (val == null) continue;
                val = val.trim();
                if (val.isEmpty()) continue;

                if (attr.contains("srcset")) {
                    addSrcsetResources(resources, val, base);
                } else if ("content".equals(attr)) {
                    if (val.startsWith("http://") || val.startsWith("https://") || val.startsWith("//") ||
                            val.startsWith("/") || val.startsWith("./") || val.startsWith("../")) {
                        String resolved = resolveUrl(val, base);
                        if (resolved != null && isResourceType(resolved)) {
                            resources.add(resolved);
                        }
                    }
                    Matcher urlInContent = Pattern.compile("(?i)url\\s*=\\s*['\"]?([^;'\"\\s]+)").matcher(val);
                    if (urlInContent.find()) {
                        addResourceIfValid(resources, urlInContent.group(1), base);
                    }
                } else {
                    addResourceIfValid(resources, val, base);
                }
            }

            final Pattern metaPattern = Pattern.compile(
                    "(?is)<meta\\s+[^>]*(?:property|name|itemprop)\\s*=\\s*['\"]([^'\"]+)['\"][^>]*content\\s*=\\s*['\"]([^'\"]*)['\"][^>]*>"
            );
            final Matcher metaMatcher = metaPattern.matcher(html);
            while (metaMatcher.find()) {
                String prop = metaMatcher.group(1).toLowerCase(Locale.ROOT);
                String content = metaMatcher.group(2);
                if (prop.contains("image") || prop.contains("video") || prop.contains("audio") ||
                        prop.contains("thumbnail") || prop.equals("og:url") || prop.equals("twitter:player")) {
                    addResourceIfValid(resources, content, base);
                }
            }

            final Pattern metaRev = Pattern.compile(
                    "(?is)<meta\\s+[^>]*content\\s*=\\s*['\"]([^'\"]*)['\"][^>]*(?:property|name|itemprop)\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>"
            );
            final Matcher metaRevM = metaRev.matcher(html);
            while (metaRevM.find()) {
                String prop = metaRevM.group(2).toLowerCase(Locale.ROOT);
                String content = metaRevM.group(1);
                if (prop.contains("image") || prop.contains("video") || prop.contains("audio") ||
                        prop.contains("thumbnail") || prop.equals("og:url") || prop.equals("twitter:player")) {
                    addResourceIfValid(resources, content, base);
                }
            }

            final Pattern metaRefresh = Pattern.compile(
                    "(?is)<meta\\s+[^>]*http-equiv\\s*=\\s*['\"]refresh['\"][^>]*content\\s*=\\s*['\"]([^'\"]*)['\"]"
            );
            final Matcher refreshM = metaRefresh.matcher(html);
            while (refreshM.find()) {
                String c = refreshM.group(1);
                Matcher urlM = Pattern.compile("(?i)url\\s*=\\s*['\"]?([^'\";]+)").matcher(c);
                if (urlM.find()) {
                    addResourceIfValid(resources, urlM.group(1).trim(), base);
                }
            }

            final Pattern stylePattern = Pattern.compile("(?i)url\\s*\\(\\s*[\"']?([^\"'\\)]+)[\"']?\\s*\\)");
            final Matcher styleMatcher = stylePattern.matcher(html);
            while (styleMatcher.find()) {
                addResourceIfValid(resources, styleMatcher.group(1), base);
            }

            final Pattern imageSetPattern = Pattern.compile("(?i)image-set\\s*\\(([^)]+)\\)");
            final Matcher imageSetMatcher = imageSetPattern.matcher(html);
            while (imageSetMatcher.find()) {
                String inner = imageSetMatcher.group(1);
                Matcher urlM = Pattern.compile("(?i)url\\s*\\(\\s*[\"']?([^\"'\\)]+)[\"']?\\s*\\)").matcher(inner);
                while (urlM.find()) {
                    addResourceIfValid(resources, urlM.group(1), base);
                }
            }

            final Pattern importPattern = Pattern.compile("(?i)@import\\s*(?:url\\()?\\s*[\"']?([^\"'\\);]+)[\"']?\\s*\\)?");
            final Matcher importMatcher = importPattern.matcher(html);
            while (importMatcher.find()) {
                addResourceIfValid(resources, importMatcher.group(1).trim(), base);
            }

            final Pattern inlineStylePattern = Pattern.compile("(?is)<style[^>]*>(.*?)</style>");
            final Matcher inlineMatcher = inlineStylePattern.matcher(html);
            while (inlineMatcher.find()) {
                String inlineCss = inlineMatcher.group(1);
                resources.addAll(extractResourcesFromCss(inlineCss, base));
            }

            final Pattern inlineScriptPattern = Pattern.compile("(?is)<script[^>]*>(.*?)</script>");
            final Matcher inlineScriptMatcher = inlineScriptPattern.matcher(html);
            while (inlineScriptMatcher.find()) {
                String inlineJs = inlineScriptMatcher.group(1);
                resources.addAll(extractResourcesFromJs(inlineJs, base));
            }

            final Pattern noscriptPattern = Pattern.compile("(?is)<noscript[^>]*>(.*?)</noscript>");
            final Matcher noscriptMatcher = noscriptPattern.matcher(html);
            while (noscriptMatcher.find()) {
                resources.addAll(extractResources(noscriptMatcher.group(1), base));
            }

            final Pattern templatePattern = Pattern.compile("(?is)<template[^>]*>(.*?)</template>");
            final Matcher templateMatcher = templatePattern.matcher(html);
            while (templateMatcher.find()) {
                resources.addAll(extractResources(templateMatcher.group(1), base));
            }

            return resources;
        }

        public static void addSrcsetResources(Set<String> resources, String srcset, String base) {
            if (srcset == null) return;
            String[] parts = srcset.split(",");
            for (String p : parts) {
                p = p.trim();
                if (p.isEmpty()) continue;
                String[] tokens = p.split("\\s+");
                if (tokens.length > 0) {
                    addResourceIfValid(resources, tokens[0], base);
                }
            }
        }

        public static Set<String> extractResourcesFromCss(final String css, final String baseUrl) {
            final Set<String> resources = new LinkedHashSet<>();
            if (css == null) return resources;

            final Pattern urlPattern = Pattern.compile("(?i)url\\s*\\(\\s*(?:[\"']([^\"']*)[\"']|([^\"'\\)\\s]+))\\s*\\)");
            final Matcher urlMatcher = urlPattern.matcher(css);
            while (urlMatcher.find()) {
                String u = urlMatcher.group(1);
                if (u == null) u = urlMatcher.group(2);
                if (u == null) continue;
                u = u.trim();
                if (u.isEmpty()) continue;
                if (u.startsWith("data:") || u.startsWith("#")) continue;
                if (u.startsWith("var(")) continue;
                addResourceIfValid(resources, u, baseUrl);
            }

            final Pattern importPattern = Pattern.compile("(?i)@import\\s+(?:url\\s*\\(\\s*)?(?:[\"']([^\"']*)[\"']|([^\"'\\)\\s;]+))");
            final Matcher importMatcher = importPattern.matcher(css);
            while (importMatcher.find()) {
                String u = importMatcher.group(1);
                if (u == null) u = importMatcher.group(2);
                if (u == null) continue;
                addResourceIfValid(resources, u.trim(), baseUrl);
            }

            final Pattern imageSetPattern = Pattern.compile("(?i)(?:-webkit-)?image-set\\s*\\(([^)]*)\\)");
            final Matcher imageSetMatcher = imageSetPattern.matcher(css);
            while (imageSetMatcher.find()) {
                String inner = imageSetMatcher.group(1);
                Matcher urlM = Pattern.compile("(?i)url\\s*\\(\\s*(?:[\"']([^\"']*)[\"']|([^\"'\\)\\s]+))\\s*\\)").matcher(inner);
                while (urlM.find()) {
                    String u = urlM.group(1) != null ? urlM.group(1) : urlM.group(2);
                    if (u != null && !u.startsWith("data:")) {
                        addResourceIfValid(resources, u, baseUrl);
                    }
                }
            }

            final Pattern fontFacePattern = Pattern.compile("(?is)@font-face\\s*\\{[^}]*\\}");
            final Matcher fontFaceMatcher = fontFacePattern.matcher(css);
            while (fontFaceMatcher.find()) {
                String block = fontFaceMatcher.group(0);
                Matcher urlM = Pattern.compile("(?i)url\\s*\\(\\s*(?:[\"']([^\"']*)[\"']|([^\"'\\)\\s]+))\\s*\\)").matcher(block);
                while (urlM.find()) {
                    String u = urlM.group(1) != null ? urlM.group(1) : urlM.group(2);
                    if (u != null && !u.startsWith("data:")) {
                        addResourceIfValid(resources, u, baseUrl);
                    }
                }
            }

            final Pattern srcPattern = Pattern.compile("(?i)\\bsrc\\s*:\\s*([^;]+);");
            final Matcher srcMatcher = srcPattern.matcher(css);
            while (srcMatcher.find()) {
                String srcList = srcMatcher.group(1);
                Matcher urlM = Pattern.compile("(?i)url\\s*\\(\\s*(?:[\"']([^\"']*)[\"']|([^\"'\\)\\s]+))\\s*\\)").matcher(srcList);
                while (urlM.find()) {
                    String u = urlM.group(1) != null ? urlM.group(1) : urlM.group(2);
                    if (u != null && !u.startsWith("data:")) {
                        addResourceIfValid(resources, u, baseUrl);
                    }
                }
            }

            final Pattern customPropPattern = Pattern.compile("--[a-zA-Z0-9_-]+\\s*:\\s*([^;}]+)");
            final Matcher customMatcher = customPropPattern.matcher(css);
            while (customMatcher.find()) {
                String val = customMatcher.group(1);
                if (val != null && val.contains("url(")) {
                    Matcher urlM = Pattern.compile("(?i)url\\s*\\(\\s*(?:[\"']([^\"']*)[\"']|([^\"'\\)\\s]+))\\s*\\)").matcher(val);
                    while (urlM.find()) {
                        String u = urlM.group(1) != null ? urlM.group(1) : urlM.group(2);
                        if (u != null && !u.startsWith("data:")) {
                            addResourceIfValid(resources, u, baseUrl);
                        }
                    }
                }
            }

            return resources;
        }

        public static Set<String> extractImportsFromCss(final String css, final String baseUrl) {
            final Set<String> resources = new LinkedHashSet<>();
            if (css == null) return resources;
            final Pattern importPattern = Pattern.compile("(?i)@import\\s+(?:url\\s*\\(\\s*)?(?:[\"']([^\"']*)[\"']|([^\"'\\)\\s;]+))");
            final Matcher importMatcher = importPattern.matcher(css);
            while (importMatcher.find()) {
                String u = importMatcher.group(1);
                if (u == null) u = importMatcher.group(2);
                if (u == null) continue;
                addResourceIfValid(resources, u.trim(), baseUrl);
            }
            return resources;
        }

        public static Set<String> extractResourcesFromJs(final String js, final String baseUrl) {
            final Set<String> resources = new LinkedHashSet<>();
            if (js == null) return resources;

            final Pattern stringPattern = Pattern.compile("(['\"])((?:https?:)?//[^\\s'\"`\\\\)]+|/[^\\s'\"`\\\\)]*\\.(?:js|mjs|css|png|jpg|jpeg|webp|avif|gif|bmp|svg|ico|mp3|wav|ogg|flac|aac|mp4|webm|mkv|mov|m3u8|mpd|ts|m4s|pdf|json|xml|wasm|woff2?|ttf|otf|eot|map|html?|php|webmanifest|manifest))\\1");
            final Matcher stringMatcher = stringPattern.matcher(js);
            while (stringMatcher.find()) {
                addResourceIfValid(resources, stringMatcher.group(2), baseUrl);
            }

            final Pattern templatePattern = Pattern.compile("`([^`$]*?(?:https?:)?//[^\\s`]+)`");
            final Matcher templateMatcher = templatePattern.matcher(js);
            while (templateMatcher.find()) {
                String inside = templateMatcher.group(1);
                Matcher urlM = Pattern.compile("((?:https?:)?//[^\\s`\"']+)").matcher(inside);
                while (urlM.find()) {
                    addResourceIfValid(resources, urlM.group(1), baseUrl);
                }
            }

            final Pattern fetchPattern = Pattern.compile("(?i)\\bfetch\\s*\\(\\s*(?:[\"']([^\"']+)[\"']|`([^`]+)`)");
            final Matcher fetchMatcher = fetchPattern.matcher(js);
            while (fetchMatcher.find()) {
                String u = fetchMatcher.group(1);
                if (u == null) u = fetchMatcher.group(2);
                if (u != null && !u.contains("${")) {
                    addResourceIfValid(resources, u, baseUrl);
                }
            }

            final Pattern xhrPattern = Pattern.compile("(?i)\\.open\\s*\\(\\s*[\"'](?:GET|POST|PUT|DELETE|HEAD|OPTIONS)[\"']\\s*,\\s*[\"']([^\"']+)[\"']");
            final Matcher xhrMatcher = xhrPattern.matcher(js);
            while (xhrMatcher.find()) {
                addResourceIfValid(resources, xhrMatcher.group(1), baseUrl);
            }

            final Pattern importPattern = Pattern.compile("(?i)(?:import|require)\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");
            final Matcher importMatcher = importPattern.matcher(js);
            while (importMatcher.find()) {
                addResourceIfValid(resources, importMatcher.group(1), baseUrl);
            }

            final Pattern staticImportPattern = Pattern.compile("(?m)^\\s*import\\s+(?:[^\"'\\n]*?from\\s+)?[\"']([^\"']+)[\"']");
            final Matcher staticImportMatcher = staticImportPattern.matcher(js);
            while (staticImportMatcher.find()) {
                addResourceIfValid(resources, staticImportMatcher.group(1), baseUrl);
            }

            final Pattern importScriptsPattern = Pattern.compile("(?i)importScripts\\s*\\(([^)]+)\\)");
            final Matcher importScriptsMatcher = importScriptsPattern.matcher(js);
            while (importScriptsMatcher.find()) {
                String args = importScriptsMatcher.group(1);
                Matcher strM = Pattern.compile("[\"']([^\"']+)[\"']").matcher(args);
                while (strM.find()) {
                    addResourceIfValid(resources, strM.group(1), baseUrl);
                }
            }

            final Pattern workerPattern = Pattern.compile("(?i)new\\s+(?:Shared)?Worker\\s*\\(\\s*[\"']([^\"']+)[\"']");
            final Matcher workerMatcher = workerPattern.matcher(js);
            while (workerMatcher.find()) {
                addResourceIfValid(resources, workerMatcher.group(1), baseUrl);
            }

            final Pattern swPattern = Pattern.compile("(?i)(?:serviceWorker|navigator\\.serviceWorker)\\.register\\s*\\(\\s*[\"']([^\"']+)[\"']");
            final Matcher swMatcher = swPattern.matcher(js);
            while (swMatcher.find()) {
                addResourceIfValid(resources, swMatcher.group(1), baseUrl);
            }

            final Pattern newUrlPattern = Pattern.compile("(?i)new\\s+URL\\s*\\(\\s*[\"']([^\"']+)[\"']");
            final Matcher newUrlMatcher = newUrlPattern.matcher(js);
            while (newUrlMatcher.find()) {
                addResourceIfValid(resources, newUrlMatcher.group(1), baseUrl);
            }

            final Pattern sourceMapPattern = Pattern.compile("(?i)//[#@]\\s*sourceMappingURL\\s*=\\s*([^\\s]+)");
            final Matcher sourceMapMatcher = sourceMapPattern.matcher(js);
            while (sourceMapMatcher.find()) {
                String u = sourceMapMatcher.group(1).trim();
                if (u.startsWith("data:")) continue;
                addResourceIfValid(resources, u, baseUrl);
            }

            final Pattern webpackPattern = Pattern.compile("(?i)__webpack_require__\\.p\\s*\\+\\s*[\"']([^\"']+)[\"']");
            final Matcher webpackMatcher = webpackPattern.matcher(js);
            while (webpackMatcher.find()) {
                addResourceIfValid(resources, webpackMatcher.group(1), baseUrl);
            }

            final Pattern locationPattern = Pattern.compile("(?i)(?:window\\.|document\\.)?location(?:\\.href)?\\s*=\\s*[\"']([^\"']+)[\"']");
            final Matcher locationMatcher = locationPattern.matcher(js);
            while (locationMatcher.find()) {
                addResourceIfValid(resources, locationMatcher.group(1), baseUrl);
            }

            final Pattern cssUrlPattern = Pattern.compile("(?i)url\\s*\\(\\s*(?:[\"']([^\"']+)[\"']|([^\"'\\)\\s]+))\\s*\\)");
            final Matcher cssUrlMatcher = cssUrlPattern.matcher(js);
            while (cssUrlMatcher.find()) {
                String u = cssUrlMatcher.group(1) != null ? cssUrlMatcher.group(1) : cssUrlMatcher.group(2);
                if (u != null && !u.startsWith("data:") && !u.contains("${")) {
                    addResourceIfValid(resources, u, baseUrl);
                }
            }

            return resources;
        }

        public static String extractSourceMappingUrl(String js, String baseUrl) {
            if (js == null) return null;
            Matcher m = Pattern.compile("(?i)//[#@]\\s*sourceMappingURL\\s*=\\s*([^\\s]+)").matcher(js);
            if (m.find()) {
                String u = m.group(1).trim();
                if (u.startsWith("data:")) return null;
                return resolveUrl(u, baseUrl);
            }
            return null;
        }

        public static Set<String> extractResourcesFromJson(final String json, final String baseUrl) {
            final Set<String> resources = new LinkedHashSet<>();
            if (json == null) return resources;
            final Pattern urlPattern = Pattern.compile("[\"']((?:https?:)?//[^\"'\\s]+|/[^\"'\\s]+\\.(?:js|css|png|jpe?g|gif|webp|avif|svg|ico|mp3|wav|ogg|mp4|webm|m3u8|mpd|json|xml|woff2?|ttf|otf|eot|wasm))[\"']");
            final Matcher matcher = urlPattern.matcher(json);
            while (matcher.find()) {
                addResourceIfValid(resources, matcher.group(1), baseUrl);
            }
            if (json.contains("\\/")) {
                String unescaped = json.replace("\\/", "/");
                Matcher m2 = Pattern.compile("[\"']((?:https?:)?//[^\"'\\s]+)[\"']").matcher(unescaped);
                while (m2.find()) {
                    addResourceIfValid(resources, m2.group(1), baseUrl);
                }
            }
            return resources;
        }

        public static Set<String> extractResourcesFromM3u8(final String content, final String baseUrl) {
            final Set<String> resources = new LinkedHashSet<>();
            if (content == null) return resources;
            for (String rawLine : content.split("\\r?\\n")) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) {
                    Matcher uriM = Pattern.compile("URI\\s*=\\s*[\"']([^\"']+)[\"']").matcher(line);
                    while (uriM.find()) {
                        addResourceIfValid(resources, uriM.group(1), baseUrl);
                    }
                    continue;
                }
                addResourceIfValid(resources, line, baseUrl);
            }
            return resources;
        }

        public static Set<String> extractResourcesFromMpd(final String content, final String baseUrl) {
            final Set<String> resources = new LinkedHashSet<>();
            if (content == null) return resources;
            Matcher m = Pattern.compile("(?i)(?:media|initialization|sourceURL|href)\\s*=\\s*[\"']([^\"']+)[\"']").matcher(content);
            while (m.find()) {
                addResourceIfValid(resources, m.group(1), baseUrl);
            }
            Matcher bm = Pattern.compile("(?is)<BaseURL[^>]*>([^<]+)</BaseURL>").matcher(content);
            while (bm.find()) {
                addResourceIfValid(resources, bm.group(1).trim(), baseUrl);
            }
            return resources;
        }

        public static String buildDomResourceScript() {
            return "(function() {"
                    + "  var out = [];"
                    + "  var seen = {};"
                    + "  function push(v){ if(!v) return; if(typeof v !== 'string') return; v = v.trim(); if(!v) return; if(seen[v]) return; seen[v] = 1; out.push(v); }"
                    + "  var els = document.querySelectorAll('*');"
                    + "  var attrs = ['src','href','data-src','data-lazy-src','data-lazy','data-original','data-original-src','data-srcset','data-lazy-srcset','data-original-set','data-bg','data-background','data-background-image','data-image','data-image-src','data-url','data-href','data-poster','data-video','data-thumb','data-thumbnail','poster','srcset','imagesrcset','background','xlink:href'];"
                    + "  for (var i = 0; i < els.length; i++) {"
                    + "    var el = els[i];"
                    + "    for (var j = 0; j < attrs.length; j++) {"
                    + "      try { var v = el.getAttribute && el.getAttribute(attrs[j]); if(v) push(v); } catch(e){}"
                    + "    }"
                    + "    try {"
                    + "      var tag = el.tagName ? el.tagName.toLowerCase() : '';"
                    + "      if (tag === 'meta') {"
                    + "        var p = (el.getAttribute('property') || el.getAttribute('name') || el.getAttribute('itemprop') || '').toLowerCase();"
                    + "        if (p.indexOf('image') >= 0 || p.indexOf('video') >= 0 || p.indexOf('audio') >= 0 || p.indexOf('thumbnail') >= 0 || p === 'og:url' || p === 'twitter:player') {"
                    + "          var c = el.getAttribute('content');"
                    + "          if (c) push(c);"
                    + "        }"
                    + "      }"
                    + "    } catch(e){}"
                    + "    try {"
                    + "      var bg = window.getComputedStyle(el).backgroundImage;"
                    + "      if (bg && bg !== 'none') {"
                    + "        var re = /url\\s*\\(\\s*[\"']?([^\"'\\)]+)[\"']?\\s*\\)/g;"
                    + "        var m;"
                    + "        while ((m = re.exec(bg)) !== null) push(m[1]);"
                    + "      }"
                    + "      var bi = window.getComputedStyle(el).borderImageSource;"
                    + "      if (bi && bi !== 'none') {"
                    + "        var re2 = /url\\s*\\(\\s*[\"']?([^\"'\\)]+)[\"']?\\s*\\)/g;"
                    + "        var m2;"
                    + "        while ((m2 = re2.exec(bi)) !== null) push(m2[1]);"
                    + "      }"
                    + "      var lbi = window.getComputedStyle(el).listStyleImage;"
                    + "      if (lbi && lbi !== 'none') {"
                    + "        var re3 = /url\\s*\\(\\s*[\"']?([^\"'\\)]+)[\"']?\\s*\\)/g;"
                    + "        var m3;"
                    + "        while ((m3 = re3.exec(lbi)) !== null) push(m3[1]);"
                    + "      }"
                    + "      if (el.hasAttribute && el.hasAttribute('style')) {"
                    + "        var st = el.getAttribute('style') || '';"
                    + "        var re4 = /url\\s*\\(\\s*[\"']?([^\"'\\)]+)[\"']?\\s*\\)/g;"
                    + "        var m4;"
                    + "        while ((m4 = re4.exec(st)) !== null) push(m4[1]);"
                    + "      }"
                    + "    } catch(e){}"
                    + "  }"
                    + "  return JSON.stringify(out);"
                    + "})()";
        }

        public static String buildInlineStyleScript() {
            return "(function() {"
                    + "  var out = [];"
                    + "  var els = document.querySelectorAll('[style]');"
                    + "  for (var i = 0; i < els.length; i++) {"
                    + "    try { var s = els[i].getAttribute('style'); if (s) out.push(s); } catch(e){}"
                    + "  }"
                    + "  return JSON.stringify(out);"
                    + "})()";
        }

        public static String buildScriptSrcScript() {
            return "(function() {"
                    + "  var out = [];"
                    + "  var scripts = document.querySelectorAll('script');"
                    + "  for (var i = 0; i < scripts.length; i++) {"
                    + "    try { var c = scripts[i].innerHTML; if (c) out.push(c); } catch(e){}"
                    + "    try { var t = scripts[i].textContent; if (t && t !== scripts[i].innerHTML) out.push(t); } catch(e){}"
                    + "  }"
                    + "  return JSON.stringify(out);"
                    + "})()";
        }

        public static String decodeQuotedPrintable(final String input) {
            if (input == null) return "";
            StringBuilder sb = new StringBuilder(input.length());
            int i = 0;
            int len = input.length();
            while (i < len) {
                char c = input.charAt(i);
                if (c == '=' && i + 2 < len) {
                    char n1 = input.charAt(i + 1);
                    char n2 = input.charAt(i + 2);
                    if (n1 == '\r' && n2 == '\n') {
                        i += 3;
                        continue;
                    }
                    if (n1 == '\n') {
                        i += 2;
                        continue;
                    }
                    if (n1 == '\r') {
                        i += 2;
                        continue;
                    }
                    int hi = hexValue(n1);
                    int lo = hexValue(n2);
                    if (hi >= 0 && lo >= 0) {
                        sb.append((char) ((hi << 4) | lo));
                        i += 3;
                        continue;
                    }
                    sb.append(c);
                    i++;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString();
        }

        private static int hexValue(char c) {
            if (c >= '0' && c <= '9') return c - '0';
            if (c >= 'a' && c <= 'f') return c - 'a' + 10;
            if (c >= 'A' && c <= 'F') return c - 'A' + 10;
            return -1;
        }
    }

    static class MimeParser {
        public static Map<String, byte[]> extractResources(String mhtContent, File dir) {
            Map<String, byte[]> resources = new LinkedHashMap<>();
            if (mhtContent == null) return resources;

            String boundary = null;
            Matcher boundaryMatcher = Pattern.compile("(?im)^Content-Type:\\s*multipart/[^;]+;\\s*boundary\\s*=\\s*\"?([^\"\\r\\n;]+)\"?").matcher(mhtContent);
            if (boundaryMatcher.find()) {
                boundary = boundaryMatcher.group(1).trim();
            }
            if (boundary == null) {
                boundaryMatcher = Pattern.compile("boundary=\"([^\"]+)\"").matcher(mhtContent);
                if (boundaryMatcher.find()) boundary = boundaryMatcher.group(1);
            }
            if (boundary == null) return resources;

            String[] parts = mhtContent.split("--" + Pattern.quote(boundary));
            int anonymous = 0;
            for (String part : parts) {
                if (part == null) continue;
                String trimmed = part.trim();
                if (trimmed.isEmpty() || "--".equals(trimmed)) continue;

                Matcher locationMatcher = Pattern.compile("(?im)^Content-Location:\\s*(.*?)\\r?$").matcher(part);
                String location = locationMatcher.find() ? locationMatcher.group(1).trim() : null;

                String contentType = "";
                Matcher ctMatcher = Pattern.compile("(?im)^Content-Type:\\s*(.*?)\\r?$").matcher(part);
                if (ctMatcher.find()) contentType = ctMatcher.group(1).trim();

                Matcher encodingMatcher = Pattern.compile("(?im)^Content-Transfer-Encoding:\\s*(.*?)\\r?$").matcher(part);
                String encoding = encodingMatcher.find() ? encodingMatcher.group(1).trim() : "7bit";

                int bodyStart = part.indexOf("\r\n\r\n");
                int skipLen = 4;
                if (bodyStart == -1) {
                    bodyStart = part.indexOf("\n\n");
                    skipLen = 2;
                }
                if (bodyStart == -1) continue;
                String rawBody = part.substring(bodyStart + skipLen);

                byte[] bodyBytes;
                try {
                    if ("quoted-printable".equalsIgnoreCase(encoding)) {
                        String decoded = Utils.decodeQuotedPrintable(rawBody);
                        bodyBytes = decoded.getBytes(StandardCharsets.ISO_8859_1);
                    } else if ("base64".equalsIgnoreCase(encoding)) {
                        String cleaned = rawBody.replaceAll("\\s+", "");
                        bodyBytes = Base64.decode(cleaned, Base64.DEFAULT);
                    } else {
                        bodyBytes = rawBody.getBytes(StandardCharsets.ISO_8859_1);
                    }
                } catch (Exception e) {
                    continue;
                }

                String fileName = null;
                if (location != null) {
                    try {
                        String path = new URL(location).getPath();
                        fileName = new File(path).getName();
                    } catch (Exception e) {
                        int slash = location.lastIndexOf('/');
                        fileName = slash >= 0 ? location.substring(slash + 1) : location;
                    }
                }
                if (fileName == null || fileName.isEmpty()) {
                    String ext = Utils.getExtensionFromContentType(contentType);
                    fileName = "resource_" + (++anonymous) + (ext != null ? ext : ".bin");
                }
                fileName = fileName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
                if (fileName.length() > 180) {
                    fileName = fileName.substring(0, 180);
                }
                resources.put(fileName, bodyBytes);
            }
            return resources;
        }

        public static String getMainHtml(String mhtContent) {
            if (mhtContent == null) return "";
            String boundary = null;
            Matcher boundaryMatcher = Pattern.compile("(?im)^Content-Type:\\s*multipart/[^;]+;\\s*boundary\\s*=\\s*\"?([^\"\\r\\n;]+)\"?").matcher(mhtContent);
            if (boundaryMatcher.find()) {
                boundary = boundaryMatcher.group(1).trim();
            }
            if (boundary == null) {
                boundaryMatcher = Pattern.compile("boundary=\"([^\"]+)\"").matcher(mhtContent);
                if (boundaryMatcher.find()) boundary = boundaryMatcher.group(1);
            }
            if (boundary == null) return "";

            String[] parts = mhtContent.split("--" + Pattern.quote(boundary));
            for (String part : parts) {
                if (part == null) continue;
                Matcher ctMatcher = Pattern.compile("(?im)^Content-Type:\\s*text/html([^\\r\\n]*)").matcher(part);
                if (!ctMatcher.find()) continue;
                String ctFull = ctMatcher.group(0);
                String charset = "UTF-8";
                Matcher csM = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([a-zA-Z0-9_\\-]+)").matcher(ctFull);
                if (csM.find()) charset = csM.group(1);

                Matcher encodingMatcher = Pattern.compile("(?im)^Content-Transfer-Encoding:\\s*(.*?)\\r?$").matcher(part);
                String encoding = encodingMatcher.find() ? encodingMatcher.group(1).trim() : "7bit";

                int bodyStart = part.indexOf("\r\n\r\n");
                int skipLen = 4;
                if (bodyStart == -1) {
                    bodyStart = part.indexOf("\n\n");
                    skipLen = 2;
                }
                if (bodyStart == -1) continue;
                String rawBody = part.substring(bodyStart + skipLen);

                try {
                    if ("quoted-printable".equalsIgnoreCase(encoding)) {
                        String decoded = Utils.decodeQuotedPrintable(rawBody);
                        return new String(decoded.getBytes(StandardCharsets.ISO_8859_1), Charset.forName(charset));
                    } else if ("base64".equalsIgnoreCase(encoding)) {
                        String cleaned = rawBody.replaceAll("\\s+", "");
                        byte[] decoded = Base64.decode(cleaned, Base64.DEFAULT);
                        return new String(decoded, Charset.forName(charset));
                    } else {
                        return new String(rawBody.getBytes(StandardCharsets.ISO_8859_1), Charset.forName(charset));
                    }
                } catch (Exception e) {
                    return rawBody;
                }
            }
            return "";
        }
    }

    private byte[] downloadResourceBytes(final String resourceUrl, final boolean usePcUa, final String ref) {
        return new DownloadServiceBridge(this).download(resourceUrl, usePcUa, ref);
    }

    private static class DownloadServiceBridge {
        private final pagedl activity;

        DownloadServiceBridge(pagedl activity) {
            this.activity = activity;
        }

        byte[] download(String resourceUrl, boolean usePcUa, String ref) {
            HttpURLConnection conn = null;
            final int maxRetries = 3;
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    final URL url = new URL(resourceUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    conn.setRequestProperty("User-Agent", usePcUa ? PC_USER_AGENT : WebSettings.getDefaultUserAgent(activity));
                    conn.setRequestProperty("Accept", ACCEPT_HEADER);
                    conn.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE);
                    conn.setRequestProperty("Accept-Encoding", ACCEPT_ENCODING);
                    if (ref != null) conn.setRequestProperty("Referer", ref);
                    final int responseCode = conn.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_NOT_FOUND ||
                            responseCode == HttpURLConnection.HTTP_FORBIDDEN ||
                            responseCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
                            responseCode == HttpURLConnection.HTTP_GONE) {
                        return null;
                    }
                    if (responseCode >= 500 && attempt < maxRetries) {
                        conn.disconnect();
                        conn = null;
                        try { Thread.sleep(600L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                        continue;
                    }
                    if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                        return null;
                    }
                    final String encoding = conn.getContentEncoding();
                    final InputStream rawIn = conn.getInputStream();
                    final InputStream in = wrapStream(rawIn, encoding);
                    try (final InputStream input = in;
                         final ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        final byte[] buffer = new byte[BUFFER_SIZE];
                        int bytesRead;
                        while ((bytesRead = input.read(buffer)) != -1) {
                            if (activity.getAvailableStorage() < MIN_STORAGE_THRESHOLD) {
                                return null;
                            }
                            baos.write(buffer, 0, bytesRead);
                        }
                        return baos.toByteArray();
                    }
                } catch (final Exception e) {
                    if (attempt >= maxRetries) {
                        Log.w(TAG, "ダウンロードエラー: " + resourceUrl, e);
                        return null;
                    }
                    try { Thread.sleep(600L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
            return null;
        }

        private InputStream wrapStream(final InputStream in, final String encoding) throws IOException {
            if (encoding == null) return in;
            final String enc = encoding.toLowerCase(Locale.ROOT);
            if (enc.contains("gzip")) return new GZIPInputStream(in);
            if (enc.contains("deflate")) return new InflaterInputStream(in);
            return in;
        }
    }
}
