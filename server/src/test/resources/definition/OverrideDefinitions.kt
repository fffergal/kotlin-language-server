interface DelegatedOperation {
    fun delegatedCall(): String
}

interface AggregateOperation : DelegatedOperation

class AggregateOperationImpl : AggregateOperation {
    override fun delegatedCall(): String = "delegated"
}

open class DelegatingBase(delegate: AggregateOperation) : AggregateOperation by delegate

class DelegatingChild(delegate: AggregateOperation) : DelegatingBase(delegate) {
    fun invokeDelegated(): String = delegatedCall()
}

interface InheritedOperation {
    fun inheritedCall(): String
}

abstract class InheritedBase : InheritedOperation

abstract class InheritedChild : InheritedBase() {
    fun invokeInherited(): String = inheritedCall()
}

interface DiamondRoot {
    fun diamondCall(): String
}

interface DiamondLeft : DiamondRoot
interface DiamondRight : DiamondRoot

abstract class DiamondChild : DiamondLeft, DiamondRight {
    fun invokeDiamond(): String = diamondCall()
}

open class DirectBase {
    open fun directCall(): String = "base"
}

class DirectChild : DirectBase() {
    override fun directCall(): String = "child"
    fun invokeDirect(): String = directCall()
}

class Constructed(val value: String)
fun constructValue(): Constructed = Constructed("value")
