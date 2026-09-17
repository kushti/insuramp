// P2PGATE phase-1 oracle box (trusted centralized oracle, authenticated by NFT).
//
// The oracle co-signs claim/release transactions only after confirming the
// source-chain payment (specs/oracle-integration.md §3.1). Its box must be a full
// INPUT of the vault-spending transaction; the vault checks only that an input box
// carries ORACLE_NFT_ID among its tokens (vault_funded.es `oracleOk`). This script
// guards the oracle box itself: any spend must preserve the NFT (id + amount) and
// value into OUTPUTS(0) — a FIXED position, so the vault contracts can pin their
// payout at OUTPUTS(1) on joint release spends (v2 2026-09-17, see
// specs/vault-contract.md §8.4). The first-token-id check relies on the NFT
// always living at tokens(0).
//
// Constants:
//   ORACLE_NFT_ID  Coll[Byte]  32 B token id minted once by the oracle operator
//   ORACLE_KEY     Coll[Byte]  33 B compressed secp256k1 point (oracle signing key)
{
  proveDlog(decodePoint(%%ORACLE_KEY%%)) && sigmaProp(
    OUTPUTS(0).tokens.size > 0 &&
    OUTPUTS(0).tokens(0)._1 == %%ORACLE_NFT_ID%% &&
    OUTPUTS(0).tokens(0)._2 == SELF.tokens(0)._2 &&
    OUTPUTS(0).value >= SELF.value
  )
}
