package p2pgate.ergo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import scala.Tuple2;

/**
 * Static bridge over the sigma-state / sigmastate Scala APIs that are awkward to
 * reach from Kotlin (immutable collections, {@code $}-named companion objects).
 * All methods are pure interop shims — the same pattern as the contracts
 * module's {@code CompilerBridge}/{@code SigmaBridge}. Only the small surface
 * {@code ClaimTxBuilder} needs lives here.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class ErgoBridge {

    private ErgoBridge() {}

    // ---------------------------------------------------------------- scala collections

    public static <T> scala.collection.immutable.IndexedSeq<T> indexedSeq(List<T> items) {
        return org.ergoplatform.sdk.JavaHelpers$.MODULE$.toIndexedSeq(items);
    }

    public static <K, V> scala.collection.immutable.Map<K, V> sMapOf(List<Tuple2<K, V>> entries) {
        scala.collection.immutable.Map<K, V> m =
                (scala.collection.immutable.Map<K, V>) scala.collection.immutable.Map$.MODULE$.empty();
        for (Tuple2<K, V> e : entries) {
            m = m.updated(e._1(), e._2());
        }
        return m;
    }

    // ---------------------------------------------------------------- sigma Coll

    public static sigma.Coll<Object> collFrom(byte[] bytes) {
        return org.ergoplatform.sdk.JavaHelpers$.MODULE$.collFrom(bytes);
    }

    public static sigma.Coll<scala.Tuple2<sigma.Coll<Object>, Object>> tokens(List<Tuple2<byte[], Long>> toks) {
        List<scala.Tuple2<sigma.Coll<Object>, Object>> items = new ArrayList<>();
        for (Tuple2<byte[], Long> t : toks) {
            items.add(Tuple2.apply(collFrom(t._1()), (Object) t._2()));
        }
        sigma.data.RType<?> rtype = sigma.data.RType$.MODULE$.pairRType(
                org.ergoplatform.sdk.JavaHelpers$.MODULE$.TokenIdRType(),
                org.ergoplatform.sdk.JavaHelpers$.MODULE$.JLongRType());
        sigma.CollBuilder b = collFrom(new byte[0]).builder();
        return b.fromItems(indexedSeq(items), (sigma.data.RType<scala.Tuple2<sigma.Coll<Object>, Object>>) rtype);
    }

    public static sigma.Coll<scala.Tuple2<sigma.Coll<Object>, Object>> emptyTokens() {
        return org.ergoplatform.ErgoBox$.MODULE$.$lessinit$greater$default$3();
    }

    // ---------------------------------------------------------------- registers

    public static org.ergoplatform.ErgoBox.NonMandatoryRegisterId regId(int i) {
        return (org.ergoplatform.ErgoBox.NonMandatoryRegisterId) org.ergoplatform.ErgoBox$.MODULE$.registerByIndex(i);
    }

    public static scala.collection.Map<org.ergoplatform.ErgoBox.NonMandatoryRegisterId, sigma.ast.EvaluatedValue<? extends sigma.ast.SType>> regs(
            List<Tuple2<org.ergoplatform.ErgoBox.NonMandatoryRegisterId, sigma.ast.EvaluatedValue<? extends sigma.ast.SType>>> entries) {
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

    // ---------------------------------------------------------------- context extension / transactions

    public static sigma.interpreter.ContextExtension contextExtension(
            Map<Integer, ? extends sigma.ast.EvaluatedValue<? extends sigma.ast.SType>> vars) {
        List<Tuple2<Object, sigma.ast.EvaluatedValue<? extends sigma.ast.SType>>> entries = new ArrayList<>();
        for (Map.Entry<Integer, ? extends sigma.ast.EvaluatedValue<? extends sigma.ast.SType>> e : vars.entrySet()) {
            entries.add(Tuple2.apply((Object) e.getKey().byteValue(), e.getValue()));
        }
        return sigma.interpreter.ContextExtension.apply(sMapOf(entries));
    }

    /**
     * Builds an unsigned transaction attaching [ext] to the extIndex-th input's
     * spending proof (the interpreter reads getVar values from the proof's
     * context extension).
     */
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

    public static sigma.interpreter.ContextExtension emptyExt() {
        return sigma.interpreter.ContextExtension$.MODULE$.empty();
    }
}
