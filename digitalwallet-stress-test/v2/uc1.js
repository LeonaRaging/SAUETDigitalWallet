// UC1 — the same payment sent many times must take the money only once.
//   k6 run uc1.js
// Each round sends 20 identical transfers at the same moment with ONE Idempotency-Key, then
// checks how much the sender's balance dropped. Exactly one debit per round = correct.
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const API = `${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1`;
const ROUNDS = Number(__ENV.ROUNDS || 50);
const COPIES = 20;
const AMOUNT = 1000;

const correctRounds = new Counter('rounds_debited_once');
const extraDebits = new Counter('extra_debits');

export const options = {
  scenarios: { uc1: { executor: 'shared-iterations', vus: 1, iterations: ROUNDS } },
  thresholds: { extra_debits: ['count==0'], rounds_debited_once: [`count==${ROUNDS}`] },
  batch: COPIES,
  batchPerHost: COPIES,
};

const json = (token, extra) => ({ headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...extra } });

function newUser(name) {
  http.post(`${API}/auth/register`, JSON.stringify({ username: name, password: 'Passw0rd!', fullName: name, email: `${name}@test.vn` }), json());
  const token = http.post(`${API}/auth/login`, JSON.stringify({ username: name, password: 'Passw0rd!' }), json()).json('data.accessToken');
  const wallet = http.get(`${API}/wallets/me`, json(token)).json('data.walletNumber');
  const bank = http.post(`${API}/linked-banks`, JSON.stringify({ bankCode: 'VCB', bankName: 'Vietcombank', accountNumber: '0011223344', holderName: name }), json(token)).json('data.id');
  http.post(`${API}/transactions/topup`, JSON.stringify({ linkedBankAccountId: bank, amount: 10000000 }), json(token));
  return { token, wallet };
}

const balance = (u) => http.get(`${API}/wallets/me`, json(u.token)).json('data.balance');

export function setup() {
  const id = Date.now().toString(36);
  return { sender: newUser(`uc1a${id}`), receiver: newUser(`uc1b${id}`) };
}

export default function ({ sender, receiver }) {
  const key = `${Date.now()}-${Math.random()}`;
  const before = balance(sender);
  const body = JSON.stringify({ targetWalletNumber: receiver.wallet, amount: AMOUNT });
  http.batch(Array.from({ length: COPIES }, () =>
    ['POST', `${API}/transactions/transfer`, body, json(sender.token, { 'Idempotency-Key': key })]));
  const debits = (before - balance(sender)) / AMOUNT;
  if (debits === 1) correctRounds.add(1);
  if (debits > 1) extraDebits.add(debits - 1);
}
