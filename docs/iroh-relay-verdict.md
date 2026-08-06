# iroh relay — что это и вердикт для cursor-bridge

Дата среза: 2026-08-06. Тесты на ПК + Nothing Phone (LTE/WiFi), регион РФ.

## Что такое iroh relay

**Relay** — публично достижимый сервер (не «сота оператора»), который:

1. Помогает двум пирам обменяться адресами и сделать **hole punch** (прямой QUIC).
2. Если direct не вышел (CGNAT, firewall, LTE↔домашний NAT) — **временно гоняет уже зашифрованный** трафик.

Relay **не читает** содержимое: E2E на уровне iroh/QUIC. Приложение (наш TLS моста) — отдельный слой доверия.

В iroh у каждого endpoint есть **home relay** (ближайший по RTT из списка). Пиры находят друг друга через home relay + ticket / discovery.

Официально:

- Концепция: https://docs.iroh.computer/concepts/relays  
- Обзор стека: https://docs.iroh.computer/what-is-iroh  
- Свой / dedicated: https://docs.iroh.computer/deployment/dedicated-infrastructure  
- Managed hosting / цены: https://www.iroh.computer/services/hosting  
- Код relay: https://github.com/n0-computer/iroh/tree/main/iroh-relay  
- n0 (оператор public): https://n0.computer  

## Публичные relay n0 (покрытие)

Жёстко зашиты в preset `N0` (не интерактивная «карта сот РФ»):

| Host | Регион (по имени) |
|------|-------------------|
| `https://euc1-1.relay.n0.iroh.link/` | EU |
| `https://use1-1.relay.n0.iroh.link/` | US East |
| `https://usw1-1.relay.n0.iroh.link/` | US West |
| `https://aps1-1.relay.n0.iroh.link/` | Asia |

Доп. discovery DNS: `https://dns.iroh.link/`

**Отдельной публичной карты покрытия по России / по операторам LTE нет.**  
Близость смотри локально:

- `iroh-doctor report` (RTT до preferred relay)  
- HTTPS/UDP probe до хостов выше  
- В нашем туннеле: `path=relay` + `rtt_ms` в событии `path`

Public relay:

- бесплатны, **rate-limit**, без SLA  
- для dev/test; production — dedicated / self-host  

Источник: https://docs.iroh.computer/concepts/relays  

## Можно ли «заработать на собственной соте»?

Под «сотой» здесь имеется в виду **свой relay-узел** (покрытие для своих клиентов), не башня МТС.

| Модель | Реальность |
|--------|------------|
| **Self-host OSS** | Да, бесплатно forever. VPS с белым IP + DNS + TLS. Трафик/электричество — твои. |
| **Managed у n0** | Ты **платишь** n0 (cloud от ~$0.27/hour и выше на hosting page; раньше фигурировало ~$199/mo tier). Это расход, не доход. |
| **Стать «публичной сотой» и зарабатывать** | В экосистеме iroh **нет** маркетплейса «хости relay — получай с пиров», как у торрент-сидов. Заработок только если **свой продукт/SaaS** гоняет юзеров через твой relay (подписка, B2B). |
| **Открытый relay для всех** | Технически можно; риск abuse, трафик за свой счёт, польза только репутация/свой app. |

Итого: **своя сота = контроль покрытия и latency для своих устройств**, не пассивный доход с чужого iroh-трафика.

## Вердикт по cursor-bridge (факт с тестов)

### Работает

- WiFi / LAN WSS к мосту.  
- ПК ↔ public n0: `iroh-doctor`, HTTPS до 4 relay.  
- ПК **relay-only** connect к своему `cursor-tunnel listen`: **OK ~1–2 с**, `path=relay` (RTT порядка 100–450 ms до euc1).  
- Телефон: HTTPS до n0 OK; иногда `home-relay online` на LTE.  
- Ticket без VPN-мусора (только relay + `192.168.*`) — обязательно.

### Не хватает

**Надёжного relay-пути телефон (LTE/CGNAT, часто `100.82.x`) → ПК.**  
Симптом: `iroh connect timed out` / pair на LTE долбит `192.168.0.18`.  
ПК при этом с тем же ticket через relay коннектится. Узкое место — **доступность/стабильность public relay + QUIC с соты РФ**, не «iroh мёртв на ПК».

Публичные n0 **не заменяют** локальную/региональную «соту»: нет RU-региона в таблице, rate-limit, shared. Для LTE-first production **не хватает своего (или dedicated) relay ближе/стабильнее для абонента**.

### Не путать

- BitTorrent DHT/PEX — не dial одного NodeId и не «соты оператора».  
- SSH — рвётся при смене IP; сам CGNAT не пробивает.  
- Claude-bridge паттерн — тот же LAN-first; вне WiFi без рабочего relay та же дыра.

## Что смотреть по «покрытию»

Интерактивной карты «где iroh ловит в РФ» у n0 нет. Опора:

1. Список public hosts — таблица выше + docs relays.  
2. Hosting / регионы managed: https://www.iroh.computer/services/hosting  
3. Managed relays docs: https://docs.iroh.computer/iroh-services/relays/managed  
4. Self-host guide: https://docs.iroh.computer/deployment/dedicated-infrastructure  
5. Локальный замер: `iroh-doctor`, `node phone-cmd.js relay-ping` / bundle, событие `path` в туннеле.

## Следующий шаг (когда скажешь)

1. VPS (EU или РФ с нормальным UDP/QUIC) + `iroh-relay`.  
2. Оба конца `RelayMode::Custom` на этот URL (+ backup euc1).  
3. Один релиз: ticket в QR уже в ветке 0.1.13 — проверить LTE pair на **своём** relay.

> **2026-08, реализовано до VPS:** бесплатные пути вместо public-релея —
> WSS по IPv6 напрямую и через overlay (Tailscale/Radmin VPN/ZeroTier), iroh
> остаётся последним фолбэком. См. [ARCHITECTURE.md](../ARCHITECTURE.md)
> «Пути соединения». VPS-шаг остаётся опцией для полной независимости при
> сером IPv4 и отсутствии IPv6 у обоих провайдеров.
