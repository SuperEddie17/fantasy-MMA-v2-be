# Instrukce pro agenty (Claude a další)

Tento repozitář je backend hry **Fight Exchange** (fantasy MMA jako burza).
Kontext se mezi sezeními maže, proto je **repozitář jediný trvalý záznam** o tom,
co je hotové a co je další krok. Než začneš cokoli dělat, přečti:

1. [`docs/PROGRESS.md`](docs/PROGRESS.md) – aktuální fáze, stav úkolů, rozhodnutí, další kroky.
2. [`docs/plan.md`](docs/plan.md) – detailní plán všech fází (co se staví a kdy je fáze hotová).

## Pravidla práce

- **Fáze se značí.** Každá větev, PR a commit nese označení fáze a úkolu:
  - větev: `f0/gradle-skeleton`, `f1/ingest-core`, … (nebo přidělená větev agenta)
  - titulek PR: `[F0] Kostra Gradle + Micronaut`
  - commit: `[F0.5] rules: Elo a ceny` (číslo za tečkou = číslo úkolu v plánu fáze)
- **Vše jde přes pull request.** Nikdy nepushuj ani nemerguj přímo do `master`/`main`;
  každá změna (i dokumentace a tracker) se dělá na větvi a do hlavní větve se dostane
  jen přes PR po zelené CI. Agent otevře PR pro každou dokončenou část plánu
  (fázi nebo ucelenou skupinu úkolů) bez další žádosti; merguje až uživatel.
- **Každý PR aktualizuje `docs/PROGRESS.md`**: odškrtne hotové úkoly, doplní rozhodnutí
  a přepíše sekci „Další krok“. PR bez aktualizace trackeru není hotový.
- Rozhodnutí, která se odchylují od `docs/plan.md` nebo ho upřesňují, zapiš do
  sekce „Rozhodnutí“ v `PROGRESS.md` (datum, co, proč).
- Komunikace s uživatelem je česky; kód, identifikátory a komentáře v kódu anglicky.

## Struktura a příkazy

```
rules/   čistý Kotlin (bez Micronautu a DB): Elo, ceny, dividendy, poplatky, soupiska
app/     Micronaut aplikace (REST, DB, plánované úlohy)
sim/     referenční Python simulace (sim.py) a export golden dat
docs/    plán a tracker
```

- Build a všechny testy: `./gradlew build`
- Jen pravidla: `./gradlew :rules:test`
- Lint: `./gradlew ktlintCheck detekt` (oprava formátu: `./gradlew ktlintFormat`)
- Lokální Postgres: `docker compose up -d`, aplikace: `./gradlew :app:run`
- Golden data znovu: `python3 sim/export_golden.py` (viz `sim/README.md`)

Integrační testy `app` potřebují Docker (Testcontainers) nebo existující Postgres v `TEST_DB_URL`;
bez obojího se přeskočí,
v CI běží vždy.

## Konvence (zkráceně, plně v `docs/plan.md`)

- Peníze: `Money` = `Long` v celých jednotkách (1 M = 1 000 000), nikdy `Double`.
  Násobení sazbou zaokrouhluje `HALF_EVEN`.
- Čas: UTC (`Instant`, `timestamptz`).
- Čísla pravidel jen v `RuleSet` (později tabulka `rule_set`), ne natvrdo v kódu.
