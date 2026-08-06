package com.q.cursorbridge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Сопряжённые компьютеры (мосты). Активный компьютер определяет, куда
 * подключаются сервис, WebView и загрузка аудио. Плоские ключи prefs
 * (host/port/token/fp) всегда зеркалят активный мост — остальной код
 * продолжает читать их как раньше.
 *
 * Уникальный идентификатор компьютера — отпечаток его TLS-сертификата (fp).
 */
public final class BridgeStore {

    public static class Bridge {
        public String name;       // имя ПК (hostname)
        public String host;       // последний известный адрес в LAN
        public int port = 8790;
        public String token;      // токен доступа этого телефона
        public String fp;         // отпечаток сертификата = id компьютера
        public boolean internet;  // разрешён коннект через интернет (p2p)
        public String nodeTicket; // iroh-адрес моста, если тот дал
        public String[] endpoints; // WSS-кандидаты "kind|host:port" (kind: v6|ov), от моста

        JSONObject toJson() throws Exception {
            JSONObject j = new JSONObject();
            j.put("name", name).put("host", host).put("port", port)
             .put("token", token).put("fp", fp).put("internet", internet);
            if (nodeTicket != null) j.put("nodeTicket", nodeTicket);
            if (endpoints != null && endpoints.length > 0) {
                j.put("endpoints", new JSONArray(Arrays.asList(endpoints)));
            }
            return j;
        }

        static Bridge fromJson(JSONObject j) {
            Bridge b = new Bridge();
            b.name = j.optString("name", "компьютер");
            b.host = j.optString("host", null);
            b.port = j.optInt("port", 8790);
            b.token = j.optString("token", null);
            b.fp = j.optString("fp", null);
            b.internet = j.optBoolean("internet", false);
            b.nodeTicket = j.has("nodeTicket") ? j.optString("nodeTicket", null) : null;
            JSONArray eps = j.optJSONArray("endpoints");
            if (eps != null && eps.length() > 0) {
                String[] arr = new String[eps.length()];
                int n = 0;
                for (int i = 0; i < eps.length(); i++) {
                    String s = eps.optString(i, null);
                    if (s != null && !s.isEmpty()) arr[n++] = s;
                }
                b.endpoints = n == arr.length ? arr : Arrays.copyOf(arr, n);
            }
            return b;
        }
    }

