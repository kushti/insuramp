package p2pgate.contracts;

import scala.collection.immutable.Map;
import scala.collection.immutable.Map$;

/**
 * Java-side interop shims for sigma-state internals that Kotlin source cannot name
 * ({@code $}-suffixed Scala classes: companion objects and the {@code SSigmaProp$}
 * type parameter).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class CompilerBridge {

    private CompilerBridge() {}

    public static Map<String, Object> emptyEnv() {
        return (Map<String, Object>) (Map<?, ?>) Map$.MODULE$.<String, Object>empty();
    }

    /** Converts a successful compile result into a constant-segregated ErgoTree. */
    public static sigma.ast.ErgoTree toErgoTree(sigma.compiler.CompilerResult<?> result) {
        sigma.ast.Value<sigma.ast.SType> tree = result.buildTree();
        sigma.ast.Value<sigma.ast.SSigmaProp$> prop = (sigma.ast.Value<sigma.ast.SSigmaProp$>) (sigma.ast.Value<?>) tree;
        return sigma.ast.ErgoTree.fromProposition(prop);
    }
}
