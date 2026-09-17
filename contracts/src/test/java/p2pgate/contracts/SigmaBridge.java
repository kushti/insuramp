package p2pgate.contracts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Static bridge over the sigma-state / sigmastate Scala APIs that are awkward to
 * reach from Kotlin (nested {@code $}-named classes, immutable collections,
 * default-argument forwarders). All methods are pure interop shims.
 */
@SuppressWarnings({"unchecked", "rawtypes", "varargs"})
public final class SigmaBridge {

    private SigmaBridge() {}

    // ---------------------------------------------------------------- scala collections

    public static <T> scala.collection.immutable.IndexedSeq<T> indexedSeq(List<T> items) {
        return org.ergoplatform.sdk.JavaHelpers$.MODULE$.toIndexedSeq(items);
    }

    public static <K, V> scala.collection.immutable.Map<K, V> sMapOf(List<scala.Tuple2<K, V>> entries) {
        scala.collection.immutable.Map<K, V> m =
                (scala.collection.immutable.Map<K, V>) scala.collection.immutable.Map$.MODULE$.empty();
        for (scala.Tuple2<K, V> e : entries) {
            m = m.updated(e._1(), e._2());
        }
        return m;
    }

    public static scala.collection.immutable.Map<String, Object> emptyEnv() {
        return (scala.collection.immutable.Map<String, Object>)
                (scala.collection.immutable.Map<?, ?>) scala.collection.immutable.Map$.MODULE$.<String, Object>empty();
    }

    // ---------------------------------------------------------------- sigma.Coll

    public static sigma.Coll<Object> collFrom(byte[] bytes) {
        return org.ergoplatform.sdk.JavaHelpers$.MODULE$.collFrom(bytes);
    }

    public static <T> sigma.Coll<T> collOf(List<T> items, sigma.data.RType<T> rtype) {
        sigma.CollBuilder b = collFrom(new byte[0]).builder();
        return b.fromItems(indexedSeq(items), rtype);
    }

    public static sigma.Coll<scala.Tuple2<sigma.Coll<Object>, Object>> tokens(List<scala.Tuple2<byte[], Long>> toks) {
        List<scala.Tuple2<sigma.Coll<Object>, Object>> items = new ArrayList<>();
        for (scala.Tuple2<byte[], Long> t : toks) {
            items.add(scala.Tuple2.apply(collFrom(t._1()), (Object) t._2()));
        }
        sigma.data.RType<?> rtype = sigma.data.RType$.MODULE$.pairRType(
                org.ergoplatform.sdk.JavaHelpers$.MODULE$.TokenIdRType(),
                org.ergoplatform.sdk.JavaHelpers$.MODULE$.JLongRType());
        return collOf(items, (sigma.data.RType<scala.Tuple2<sigma.Coll<Object>, Object>>) rtype);
    }

    public static sigma.Coll<scala.Tuple2<sigma.Coll<Object>, Object>> emptyTokens() {
        return org.ergoplatform.ErgoBox$.MODULE$.$lessinit$greater$default$3();
    }

    public static scala.collection.Map<org.ergoplatform.ErgoBox.NonMandatoryRegisterId, sigma.ast.EvaluatedValue<? extends sigma.ast.SType>> emptyRegs() {
        return org.ergoplatform.ErgoBox$.MODULE$.$lessinit$greater$default$4();
    }

    // ---------------------------------------------------------------- constants / registers

    public static sigma.ast.EvaluatedValue<? extends sigma.ast.SType> bytesConst(byte[] b) {
        return sigma.ast.ByteArrayConstant.apply(b);
    }

    public static sigma.ast.Value<sigma.ast.SType> asVal(sigma.ast.EvaluatedValue<? extends sigma.ast.SType> c) {
        return (sigma.ast.Value<sigma.ast.SType>) (sigma.ast.Value<?>) c;
    }

