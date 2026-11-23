// Fixed-rate load test.
//   k6 run -e RATE=100 -e DURATION=10m load.js
// setup() creates USERS users (not measured), then k6 sends exactly RATE requests/second.
// Pass = p95 under 500 ms and under 1% failures (shown as ✓/✗ at the end of k6's summary).
//
// Options:
//   HOT=1        every transfer goes into ONE wallet (worst case for locking; shows I2)
//   HISTORY=200  give every user 200 past transactions before the test (long histories; shows I3)
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const API = `${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1`;
const RATE = Number(__ENV.RATE || 100);
const USERS = Number(__ENV.USERS || 300);
const HOT = __ENV.HOT === '1';
const HISTORY = Number(__ENV.HISTORY || 0);

// What counts as a correct answer, per request type. Anything else = failure.
// 400 is accepted ONLY where it can be a real business answer ("insufficient balance");
// a 400 on a read or a top-up means the request was broken, so it fails the test.
const OK_ONLY = http.expectedStatuses(200);
const OK_OR_REJECTED = http.expectedStatuses(200, 400);
const conflicts = new Counter('conflicts_409');
const rejected = new Counter('rejected_400');

export const options = {
  scenarios: {
    load: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s',
      duration: __ENV.DURATION || '10m', preAllocatedVUs: 50, maxVUs: Math.max(200, RATE * 3),
    },
  },
  thresholds: {
    'http_req_duration{scenario:load}': ['p(95)<500'],
    'http_req_failed{scenario:load}': ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  setupTimeout: '20m',
  batch: 50,
  batchPerHost: 50,
};

const json = (token) => ({
  headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  timeout: '10s',
});
const post = (path, body, token) => ['POST', `${API}${path}`, JSON.stringify(body), json(token)];

export function setup() {
  const run = Date.now().toString(36);
  const users = [];
  for (let start = 0; start < USERS; start += 25) {
    const names = [];
    for (let i = start; i < Math.min(USERS, start + 25); i++) names.push(`lt${run}_${i}`);
    http.batch(names.map((n) => post('/auth/register', { username: n, password: 'Passw0rd!', fullName: n, email: `${n}@test.vn` })));
    const tokens = http.batch(names.map((n) => post('/auth/login', { username: n, password: 'Passw0rd!' })))
      .map((r) => r.json('data.accessToken'));
    if (tokens.some((t) => !t)) throw new Error('seeding failed: could not log in (is the app running and the DB empty?)');
    const wallets = http.batch(tokens.map((t) => ['GET', `${API}/wallets/me`, null, json(t)]));
    const banks = http.batch(tokens.map((t, i) => post('/linked-banks',
      { bankCode: 'VCB', bankName: 'Vietcombank', accountNumber: `00112233${start + i}`, holderName: names[i] }, t)));
    tokens.forEach((token, i) => users.push({
      token, wallet: wallets[i].json('data.walletNumber'), bank: banks[i].json('data.id'),
    }));
    http.batch(users.slice(start).map((u) => post('/transactions/topup', { linkedBankAccountId: u.bank, amount: 10000000 }, u.token)));
  }
  // Past transactions: HISTORY small top-ups per user, sent 50 at a time.
  for (let h = 0; h < HISTORY; h++) {
    for (let start = 0; start < users.length; start += 50) {
      http.batch(users.slice(start, start + 50).map((u) => post('/transactions/topup', { linkedBankAccountId: u.bank, amount: 1000 }, u.token)));
    }
  }
  return users;
}

// Request mix (weights out of 100).
const MIX = [['balance', 30], ['history', 10], ['banks', 10], ['transfer', 35], ['topup', 10], ['withdraw', 5]];
const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];
const amount = () => 1000 * (1 + Math.floor(Math.random() * 20)); // 1,000 - 20,000 VND

export default function (users) {
  let r = Math.random() * 100;
  const op = MIX.find(([, w]) => (r -= w) < 0)[0];
  // With HOT=1, users[0] is the hot wallet and never sends.
  const u = HOT ? users[1 + Math.floor(Math.random() * (users.length - 1))] : pick(users);
  const p = (expected) => ({ ...json(u.token), tags: { name: op }, responseCallback: expected });
  let res;
  if (op === 'balance') res = http.get(`${API}/wallets/me`, p(OK_ONLY));
  if (op === 'history') res = http.get(`${API}/transactions`, p(OK_ONLY));
  if (op === 'banks') res = http.get(`${API}/linked-banks`, p(OK_ONLY));
  if (op === 'transfer') {
    const to = HOT ? users[0] : pick(users.filter((x) => x !== u));
    res = http.post(`${API}/transactions/transfer`, JSON.stringify({ targetWalletNumber: to.wallet, amount: amount() }), p(OK_OR_REJECTED));
  }
  if (op === 'topup') res = http.post(`${API}/transactions/topup`, JSON.stringify({ linkedBankAccountId: u.bank, amount: amount() }), p(OK_ONLY));
  if (op === 'withdraw') res = http.post(`${API}/transactions/withdraw`, JSON.stringify({ linkedBankAccountId: u.bank, amount: amount() }), p(OK_OR_REJECTED));
  if (res.status === 409) conflicts.add(1);
  if (res.status === 400) rejected.add(1);
}
