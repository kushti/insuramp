// P2PGATE phase-1 oracle box (trusted centralized oracle, authenticated by NFT).
//
// WHAT AN ATTESTATION IS: a single box saying "this deal's USDT payment is done".
// The oracle posts one by spending this singleton and recreating it at OUTPUTS(0)
// with R4 = the 32-byte dealId — the name of the deal, and nothing else. The
// vault contracts read that box as a DATA INPUT of the release tx and accept it
// on the strength of the NFT alone (vault_funded.es path C /
// vault_payment_proven.es path C′); a data input's script never executes, so no
// oracle signature rides along in buyer or operator transactions. NFT custody is
// the entire phase-1 trust root — see specs/oracle-integration.md §2 for what the
// oracle is trusted to have checked.
//
// This script protects the NFT, not the claim it makes: any spend — posting an
// attestation or rotating the box — must carry the same token (id and amount)
// into OUTPUTS(0). Only the token id and amount are pinned; the payload is
// whatever the oracle writes, and the box's ERG value is not conserved (removed
// 2026-09-23, owner decision: only the oracle can spend this box, so preserving
// value would have been belt-and-braces).
//
// Before posting, the oracle checks three things off-chain: the transfer matches
// the (recipient, amount) registered at funding, it has reached the confirmation
// rule (§4.2), and it survived the taint screen (Tether blacklist/freeze and
// sanctions, §4.3 — a transfer that fails is never attested). One attestation per
// deal, and one in flight at a time: this box holds a single payload, so a release
// using it must confirm before the next one is posted (§3.1).
//
// WHAT IS NOT CHECKED ON-CHAIN: nothing reads R4 here — any contents pass as
// long as the NFT survives — and no contract anywhere can confirm the transfer,
// the screening, or who sent it. The vault contracts check only that the NFT is
// present and that R4 names the deal being paid out. Note the dealId (blake2b256
// of the deal terms) covers asset, chain, amount, keys and nonce but NOT the
// buyer's receive address: "paid to the right address" rests entirely on the
// oracle's word, and the srcTxId audit trail lives at the oracle's API, not in
// this register.
//
// Phase 2 replaces the single trusted key with a guard set that threshold-signs
// the same 32-byte dealId — same register, same size.
//
// Constants:
//   ORACLE_NFT_ID  Coll[Byte]  32 B token id minted once by the oracle operator
//   ORACLE_KEY     Coll[Byte]  33 B compressed secp256k1 point (oracle signing key)
{
  proveDlog(decodePoint(%%ORACLE_KEY%%)) && sigmaProp(
    OUTPUTS(0).tokens.size > 0 &&
    OUTPUTS(0).tokens(0)._1 == %%ORACLE_NFT_ID%% &&
    OUTPUTS(0).tokens(0)._2 == SELF.tokens(0)._2
  )
}
