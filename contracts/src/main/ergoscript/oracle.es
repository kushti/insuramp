// P2PGATE phase-1 oracle box (trusted centralized oracle, authenticated by NFT).
//
// The oracle posts an attestation by spending this singleton box and
// recreating it at OUTPUTS(0) with R4 = the 32-byte dealId (its registers
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
// R4 payload format — the bare 32-byte dealId, nothing else
// (specs/oracle-integration.md §2.2).
//
// What an attestation MEANS: a single per-deal signal — "the seller's USDT
// transfer to the buyer's address is confirmed on the source chain AND the
// funds screened as non-tainted". The signing oracle posts it only after its
// observer matched the exact (recipient, amount) transfer registered at
// funding, the transfer reached the confirmation rule (§4.2), and it passed
// the taint screen (Tether blacklist/freeze + sanctions, §4.3 — an
// attestation PRECONDITION: a transfer that fails screening is never
// attested). One attestation per deal, one in flight at a time (this
// singleton holds only the current payload; the release using it must
// confirm before the next posting, §3.1).
//
// Trust boundary, stated plainly: this script does NOT parse R4 (any register
// contents are accepted as long as the NFT and value survive into OUTPUTS(0)),
// and NO contract anywhere can check the preconditions — confirmation, taint,
// and the sender's identity are the trusted oracle's off-chain assertions. The
// vault contracts (paths C/C′) check only NFT custody + dealId equality
// against their own R4. Note the dealId (blake2b256 of the deal terms)
// commits to asset/chain/amount/keys/nonce but NOT to the buyer's receive
// address — so "paid to the right address" is wholly the oracle's off-chain
// assertion, and the srcTxId-based ex-post audit anchor lives off-chain at
// the oracle's API, not in this register.
//
// Phase 2: the guard set threshold-signs the 32-byte dealId (equivalently
// blake2b256(dealId)) — same register, same size.
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
