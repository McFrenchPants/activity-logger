package com.mcfrenchpants.activityledger.core.testing

import com.mcfrenchpants.activityledger.core.domain.interpretation.ActivityInterpreter
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance

/**
 * Scriptable [ActivityInterpreter] for tests.
 *
 * Responses come from, in priority order: results queued with [enqueue] (consumed in order),
 * then the [respondWith] lambda if set. Calling [interpret] with neither available throws
 * [IllegalStateException]. Every input is recorded in [receivedInputs].
 */
class FakeActivityInterpreter(
    override var provenance: InterpreterProvenance = DEFAULT_PROVENANCE,
) : ActivityInterpreter {

    private val scripted = ArrayDeque<InterpretationResult>()
    private var responder: ((InterpretationInput) -> InterpretationResult)? = null
    private val inputs = mutableListOf<InterpretationInput>()

    /** Inputs received so far, in call order. */
    val receivedInputs: List<InterpretationInput> get() = inputs.toList()

    /** Number of [interpret] calls so far. */
    val callCount: Int get() = inputs.size

    /** Queues results to be returned by subsequent calls, in order. */
    fun enqueue(vararg results: InterpretationResult) {
        scripted.addAll(results)
    }

    /** Sets a fallback responder used once queued results are exhausted. */
    fun respondWith(block: (InterpretationInput) -> InterpretationResult) {
        responder = block
    }

    override suspend fun interpret(input: InterpretationInput): InterpretationResult {
        inputs += input
        scripted.removeFirstOrNull()?.let { return it }
        val r = responder ?: throw IllegalStateException("FakeActivityInterpreter has no scripted result for call #${inputs.size}")
        return r(input)
    }

    companion object {
        val DEFAULT_PROVENANCE: InterpreterProvenance = InterpreterProvenance(
            interpreterVersion = "fake-interpreter-1",
            promptVersion = "fake-prompt-1",
            schemaVersion = 1,
        )
    }
}
