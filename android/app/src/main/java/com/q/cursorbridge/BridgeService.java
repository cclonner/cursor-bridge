package com.q.cursorbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Foreground-сервис: держит WebSocket с мостом на ПК, показывает системные
 * уведомления о событиях Cursor Agent, сам находит мост через mDNS (NSD).
 */
public class BridgeService extends Service {

    private static final String TAG = "BridgeService";
    public static final String SERVICE_TYPE = "_cursor-bridge._tcp.";
    public static final String ACTION_STATE = "com.q.cursorbridge.STATE";
    public static final String ACTION_INPUT = "com.q.cursorbridge.INPUT";
    public static final String ACTION_RECONNECT = "com.q.cursorbridge.RECONNECT";
    public static final String ACTION_VISIBILITY = "com.q.cursorbridge.VISIBILITY";
    public static final String INTERNAL_PERM = "com.q.cursorbridge.permission.INTERNAL";
    public static final String PREFS = "bridge";

    private static final String CH_STATUS = "bridge_status";
    private static final String CH_EVENTS = "cursor_events";
    private static final int STATUS_ID = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private OkHttpClient http;
    private WebSocket ws;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock mcLock;
    private ConnectivityManager.NetworkCallback netCallback;

    private String host = null;
    private int port = 8790;
    private boolean connected = false;
    private boolean connecting = false;
    private boolean resolving = false;
    private boolean discovering = false;
    private boolean probeStarted = false;
    private Thread probeThread;
    // Сервис уничтожен (режим «app» останавливает его при закрытии приложения).
    // Асинхронные колбэки OkHttp/туннеля не должны после этого переподключаться
    // и вешать уведомление-сироту: ws.cancel() в onDestroy порождает onFailure,
    // тот — onDown → postDelayed(connect) уже на мёртвом сервисе.
    private volatile boolean destroyed = false;
    private volatile boolean appVisible = false; // активити на экране
    private String lastStatus = "Поиск моста в WiFi-сети…";
    private int retryMs = 2000;
    private int eventSeq = 100;

    // активные событийные уведомления: сессия -> id уведомления и тип события;
    // нужны, чтобы гасить устаревшие (ответили на ПК, пришло новое событие)
    private final java.util.Map<String, Integer> eventNids = new java.util.HashMap<>();
    private final java.util.Map<String, String> eventKinds = new java.util.HashMap<>();

    private String token = null;
    private String certFp = null;
    private BridgeStore store;

    // --- интернет-туннель (сайдкар cursor-tunnel, p2p iroh) ---
    private Process tunnelProc = null;
    private volatile int tunnelPort = 0;        // >0 — туннель готов на 127.0.0.1:port
    private volatile boolean viaTunnel = false; // текущее WS-соединение идёт через туннель
    private volatile String tunnelPath = null;  // direct | relay | mixed
    private volatile String tunnelAddr = null;  // фактический адрес пира / relay-URL
    private volatile long lanLastSeen = 0;      // активный ПК последний раз виделся в LAN
    private volatile long lanGraceUntil = 0;    // до этого момента даём LAN шанс, туннель не поднимаем
    // Непроверенный LAN-адрес активного ПК из discovery: пробуем подключиться к
    // нему, но в store сохраняем ТОЛЬКО после успешного пиннингового рукопожатия
    // (onOpen). Иначе любой хост в LAN, назвав публичный fp моста, перенаправил
    // бы/оборвал бы связь, перезаписав сохранённый адрес (F4).
    private volatile String candHost = null;
    private volatile int candPort = 0;

