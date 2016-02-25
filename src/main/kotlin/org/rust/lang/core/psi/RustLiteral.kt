package org.rust.lang.core.psi

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import org.rust.lang.core.psi.visitors.RustVisitorEx

sealed class RustLiteral(type: IElementType, text: CharSequence) : LeafPsiElement(type, text) {
    /**
     * Get literal value as a Java object.
     */
    abstract val value: Any?

    /**
     * Get a list of possible suffixes for given literal type.
     */
    abstract val possibleSuffixes: Collection<String>

    abstract override fun toString(): String

    protected abstract fun computeMetadata(): Metadata

    val metadata: Metadata by lazy { computeMetadata() }

    /**
     * Literal token type.
     */
    val tokenType: IElementType
        get() = node.elementType

    /**
     * Get a fragment of the source which denotes the value of the literal as-is (without any escaping etc).
     */
    val valueString: String?
        get() = metadata.value?.substring(text)

    /**
     * Get literal suffix.
     */
    val suffix: String?
        get() = metadata.suffix?.substring(text)

    override fun accept(visitor: PsiElementVisitor) = when (visitor) {
        is RustVisitorEx -> visitor.visitLiteral(this)
        else             -> super.accept(visitor)
    }

    /**
     * Base class for numeric literals: integers and floats.
     */
    abstract class Number(type: IElementType, text: CharSequence) : RustLiteral(type, text)

    /**
     * Base class for character and string literals.
     */
    abstract class Text(type: IElementType, text: CharSequence) : RustLiteral(type, text) {
        override abstract val value: String?
        abstract val hasPairedQuotes: Boolean
    }

    /**
     * Stores offsets of distinguishable parts of a literal.
     */
    data class Metadata(val prefix: TextRange? = null,
                        val openDelim: TextRange? = null,
                        val value: TextRange? = null,
                        val closeDelim: TextRange? = null,
                        val suffix: TextRange? = null) {
        companion object {
            fun fromEndOffsets(prefixEnd: Int, openDelimEnd: Int, valueEnd: Int,
                               closeDelimEnd: Int, suffixEnd: Int): Metadata =
                Metadata(
                    prefix = makeRange(0, prefixEnd),
                    openDelim = makeRange(prefixEnd, openDelimEnd),
                    value = makeRange(openDelimEnd, valueEnd),
                    closeDelim = makeRange(valueEnd, closeDelimEnd),
                    suffix = makeRange(closeDelimEnd, suffixEnd))

            private fun makeRange(start: Int, end: Int): TextRange? = when {
                end - start > 0 -> TextRange(start, end)
                else            -> null
            }
        }
    }
}
