# Fight Exchange backend – průběh prací

> **Pro agenty:** tento soubor je zdroj pravdy o stavu projektu. Na začátku práce ho
> přečti, na konci každého PR ho aktualizuj (odškrtni úkoly, doplň rozhodnutí,
> přepiš „Další krok“). Detailní obsah fází je v [`plan.md`](plan.md).
> Značení: větev/PR `[F<fáze>]`, commit `[F<fáze>.<úkol>]`.

## Aktuální stav

- **Aktuální fáze:** F3 – Hráči, portfolio, obchodování
- **Stav fáze:** rozpracováno (F2 je v `main`; F3.1 schéma v review, další úkoly stackované na něm)
- **Poslední aktualizace:** 2026-10-30

## Přehled fází

| Fáze | Obsah | Stav | PR |
| --- | --- | --- | --- |
| F0 | Základy a přenos simulace | ✅ hotovo | #2 |
| F1 | Sportovní data a admin | ✅ hotovo | #8–#12 |
| F2 | Trh a ceny | ✅ hotovo | #14–#19 |
| F3 | Hráči, portfolio, obchodování | 🟡 rozpracováno | F3.1 |
| F4 | Dividendy, kapitán, žebříčky | ⚪ nezačato | – |
| F5 | Automatická data | ⚪ nezačato | – |
| F6 | Ostrý provoz a ligy | ⚪ nezačato | – |

Legenda: ⚪ nezačato · 🟡 rozpracováno · 🔵 v review · ✅ hotovo

---

## F0 – Základy a přenos simulace

Hotovo, když: CI je zelené, všechny golden testy procházejí, přehrání 2025 v Kotlinu
odpovídá Python simulaci.

- [x] F0.1 Gradle multi-projekt `rules` + `app`, Kotlin 2, JVM 21, ktlint/detekt
- [x] F0.2 Micronaut 4 aplikace, `GET /health` (včetně stavu DB), konfigurace přes env (`PORT`, `DB_URL`, `DB_USER`, `DB_PASSWORD`)
- [x] F0.3 Docker Compose (Postgres 16), Flyway: `V001__rule_set.sql`, `V002__rule_set_v2.sql` (výchozí pravidla v2)
- [x] F0.4 GitHub Actions (`.github/workflows/ci.yml`): `./gradlew build` = lint + testy + Testcontainers
- [x] F0.5 Modul `rules`: `Money`, `RuleSet`, `Elo`, `Pricing`, `Inactivity`, `Dividend`, `Eligibility`, `TradeCost`, `RosterRules`
- [x] F0.6 `sim/`: kopie `sim.py` + `export_golden.py` → `rules/src/test/resources/golden/*.json`
- [x] F0.7 Golden testy (`GoldenTest`): 11 funkcí × 500 případů, vše v toleranci 1 jednotky
- [x] F0.8 Přehrání sezóny 2025 (`Season2025Test`): 11 strategií × 2 scénáře, čisté jmění i počet obchodů
  odpovídá `sim.py` na 3 desetinná místa (požadavek ±2 %)
