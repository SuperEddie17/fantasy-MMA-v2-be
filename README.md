# fantasy-mma-v2-be

Backend hry Fight Exchange (fantasy MMA jako burza). Kotlin, Micronaut, PostgreSQL.

- Plán po fázích: [`docs/plan.md`](docs/plan.md)
- Stav prací (aktuální fáze, úkoly, rozhodnutí): [`docs/PROGRESS.md`](docs/PROGRESS.md)
- Instrukce pro agenty: [`CLAUDE.md`](CLAUDE.md)

## Rychlý start

```
docker compose up -d          # Postgres 16 na localhost:5432
./gradlew build               # lint + testy
./gradlew :app:run            # http://localhost:8080/health
```

Konfigurace přes proměnné prostředí: `PORT`, `DB_URL`, `DB_USER`, `DB_PASSWORD`
(výchozí hodnoty odpovídají `docker-compose.yml`).

Integrační testy v `app` spouštějí Postgres přes Testcontainers (potřebují Docker). Bez Dockeru
lze použít existující databázi: `TEST_DB_URL=jdbc:postgresql://localhost:5432/fightexchange ./gradlew build`.
Bez Dockeru i bez `TEST_DB_URL` se přeskočí.
