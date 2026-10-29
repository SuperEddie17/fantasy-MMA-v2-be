# sim – referenční simulace

- `sim.py` – kopie Python simulace sezóny UFC 2025 (pravidla navržená v plánování).
  Slouží jen ke kalibraci; od dokončení F0 je pravdou Kotlin modul `rules`.
- `vysledky*.md` – výstupy simulace z plánování (pro porovnání).
- `export_golden.py` – volá kód `sim.py` a zapisuje referenční data pro Kotlin testy do
  `rules/src/test/resources/golden/`:
  - `functions.json` – ~500 případů pro každou funkci pravidel (Elo, ceny, ohlášení, kurzy,
    neaktivita, dividendy, shorty, poplatky, nárok na dividendu),
  - `fights.json` – zápasy 2010–2025 z datasetu včetně aproximace bonusů večera (vstup přehrání),
  - `season2025.json` – výsledky `run()` pro scénáře `v2_limit2` a `v2_free2_extra3`
    (= aktuální pravidla: 2 nákupy týdně zdarma, každý další +3 % ceny).

Spuštění (dataset se stáhne do `sim/data/`, který je v `.gitignore`):

```
python3 sim/export_golden.py            # nebo --csv cesta/k/ufc-master.csv
./gradlew :rules:test
```

Dataset: [shortlikeafox/ultimate_ufc_dataset](https://github.com/shortlikeafox/ultimate_ufc_dataset)
(`ufc-master.csv`). Větev `master` se průběžně mění, proto je jeho SHA-256 uložen v `meta`
každého JSON a testy čtou commitnutá data, ne stažený soubor. Po změně pravidel nebo `sim.py`
data znovu vygeneruj a commitni.
