# Fight Exchange backend – průběh prací

> **Pro agenty:** tento soubor je zdroj pravdy o stavu projektu. Na začátku práce ho
> přečti, na konci každého PR ho aktualizuj (odškrtni úkoly, doplň rozhodnutí,
> přepiš „Další krok“). Detailní obsah fází je v [`plan.md`](plan.md).
> Značení: větev/PR `[F<fáze>]`, commit `[F<fáze>.<úkol>]`.

## Aktuální stav

- **Aktuální fáze:** F1 – Sportovní data a admin
- **Stav fáze:** nezačato (F0 hotová, PR #2 merged 2026-10-29)
- **Poslední aktualizace:** 2026-10-29

## Přehled fází

| Fáze | Obsah | Stav | PR |
| --- | --- | --- | --- |
| F0 | Základy a přenos simulace | ✅ hotovo | #2 |
| F1 | Sportovní data a admin | ⚪ nezačato | – |
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

Úkoly viz [`plan.md`](plan.md#fáze-1--sportovní-data-a-admin-23-týdny). Rozepíše se při startu fáze.

## F2–F6

Rozepíší se při startu příslušné fáze.

---

## Rozhodnutí

Zapisuj sem vše, co plán upřesňuje nebo mění (nejnovější nahoře).

| Datum | Fáze | Rozhodnutí | Proč |
| --- | --- | --- | --- |
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

Start F1 (Sportovní data a admin): rozepsat úkoly F1.x do sekce F1 podle `plan.md`, první PR
`[F1] Tabulky sportovních dat (Flyway)` – `organization`, `fighter`, `event`, `bout`, … a ingest jádro.

## Otevřené otázky

Viz konec [`plan.md`](plan.md#otevřené-otázky-které-blokují-fáze). Nové otázky zapisuj sem.

- Přetížení: má zápasník s přetížením přijít o 1 M i při prohře (tj. strhnout se držitelům)? Zatím jen snižuje výplatu za výhru.
- Má se kapitánův násobek vztahovat i na bonusy? Zatím ano (jako `sim.py`).
