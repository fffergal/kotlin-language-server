interface Operation {
    fun execute(): String
    val state: String
}

interface AggregateOperation : Operation

abstract class AbstractOperation : AggregateOperation

class AlphaOperation : AbstractOperation() {
    override fun execute(): String = "alpha"
    override val state: String = "alpha"
}

class BetaOperation : Operation {
    override fun execute(): String = "beta"
    override val state: String = "beta"
}

interface DiamondLeft : Operation
interface DiamondRight : Operation

class DiamondOperation : DiamondLeft, DiamondRight {
    override fun execute(): String = "diamond"
    override val state: String = "diamond"
}

open class DelegatingOperation(delegate: Operation) : Operation by delegate

class DelegatingChild(delegate: Operation) : DelegatingOperation(delegate) {
    fun invokeDelegated(): String = execute()
}

open class ConcreteBase
open class ConcreteChild : ConcreteBase()
class ConcreteGrandchild : ConcreteChild()

interface Unimplemented
class PendingImplementation

fun invokeReference(operation: Operation): String = operation.execute() + operation.state