    // Мосты, замеченные в сети (по fp): для экрана «добавить компьютер»
    private final java.util.Map<String, JSONObject> discovered =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Экран гаснет → помечаем, что после последней разблокировки был выключен
    // экран: активность потребует биометрию снова. Сервис живёт постоянно,
    // поэтому ловит гашение экрана даже когда активность уничтожена.
    private BroadcastReceiver screenReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();

        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                    MainActivity.screenOffSinceUnlock = true;
                }
            }
        };
        registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));

        LocalGateway.start(this); // локальный интерфейс + ws-прокси для WebView
        store = new BridgeStore(this); // мигрирует старые ключи и зеркалит активный ПК
        ensurePhoneNodeId(); // заранее вычисляем свой p2p-NodeId для allowlist
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        host = p.getString("host", null);
        port = p.getInt("port", 8790);
        token = p.getString("token", null);
        certFp = p.getString("fp", null);
        http = Tls.buildClient(certFp);

        lanGraceUntil = System.currentTimeMillis() + 12_000; // сперва ищем ПК в LAN

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mcLock = wifi.createMulticastLock("cursor-bridge-mdns");
        mcLock.setReferenceCounted(false);
        mcLock.acquire();

        watchNetwork();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // не «Поиск моста…», а последний реальный статус: команды вроде
        // «приложение открылось» не должны сбрасывать текст уведомления
        Notification n = statusNotification(lastStatus);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(STATUS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(STATUS_ID, n);
        }

        // видимость приложения — для режимов экономии батареи
        if (intent != null && ACTION_VISIBILITY.equals(intent.getAction())) {
            appVisible = intent.getBooleanExtra("visible", false);
            if (appVisible) {
                retryMs = 2000;
                // сервис мог быть пересоздан в живом процессе (режим «app»):
                // обнаружение ПК должно работать и на этом пути запуска
                startDiscovery();
                if (!probeStarted) { probeStarted = true; startUdpProbe(); }
                handler.post(this::connect);
            } else if ("app".equals(bgMode())) {
                // «только при открытом приложении»: в фоне демон не нужен вовсе.
                // stopSelf(startId) — если уже пришла новая команда (приложение
                // тут же открыли снова), остановка молча отменяется
                stopSelf(startId);
                return START_NOT_STICKY;
            } else if (!allowedNow()) {
                handler.post(this::pauseConnection);
            }
            return stickiness();
        }

        // Быстрый ответ из кнопки уведомления: шлём ввод в PTY, не открывая приложение
        if (intent != null && ACTION_INPUT.equals(intent.getAction())) {
            String sess = intent.getStringExtra("session");
            String data = intent.getStringExtra("data");
            int nid = intent.getIntExtra("nid", -1);
            if (ws != null && connected && sess != null && data != null) {
                try {
                    JSONObject j = new JSONObject();
                    j.put("type", "input");
                    j.put("id", sess);
                    j.put("data", data);
                    ws.send(j.toString());
                } catch (Exception e) {
                    Log.w(TAG, "quick reply failed", e);
                }
            }
            if (nid >= 0) getSystemService(NotificationManager.class).cancel(nid);
            if (sess != null) cancelEventNotif(sess);
            // тап по залежавшемуся уведомлению не должен воскрешать демона
            // в режиме «только при открытом приложении»
            if (!appVisible && "app".equals(bgMode())) stopSelf(startId);
            return stickiness();
        }

        // Сменился активный компьютер (настройки) — переподключаемся к нему
        if (intent != null && ACTION_RECONNECT.equals(intent.getAction())) {
            handler.post(this::forceReconnect);
            return stickiness();
        }

        broadcastState(); // свежая активити сразу узнаёт состояние, не ждёт событий
        startDiscovery();
        if (!probeStarted) { probeStarted = true; startUdpProbe(); }
        if (host != null) connect();
        return stickiness();
    }

    // ------------------------------------------------------- udp discovery

    /**
     * Мост найден в сети. По отпечатку сертификата понимаем, какой это из
     * сопряжённых компьютеров: обновляем его адрес; если это активный ПК и мы
     * не подключены — подключаемся. Незнакомые ПК попадают в список
     * «обнаруженные» для экрана добавления.
     */
    private void onBridgeFound(String h, int p, String fp, String name) {
        if (fp != null && fp.isEmpty()) fp = null;
        try {
            JSONObject d = new JSONObject()
                    .put("fp", fp == null ? "" : fp.toLowerCase())
                    .put("host", h).put("port", p)
                    .put("name", name == null || name.isEmpty() ? h : name)
                    .put("at", System.currentTimeMillis());
            discovered.put(fp != null ? fp.toLowerCase() : h, d);
        } catch (Exception ignored) { }

        boolean isActive;
        if (fp != null && store.byFp(fp) != null) {
            // адрес известного моста здесь НЕ сохраняем: discovery не
            // аутентифицирован. Запишем его в store лишь после успешного
            // пиннингового рукопожатия к нему (onOpen) — см. поля candHost (F4).
            isActive = fp.equalsIgnoreCase(store.activeFp());
        } else {
            // мост без fp (старый сервер) считаем активным; незнакомый ПК — нет
            isActive = fp == null;
        }
        if (store.list().isEmpty()) {
            // ещё нет ни одного сопряжения: адрес нужен экрану сопряжения
            // (доверие устанавливается самим сопряжением, там пиннится fp)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("host", h).putInt("port", p).apply();
        }
        broadcastState();
        if (!isActive) return;
        lanLastSeen = System.currentTimeMillis();
        if (connected) {
            // активный ПК появился в LAN, а мы на туннеле — LAN важнее
            if (viaTunnel) handler.post(this::forceReconnect);
            return;
        }
        boolean changed = !h.equals(host) || p != port;
        candHost = h; // непроверенный кандидат — подключимся к нему, но не сохраним, пока пиннинг не пройдёт
        candPort = p;
        host = h;
        port = p;
        handler.post(changed ? this::forceReconnect : this::connect);
    }

    /** Резервное обнаружение: broadcast "CURSOR_BRIDGE?" на порт 8791, мост отвечает JSON'ом. */
    private void startUdpProbe() {
        Thread t = new Thread(() -> {
            byte[] q = "CURSOR_BRIDGE?".getBytes();
            while (!Thread.interrupted()) {
                if (!allowedNow()) { // экономия: в фоне не опрашиваем сеть
                    try { Thread.sleep(8000); } catch (InterruptedException e) { return; }
                    continue;
                }
                try (DatagramSocket sock = new DatagramSocket()) {
                    sock.setBroadcast(true);
                    sock.setSoTimeout(2000);
                    sock.send(new DatagramPacket(q, q.length,
                            InetAddress.getByName("255.255.255.255"), 8791));
                    byte[] buf = new byte[1024];
                    DatagramPacket resp = new DatagramPacket(buf, buf.length);
                    sock.receive(resp); // ждём unicast-ответ моста
                    JSONObject j = new JSONObject(new String(resp.getData(), 0, resp.getLength()));
                    if (j.optBoolean("cursorBridge")) {
                        String h = resp.getAddress().getHostAddress();
                        Log.i(TAG, "UDP: мост найден " + h + ":" + j.optInt("port", 8790));
                        onBridgeFound(h, j.optInt("port", 8790),
                                j.optString("fp", null), j.optString("host", h));
                    }
                } catch (Exception ignored) {
                    // таймаут/нет сети — просто пробуем снова
                }
                // подключены — опрашиваем реже, только чтобы видеть другие ПК
                try { Thread.sleep(connected ? 12000 : 4000); } catch (InterruptedException e) { return; }
            }
        }, "udp-probe");
        t.setDaemon(true);
        t.start();
        probeThread = t;
    }

    // ------------------------------------------------------------ discovery

    private void startDiscovery() {
        if (discovering) return;
        // флаг ставим сразу, не дожидаясь onDiscoveryStarted: повторный вызов
        // (onCreate-путь + сразу ACTION_VISIBILITY) не должен дублировать поиск
        discovering = true;
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { discovering = false; }
            @Override public void onStartDiscoveryFailed(String t, int e) { discovering = false; }
            @Override public void onStopDiscoveryFailed(String t, int e) { discovering = false; }
            @Override public void onServiceLost(NsdServiceInfo info) { }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                if (resolving || !info.getServiceType().contains("cursor-bridge")) return;
                resolving = true;
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int e) { resolving = false; }
                    @Override
                    public void onServiceResolved(NsdServiceInfo i) {
                        resolving = false;
                        String h = i.getHost() != null ? i.getHost().getHostAddress() : null;
                        if (h == null) return;
                        Log.i(TAG, "mDNS: мост найден " + h + ":" + i.getPort());
                        String fp = null;
                        try {
                            byte[] f = i.getAttributes().get("fp");
                            if (f != null) fp = new String(f);
                        } catch (Exception ignored) { }
                        onBridgeFound(h, i.getPort(), fp, null);
                    }
                });
            }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            discovering = false;
            Log.w(TAG, "NSD discover failed", e);
        }
    }

    // ------------------------------------------------------------ websocket

    // ------------------------------------------------------ экономия батареи
    // Режим bgMode в prefs: "always" — на связи всегда; "wifi" — в фоне только
    // по WiFi; "app" — только пока приложение открыто. При открытом приложении
    // связь есть в любом режиме.

    private boolean isOnWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    /** Текущий режим экономии батареи из настроек. */
    private String bgMode() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("bgMode", "always");
    }

    /**
     * В режиме «только при открытом приложении» система не должна воскрешать
     * убитый сервис: START_STICKY возвращал демона в фон через пару секунд
     * после закрытия приложения.
     */
    private int stickiness() {
        return "app".equals(bgMode()) ? START_NOT_STICKY : START_STICKY;
    }

    /** Разрешена ли связь прямо сейчас с учётом режима экономии. */
    private boolean allowedNow() {
        if (appVisible) return true;
        String mode = bgMode();
        if ("wifi".equals(mode)) return isOnWifi();
        if ("app".equals(mode)) return false;
        return true; // "always"
    }

    /** Текст паузы для уведомления: зависит от режима экономии. */
    private String pausedText() {
        return "wifi".equals(bgMode())
                ? "Экономия батареи — в фоне подключусь по WiFi"
                : "Экономия батареи — подключусь, когда откроете приложение";
    }

    /** Разорвать связь ради экономии батареи (возобновится по событию). */
    private synchronized void pauseConnection() {
        if (ws != null) { ws.cancel(); ws = null; }
        connected = false;
        connecting = false;
        viaTunnel = false;
        stopTunnel();
        handler.removeCallbacksAndMessages(null); // остановить цикл переподключения
        updateStatus(pausedText());
        broadcastState();
    }

    /** Разорвать текущее соединение и подключиться заново (например, сменился адрес моста). */
    private synchronized void forceReconnect() {
        if (ws != null) { ws.cancel(); ws = null; }
        connected = false;
        connecting = false;
        viaTunnel = false;
        stopTunnel(); // путь будет выбран заново; LAN в приоритете
        connect();
    }

    /** Идемпотентно: ничего не делает, если уже подключены или подключаемся. */
    private synchronized void connect() {
        if (destroyed) return;
        // перечитываем всё из prefs: активный ПК мог смениться в настройках,
        // учётные данные — появиться после сопряжения
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        token = p.getString("token", null);
        host = p.getString("host", host);
        port = p.getInt("port", port);
        String fp = p.getString("fp", null);
        if (fp != null && !fp.equals(certFp)) {
            certFp = fp;
            http = Tls.buildClient(certFp);
        }
        if (token == null || connected || connecting) return;
        if (!allowedNow()) return; // режим экономии: подключимся по событию

        // Выбор пути: LAN приоритетен; туннель — только если ПК давно не виден
        // в сети, а пользователь разрешил доступ через интернет для этого ПК.
        long now = System.currentTimeMillis();
        BridgeStore.Bridge act = store.active();
        boolean lanFresh = (now - lanLastSeen < 15_000) || now < lanGraceUntil;
        boolean wantTunnel = act != null && act.internet && act.nodeTicket != null && !lanFresh;

        final String eh;
        final int epn;
        if (wantTunnel) {
            if (tunnelPort == 0) {
                ensureTunnel(act.nodeTicket); // по событию ready туннель сам позовёт connect()
                handler.postDelayed(this::connect, retryMs); // страховка, если сайдкар молчит
                retryMs = Math.min(retryMs * 2, 30000);
                return;
            }
            eh = "127.0.0.1";
            epn = tunnelPort;
        } else {
            // непроверенный кандидат из discovery имеет приоритет для LAN-попытки;
            // в store он попадёт только после успешного пиннинга (onOpen) (F4)
            String lh = candHost != null ? candHost : host;
            int lp = candHost != null ? candPort : port;
            if (lh == null) return;
            eh = lh;
            epn = lp;
        }
        final boolean tun = wantTunnel;
        connecting = true;
        // эффективный адрес — его же используют WebView и загрузка аудио
        p.edit().putString("useHost", eh).putInt("usePort", epn).apply();
        // Токен — в заголовке Cookie, не в URL: query-строка попадает в логи
        // сервера/прокси, а это долгоживущий bearer полного доступа (F16).
        // Мост читает bridgeToken и из cookie (requestToken).
        String url = "wss://" + eh + ":" + epn + "/ws";
        Log.i(TAG, "connect wss://" + eh + ":" + epn + "/ws" + (tun ? " (туннель)" : ""));
        ws = http.newWebSocket(new Request.Builder().url(url)
                .header("Cookie", "bridgeToken=" + token).build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response r) {
                connected = true;
                connecting = false;
                viaTunnel = tun;
                retryMs = 2000;
                // Адрес прошёл пиннинговое рукопожатие — теперь ему можно доверять
                // и сохранить (только LAN; имя из discovery не берём) (F4).
                if (!tun && candHost != null) {
                    String afp = store.activeFp();
                    if (afp != null) store.updateHost(afp, eh, epn, null);
                }
                candHost = null; // кандидат разрешён
                if (!tun && tunnelPort != 0) stopTunnel(); // LAN победил — туннель не нужен
                updateStatus("Подключено (" + pathRu() + "): " + bridgeName());
                broadcastState();
                socket.send("{\"type\":\"internet\"}"); // узнаём/обновляем p2p-адрес ПК
                registerNode(socket); // сообщаем мосту наш p2p-NodeId для allowlist
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                try {
                    JSONObject m = new JSONObject(text);
                    String type = m.optString("type");
                    if ("notify".equals(type)) {
                        showEvent(m.optString("title", "Cursor Agent"),
                                m.optString("message", "Событие"),
                                m.optString("id", ""),
                                m.optString("event", ""));
                    } else if ("status".equals(type)) {
                        // Agent снова работает (например, ответили на ПК) —
                        // уведомление этой сессии больше не актуально
                        if ("working".equals(m.optString("status"))) {
                            cancelEventNotif(m.optString("id"));
                        }
                    } else if ("sessions".equals(type)) {
                        int alive = 0;
                        var arr = m.optJSONArray("sessions");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                if (arr.getJSONObject(i).optBoolean("alive")) alive++;
                            }
                        }
                        updateStatus("Подключено (" + pathRu() + "): " + bridgeName() + " · сессий: " + alive);
                    } else if ("internet".equals(type)) {
                        // мост сообщил свой p2p-адрес — запоминаем на случай отъезда
                        String afp = store.activeFp();
                        String tk = m.optString("ticket", "");
                        if (afp != null && !tk.isEmpty()) store.setTicket(afp, tk);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "bad message", e);
                }
            }

            @Override
            public void onFailure(WebSocket socket, Throwable t, Response r) {
                Log.w(TAG, "ws failure: " + t + (r != null ? " http=" + r.code() : ""));
                onDown();
            }

            @Override
            public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, reason); // подтверждаем закрытие, иначе onClosed не наступит
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                if (code == 4401) {
                    // устройство отозвано на этом мосту: убираем его из списка;
                    // если остались другие ПК — активным станет первый из них
                    String afp = store.activeFp();
                    if (afp != null) store.remove(afp);
                    token = null;
                    updateStatus(store.active() != null
                            ? "Доступ отозван, переключаюсь на другой компьютер…"
                            : "Доступ отозван — откройте приложение для сопряжения");
                }
                onDown();
            }
        });
    }

    private void onDown() {
        if (destroyed) return; // обрыв вызван остановкой сервиса — не переподключаемся
        boolean was = connected;
        connected = false;
        connecting = false;
        viaTunnel = false;
        ws = null;
        // Непроверенный кандидат не подключился (или связь оборвалась) — сбрасываем
        // его, чтобы следующая попытка взяла последний СОХРАНЁННЫЙ (доверенный)
        // адрес, а не зависла на подсунутом. Свежий discovery снова его выставит (F4).
        candHost = null;
        if (was) {
            updateStatus(allowedNow() ? "Связь потеряна, переподключение…" : pausedText());
        }
        broadcastState();
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::connect, retryMs);
        retryMs = Math.min(retryMs * 2, 30000);
    }

    private void watchNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // появилась сеть (WiFi или мобильная) — пробуем переподключиться.
                // В WiFi даём LAN-обнаружению фору перед интернет-туннелем.
                retryMs = 2000;
                boolean wifi = false;
                try {
                    NetworkCapabilities c = cm.getNetworkCapabilities(network);
                    wifi = c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                } catch (Exception ignored) { }
                if (wifi) lanGraceUntil = System.currentTimeMillis() + 12_000;
                handler.post(() -> {
                    // сменилась сеть: в режиме «только WiFi» она могла стать запрещённой
                    if (!allowedNow()) { if (connected) pauseConnection(); return; }
                    if (!connected) connect();
                });
            }
        };
        cm.registerDefaultNetworkCallback(netCallback);
    }

    // ------------------------------------------------------ интернет-туннель

    private boolean tunnelBinMissing = false;

    private String bridgeName() {
        BridgeStore.Bridge a = store.active();
        return a != null && a.name != null ? a.name : String.valueOf(host);
    }

    /** Человекочитаемый путь связи для уведомления и настроек. */
    private String pathRu() {
        if (!viaTunnel) return "WiFi";
        if ("direct".equals(tunnelPath)) return "интернет, p2p напрямую";
        if ("relay".equals(tunnelPath)) return "интернет, через relay";
        return "интернет";
    }

    // ----- постоянная p2p-личность телефона (NodeId) для allowlist моста -----

    private java.io.File tunnelBin() {
        return new java.io.File(getApplicationInfo().nativeLibraryDir, "libcursortunnel.so");
    }

    private String phoneKeyPath() {
        return new java.io.File(getFilesDir(), "iroh_phone.key").getAbsolutePath();
    }

    private volatile boolean computingNodeId = false;

    /** Вычислить (один раз) и запомнить постоянный NodeId телефона. */
    private synchronized void ensurePhoneNodeId() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (p.getString("phoneNodeId", null) != null || computingNodeId) return;
        java.io.File bin = tunnelBin();
        if (!bin.exists()) return;
        computingNodeId = true;
        new Thread(() -> {
            try {
                Process proc = new ProcessBuilder(bin.getAbsolutePath(),
                        "id", "--secret-file", phoneKeyPath()).start();
                String id = null;
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        try {
                            JSONObject j = new JSONObject(line);
                            if ("id".equals(j.optString("event"))) { id = j.optString("node_id", null); break; }
                        } catch (Exception ignored) { }
                    }
                }
                proc.waitFor();
                if (id != null && !id.isEmpty()) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("phoneNodeId", id).apply();
                    Log.i(TAG, "p2p NodeId телефона готов");
                    // если уже подключены — сразу зарегистрируем
                    if (connected && ws != null) registerNode(ws);
                }
            } catch (Exception e) {
                Log.w(TAG, "не удалось получить NodeId телефона", e);
            } finally {
                computingNodeId = false;
            }
        }, "phone-node-id").start();
    }

    /** Сообщить мосту наш постоянный NodeId (для allowlist интернет-туннеля). */
    private void registerNode(WebSocket socket) {
        String id = getSharedPreferences(PREFS, MODE_PRIVATE).getString("phoneNodeId", null);
        if (id == null) { ensurePhoneNodeId(); return; } // вычислим и зарегистрируем позже
        if (socket == null) return;
        try {
            JSONObject j = new JSONObject();
            j.put("type", "registerNode");
            j.put("nodeId", id);
            socket.send(j.toString());
        } catch (Exception ignored) { }
    }

    /** Запустить сайдкар cursor-tunnel в режиме connect (телефон -> ПК). */
    private synchronized void ensureTunnel(String ticket) {
        if (tunnelProc != null || tunnelBinMissing) return;
        java.io.File bin = tunnelBin();
        if (!bin.exists()) {
            tunnelBinMissing = true;
            Log.w(TAG, "туннель недоступен: " + bin + " не найден");
            return;
        }
        final Process proc;
        try {
            // тот же secret-file, что и для команды id → стабильный NodeId,
            // который мост уже внёс в allowlist при регистрации по LAN
            // --ticket=<value> одним аргументом: тикет не спутается с флагом,
            // даже если начинается с «--» (ProcessBuilder не использует shell)
            proc = new ProcessBuilder(bin.getAbsolutePath(),
                    "connect", "--ticket=" + ticket, "--listen", "127.0.0.1:0",
                    "--secret-file", phoneKeyPath())
                    .start();
        } catch (Exception e) {
            Log.w(TAG, "не удалось запустить туннель", e);
            return;
        }
        tunnelProc = proc;
        updateStatus("ПК не виден в WiFi — пробую через интернет (p2p)…");
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    JSONObject j;
                    try { j = new JSONObject(line); } catch (Exception e) { continue; }
                    String ev = j.optString("event");
                    if ("ready".equals(ev)) {
                        tunnelPort = j.optInt("port", 0);
                        handler.post(BridgeService.this::connect);
                    } else if ("path".equals(ev)) {
                        tunnelPath = j.optString("path", null);
                        tunnelAddr = j.optString("addr", null);
                        broadcastState();
                        if (connected && viaTunnel) {
                            updateStatus("Подключено (" + pathRu() + "): " + bridgeName());
                        }
                    } else if ("error".equals(ev)) {
                        Log.w(TAG, "туннель: " + j.optString("message"));
                    }
                }
            } catch (Exception ignored) { }
            // сайдкар завершился
            synchronized (BridgeService.this) {
                if (tunnelProc == proc) {
                    tunnelProc = null;
                    tunnelPort = 0;
                    tunnelPath = null;
                    tunnelAddr = null;
                }
            }
            broadcastState();
        }, "tunnel-io");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void stopTunnel() {
        if (tunnelProc != null) {
            try { tunnelProc.destroy(); } catch (Exception ignored) { }
            tunnelProc = null;
        }
        tunnelPort = 0;
        tunnelPath = null;
        tunnelAddr = null;
    }

    // ------------------------------------------------------------ notifications

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel st = new NotificationChannel(CH_STATUS, "Состояние подключения",
                NotificationManager.IMPORTANCE_MIN);
        st.setShowBadge(false);
        nm.createNotificationChannel(st);
        NotificationChannel ev = new NotificationChannel(CH_EVENTS, "События Cursor Agent",
                NotificationManager.IMPORTANCE_HIGH);
        ev.enableVibration(true);
        // на локскрине содержимое (текст запроса Agent) скрыто по умолчанию
        ev.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(ev);
    }

    private Notification statusNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Cursor Bridge")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateStatus(String text) {
        lastStatus = text;
        // после onDestroy уведомление не трогаем: notify() от мёртвого сервиса
        // вернул бы на экран «вечное» уведомление, которое нечем снять
        if (destroyed) return;
        getSystemService(NotificationManager.class).notify(STATUS_ID, statusNotification(text));
    }

    private int reqSeq = 1000;

    /**
     * Секрет для подтверждения, что intent с extra «session» создан этим
     * приложением (уведомлением), а не чужим. Живёт в приватных prefs — переживает
     * смерть процесса, поэтому тап по уведомлению работает и после перезапуска.
     */
    static synchronized String notifAuthSecret(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = p.getString("notifAuth", null);
        if (s == null) {
            byte[] b = new byte[16];
            new java.security.SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            s = sb.toString();
            p.edit().putString("notifAuth", s).apply();
        }
        return s;
    }

    private Notification.Action quickAction(String sessionId, String data, String label,
                                            int nid, boolean requireAuth) {
        Intent i = new Intent(this, BridgeService.class)
                .setAction(ACTION_INPUT)
                .putExtra("session", sessionId)
                .putExtra("data", data)
                .putExtra("nid", nid);
        PendingIntent pi = PendingIntent.getService(this, reqSeq++, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action.Builder ab = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat),
                label, pi);
        // при включённой биометрии нажатие требует разблокировки устройства
        // (иначе ответ Agent можно было бы дать прямо с локскрина)
        if (requireAuth && Build.VERSION.SDK_INT >= 31) ab.setAuthenticationRequired(true);
        return ab.build();
    }

    /** Убрать событийное уведомление сессии (стало неактуальным). */
    private void cancelEventNotif(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        Integer nid;
        synchronized (eventNids) {
            nid = eventNids.remove(sessionId);
            eventKinds.remove(sessionId);
        }
        if (nid != null) getSystemService(NotificationManager.class).cancel(nid);
    }

    private void showEvent(String title, String message, String sessionId, String event) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        synchronized (eventNids) {
            // новое событие заменяет прежнее уведомление этой же сессии
            Integer prev = eventNids.remove(sessionId);
            if (prev != null) { nm.cancel(prev); eventKinds.remove(sessionId); }
            // «Agent завершил ответ» устаревает, как только пришло любое новое событие
            var it = eventNids.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if ("Stop".equals(eventKinds.get(e.getKey()))) {
                    nm.cancel(e.getValue());
                    eventKinds.remove(e.getKey());
                    it.remove();
                }
            }
        }
        int nid = eventSeq++;
        // cbAuth — секрет из приватных prefs: подтверждает, что intent с session
        // создан нами. Чужое приложение (экспортированная MainActivity) его не
        // знает, поэтому не сможет заставить нас переключить сессию (F15).
        Intent open = new Intent(this, MainActivity.class)
                .putExtra("session", sessionId)
                .putExtra("cbAuth", notifAuthSecret(this))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, nid, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean biolock = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("biolock", false);
        Notification.Builder b = new Notification.Builder(this, CH_EVENTS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(pi)
                // при биометрии на локскрине не показываем ничего, иначе только заголовок
                .setVisibility(biolock ? Notification.VISIBILITY_SECRET : Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true);
        // Agent ждёт ответа — отвечаем прямо из уведомления. Но при включённой
        // биометрии кнопки НЕ должны срабатывать с локскрина без разблокировки:
        //  • API ≥ 31: требуем разблокировку на нажатие (setAuthenticationRequired);
        //  • API < 31 (нельзя защитить кнопку): не показываем кнопки вовсе.
        if ("Notification".equals(event) && sessionId != null && !sessionId.isEmpty()
                && (!biolock || Build.VERSION.SDK_INT >= 31)) {
            b.addAction(quickAction(sessionId, "1", "Да (1)", nid, biolock));
            b.addAction(quickAction(sessionId, "2", "Всегда (2)", nid, biolock));
            b.addAction(quickAction(sessionId, "\u001b", "Нет (Esc)", nid, biolock));
        }
        if (sessionId != null && !sessionId.isEmpty()) {
            synchronized (eventNids) {
                eventNids.put(sessionId, nid);
                eventKinds.put(sessionId, event);
            }
        }
        nm.notify(nid, b.build());
    }

    private void broadcastState() {
        // список замеченных в сети мостов (для экрана «добавить компьютер»)
        org.json.JSONArray arr = new org.json.JSONArray();
        long now = System.currentTimeMillis();
        for (JSONObject d : discovered.values()) {
            if (now - d.optLong("at") < 60_000) arr.put(d);
        }
        sendBroadcast(new Intent(ACTION_STATE)
                .setPackage(getPackageName())
                .putExtra("host", host)
                .putExtra("port", port)
                .putExtra("connected", connected)
                .putExtra("path", connected
                        ? (viaTunnel ? "inet-" + (tunnelPath == null ? "" : tunnelPath) : "lan")
                        : "none")
                .putExtra("pathAddr", viaTunnel ? tunnelAddr : null)
                .putExtra("activeFp", store.activeFp())
                .putExtra("discovered", arr.toString()));
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onDestroy() {
        destroyed = true; // колбэки OkHttp/туннеля больше не должны переподключаться
        if (probeThread != null) probeThread.interrupt();
        try { if (discovery != null) nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {}
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (netCallback != null) cm.unregisterNetworkCallback(netCallback);
        } catch (Exception ignored) {}
        if (ws != null) ws.cancel();
        stopTunnel();
        try { if (screenReceiver != null) unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        if (mcLock != null && mcLock.isHeld()) mcLock.release();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