- [x] Zelené CI na PR a merge → F0 hotová (PR #2)

Ověřeno lokálně: `./gradlew build` zelené, integrační testy `app` proti Postgresu 16
(přes `TEST_DB_URL`, Docker v sandboxu nebyl), `GET /health` vrací `UP` včetně `jdbc`.

## F1 – Sportovní data a admin

Detail viz [`plan.md`](plan.md#fáze-1--sportovní-data-a-admin-23-týdny).
Hotovo, když: admin zadá kompletní event Oktagonu (karta, výsledky, bonusy) za ≤ 15 minut přes API,
Elo se přepočítá a import historie UFC dá stejné Elo jako simulace.

- [x] F1.1 Flyway `V003__sport_data.sql`: všechny sportovní tabulky + seed 5 organizací, `SportSchemaTest`
- [x] F1.2 Ingest jádro: `IngestService.submit` (idempotence), sealed třídy payloadů, `IngestProcessor` (`IngestCoreTest`)
- [x] F1.3 `FighterMatcher` (external id → normalizované jméno + divize; nejistá shoda vrací `Uncertain`, `review_item` zakládá handler v F1.4), `FighterMatcherTest`
- [x] F1.4 Handlery všech 8 typů událostí (`MasterDataHandlers`, `BoutHandlers`), `ConfirmationPolicy` (`ingest.confirmation.required-types`), Flyway `V004__bout_external_id.sql`, `IngestHandlersTest`
- [x] F1.5 Elo po `bout.result` (`EloService.recalculateFrom`), oprava výsledku = nová verze + přepočet od zápasu dál, `EloRecalculationTest`
- [x] F1.6 Veřejné čtecí endpointy: `/api/v1/organizations`, `/fighters`, `/fighters/{id}`, `/events`, `/events/{id}` (`SportController`, `SportQueries`), `SportApiTest`
- [x] F1.7 Admin API + statický admin účet (`POST /login` → JWT, `ROLE_ADMIN`) + fronta ke kontrole (`AdminController`, `ReviewService`, `FighterMerge`, Flyway `V005`), `AdminApiTest`
- [x] F1.8 Hromadné zadání výsledků karty (`POST /admin/events/{id}/results`), ruční kurzy (`POST /admin/bouts/{id}/odds`), `AdminApiTest`
- [x] F1.9 Import historie (`:app:importHistory --file=`, `POST /admin/import/history`), `HistoryImporter`, `EloService.recalculateBouts`, `HistoryImportTest` (Elo 7 061 zápasů UFC = simulace)

## F2 – Trh a ceny

Detail viz [`plan.md`](plan.md#fáze-2--trh-a-ceny-23-týdny).
Hotovo, když: přehrání roku 2025 vytvoří cenové křivky odpovídající simulaci (koncové ceny ±1 %)
a endpoint historie vrací graf s vysvětlením každého pohybu.

- [x] F2.1 Flyway `V006__market.sql`: `market_listing`, `price_event`, `price_daily`, `MarketSchemaTest`
- [x] F2.2 `MarketService` jádro: IPO (`fighter.upsert` → listing), cena ze základní hodnoty × multiplikátor × poptávka, zápis `price_event` (`MarketServiceTest`)
- [x] F2.3 Reakce na `bout.announced` / `bout.changed` / `bout.cancelled` (bonusy, bonus za silnějšího soupeře, pokles při zrušení): `MarketService.syncBout`/`cancelBout`, Flyway `V007__bout_price_bump.sql`, `MarketBoutReactionsTest`
- [x] F2.4 Reakce na `odds.snapshot` (drift v týdnu zápasu), `ranking.snapshot` a `bout.result` (nové Elo, multiplikátor = 1): `MarketService.driftBout`/`applyRanking`/`settleBout`, `Pricing.rankingFactor`, Flyway `V008__market_reactions.sql`, `MarketResultReactionsTest`
- [x] F2.5 `InactivityJob`, `PriceDailyJob` (`MarketJobs` `@Scheduled` + advisory lock v Postgresu, idempotentní): `MarketMaintenance.applyInactivity`/`closeDay`, `MarketService.delist`, Flyway `V009__inactivity_ticks.sql`, `MarketJobsTest`
- [x] F2.6 Čtecí endpointy `/api/v1/market`, `/{id}`, `/{id}/history`, `/{id}/events` (`MarketController`, `MarketQueries`), `MarketApiTest`
- [x] F2.7 Admin: `POST /admin/market/{id}/delist`, `POST /admin/market/replay` (`AdminMarketController`, `MarketAdmin`, `MarketReplay`, Flyway `V010__bout_cancelled_at.sql`), `AdminMarketApiTest`, `MarketReplayTest`
- [x] F2.8 Přehrání roku 2025 přes ingest = ceny simulace (±1 %): `Season2025PricesTest` (import historie → replay → týdenní kroky přes ingest s ovladatelnými hodinami `MutableClock`), porovnání s portem `sim.py` z testů modulu `rules`

## F3 – Hráči, portfolio, obchodování

Detail viz [`plan.md`](plan.md#fáze-3--hráči-portfolio-a-obchodování-23-týdny).
Hotovo, když: 10–20 testovacích hráčů složí portfolio přes API, noční kontrola ledgeru nehlásí rozdíl
a obchody v uzávěrce se odmítnou.

- [x] F3.1 Flyway `V011__trading.sql`: `app_user`, `season`, `portfolio`, `trade`, `position_lot`, `ledger_entry`, `weekly_buy_counter`, `TradingSchemaTest`
- [x] F3.2 Účty: `POST /api/v1/auth/register`, `/auth/login` (JWT, `ROLE_USER`), `GET /api/v1/me` (`AuthController`, `UserService`, `PasswordHasher`, `common/Problem.kt`), `AuthApiTest`
- [ ] F3.3 Sezóny a portfolia: admin `POST /admin/seasons`, `POST /portfolios` (rozpočet 100 M), `GET /portfolios/{id}` (+ ocenění), `/ledger`, `/trades`
- [ ] F3.4 `TradingService`: nákup, prodej, short (limity soupisky, uzávěrka, poplatky, přirážka, týdenní čítač, idempotence, zámek portfolia)
- [ ] F3.5 Endpointy `POST /portfolios/{id}/quote`, `POST /portfolios/{id}/trades`, chyby jako `application/problem+json`
- [ ] F3.6 `DemandJob` (hodinově, faktor poptávky → `price_event` `DEMAND`)
- [ ] F3.7 Stažení z trhu: výplata všech lotů (`FORCED_SELL`, `DELIST_PAYOUT`), noční kontrola ledgeru proti `cash_cached`
- [ ] F3.8 Souběh a přejímka: souběžné nákupy, 10–20 hráčů přes API

## F4–F6

Rozepíší se při startu příslušné fáze.

---

## Rozhodnutí

Zapisuj sem vše, co plán upřesňuje nebo mění (nejnovější nahoře).

| Datum | Fáze | Rozhodnutí | Proč |
| --- | --- | --- | --- |
| 2026-10-30 | F3 | Účty: registrace i login vrací `{accessToken, tokenType, expiresIn, user}` (JWT na 7 dní, role `ROLE_USER`, uživatel s `app_user.role = ADMIN` má navíc `ROLE_ADMIN`, jméno principalu = `app_user.id`). Heslo PBKDF2-HMAC-SHA256 (210 000 iterací, náhodná sůl) z JDK, bez nové závislosti; neznámý e-mail se ověřuje proti fiktivnímu hashi (časování). E-mail je unikátní bez ohledu na velikost písmen (409 `EMAIL_TAKEN`). Chráněno je `/api/v1/me` a `/api/v1/portfolios/**`; čtecí API zůstává veřejné. Chyby jsou `application/problem+json` s polem `code` (`ApiException` → `Problem`). Magic link (v plánu „volitelné pro betu“) se nedělá; admin dál přes `POST /login` | plán: JWT, problem+json; magic link vyžaduje odesílání e-mailů, které nemáme |
| 2026-10-30 | F2 | Replay (`MarketReplay.replay(from, to)`, `POST /admin/market/replay {"from":"yyyy-MM-dd"}`): přestaví trh z dat o sportu (zápasníci, zápasy, kurzy, žebříčky, Elo) v časovém pořadí. Smaže `price_event` od `from`, `price_daily` od dne `from`, všechny listingy a bumpy/drifty a stav listingů odehraje znovu; před `from` jen „potichu“ (`PriceCause.record = false`: mění stav, nezapisuje `price_event`, staré řádky před `from` zůstanou), od `from` zapisuje události, denní `InactivityJob` ve 03:00 UTC a nakonec uzavře `price_daily` do včerejška. Elo se čte k okamžiku kroku (`PriceCause.eloAsOf`), kurzy jen do tohoto okamžiku. Co tabulky už nevědí, se aproximuje: zápas je ohlášen v nejstarším známém čase ohlášení se současnými zápasníky a typem, zrušení v novém `bout.cancelled_at` (V010; u starších zrušení začátek eventu), výsledek na začátku eventu. Zápasy dokončené před `from` se jen vypořádají (bump by stejně zanikl); zápasníci se listují při prvním setkání (IPO) nebo v `fighter.created_at`. Zámek jako u úloh (`pg_try_advisory_xact_lock` na obě), obsazeno = 409. Import historie tak dostane listingy: replay od data po importu je „IPO historie“ | plán říká jen „přepočet cen od data“; ingest log nejde přehrát, protože by přepsal i data o sportu |
| 2026-10-30 | F2 | Neaktivita se strhává jako `sim.py` po týdenních krocích: první −1 % hned, jak mezera přesáhne `inactiveWeeks` (183. den), další po 7 dnech (`ticks = ceil((dny − 7·inactiveWeeks) / 7)`, dřív `floor(týdny) − 26`, tj. o jeden tik později a v mezi-týdnech o jeden méně → ceny se rozcházely o 1 %). „Ohlášený zápas“ pro neaktivitu = řádek v `bout_price_bump` (ne stav zápasu v DB), aby šlo trh přehrát k okamžiku v minulosti | koncové ceny vs. simulace ±1 % (F2.8); stav `bout.status` je jen aktuální | 
| 2026-10-30 | F2 | Handlery ingestu berou čas z beanu `java.time.Clock` (`Clocks`, výchozí UTC systémové hodiny; `IngestSupport.now()`) místo `Instant.now()`; test `MutableClock` (`test.clock=mutable`) ho posouvá po týdnech | přehrání sezóny 2025 přes skutečný ingest s časem událostí |
| 2026-10-30 | F2 | Test F2.8 (`Season2025PricesTest`) vynechává zápasníky, jejichž jméno je v datech psáno dvěma způsoby (a všechny, s kým se od 2024 potkali): simulace je bere jako dva zápasníky, my jako jednoho, takže se Elo soupeřů a tím ceny oprávněně liší. Zápasy prvních šesti týdnů sezóny simulace nikdy neohlásí (zná je jen jako „blížící se“), test je ohlásí před startem; po jejich odzápasení se multiplikátor stejně vrací na 1. Zbytek (~600 zápasníků) sedí do ±1 %, stažení ze stejné množiny jako v simulaci | rozdíl jmen je známá odchylka z F1.9 |
| 2026-10-30 | F2 | Čtecí trh: přehled řadí podle `price` (výchozí) nebo `change7d` sestupně a stránkuje `?offset=&limit=` (`nextOffset`), protože řazený seznam nemá stabilní id kurzor; neznámé `sort` = 400. Přehled vynechává `DELISTED` a sloučené zápasníky, detail stažený listing vrací. `change7d` = zlomek (0,05 = +5 %): cena nyní / `price_after` poslední události do před 7 dny, jinak `price_before` první události, bez událostí 0. `/events` jde od nejnovější podle `id` s kurzorem `?after=` (`{items, nextCursor}`), `/history` vrací `price_daily` od nejstaršího, `?from=&to=` jako `yyyy-MM-dd`; všechny tři 404 bez listingu | plán uvádí jen cesty; řazení podle změny nejde kurzorem podle id |
| 2026-10-30 | F2 | Úlohy: místo ShedLocku se zámek bere `pg_try_advisory_xact_lock` v transakci úlohy (bez nové závislosti; zámek zmizí s transakcí, jiná instance běh přeskočí). Neaktivita: referenční čas = `last_fight_at`, jinak `listed_at` (zápasník, který nikdy nebojoval, taky stárne); za každý celý týden nad `inactiveWeeks` jedno −1 % (`weeks = dny/7` jako `sim.py`), počet už strhnutých týdnů drží `market_listing.inactivity_ticks` (`V009`), takže opakovaný běh nic nemění a vynechané noci se doženou jednou `price_event` `INACTIVITY` s faktorem `0,99^n`; s ohlášeným zápasem (`bout.status` `ANNOUNCED`/`CONFIRMED`) se nestrhává a zameškané týdny se neúčtují zpětně; po `delistWeeks` bez ohlášeného zápasu `delist` (`DELIST`, cena zůstane). Po zápase `settleBout` vrací multiplikátor na bumpy, takže neaktivita se tím smaže. `PriceDailyJob` v 00:05 UTC dožene všechny dny po posledním uzavřeném do včerejška; open = cena na konci předchozího dne (jinak `price_before` první události), dny bez události nesou poslední cenu; přepis dne je `ON CONFLICT DO UPDATE`, aby replay (F2.7) opravil historii | plán: „idempotentní, znovuspuštění za stejný den nic nezmění“; `sim.py` neaktivitu počítá týdně |
| 2026-10-30 | F2 | Kurzy: `driftBout` platí jen pro otevřený zápas a nejnovější snapshot z posledních 7 dní před začátkem eventu (jako týden zápasu v `sim.py`); aplikovaný faktor drží `bout_price_drift` (`V008`), takže novější snapshot posune jen rozdíl a zrušení drift vrátí. Výsledek: `settleBout` smaže bump i drift zápasu, multiplikátor nastaví na součin bumpů/driftů jeho *jiných* otevřených zápasů (1, když žádné), základ přepočte z nového Elo, zapíše `last_fight_at` a přecení i zápasníky, kterým se Elo změnilo jen přepočtem pozdějších zápasů (`ELO`); `price_event` se nezapisuje, pokud se nic nezměnilo (opravený výsledek). Pořadí: `bout.result` po Elo. Ranking: nová pole `RuleSet` `rankingStep` 0,5 % za pozici, `rankingCap` ±5 % na snapshot, `rankingUnranked` 16 (chybějící pozice), v DB doplněno migrací `V008`; první snapshot divize je jen základ, dvakrát poslaný snapshot cenu nehne | plán uvádí „koeficient v rule_set“ bez hodnoty; `sim.py` ranking nezná, hodnoty jsou odhad k doladění |
| 2026-10-30 | F2 | Přihláška zápasu: `MarketService.syncBout(boutId)` srovná bump zápasníků se stavem zápasu (typ, soupeř, Elo obou stran v okamžiku události); aplikovaný bump drží tabulka `bout_price_bump` (`V007`, `base_bump` z typu, `opp_bump` za silnějšího soupeře), takže změna zápasu posune jen rozdíl a zrušení vrátí přesně to, co zápas přidal. Každá část je vlastní `price_event` (`ANNOUNCED` / `OPPONENT_BONUS`, u vrácení s poznámkou). `cancelBout`: oba ztratí bump, ten kdo vypadl navíc klesne o `cancelDropMax` při zranění (důvod obsahuje „injur“), jinak o `cancelDropMin`; bez uvedeného zápasníka se nic neubírá. Opakované zrušení nic nemění. Multiplikátor je v DB `NUMERIC(12,6)`, vrácení bumpu proto může mít odchylku řádu 1e-6 | plán říká „−5 až −10 %“ bez pravidla výběru; bump je třeba umět přesně odečíst |
| 2026-10-30 | F2 | `MarketService` (JDBC na transakčním spojení handleru): `ensureListed` = IPO z aktuálního Elo (debutant `eloStart` → 6 M), loguje `price_event` `ELO` s poznámkou „IPO“ (před = po); `apply(cause, multiplierFactor | resetMultiplier, baseValue)` přepočte `Pricing.price`, přepne `IPO` → `ACTIVE`, zapíše `price_event` s faktorem = nová/stará cena; staženého zápasníka nemění. `fighter.upsert` zakládá listing i u již existujícího zápasníka bez něj. Import historie (`HistoryImporter`) zápasníky zatím nelistuje — dořeší replay v F2.7/F2.8 | jedna cesta pro všechny změny ceny, každá s důvodem |
| 2026-10-30 | F2 | Rozpis F2 na úkoly F2.1–F2.8 (schéma → jádro → reakce na události → úlohy → endpointy → admin → přehrání 2025). `V006`: `price_event.at` je čas události (ne zápisu, ten je `created_at`), `event_multiplier`/`demand_factor` jsou `NUMERIC(12,6)`, `market_listing` hlídá `DELISTED` ⇔ `delisted_at` a `price_daily` `low ≤ open,close ≤ high` | plán tabulky popisuje jen přibližně; invarianty hlídá DB |
| 2026-10-30 | F1 | Import historie: CSV ve sloupcích `ufc-master.csv` (povinné `date`, `R_fighter`, `B_fighter`, `Winner`, `finish`; volitelné kurzy v americkém formátu, `finish_round`, `title_bout`, `no_of_rounds`, `gender`, `weight_class`). Zdroj `HISTORY`: event na den (`HIST-<ORG>-<datum>`), zápas `HIST-<ORG>-<datum>-<pořadí>`, zápasník = normalizované jméno; opakovaný import přeskočí existující zápasy. Zápasy se řadí podle data (stabilně), Elo se počítá novým `EloService.recalculateBouts` (množina nových zápasů se hodnotí vždy, ne jen ta, co se dotýkají „špinavých“ zápasníků). Bez `Winner` je výsledek no contest (jako `sim.py`), remíza jen když `finish` obsahuje „draw“; neznámá metoda s vítězem = `DEC_U`. Chybné řádky se přeskočí a hlásí (max. 20). Zápasníci se párují podle normalizovaného jména, takže 8 variant pravopisu v datech (`Da-Un Jung` / `Da Un Jung`) je jeden zápasník, kdežto `sim.py` je bere jako dva; test proto porovnává Elo podle normalizovaného jména. Dotaz na Elo před zápasem čte jen `elo_rating` (index podle zápasníka), původní join s `event` byl při hromadném importu pomalý | plán: import historie dá stejné Elo jako simulace; jedna pravda pro zápasníky napříč zdroji |
| 2026-10-30 | F1 | Hromadné zadání karty: nejdřív se zvaliduje celé tělo (zápasy musí patřit k eventu, každý max. jednou, vítěz/bonusy existují) a při chybě se nezapíše nic (400); pak se výsledky zadávají po jednom přes ingest a odpověď (200) vrací stav každého zápasu (`APPLIED`/`NEEDS_REVIEW`/`REJECTED`). Ruční kurz: desetinný kurz > 1 u obou stran, `closing` označí závěrečný kurz, `takenAt` výchozí teď | chyba v jednom zápase nesmí nechat kartu napůl zadanou kvůli překlepu; neplatné kombinace se chytí předem |
| 2026-10-30 | F1 | Admin API: admin formuláře jdou přes `IngestService` se `source = ADMIN` a zpracují se synchronně (201 vytvořeno / 200 změněno / 202 čeká ve frontě ke kontrole / 422 zamítnuto). Zápasníky v těle žádostí adresuje naše `id` (`FighterRef.id`), cizí zápasy a eventy se adresují přidaným `boutSource`/`eventSource` v payloadu (nepovinná pole na konci). `PATCH /bouts/{id}` umí i `announcedAt` (zapíše `announced_at_admin`) | admin edituje i zápasy ze zdrojů UFC/PFL, ne jen vlastní |
| 2026-10-30 | F1 | Přihlášení: `POST /login` (JSON `username`/`password`) vrací JWT; účet z `ADMIN_USER`/`ADMIN_PASSWORD`, při prázdném hesle login nejde (bezpečné výchozí chování); `JWT_SECRET` volitelný, jinak se při startu vygeneruje náhodný. Chráněno je jen `/api/v1/admin/**`, zbytek (čtecí API, `/health`) zůstává veřejný. Plán říkal „basic → JWT“, basic jsem vynechal | jeden mechanismus místo dvou; plné účty přijdou ve F3 |
| 2026-10-30 | F1 | `V005`: `fighter_external_id` už nemá PK (`fighter_id`, `source`), zůstává `UNIQUE (source, external_id)`; zápasník může mít u zdroje víc id | admin přiřazuje druhé id existujícímu zápasníkovi a sloučení spojuje id duplicit |
| 2026-10-30 | F1 | Fronta ke kontrole: `resolve` má `CONFIRM` (použije držený `bout.result`/`bout.cancelled`), `REJECT` a `ASSIGN` (`fighterId` + `externalId` → naváže externí id zdroje na zápasníka a událost se zpracuje znovu; držené typy se použijí přímo, aby se znovu nezadržely). `ASSIGN` umí jen nespárované reference s externím id, jen se jménem ne | nejjednodušší cesta k opakovanému párování bez nové struktury review položky |
| 2026-10-30 | F1 | Sloučení zápasníků (`POST /fighters/{id}/merge`): stejná organizace i pohlaví, nikdy dva, kteří spolu bojovali; přesune externí id, zápasy, výsledky, bonusy, rankingy a Elo řádky a přepočítá Elo od nejstaršího dokončeného zápasu cílového zápasníka | zdvojený zápasník nesmí rozdělit historii Elo |
| 2026-10-30 | F1 | Čtecí endpointy stránkují kurzorem = `id` poslední položky (`?after=&limit=`, výchozí 50, max 200, odpověď `{items, nextCursor}`); seznamy vynechávají sloučené zápasníky, karta eventu je ukazuje; výsledek v detailu zápasníka je z jeho pohledu (`WIN`/`LOSS`/`DRAW`/`NO_CONTEST`), zrušené zápasy se v historii nezobrazují; hledání `q` používá `NameNormalizer` (bez diakritiky) | plán: kurzorové stránkování; vyhledávání musí najít „Prochazka“ i „Procházka“ |
| 2026-10-30 | proces | Jeden úkol plánu = jeden PR (větev `f<fáze>/<úkol>-<slug>`, titulek `[F1.6] …`), PR se otevírá hned po úkolu; navazující úkol se stackuje na nemergovaný PR (base = jeho větev). F1.1–F1.5 vznikly na jedné větvi, proto jdou jako jeden PR. Nahrazuje „PR za ucelenou skupinu úkolů“ | výslovný pokyn uživatele, zapsáno do `CLAUDE.md` |
| 2026-10-30 | F1 | `EloService.recalculateFrom(boutId)` přepočítá zápas a všechny pozdější zápasy, kterých se dotkne změna (zasažení zápasníci se šíří přes soupeře); pořadí = (`event.starts_at`, `bout.id`), `rated_at` = začátek eventu; „konec“ = `KO_TKO`/`SUB`; remíza skóre 0,5; no contest řádek `elo_rating` nezapisuje (a případný starý smaže). Volá se z `BoutResultHandler.apply` ve stejné transakci | opravený výsledek mění Elo i následných soupeřů; shoda se `sim.py` (finish jen KO/SUB) |
| 2026-10-30 | F1 | Zápas se adresuje dvojicí (`source`, `external_id`) (V004: `bout.source`, `bout.external_id`, `bout.withdrawn_fighter_id`); eventy z `ADMIN` se nepotvrzují, ostatní zdroje čekají u typů v `ingest.confirmation.required-types` (výchozí `bout.result`, `bout.cancelled`) → `review_item` `RESULT_CONFIRM`/`CANCELLATION`. `BoutResultHandler.apply`/`BoutCancelledHandler.apply` jsou veřejné pro potvrzení v F1.7 | plán nemá sloupec pro externí id zápasu; admin je sám potvrzovatel |
| 2026-10-30 | F1 | Nejistý/neznámý zápasník v `bout.*` → `review_item` `FIGHTER_MATCH`, nic se nezapíše; neznámý event/zápas = `REJECTED`; `ranking.snapshot` nespárované zápasníky přeskočí (log) | hromadná data nemají blokovat; ostatní chyby jsou data zdroje |
| 2026-10-30 | F1 | `bout.result` zapíše novou verzi `bout_result` (předchozí `is_current=false`), bonusy a přetížení se nahradí, zápas → `COMPLETED`; Elo zatím ne (F1.5) | oprava výsledku = nová verze dle plánu |
| 2026-10-30 | proces | Pro každou dokončenou část plánu agent otevře PR sám (bez další žádosti); merguje až uživatel. Nahrazuje dřívější „PR jen na žádost“ | výslovný pokyn uživatele, zapsáno do `CLAUDE.md` |
| 2026-10-30 | F1 | `FighterMatcher.match` vrací `Matched` (external id, nebo jedinečné normalizované jméno s potvrzující divizí), `Uncertain` (jméno sedí, ale divize nepotvrzuje / víc kandidátů) a `NotFound`; hledá se i s obráceným pořadím jmen, jen v dané organizaci a mimo sloučené zápasníky; external id sloučeného zápasníka vede na cílového | plán: nejistá shoda → review; automaticky se páruje jen to, co je jisté |
| 2026-10-30 | proces | Všechny změny (i dokumentace) jdou do `master` jen přes PR po zelené CI, nikdy přímým pushem; PR otevírá agent jen na žádost, merguje uživatel | pokyn uživatele, zapsáno do `CLAUDE.md` |
| 2026-10-30 | F1 | Ingest přes JDBC a `TransactionOperations<Connection>` (ne Micronaut Data repozitáře); pool Micronaut Data je `autoCommit=false`, transakce proto řídí jen `tx.executeWrite/Read`; testy ingestu jsou `@MicronautTest(transactional = false)` | jedna transakce na událost s rollbackem při výjimce handleru; přímý `DataSource.connection` mimo transakci nefunguje |
| 2026-10-30 | F1 | `IngestPayload` je `sealed interface` s `@JsonTypeInfo` (`type` = `fighter.upsert` …) a `schemaVersion`; zpracování typů je přes rozhraní `IngestHandler` (jeden bean na typ, F1.4); událost bez handleru zůstane `NEW`, chyba handleru = `REJECTED` jen pro ni | rozšiřitelné bez zásahu do procesoru |
| 2026-10-29 | F1 | Výčty v DB jako `text` + `CHECK`; `bout.announced_at` je `GENERATED ... STORED` (`LEAST` ze tří časů); `bout_result.outcome` (WIN/DRAW/NO_CONTEST) navíc, vítěz jen u WIN; `ingest_event` unikátní jen mimo `DUPLICATE` (duplicity se ukládají); `fighter.normalized_name` a `merged_into_id` navíc (párování, sloučení); `fighter.division` nullable (ne všude známá) | plán popisuje sloupce jen přibližně; takhle jdou invarianty hlídat v DB |
| 2026-10-29 | F0 | Verze: Kotlin 2.0.21, KSP 2.0.21-1.0.28, Micronaut platforma 4.10.22 (ne 5.x), Gradle 8.14.3, detekt 1.23.8, ktlint plugin 13.1.0, Testcontainers 1.21.4 | plán říká Micronaut 4; Kotlin 2.0.21 je verze, na které běží detekt 1.23 |
| 2026-10-29 | F0 | Balíček `com.fightexchange` (`…rules`, aplikace v `com.fightexchange`) | – |
| 2026-10-29 | F0 | `Money` je `@JvmInline value class` nad `Long`; sazby a faktory jsou `Double`, výsledek se zaokrouhlí `HALF_EVEN` jednou na konci funkce | peníze nikdy v `Double`, ale exp/kurzy se jinak počítat nedají |
| 2026-10-29 | F0 | `RuleSet` má navíc proti tabulce v plánu: `defaultOdds` 2,0 (zápas bez kurzu), `yieldNorm` 1,2 (dělitel vzorce dividendy), `shortUnit` 1 M (základ shortu), `annStrongerOpp` jako seznam prahů | čísla ze `sim.py`, která byla natvrdo v kódu |
| 2026-10-29 | F0 | Remíza mění Elo se skóre 0,5; `sim.py` remízy počítá jako no contest (přehrání to tak zachovává) | standardní Elo; v datech UFC je remíz minimum |
| 2026-10-29 | F0 | Dividenda: kapitán násobí i bonusy (jako `sim.py`); přetížení (−1 M) snižuje jen výplatu za výhru a celková dividenda nikdy není záporná | `sim.py` přetížení neznal; hráč nemá za dividendu platit |
| 2026-10-29 | F0 | Short: při výhře zápasníka platí `shortUnit × kurz` bez stropu, při prohře dostane `shortUnit × min(kurz soupeře, 3,5)`; short nejde otevřít na zápasníka s ohlášeným kurzem > 3,5 | „opravené shorty“ z pravidel v2 |
| 2026-10-29 | F0 | Nárok na dividendu: `boughtAt < announcedAt` (ostře). `sim.py` počítá po dnech (drženo ≥ 42 dní = koupeno nejpozději v den ohlášení); testy to mapují jako nákup na začátku dne, ohlášení na konci | plán (FIFO po kusech podle času ohlášení) |
| 2026-10-29 | F0 | Min. 2 zápasníci mimo UFC: nákup UFC zápasníka projde jen při ≤ 13 UFC na soupisce; prodej mimo-UFC zápasníka se odmítne jen když by limit porušil (soupiska už pod limitem smí dál klesat) | plán říká „kontroluje se při prodeji, který by limit porušil“; sim tohle pravidlo neměl |
| 2026-10-29 | F0 | `rule_set` verze 2 se zakládá migrací `V002` (`valid_from` 2026-01-01), `DbSetupTest` hlídá shodu s `RuleSet.V2` | jedna pravda pro výchozí pravidla |
| 2026-10-29 | F0 | Přehrání sezóny je testový kód (`rules/src/test/.../sim/`) s přesným portem Python RNG (MT19937), takže sedí i náhodná strategie; kromě ±2 % čistého jmění test vyžaduje i stejný počet obchodů | přísnější kontrola, shoda je stejně přesná |
| 2026-10-29 | F0 | Golden data jsou commitnutá (`fights.json` ~0,7 MB z datasetu shortlikeafox), dataset se nestahuje v CI | dataset na `master` se mění; SHA-256 je v `meta` |
| 2026-10-29 | F0 | detekt kontroluje jen produkční kód (`src/main`) | port `sim.py` v testech schválně kopíruje strukturu Pythonu |
| 2026-10-29 | F0 | Zaveden tracker (`docs/PROGRESS.md`, `CLAUDE.md`) a značení `[F0]`/`[F0.x]` | kontext agentů se maže, repo je jediný trvalý záznam |

Poznámka pro agenty v sandboxu: Maven Central tam vrací 429. Pomáhá lokální init skript
`~/.gradle/init.d/mirror.init.gradle.kts`, který místo `mavenCentral()` použije
`https://maven-central.storage-download.googleapis.com/maven2/` (do repa se necommituje).

## Další krok

F3 běží (viz seznam úkolů výše). Každý úkol = vlastní větev a PR (`f3/f3.x-<slug>`, base = větev předchozího
nemergovaného PR, jinak `main`).
Po merge F2 spusť v ostrém provozu po importu historie `POST /api/v1/admin/market/replay` od data importu
(vytvoří listingy a ceny z Elo historie).
Ověření kritéria F1 „admin zadá event za ≤ 15 minut“ zůstává na uživateli.

Poznámka: v sandboxu běží Postgres 16 lokálně (`pg_ctlcluster 16 main start`, DB `fightexchange_test`,
uživatel a heslo `fightexchange`); testy s `TEST_DB_URL=jdbc:postgresql://localhost:5432/fightexchange_test TEST_DB_PASSWORD=fightexchange`.
Při chybě „Number of loaded files in snapshots differs“ pomůže `rm -rf app/build/kspCaches`.

## Otevřené otázky

Viz konec [`plan.md`](plan.md#otevřené-otázky-které-blokují-fáze). Nové otázky zapisuj sem.

- Přetížení: má zápasník s přetížením přijít o 1 M i při prohře (tj. strhnout se držitelům)? Zatím jen snižuje výplatu za výhru.
- Má se kapitánův násobek vztahovat i na bonusy? Zatím ano (jako `sim.py`).
