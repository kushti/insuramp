// P2PGATE phase-1 oracle box (trusted centralized oracle, authenticated by NFT).
//
// The oracle posts an attestation by spending this singleton box and
// recreating it at OUTPUTS(0) with R4 = the 112-byte payload (its registers
// are unconstrained; only the NFT id + amount and value are pinned). The
// vault contracts then take that box as a DATA INPUT of the release tx and
// authenticate it by NFT custody (vault_funded.es path C / vault_payment_proven.es
// path C′) — a data input's script never executes, so no oracle signature
// rides in buyer/operator txs; NFT custody alone is the phase-1 trust root.
// This script guards the oracle box itself: any spend (attestation posting
// or rotation) must preserve the NFT (id + amount) and value into OUTPUTS(0)
// — a FIXED position (see specs/vault-contract.md §8.4). The first-token-id
// check relies on the NFT always living at tokens(0).
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
