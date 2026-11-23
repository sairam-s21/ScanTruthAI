package com.scantruth.ai.ui.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * A coroutine scope owned by a state holder instead of a composable.
 *
 * `rememberCoroutineScope()` is cancelled as soon as its screen leaves composition, so
 * starting OCR or an AI call there aborts the work the moment navigation opens the next
 * screen. The state holders in [com.scantruth.ai.ui.ScanTruthAppShell] outlive every
 * destination, so long-running work is launched here and survives navigation.
 *
 * [cancelChildren] stops pending work (reject, sign-out, new document) while keeping the
 * holder usable for the next run.
 */
internal class StateWorkScope(
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    private val parent = SupervisorJob()
    private val scope = CoroutineScope(parent + dispatcher)

    val isActive: Boolean get() = parent.isActive

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    fun cancelChildren() {
        scope.coroutineContext.cancelChildren()
    }
}
