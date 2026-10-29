# Fight Exchange – detailní plán backendu

Stav k 29. 10. 2026. Přehled a zdůvodnění rozhodnutí jsou v dokumentu
[Fight Exchange – plán backendu po fázích](https://claude.ai/artifact/URyVVy6UtQJ4YxGTXFmVeW),
zdroje dat v dokumentu [Zdroje dat pro Fantasy MMA](https://claude.ai/artifact/9jqzEo9z3xw6mANuuid4ao).
Tento soubor je pracovní rozpis: co přesně se v každé fázi postaví a kdy je fáze hotová.

Stack: Kotlin 2 (JVM 21), Micronaut 4, PostgreSQL 16, Flyway, Micronaut Data JDBC,
Gradle (Kotlin DSL), JUnit 5 / Kotest, Testcontainers. Frontend se neřeší.

| Fáze | Obsah | Odhad | Výsledek |
| --- | --- | --- | --- |
| 0 | Základy a přenos simulace | 2–3 týdny | modul `rules` ověřený proti `sim.py` |
| 1 | Sportovní data a admin | 2–3 týdny | admin zadá event, přepočítá se Elo |
| 2 | Trh a ceny | 2–3 týdny | ceny se hýbou podle událostí |
| 3 | Hráči, portfolio, obchodování | 2–3 týdny | uzavřená beta s ručními daty |
| 4 | Dividendy, kapitán, žebříčky | 2 týdny | kompletní herní smyčka |
| 5 | Automatická data | 2–3 týdny | UFC, PFL, Oktagon bez ručního zadávání |
| 6 | Ostrý provoz a ligy | 2–3 týdny | veřejné spuštění |

---

## Společné konvence (platí pro všechny fáze)

**Struktura repozitáře**

```
fantasy-mma-v2-be/
  rules/            čistý Kotlin, žádný Micronaut ani DB: výpočty cen, Elo, dividend, limitů
  app/              Micronaut aplikace
    src/main/kotlin/.../
      sport/        organizace, zápasníci, eventy, zápasy, výsledky
      ingest/       příjem událostí, adaptéry zdrojů, párování
      market/       ceny, Elo, neaktivita, IPO
      trading/      uživatelé, portfolia, obchody, účetní kniha
      payout/       dividendy, kapitán, shorty
      leaderboard/  čisté jmění, žebříčky, sprinty
      admin/        admin API a fronta ke kontrole
      common/       peníze, čas, chyby, stránkování
    src/main/resources/db/migration/   Flyway V001__...sql
  sim/              kopie sim.py + skript na generování referenčních dat
  docs/
```

**Peníze.** Typ `Money` = `Long` v celých jednotkách herní měny (1 M = 1 000 000).
Nikdy `Double`. Násobení procentem zaokrouhluje `HALF_EVEN` na celé jednotky.
Ceny zápasníků se ukládají stejně.

**Čas.** Vše v UTC (`timestamptz`, `Instant`). Herní týden začíná v pondělí 00:00 UTC
(reset nákupů zdarma). Datum eventu v místním čase organizace se ukládá zvlášť jen pro zobrazení.

**Pravidla jako data.** Každé číslo z pravidel je v `rule_set` (JSON v DB, verzované,
`valid_from`). Kód čte vždy verzi platnou k času akce. Výchozí hodnoty pravidel v2:

| Parametr | Hodnota | Význam |
| --- | --- | --- |
| `budget` | 100 M | počáteční hotovost |
| `eloStart`, `eloK`, `eloFinishMult` | 1500, 40, 1,25 | Elo; ukončení váží víc |
| `priceAt1500`, `priceScale`, `priceMin` | 6 M, 200, 0,5 M | cena = 6 × e^((Elo − 1500) / 200), min. 0,5 M |
| `annNormal`, `annMain`, `annTitle` | +2 %, +5 %, +10 % | růst při ohlášení |
| `annStrongerOpp` | +1 / +2 / +3 % | soupeř o 0–100 / 100–200 / 200+ Elo silnější |
| `cancelDrop` | −5 až −10 % | zrušení, zranění |
| `oddsDrift` | 0,05 | pohyb ceny podle rozdílu kurzy vs. Elo v týdnu zápasu |
| `inactiveWeeks`, `decay`, `delistWeeks` | 26, 1 % týdně, 78 | neaktivita a stažení |
| `demandCap` | ±30 % | strop faktoru poptávky |
| `baseDec`, `baseFin`, `oddsCap` | 1, 1,5, 3,5 | základ dividendy a strop kurzu |
| `yieldScale` | 0,25 | dividenda v % ceny (pravidla v2) |
| `bonusR1`, `bonusNight`, `bonusTitle`, `weightMiss` | +0,5, +1, +3, −1 M | bonusy (nenásobí se) |
| `captainMult` | 2 | kapitán na event |
| `fee` | 1 % | poplatek za obchod |
| `freeBuysPerWeek`, `extraBuyFee` | 2, 3 % ceny | nákupy zdarma a přirážka |
| `minRoster`, `maxRoster`, `maxShorts`, `minNonUfc` | 8, 15, 3, 2 | soupiska |

Hodnoty se po kalibraci mění jen v `rule_set`, ne v kódu.

**API.** REST pod `/api/v1`, JSON, OpenAPI generované z kódu (`/swagger`).
Chyby jako `application/problem+json` s kódem (`INSUFFICIENT_CASH`, `TRADING_LOCKED`, …).
Stránkování kurzorem. Admin endpointy pod `/api/v1/admin`, role `ADMIN`.

**Testy.** Každá fáze: jednotkové testy `rules`, integrační testy endpointů proti
Postgresu v Testcontainers, CI (GitHub Actions) musí být zelené před merge.

---

## Fáze 0 – Základy a přenos simulace (2–3 týdny)

Cíl: běžící kostra aplikace a modul `rules`, který počítá přesně jako `sim.py`.

**Úkoly**

1. Gradle multi-projekt `rules` + `app`, Kotlin 2, JVM 21, ktlint/detekt.
2. Micronaut 4 aplikace s health endpointem `GET /health`, konfigurace přes env proměnné.
3. Docker Compose pro lokální vývoj (Postgres 16), Flyway s první prázdnou migrací.
4. GitHub Actions: build, testy, Testcontainers.
5. Modul `rules` (čisté funkce, vstupy a výstupy jako datové třídy):
   - `Elo.expected(a, b)`, `Elo.update(a, b, result, finish)`
   - `Pricing.basePrice(elo)`, `Pricing.announcementBump(type, eloDiff)`, `Pricing.oddsDrift(...)`, `Pricing.applyDemand(base, factor)`
   - `Inactivity.weeklyFactor(weeksSinceFight, hasAnnounced)`, `Inactivity.shouldDelist(...)`
   - `Dividend.forWinner(result, odds, price, isCaptain, bonuses)`, `Dividend.forShort(...)`
   - `Eligibility.dividendEligible(boughtAt, announcedAt)` (FIFO po kusech)
   - `TradeCost.buy(price, buysThisWeek, rules)`, `TradeCost.sell(price, rules)`
   - `RosterRules.validate(roster, action)` (8–15, max. 3 shorty, min. mimo UFC, ne oba soupeři ohlášeného zápasu)
   - `RuleSet` datová třída se všemi parametry výše
6. Referenční data: do `sim/` přidat `sim.py` a skript `export_golden.py`, který pro
   ~500 náhodných vstupů každé funkce zapíše vstup a výstup do `rules/src/test/resources/golden/*.json`.
7. Golden testy v Kotlinu: pro každý záznam stejný výsledek (tolerance 1 jednotka měny po zaokrouhlení).
8. Přehrání sezóny 2025: Kotlin verze smyčky `run()` a strategií jako test, porovnání
   s tabulkou v `vysledky_v2.md` (čisté jmění strategií ±2 %).

**Tabulky:** `rule_set (id, version, valid_from, params jsonb)`.

**Endpointy:** `GET /health`.

**Hotovo, když:** CI je zelené, všechny golden testy procházejí, přehrání 2025 v Kotlinu
odpovídá Python simulaci. Od té chvíle je pravdou Kotlin; `sim.py` slouží jen ke kalibraci.

---

## Fáze 1 – Sportovní data a admin (2–3 týdny)

Cíl: v systému jsou zápasníci, eventy a zápasy všech pěti organizací, admin umí zadat
celý event a výsledky, přepočítá se Elo.

**Tabulky (Flyway)**

| Tabulka | Klíčové sloupce |
| --- | --- |
| `organization` | `id`, `code` (UFC, PFL, ONE, OKT, KSW), `name`, `manual_data bool` |
| `fighter` | `id`, `name`, `nickname`, `sex`, `division`, `organization_id`, `country`, `created_at` |
| `fighter_external_id` | `fighter_id`, `source`, `external_id`, unikátní (`source`, `external_id`) |
| `event` | `id`, `organization_id`, `name`, `starts_at`, `weigh_in_at`, `status`, `source`, `external_id` |
| `bout` | `id`, `event_id`, `fighter_a_id`, `fighter_b_id`, `bout_type` (NORMAL, MAIN, TITLE), `status` (ANNOUNCED, CONFIRMED, NEEDS_REVIEW, CANCELLED, COMPLETED), `announced_at_source`, `announced_at_seen`, `announced_at_admin`, `announced_at` (vypočtené: nejdřívější) |
| `bout_result` | `bout_id`, `version`, `winner_id` / remíza / NC, `method` (KO_TKO, SUB, DEC_U, DEC_S, DEC_M, DQ, NC), `round`, `time`, `title_won`, `is_current` |
| `bout_bonus` | `bout_id`, `fighter_id`, `type` (FIGHT_OF_NIGHT, PERFORMANCE) |
| `weight_miss` | `bout_id`, `fighter_id` |
| `odds_snapshot` | `bout_id`, `source`, `taken_at`, `odds_a`, `odds_b`, `is_closing` |
| `ranking_snapshot` | `division`, `taken_at`, `fighter_id`, `rank` |
| `elo_rating` | `fighter_id`, `bout_id`, `rating_before`, `rating_after`, `rated_at` |
| `ingest_event` | `id`, `source`, `external_key`, `type`, `payload jsonb`, `status` (NEW, APPLIED, NEEDS_REVIEW, REJECTED, DUPLICATE), `received_at`, `applied_at`, unikátní (`source`, `external_key`) |
| `review_item` | `id`, `ingest_event_id`, `reason` (FIGHTER_MATCH, CANCELLATION, RESULT_CONFIRM, UNKNOWN_BOUT), `status`, `resolved_by`, `resolved_at` |

**Ingest (jádro, bez externích zdrojů)**

- `IngestService.submit(event)`: uloží do `ingest_event`, duplicitu (`source` + `external_key`) označí `DUPLICATE`.
- `IngestProcessor`: zpracuje `NEW` události v pořadí přijetí, každou v jedné DB transakci.
- Typy událostí: `fighter.upsert`, `event.upsert`, `bout.announced`, `bout.changed`,
  `bout.cancelled`, `odds.snapshot`, `bout.result`, `ranking.snapshot` (schéma payloadu
  jako Kotlin `sealed class`, verze schématu v payloadu).
- `FighterMatcher`: nejdřív `fighter_external_id`, pak normalizované jméno (bez diakritiky,
  malá písmena) + divize; nejistá shoda → `review_item`.
- Které události čekají na potvrzení adminem, určuje konfigurace (výchozí: `bout.result`, `bout.cancelled`).
- Admin formuláře volají stejný `IngestService` se `source = ADMIN`.

**Elo:** po potvrzení `bout.result` zapsat `elo_rating` pro oba zápasníky (`rules.Elo`).
Oprava výsledku = nová verze `bout_result`, přepočet Elo od toho zápasu dál.

**Import historie:** příkaz `./gradlew :app:importHistory --file=...` načte CSV
(stejný dataset jako simulace pro UFC; pro ostatní organizace ruční CSV) a spočítá
počáteční Elo chronologicky.

**Endpointy**

| Metoda | Cesta | Účel |
| --- | --- | --- |
| GET | `/api/v1/organizations` | seznam organizací |
| GET | `/api/v1/fighters?org=&division=&q=` | hledání zápasníků |
| GET | `/api/v1/fighters/{id}` | detail včetně Elo a posledních zápasů |
| GET | `/api/v1/events?from=&to=&org=` | kalendář eventů |
| GET | `/api/v1/events/{id}` | karta eventu se zápasy a výsledky |
| POST | `/api/v1/admin/fighters` | založit zápasníka |
| POST | `/api/v1/admin/fighters/{id}/merge` | sloučit duplicitu do jiného zápasníka |
| POST | `/api/v1/admin/events` | založit event (včetně času vážení) |
| POST | `/api/v1/admin/events/{id}/bouts` | přidat zápas (ohlášení) |
| PATCH | `/api/v1/admin/bouts/{id}` | změna soupeře, typu, času ohlášení |
| POST | `/api/v1/admin/bouts/{id}/cancel` | zrušení s důvodem a kdo vypadl |
| POST | `/api/v1/admin/bouts/{id}/result` | výsledek, bonusy, přetížení, titul |
| POST | `/api/v1/admin/bouts/{id}/odds` | ruční kurz |
| POST | `/api/v1/admin/events/{id}/results` | hromadné zadání výsledků celé karty |
| GET | `/api/v1/admin/review` | fronta ke kontrole |
| POST | `/api/v1/admin/review/{id}/resolve` | potvrdit, zamítnout, přiřadit zápasníka |
| POST | `/api/v1/admin/import/history` | upload CSV historie |

**Přihlášení adminů:** zatím jeden statický admin účet přes Micronaut Security (basic → JWT), plné účty ve fázi 3.

**Testy:** idempotence ingestu (stejná událost 2× = 1 změna), párování jmen (diakritika,
přehozené pořadí jmen), přepočet Elo po opravě výsledku, hromadné zadání karty.

**Hotovo, když:** admin zadá kompletní event Oktagonu (karta, výsledky, bonusy) za
≤ 15 minut přes API, Elo se přepočítá a import historie UFC dá stejné Elo jako simulace.

---

## Fáze 2 – Trh a ceny (2–3 týdny)

Cíl: každý aktivní zápasník má cenu, která se hýbe podle událostí, a každá změna má důvod.

**Tabulky**

| Tabulka | Klíčové sloupce |
| --- | --- |
| `market_listing` | `fighter_id` (PK), `status` (IPO, ACTIVE, DELISTED), `base_value`, `event_multiplier`, `demand_factor`, `price`, `listed_at`, `delisted_at`, `last_fight_at` |
| `price_event` | `id`, `fighter_id`, `at`, `kind` (ELO, ANNOUNCED, OPPONENT_BONUS, CANCELLED, RANKING, ODDS_DRIFT, INACTIVITY, DEMAND, RESULT_RESET, DELIST), `factor`, `price_before`, `price_after`, `bout_id`, `ingest_event_id`, `note` |
| `price_daily` | `fighter_id`, `day`, `open`, `close`, `low`, `high` (pro grafy, plní úloha) |

**Model ceny** (podle `sim.py`):
`cena = základní hodnota (z Elo) × multiplikátor událostí × faktor poptávky`.
Multiplikátor událostí se po odzápasení vrací na 1 (cenu pak určuje nové Elo).
Faktor poptávky zatím 1 (zapne se ve fázi 3).

**`MarketService` – reakce na události**

| Událost | Dopad |
| --- | --- |
| `bout.announced` | ×(1 + 2/5/10 %) + bonus za silnějšího soupeře; zastaví neaktivitu |
| `bout.changed` | přepočet bonusu za soupeře (rozdíl proti původnímu) |
| `bout.cancelled` (potvrzené) | −5 až −10 % tomu, kdo vypadl; soupeři se vrátí bonus |
| `odds.snapshot` v týdnu zápasu | `oddsDrift × (pravděpodobnost z kurzů − Elo očekávání)` |
| `ranking.snapshot` | ± podle změny pozice (koeficient v `rule_set`) |
| `bout.result` (potvrzený) | nové Elo → nová základní hodnota, multiplikátor = 1 |

**Plánované úlohy**

| Úloha | Kdy | Co dělá |
| --- | --- | --- |
| `InactivityJob` | denně 03:00 UTC | týden po týdnu −1 % od 26 týdnů bez zápasu (pokud nemá ohlášený zápas); od 78 týdnů stažení |
| `PriceDailyJob` | denně 00:05 UTC | uzavře `price_daily` za předchozí den |
| `IpoJob` | při `fighter.upsert` | nový zápasník → `market_listing` IPO s cenou z počátečního Elo (debutant = `eloStart` nebo nižší dle `rule_set`) |

Všechny úlohy přes Micronaut `@Scheduled` se zámkem ShedLock v Postgresu, idempotentní (znovuspuštění za stejný den nic nezmění).

**Endpointy**

| Metoda | Cesta | Účel |
| --- | --- | --- |
| GET | `/api/v1/market?org=&division=&sort=price nebo change7d` | přehled trhu |
| GET | `/api/v1/market/{fighterId}` | aktuální cena a rozpad (základ, události, poptávka) |
| GET | `/api/v1/market/{fighterId}/history?from=&to=` | denní ceny pro graf |
| GET | `/api/v1/market/{fighterId}/events` | seznam změn ceny s důvodem |
| POST | `/api/v1/admin/market/{fighterId}/delist` | ruční stažení |
| POST | `/api/v1/admin/market/replay` | přepočet cen od data (po opravách dat) |

**Testy:** každý typ `price_event` jednotkově, neaktivita přes hranice 26 a 78 týdnů,
ohlášený zápas zastaví pokles, přehrání všech událostí roku 2025 přes ingest dá stejné
ceny jako simulace (koncové ceny ±1 %).

**Hotovo, když:** přehrání roku 2025 vytvoří cenové křivky odpovídající simulaci a
endpoint historie vrací graf s vysvětlením každého pohybu.

---

## Fáze 3 – Hráči, portfolio a obchodování (2–3 týdny)

Cíl: hráči se registrují, skládají portfolio a obchodují podle všech limitů. Začátek uzavřené bety.

**Tabulky**

| Tabulka | Klíčové sloupce |
| --- | --- |
| `app_user` | `id`, `email` (unikátní), `password_hash` nebo magic link, `display_name`, `role`, `created_at` |
| `season` | `id`, `name`, `starts_at`, `ends_at`, `rule_set_id` |
| `portfolio` | `id`, `user_id`, `season_id`, `league_id` (null = globální trh), `created_at` |
| `position_lot` | `id`, `portfolio_id`, `fighter_id`, `side` (LONG, SHORT), `opened_at`, `open_price`, `closed_at`, `close_price` (jeden kus = jeden lot, kvůli FIFO a nároku na dividendu) |
| `trade` | `id`, `portfolio_id`, `fighter_id`, `kind` (BUY, SELL, SHORT_OPEN, SHORT_CLOSE, FORCED_SELL), `price`, `fee`, `extra_fee`, `free_buy bool`, `at`, `idempotency_key` |
| `ledger_entry` | `id`, `portfolio_id`, `at`, `kind` (START_BUDGET, TRADE, FEE, EXTRA_FEE, DIVIDEND, SHORT_SETTLE, DELIST_PAYOUT, REVERSAL), `amount` (±), `trade_id`, `bout_id`, `reverses_id` |
| `weekly_buy_counter` | `portfolio_id`, `week_start`, `buys` |

Hotovost = `SUM(ledger_entry.amount)`; pro rychlost navíc `portfolio.cash_cached`
aktualizovaný ve stejné transakci, noční kontrola shody.

**Obchodní pravidla (`TradingService`, vše v jedné transakci s `SELECT … FOR UPDATE` na portfolio)**

1. Zápasník musí být `ACTIVE` nebo `IPO` a nesmí být v uzávěrce (od `event.weigh_in_at` do potvrzení výsledku).
2. Nákup: cena + 1 % poplatek; 3. a další nákup v týdnu + 3 % ceny; první týden sezóny zdarma.
3. Soupiska po akci: max. 15 longů, max. 3 shorty, nelze long i short na stejného,
   nelze držet oba soupeře ohlášeného zápasu, aspoň 2 mimo UFC (kontroluje se při
   prodeji, který by limit porušil, a v době eventu pro výplatu dividendy).
4. Pod 8 zápasníky lze obchodovat, ale dividendy se nevyplácejí (řeší fáze 4).
5. Hotovost nesmí jít do mínusu (krom zúčtování shortu).
6. `idempotency_key` z klienta: opakovaný požadavek vrátí stejný výsledek.

**Faktor poptávky:** `DemandJob` každou hodinu spočítá čisté nákupy − prodeje za
posledních 7 dní vůči počtu aktivních portfolií, převede na faktor a omezí na ±30 %.
Změna se zapíše jako `price_event` typu `DEMAND`.

**Stažení z trhu:** při `DELIST` se všechny loty vyplatí za poslední cenu (`FORCED_SELL`, `DELIST_PAYOUT`).

**Endpointy**

| Metoda | Cesta | Účel |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | registrace |
| POST | `/api/v1/auth/login` | přihlášení, vrací JWT |
| POST | `/api/v1/auth/magic-link` | přihlášení e-mailem (volitelné pro betu) |
| GET | `/api/v1/me` | profil |
| POST | `/api/v1/portfolios` | vstup do sezóny (dostane rozpočet 100 M) |
| GET | `/api/v1/portfolios/{id}` | hotovost, pozice, hodnota, čisté jmění |
| GET | `/api/v1/portfolios/{id}/ledger` | pohyby hotovosti |
| GET | `/api/v1/portfolios/{id}/trades` | historie obchodů |
| POST | `/api/v1/portfolios/{id}/quote` | náhled ceny obchodu (poplatek, přirážka, zbývající nákupy zdarma, porušení limitů) |
| POST | `/api/v1/portfolios/{id}/trades` | provést obchod (`kind`, `fighterId`, `idempotencyKey`) |

**Testy:** souběžné nákupy na stejné portfolio (žádný dvojí odpis), všechny limity
soupisky, přirážka od 3. nákupu, reset v pondělí, uzávěrka při vážení, stažení z trhu
s výplatou, ledger vždy sedí s `cash_cached`.

**Hotovo, když:** 10–20 testovacích hráčů složí portfolio přes API, noční kontrola
ledgeru nehlásí rozdíl a obchody v uzávěrce se odmítnou. Start uzavřené bety s ručními daty.

---

## Fáze 4 – Dividendy, kapitán a žebříčky (2 týdny)

Cíl: kompletní herní smyčka od ohlášení po výplatu a pořadí hráčů.

**Tabulky**

| Tabulka | Klíčové sloupce |
| --- | --- |
| `captain_pick` | `portfolio_id`, `event_id`, `fighter_id`, `picked_at` (zamyká se při vážení) |
| `dividend_payout` | `id`, `portfolio_id`, `bout_id`, `fighter_id`, `lot_id`, `kind` (WIN, SHORT), `base`, `odds_used`, `bonuses`, `captain`, `amount`, `ledger_entry_id`, `result_version` |
| `net_worth_snapshot` | `portfolio_id`, `day`, `cash`, `long_value`, `short_value`, `net_worth` |
| `leaderboard_entry` | `season_id`, `period` (SEASON, MONTH-2026-11, …), `portfolio_id`, `rank`, `net_worth`, `change` |

**`PayoutService`** (spouští se po potvrzení `bout.result`, idempotentní podle `bout_id` + `result_version`):

1. Pro každý lot na zápasníky ze zápasu: nárok jen když `opened_at < bout.announced_at`
   a portfolio mělo v čase eventu ≥ 8 zápasníků.
2. Výhra: `cena × yieldScale × (základ × min(kurz, 3,5)) / 1,2` + bonusy
   (1. kolo +0,5 M, bonus večera +1 M, titul +3 M, přetížení −1 M); kapitán ×2.
   Kurz = závěrečný `odds_snapshot` (`is_closing`), bez kurzu podle náhradního pravidla (admin / Elo).
3. Short: podle `rules.Dividend.forShort` (výhra zápasníka = hráč platí).
4. Remíza a no contest: 0.
5. Oprava výsledku: storno všech výplat staré verze (`REVERSAL`) a nový výpočet.

**Plánované úlohy**

| Úloha | Kdy | Co dělá |
| --- | --- | --- |
| `NetWorthJob` | denně 00:15 UTC | snímek čistého jmění všech portfolií |
| `LeaderboardJob` | denně po `NetWorthJob` | celkové pořadí a měsíční sprint (změna čistého jmění od 1. dne měsíce) |
| `MonthCloseJob` | 1. den v měsíci 01:00 UTC | uzavře sprint, uloží vítěze |
| `CaptainLockJob` | každých 5 min | zamkne kapitány eventů po čase vážení |

**Endpointy**

| Metoda | Cesta | Účel |
| --- | --- | --- |
| PUT | `/api/v1/portfolios/{id}/captain/{eventId}` | zvolit kapitána |
| GET | `/api/v1/portfolios/{id}/dividends` | výplaty s rozpadem výpočtu |
| GET | `/api/v1/portfolios/{id}/net-worth?from=&to=` | vývoj čistého jmění |
| GET | `/api/v1/leaderboard?season=&period=` | žebříček |
| GET | `/api/v1/events/{id}/payouts/summary` | souhrn výplat za event |
| POST | `/api/v1/admin/bouts/{id}/payout/rerun` | ruční přepočet výplat zápasu |

**Testy:** příklad z pravidel (outsider, KO v 1. kole + bonus) na přesnou částku,
nárok podle času ohlášení (nákup minutu před a po), kapitán, short výhra i prohra,
oprava výsledku (storno + nová výplata), soupiska pod 8, golden testy z fáze 0 přes celou službu.

**Hotovo, když:** jeden reálný event projde celým cyklem v betě a výplaty souhlasí s ručním výpočtem.

---

## Fáze 5 – Automatická data (2–3 týdny, adaptéry lze začít souběžně s fází 2)

Cíl: UFC, PFL a Oktagon jdou bez ručního zadávání, ONE a KSW zadává admin.

**Adaptéry** (balíček `ingest.source`, každý implementuje `SourceAdapter` a produkuje jen události z fáze 1)

| Adaptér | Zdroj | Co dodává | Jak |
| --- | --- | --- | --- |
| `UfcalendarAdapter` | UFCalendar Pro (49 USD/měs.) | `fighter.upsert`, `event.upsert`, `bout.announced/changed/cancelled`, `bout.result` vč. bonusů, `ranking.snapshot` (UFC) | webhook `POST /api/v1/ingest/webhooks/ufcalendar` (ověření podpisu) + noční plné stažení příštích 90 dní a posledních 7 dní |
| `TheOddsApiAdapter` | The Odds API 20K (30 USD/měs.) | `odds.snapshot` (vítěz) | polling: denně; 7 dní před eventem každé 3 h; v den eventu každou hodinu; hlídání kreditů |
| `OddsApiIoAdapter` | odds-api.io (jen pokud The Odds API nepokryje ONE a KSW) | `odds.snapshot` | stejné rozhraní, zapíná se konfigurací |
| Admin | formuláře z fáze 1 | ONE a KSW celé, u všech čas vážení, přetížení, potvrzení | – |

**Logika ohlášení** (podle průzkumu zdrojů):

1. Feed karet vytvoří zápas `ANNOUNCED` a spustí růst ceny.
2. První kurz k existujícímu zápasu jen potvrdí stav (`CONFIRMED`) a zapne pohyb podle kurzů.
3. Kurz k neznámému zápasu → `review_item` (`UNKNOWN_BOUT`), bez vlivu na cenu.
4. Zrušení z feedu nebo kurzy chybí 48 h a déle → `NEEDS_REVIEW`; pokles ceny až po potvrzení adminem.
5. `announced_at` = nejdřívější z `announced_at_source`, `announced_at_seen`, `announced_at_admin`.

**Párování:** `fighter_external_id` pro UFCalendar a The Odds API (ta má jen jména),
fuzzy shoda jména + datum zápasu, nejisté → fronta.

**Provoz zdrojů:** tabulka `source_status` (`source`, `last_success_at`, `last_error`,
`credits_left`), upozornění adminovi (e-mail) při výpadku > 6 h nebo docházejících kreditech.

**Endpointy:** `POST /api/v1/ingest/webhooks/ufcalendar`, `GET /api/v1/admin/sources`,
`POST /api/v1/admin/sources/{source}/sync` (ruční stažení).

**Testy:** nahrané odpovědi API (WireMock) pro celý průběh eventu, výpadek webhooku
(noční stažení dožene), zmizení kurzů, duplicitní a pozdní události, docházející kredity.

**Před začátkem ověřit** (úkoly z průzkumu zdrojů): na free klíči The Odds API zjistit
pokrytí PFL, ONE, Oktagonu a KSW; na trialu UFCalendar zjistit, zda feed nese čas ohlášení.

**Hotovo, když:** event UFC a event Oktagonu projdou od ohlášení po dividendy jen
s potvrzením adminem a event KSW se zadá ručně do 15 minut.

---

## Fáze 6 – Ostrý provoz a ligy (2–3 týdny)

Cíl: veřejné spuštění.

**Provoz**

- produkční nasazení (Docker image, spravovaný Postgres, denní zálohy, test obnovy)
- logy a metriky (Micrometer → Prometheus/Grafana nebo hostovaná služba), upozornění na chyby úloh
- rate limit na veřejná a obchodní API, ochrana proti více účtům (ověřený e-mail, limit na IP)
- audit log adminů (`admin_audit`: kdo, co, kdy, před/po)
- dashboard pro admina: `GET /api/v1/admin/dashboard` (co čeká na potvrzení, stav zdrojů, nadcházející eventy bez dat)
- export dat uživatele a smazání účtu (GDPR)

**Ligy** (podle rozhodnutí otevřené otázky 1)

- `league (id, name, owner_id, invite_code, season_id, mode)`; `portfolio.league_id` už existuje od fáze 3
- endpointy `POST /api/v1/leagues`, `POST /api/v1/leagues/join`, `GET /api/v1/leagues/{id}/leaderboard`
- při exkluzivním vlastnictví (aukce, výměny) je to samostatná větší fáze, odhad +3–4 týdny

**Hotovo, když:** zátěžový test (1 000 hráčů, 50 obchodů za sekundu) projde, obnova
zálohy je vyzkoušená a beta hráči přejdou do ostré sezóny.

---

## Otevřené otázky, které blokují fáze

| Otázka | Potřeba nejpozději před |
| --- | --- |
| Limit na váhovou kategorii, kvóta pro ženy | fází 3 |
| Kdo zadává ONE a KSW a s jakým zpožděním | fází 5 |
| Globální trh, nebo soukromé ligy | fází 6 |
| Smlouvy na dobu určitou (expirace lotů) | fází 3 (mění `position_lot`) |