    public static sigma.ast.Tuple tuple(sigma.ast.Value<sigma.ast.SType>... elems) {
        List<sigma.ast.Value<sigma.ast.SType>> l = java.util.Arrays.asList(elems);
        return sigma.ast.Tuple.apply(indexedSeq(l));
    }

    public static sigma.ast.Value<sigma.ast.SType> intVal(int v) {
        return asVal(sigma.ast.IntConstant.apply(v));
    }

    public static sigma.ast.Value<sigma.ast.SType> longVal(long v) {
        return asVal(sigma.ast.LongConstant.apply(v));
    }

    public static sigma.ast.Value<sigma.ast.SType> byteVal(byte v) {
        return asVal(sigma.ast.ByteConstant.apply(v));
    }

    public static org.ergoplatform.ErgoBox.NonMandatoryRegisterId regId(int i) {
        return (org.ergoplatform.ErgoBox.NonMandatoryRegisterId) org.ergoplatform.ErgoBox$.MODULE$.registerByIndex(i);
    }

    public static scala.collection.Map<org.ergoplatform.ErgoBox.NonMandatoryRegisterId, sigma.ast.EvaluatedValue<? extends sigma.ast.SType>> regs(
            List<scala.Tuple2<org.ergoplatform.ErgoBox.NonMandatoryRegisterId, sigma.ast.EvaluatedValue<? extends sigma.ast.SType>>> entries) {
        return sMapOf(entries);
    }

    // ---------------------------------------------------------------- boxes

    public static org.ergoplatform.ErgoBox box(
            long value, sigma.ast.ErgoTree tree,
            sigma.Coll<scala.Tuple2<sigma.Coll<Object>, Object>> tokens,
            scala.collection.Map<org.ergoplatform.ErgoBox.NonMandatoryRegisterId, sigma.ast.EvaluatedValue<? extends sigma.ast.SType>> regs,
            String txId, short index, int creationHeight) {
        return new org.ergoplatform.ErgoBox(value, tree, tokens, regs, txId, index, creationHeight);
    }

    public static org.ergoplatform.ErgoBoxCandidate candidate(
            long value, sigma.ast.ErgoTree tree, int creationHeight,
            sigma.Coll<scala.Tuple2<sigma.Coll<Object>, Object>> tokens,
            scala.collection.Map<org.ergoplatform.ErgoBox.NonMandatoryRegisterId, sigma.ast.EvaluatedValue<? extends sigma.ast.SType>> regs) {
        return new org.ergoplatform.ErgoBoxCandidate(value, tree, creationHeight, tokens, regs);
    }

    public static sigma.ast.ErgoTree p2pkTree(sigma.data.ProveDlog pk) {
        return sigma.ast.ErgoTree.fromSigmaBoolean(pk);
    }

    // ---------------------------------------------------------------- context extension

    public static sigma.interpreter.ContextExtension contextExtension(
            Map<Integer, ? extends sigma.ast.EvaluatedValue<? extends sigma.ast.SType>> vars) {
        List<scala.Tuple2<Object, sigma.ast.EvaluatedValue<? extends sigma.ast.SType>>> entries = new ArrayList<>();
        for (Map.Entry<Integer, ? extends sigma.ast.EvaluatedValue<? extends sigma.ast.SType>> e : vars.entrySet()) {
            entries.add(scala.Tuple2.apply((Object) e.getKey().byteValue(), e.getValue()));
        }
        return sigma.interpreter.ContextExtension.apply(sMapOf(entries));
    }

    // ---------------------------------------------------------------- keys / group elements

    public static sigmastate.crypto.DLogProtocol.DLogProverInput dlogRandom() {
        return sigmastate.crypto.DLogProtocol.DLogProverInput$.MODULE$.random();
    }

    public static sigma.crypto.Platform.Ecp ecp(sigmastate.crypto.DLogProtocol.DLogProverInput k) {
        return k.publicImage().value();
    }

    public static byte[] ecpEncoded(sigma.crypto.Platform.Ecp p, boolean compressed) {
        return sigma.crypto.Platform$.MODULE$.getASN1Encoding(p, compressed);
    }

