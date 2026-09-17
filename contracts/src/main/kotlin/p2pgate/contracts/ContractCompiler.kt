package p2pgate.contracts

import java.util.Base64
import sigma.ast.ErgoTree
import sigma.compiler.SigmaCompiler
import sigma.compiler.ir.CompiletimeIRContext

/**
 * A compile-time constant injected into an ErgoScript source. Sources carry
 * `%%NAME%%` markers which are substituted with ErgoScript literals before
 * compilation (env-map value wrapping is avoided deliberately — substitution
 * makes the injected values visible to the typechecker exactly as written).
 */
sealed interface ConstValue {
    data class Bytes(val value: ByteArray) : ConstValue
    data class IntNum(val value: Int) : ConstValue
    data class LongNum(val value: Long) : ConstValue

    /** Verbatim ErgoScript literal (e.g. {@code bigInt("14400000")}). */
    data class Raw(val literal: String) : ConstValue
}

/** Compiles the `.es` sources in `contracts/src/main/ergoscript/`. */
object ContractCompiler {

    // One compiler per network prefix: SigmaCompiler bakes the prefix (0x00 mainnet,
    // 0x10 testnet) into address literals at compile time. Cached because compilation
    // is not thread-safe to interleave on a shared instance.
    private val compilers = java.util.concurrent.ConcurrentHashMap<Byte, SigmaCompiler>()

    private fun compilerFor(networkPrefix: Byte): SigmaCompiler =
        compilers.computeIfAbsent(networkPrefix) { SigmaCompiler.apply(it) }

    fun loadSource(name: String): String =
        javaClass.getResource("/$name")?.readText()
            ?: error("resource /$name not found")

    fun substitute(source: String, constants: Map<String, ConstValue>): String =
        constants.entries.fold(source) { code, (name, cv) ->
            val literal = when (cv) {
                is ConstValue.Bytes -> "fromBase64(\"${Base64.getEncoder().encodeToString(cv.value)}\")"
                is ConstValue.IntNum -> cv.value.toString()
                is ConstValue.LongNum -> "${cv.value}L"
                is ConstValue.Raw -> cv.literal
            }
            code.replace("%%$name%%", literal)
        }

    fun compile(
        source: String,
        constants: Map<String, ConstValue> = emptyMap(),
        networkPrefix: Byte = ContractParams.NETWORK_PREFIX_MAINNET,
    ): ErgoTree {
        val code = substitute(source, constants)
        val result = compilerFor(networkPrefix).compile(CompilerBridge.emptyEnv(), code, CompiletimeIRContext())
        return CompilerBridge.toErgoTree(result)
    }

    fun compileResource(
        name: String,
        constants: Map<String, ConstValue> = emptyMap(),
        networkPrefix: Byte = ContractParams.NETWORK_PREFIX_MAINNET,
    ): ErgoTree = compile(loadSource(name), constants, networkPrefix)
}
