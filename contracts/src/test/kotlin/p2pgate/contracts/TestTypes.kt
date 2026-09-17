package p2pgate.contracts

import sigma.Coll
import sigma.GroupElement
import sigma.PreHeader
import sigmastate.crypto.SigmaProtocolPrivateInput
import sigmastate.helpers.ErgoLikeTestProvingInterpreter

/** PreHeader with an explicit timestamp so freshness checks are testable. */
class TestPreHeader(
    private val ts: Long,
    private val h: Int,
    private val miner: GroupElement,
) : PreHeader {
    override fun version(): Byte = 1
    override fun parentId(): Coll<Any> = SigmaBridge.collFrom(ByteArray(32))
    override fun timestamp(): Long = ts
    override fun nBits(): Long = 0L
    override fun height(): Int = h
    override fun minerPk(): GroupElement = miner
    override fun votes(): Coll<Any> = SigmaBridge.collFrom(byteArrayOf(1, 1, 1))
}

/** Proving interpreter holding an explicit secret list (seller/user/oracle keys). */
class TestProver(
    secrets: List<SigmaProtocolPrivateInput<*>>,
) : ErgoLikeTestProvingInterpreter() {

    private val s: scala.collection.immutable.IndexedSeq<SigmaProtocolPrivateInput<*>> =
        SigmaBridge.indexedSeq(secrets)

    override fun secrets(): scala.collection.immutable.Seq<SigmaProtocolPrivateInput<*>> = s
}