    public static sigma.GroupElement groupElement(sigma.crypto.Platform.Ecp p) {
        return sigma.data.CGroupElement.apply(p);
    }

    // ---------------------------------------------------------------- hashing (Ergo blake2b-256)

    public static byte[] blake2b256(byte[]... parts) {
        org.bouncycastle.crypto.digests.Blake2bDigest d = new org.bouncycastle.crypto.digests.Blake2bDigest(256);
        for (byte[] p : parts) {
            d.update(p, 0, p.length);
        }
        byte[] out = new byte[32];
        d.doFinal(out, 0);
        return out;
    }

    // ---------------------------------------------------------------- transactions / context

    public static org.ergoplatform.UnsignedErgoLikeTransaction unsignedTx(
            List<org.ergoplatform.ErgoBox> inputs,
            List<org.ergoplatform.ErgoBox> dataInputs,
            List<org.ergoplatform.ErgoBoxCandidate> outputs) {
        return unsignedTxWithExt(inputs, dataInputs, outputs, sigma.interpreter.ContextExtension$.MODULE$.empty(), 0);
    }

    /** Like [unsignedTx], but attaches a context extension to the extIndex-th input's spending proof —
     *  the interpreter reads getVar values from the proof's contextExt, not from ErgoLikeContext. */
    public static org.ergoplatform.UnsignedErgoLikeTransaction unsignedTxWithExt(
            List<org.ergoplatform.ErgoBox> inputs,
            List<org.ergoplatform.ErgoBox> dataInputs,
            List<org.ergoplatform.ErgoBoxCandidate> outputs,
            sigma.interpreter.ContextExtension ext,
            int extIndex) {
        List<org.ergoplatform.UnsignedInput> ins = new ArrayList<>();
        int i = 0;
        for (org.ergoplatform.ErgoBox b : inputs) {
            sigma.interpreter.ProverResult pr = (i == extIndex)
                    ? new sigma.interpreter.ProverResult(new byte[0], ext)
                    : sigma.interpreter.ProverResult$.MODULE$.empty();
            ins.add(org.ergoplatform.Input.apply(b.id(), pr));
            i++;
        }
        List<org.ergoplatform.DataInput> dis = new ArrayList<>();
        for (org.ergoplatform.ErgoBox b : dataInputs) {
            dis.add(org.ergoplatform.DataInput.apply(b.id()));
        }
        return org.ergoplatform.UnsignedErgoLikeTransaction.apply(indexedSeq(ins), indexedSeq(dis), indexedSeq(outputs));
    }

    public static sigma.data.AvlTreeData avlDummy() {
        return sigma.data.AvlTreeData$.MODULE$.dummy();
    }

    public static sigma.interpreter.ProverResult proverResultEmpty() {
        return sigma.interpreter.ProverResult$.MODULE$.empty();
    }

    public static org.ergoplatform.ErgoLikeContext context(
            int height, byte[] headerBytes,
            scala.collection.immutable.IndexedSeq<org.ergoplatform.ErgoBox> inputs,
            scala.collection.immutable.IndexedSeq<org.ergoplatform.ErgoBox> dataInputs,
            org.ergoplatform.ErgoLikeTransactionTemplate<? extends org.ergoplatform.UnsignedInput> tx,
            int selfIndex, byte version) {
        return sigmastate.helpers.ErgoLikeContextTesting.apply(
                height, avlDummy(), headerBytes, inputs, dataInputs, tx, selfIndex, version);
    }

