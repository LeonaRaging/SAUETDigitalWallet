-- Money check, run after a load test on a freshly reset DB. All three must hold:
--   total_balance = expected_total   (money only enters by top-up and leaves by withdrawal)
--   wallets_not_matching_ledger = 0  (each balance = what its transactions add up to)
--   negative_wallets = 0
SELECT
  (SELECT SUM(balance) FROM wallets) AS total_balance,
  (SELECT COALESCE(SUM(CASE type WHEN 'TOPUP' THEN amount WHEN 'WITHDRAW' THEN -amount ELSE 0 END), 0)
     FROM transactions) AS expected_total,
  (SELECT COUNT(*) FROM wallets w
     LEFT JOIN (SELECT wallet_id, SUM(delta) AS net FROM (
                  SELECT target_wallet_id AS wallet_id,  amount AS delta FROM transactions WHERE target_wallet_id IS NOT NULL
                  UNION ALL
                  SELECT source_wallet_id AS wallet_id, -amount AS delta FROM transactions WHERE source_wallet_id IS NOT NULL
                ) moves GROUP BY wallet_id) ledger ON ledger.wallet_id = w.id
     WHERE w.balance <> COALESCE(ledger.net, 0)) AS wallets_not_matching_ledger,
  (SELECT COUNT(*) FROM wallets WHERE balance < 0) AS negative_wallets;
