// UC2 — two transfers at the same moment whose total exceeds the balance: at most one may succeed.
//   k6 run uc2.js
// Each round sets the sender's balance to 1,000,000 and fires two transfers of 600,000 at once.
// Correct: exactly one succeeds and the balance drops by 600,000 once.
// The loser's status shows HOW it was stopped: 400 = "insufficient balance" (clean),
// 409 = lock conflict (money is safe, but the user just sees an error).
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const API = `${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1`;
const ROUNDS = Number(__ENV.ROUNDS || 200);
const START = 1000000;
const AMOUNT = 600000;

const bothSucceeded = new Counter('both_succeeded');
const wrongBalance = new Counter('wrong_balance');
const loser400 = new Counter('loser_got_400');
const loser409 = new Counter('loser_got_409');

export const options = {
  scenarios: { uc2: { executor: 'shared-iterations', vus: 1, iterations: ROUNDS } },
  thresholds: { both_succeeded: ['count==0'], wrong_balance: ['count==0'] },
  batch: 2,
  batchPerHost: 2,
};

const json = (token) => ({ headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) } });

function newUser(name) {
  http.post(`${API}/auth/register`, JSON.stringify({ username: name, password: 'Passw0rd!', fullName: name, email: `${name}@test.vn` }), json());
  const token = http.post(`${API}/auth/login`, JSON.stringify({ username: name, password: 'Passw0rd!' }), json()).json('data.accessToken');
  const wallet = http.get(`${API}/wallets/me`, json(token)).json('data.walletNumber');
  const bank = http.post(`${API}/linked-banks`, JSON.stringify({ bankCode: 'VCB', bankName: 'Vietcombank', accountNumber: '0011223344', holderName: name }), json(token)).json('data.id');
  return { token, wallet, bank };
}

const balance = (u) => http.get(`${API}/wallets/me`, json(u.token)).json('data.balance');

export function setup() {
  const id = Date.now().toString(36);
  return { sender: newUser(`uc2a${id}`), receiver: newUser(`uc2b${id}`) };
}

export default function ({ sender, receiver }) {
  // Top the sender back up to exactly START (it only ever goes down in this test).
  const missing = START - balance(sender);
  if (missing > 0) {
    http.post(`${API}/transactions/topup`, JSON.stringify({ linkedBankAccountId: sender.bank, amount: missing }), json(sender.token));
  }
  const before = balance(sender);
  const body = JSON.stringify({ targetWalletNumber: receiver.wallet, amount: AMOUNT });
  const res = http.batch([1, 2].map(() => ['POST', `${API}/transactions/transfer`, body, json(sender.token)]));
  const ok = res.filter((r) => r.status === 200).length;

  if (ok === 2) bothSucceeded.add(1);
  if (before - balance(sender) !== ok * AMOUNT) wrongBalance.add(1);
  res.forEach((r) => { if (r.status === 400) loser400.add(1); if (r.status === 409) loser409.add(1); });
}