    /** Builds a context with an explicit PreHeader. ErgoLikeContextTesting.apply is unusable:
     *  its dummyPreHeader embeds a malformed (32-byte) miner pubkey that crashes GroupElementSerializer. */
    public static org.ergoplatform.ErgoLikeContext contextWithPreHeader(
            sigma.PreHeader ph,
            scala.collection.immutable.IndexedSeq<org.ergoplatform.ErgoBox> inputs,
            scala.collection.immutable.IndexedSeq<org.ergoplatform.ErgoBox> dataInputs,
            org.ergoplatform.ErgoLikeTransactionTemplate<? extends org.ergoplatform.UnsignedInput> tx,
            int selfIndex,
            sigma.interpreter.ContextExtension ext,
            byte version) {
        return new org.ergoplatform.ErgoLikeContext(
                avlDummy(),
                sigmastate.helpers.ErgoLikeContextTesting$.MODULE$.noHeaders(),
                ph,
                dataInputs,
                inputs,
                tx,
                selfIndex,
                ext,
                org.ergoplatform.validation.ValidationRules$.MODULE$.currentSettings(),
                10_000_000L,
                0L,
                version);
    }

    public static org.ergoplatform.ErgoLikeContext withPreHeader(org.ergoplatform.ErgoLikeContext ctx, sigma.PreHeader ph) {
        return org.ergoplatform.ErgoLikeContext.copy(ctx,
                ctx.lastBlockUtxoRoot(),
                ctx.headers(),
                ph,
                org.ergoplatform.ErgoLikeContext.copy$default$5(ctx),
                org.ergoplatform.ErgoLikeContext.copy$default$6(ctx),
                org.ergoplatform.ErgoLikeContext.copy$default$7(ctx),
                org.ergoplatform.ErgoLikeContext.copy$default$8(ctx),
                org.ergoplatform.ErgoLikeContext.copy$default$9(ctx),
                org.ergoplatform.ErgoLikeContext.copy$default$10(ctx),
                org.ergoplatform.ErgoLikeContext.copy$default$11(ctx),
                org.ergoplatform.ErgoLikeContext.copy$default$12(ctx),
                org.ergoplatform.ErgoLikeContext.copy$default$13(ctx),
                org.ergoplatform.ErgoLikeContext.copy$default$14(ctx));
    }

    // ---------------------------------------------------------------- proving / verification

    public static scala.util.Try<sigma.interpreter.CostedProverResult> prove(
            sigmastate.helpers.ErgoLikeTestProvingInterpreter prover,
            sigma.ast.ErgoTree tree, sigmastate.interpreter.InterpreterContext ctx, byte[] msg) {
        return prover.prove(emptyEnv(), tree, ctx, msg, sigmastate.interpreter.HintsBag$.MODULE$.empty());
    }

    public static boolean verify(
            sigma.ast.ErgoTree tree, sigmastate.interpreter.InterpreterContext ctx,
            sigma.interpreter.ProverResult pr, byte[] msg) {
        scala.util.Try<scala.Tuple2<Object, Object>> t =
                new sigmastate.helpers.ErgoLikeTestInterpreter().verify(emptyEnv(), tree, ctx, pr, msg);
        return t.isSuccess() && (Boolean) t.get()._1();
    }

    /** Like [verify] but surfaces the failure instead of collapsing it to false. */
    public static String verifyDetail(
            sigma.ast.ErgoTree tree, sigmastate.interpreter.InterpreterContext ctx,
            sigma.interpreter.ProverResult pr, byte[] msg) {
        scala.util.Try<scala.Tuple2<Object, Object>> t =
                new sigmastate.helpers.ErgoLikeTestInterpreter().verify(emptyEnv(), tree, ctx, pr, msg);
        if (t.isSuccess()) return "OK " + t.get()._1();
        Throwable e = (Throwable) t.failed().get();
        StringBuilder sb = new StringBuilder("FAIL ").append(e);
        for (StackTraceElement el : e.getStackTrace()) {
            if (el.getClassName().startsWith("sigma") || el.getClassName().startsWith("sigmastate") || el.getClassName().startsWith("org.ergoplatform")) {
                sb.append(" @ ").append(el.getClassName()).append(".").append(el.getMethodName()).append(":").append(el.getLineNumber());
            }
        }
        return sb.toString();
    }
}