    /** Разобрать список {k,u} от моста (/pair, internetMsg) в строки "k|u" (только v6/ov). */
    public static String[] parseEndpoints(JSONArray eps) {
        if (eps == null) return new String[0];
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e == null) continue;
            String k = e.optString("k", "ov");
            String u = e.optString("u", null);
            if (u == null || u.isEmpty()) continue;
            if ("v6".equals(k) || "ov".equals(k)) out.add(k + "|" + u);
        }
        return out.toArray(new String[0]);
    }

    /** WSS-кандидаты для интернета из endpoints моста: сначала v6, затем overlay. */
    public static String[][] remoteCandidates(Bridge b) {
        if (b == null || b.endpoints == null) return new String[0][];
        java.util.ArrayList<String[]> v6 = new java.util.ArrayList<>();
        java.util.ArrayList<String[]> ov = new java.util.ArrayList<>();
        for (String ep : b.endpoints) {
            if (ep == null) continue;
            int bar = ep.indexOf('|');
            String kind = bar > 0 ? ep.substring(0, bar) : "ov";
            String u = bar > 0 ? ep.substring(bar + 1) : ep;
            int colon = u.lastIndexOf(':');
            if (colon <= 0) continue;
            String hostP = u.substring(0, colon);
            String portS = u.substring(colon + 1);
            int portN;
            try { portN = Integer.parseInt(portS); } catch (Exception ignored) { continue; }
            if (hostP.isEmpty() || portN <= 0) continue;
            if ("v6".equals(kind)) v6.add(new String[]{hostP, String.valueOf(portN), "v6"});
            else if ("ov".equals(kind)) ov.add(new String[]{hostP, String.valueOf(portN), "ov"});
        }
        v6.addAll(ov);
        return v6.toArray(new String[0][]);
    }

    private final SharedPreferences prefs;

    public BridgeStore(Context c) {
        prefs = c.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE);
        migrate();
    }

    /** Старая схема (один ПК в плоских ключах) переезжает в список. */
    private void migrate() {
        if (prefs.contains("bridges")) return;
        String token = prefs.getString("token", null);
        String fp = prefs.getString("fp", null);
        if (token == null || fp == null) return;
        Bridge b = new Bridge();
        b.host = prefs.getString("host", null);
        b.name = b.host != null ? b.host : "компьютер";
        b.port = prefs.getInt("port", 8790);
        b.token = token;
        b.fp = fp;
        List<Bridge> list = new ArrayList<>();
        list.add(b);
        save(list, fp);
    }

    public synchronized List<Bridge> list() {
        List<Bridge> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("bridges", "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(Bridge.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) { }
        return out;
    }

    public synchronized String activeFp() {
        return prefs.getString("activeFp", null);
    }

    public synchronized Bridge active() {
        return byFp(activeFp());
    }

    public synchronized Bridge byFp(String fp) {
        if (fp == null) return null;
        for (Bridge b : list()) if (fp.equalsIgnoreCase(b.fp)) return b;
        return null;
    }

    /** Добавить новый ПК или обновить существующий (по fp). */
    public synchronized void addOrUpdate(Bridge b, boolean makeActive) {
        List<Bridge> l = list();
        boolean found = false;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).fp != null && l.get(i).fp.equalsIgnoreCase(b.fp)) {
                l.set(i, b);
                found = true;
                break;
            }
        }
        if (!found) l.add(b);
        save(l, makeActive ? b.fp : activeFp());
    }

    /** Обновить адрес/имя ПК, найденного в сети. Ничего не делает для чужих fp. */
    public synchronized void updateHost(String fp, String host, int port, String name) {
        List<Bridge> l = list();
        boolean changed = false;
        for (Bridge b : l) {
            if (b.fp != null && b.fp.equalsIgnoreCase(fp)) {
                if (!host.equals(b.host) || port != b.port) { b.host = host; b.port = port; changed = true; }
                if (name != null && !name.isEmpty() && !name.equals(b.name)) { b.name = name; changed = true; }
            }
        }
        if (changed) save(l, activeFp());
    }

    /** Удалить ПК. Если он был активным — активным станет первый оставшийся. */
    public synchronized void remove(String fp) {
        List<Bridge> l = list();
        l.removeIf(b -> b.fp != null && b.fp.equalsIgnoreCase(fp));
        String act = activeFp();
        if (act != null && act.equalsIgnoreCase(fp)) {
            act = l.isEmpty() ? null : l.get(0).fp;
        }
        save(l, act);
    }

    public synchronized void setActive(String fp) {
        if (byFp(fp) != null) save(list(), fp);
    }

    /** Пользовательский флаг «разрешить доступ через интернет» для ПК. */
    public synchronized void setInternet(String fp, boolean on) {
        List<Bridge> l = list();
        boolean ch = false;
        for (Bridge b : l) {
            if (b.fp != null && b.fp.equalsIgnoreCase(fp) && b.internet != on) {
                b.internet = on;
                ch = true;
            }
        }
        if (ch) save(l, activeFp());
    }

    /** Запомнить p2p-адрес (iroh ticket) моста — узнаём его, будучи в LAN. */
    public synchronized void setTicket(String fp, String ticket) {
        if (ticket == null || ticket.isEmpty()) return;
        List<Bridge> l = list();
        boolean ch = false;
        for (Bridge b : l) {
            if (b.fp != null && b.fp.equalsIgnoreCase(fp) && !ticket.equals(b.nodeTicket)) {
                b.nodeTicket = ticket;
                ch = true;
            }
        }
        if (ch) save(l, activeFp());
    }

    /** Обновить WSS-эндпоинты (v6/overlay) моста — приходят в /pair и internetMsg. */
    public synchronized void setEndpoints(String fp, String[] eps) {
        if (fp == null || eps == null) return;
        List<Bridge> l = list();
        boolean ch = false;
        for (Bridge b : l) {
            if (b.fp != null && b.fp.equalsIgnoreCase(fp) && !Arrays.equals(b.endpoints, eps)) {
                b.endpoints = eps;
                ch = true;
            }
        }
        if (ch) save(l, activeFp());
    }

    private void save(List<Bridge> l, String activeFp) {
        try {
            JSONArray arr = new JSONArray();
            for (Bridge b : l) arr.put(b.toJson());
            SharedPreferences.Editor e = prefs.edit()
                    .putString("bridges", arr.toString())
                    .putString("activeFp", activeFp);
            // зеркало активного моста в плоских ключах — их читает весь старый код
            Bridge a = null;
            if (activeFp != null) {
                for (Bridge b : l) if (activeFp.equalsIgnoreCase(b.fp)) { a = b; break; }
            }
            if (a != null) {
                e.putString("token", a.token).putString("fp", a.fp).putInt("port", a.port);
                if (a.host != null) e.putString("host", a.host); else e.remove("host");
            } else {
                e.remove("token").remove("fp").remove("host").remove("activeFp");
            }
            e.apply();
        } catch (Exception ignored) { }
    }
}
