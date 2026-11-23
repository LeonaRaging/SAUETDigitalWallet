# Load test (v2)

| File | What it does |
|---|---|
| `load.js` | Creates users, then sends a **fixed rate** of mixed requests (balance, history, banks, transfer, top-up, withdraw). Pass = p95 < 500 ms and < 1 % failures. |
| `uc1.js` | UC1: the same transfer sent 20× at once with one `Idempotency-Key` must debit **once**. |
| `uc2.js` | UC2: two transfers at once that together exceed the balance — **at most one** may succeed. |
| `money_check.sql` | After a load test: no money created or lost, every balance matches its transactions, none negative. |
| `LoadTest.ipynb` | Kaggle notebook: setup, smoke, load, hot wallet, long history, stress, UC1, UC2, results table. |

Run one test by hand (app running, fresh DB):

```bash
k6 run -e RATE=100 -e DURATION=10m load.js
k6 run uc1.js
k6 run uc2.js
mysql -uroot -D digitalwallet_db -t < money_check.sql
```

Settings for `load.js`: `RATE` (req/s, default 100), `DURATION` (default 10m), `USERS` (default 300),
`HOT=1` (every transfer goes into one wallet — worst case for locking), `HISTORY=200` (each user starts
with 200 past transactions — makes history reads heavy).

Status codes: 200 = success. 400 counts as a correct answer **only** on transfer and withdraw (e.g.
insufficient balance); a 400 anywhere else fails the test. 409 / 5xx / timeouts = failure. 409s and
400s are also counted separately (`conflicts_409`, `rejected_400`).

**Not covered yet:** a failing bank (circuit breaker), `429` from rate limiting and `202` from async
transfers. Add these when those Phase 2 changes exist, or those versions will look like failures.

**Stress** = `load.js` at increasing `RATE` (the notebook runs 100 → 800). The highest rate that still
passes is the most load the version can sustain.

**On Kaggle:** set `BRANCH` in the notebook to the version to test, Internet on, Save & Run All.
The app + MySQL are pinned to CPUs 0,2 and k6 to CPUs 1,3 (one physical core each).
