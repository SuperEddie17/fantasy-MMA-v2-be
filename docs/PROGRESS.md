# Fight Exchange backend – průběh prací

> **Pro agenty:** tento soubor je zdroj pravdy o stavu projektu. Na začátku práce ho
> přečti, na konci každého PR ho aktualizuj (odškrtni úkoly, doplň rozhodnutí,
> přepiš „Další krok“). Detailní obsah fází je v [`plan.md`](plan.md).
> Značení: větev/PR `[F<fáze>]`, commit `[F<fáze>.<úkol>]`.

## Aktuální stav

- **Aktuální fáze:** F1 – Sportovní data a admin
- **Stav fáze:** rozpracováno (F1.1–F1.6 hotovo; F0 hotová, PR #2 merged 2026-10-29)
- **Poslední aktualizace:** 2026-10-30

## Přehled fází

| Fáze | Obsah | Stav | PR |
| --- | --- | --- | --- |
| F0 | Základy a přenos simulace | ✅ hotovo | #2 |
| F1 | Sportovní data a admin | 🟡 rozpracováno | – |
| F2 | Trh a ceny | ⚪ nezačato | – |
| F3 | Hráči, portfolio, obchodování | ⚪ nezačato | – |
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
- [ ] F1.7 Admin API + statický admin účet (Micronaut Security basic → JWT) + fronta ke kontrole
- [ ] F1.8 Hromadné zadání výsledků karty, ruční kurzy
- [ ] F1.9 Import historie (`:app:importHistory`, `POST /admin/import/history`), shoda Elo se simulací UFC

## F2–F6

Rozepíší se při startu příslušné fáze.

---

## Rozhodnutí

Zapisuj sem vše, co plán upřesňuje nebo mění (nejnovější nahoře).

| Datum | Fáze | Rozhodnutí | Proč |
| --- | --- | --- | --- |
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

F1.7: admin API + statický admin účet (Micronaut Security basic → JWT) + fronta ke kontrole
(`/api/v1/admin/**`, `GET /admin/review`, `POST /admin/review/{id}/resolve`). Každý úkol = vlastní větev a PR.

Poznámka: v sandboxu běží Postgres 16 lokálně (`pg_ctlcluster 16 main start`, DB `fightexchange_test`,
uživatel a heslo `fightexchange`); testy s `TEST_DB_URL=jdbc:postgresql://localhost:5432/fightexchange_test TEST_DB_PASSWORD=fightexchange`.
Při chybě „Number of loaded files in snapshots differs“ pomůže `rm -rf app/build/kspCaches`.

## Otevřené otázky

Viz konec [`plan.md`](plan.md#otevřené-otázky-které-blokují-fáze). Nové otázky zapisuj sem.

- Přetížení: má zápasník s přetížením přijít o 1 M i při prohře (tj. strhnout se držitelům)? Zatím jen snižuje výplatu za výhru.
- Má se kapitánův násobek vztahovat i na bonusy? Zatím ano (jako `sim.py`).
