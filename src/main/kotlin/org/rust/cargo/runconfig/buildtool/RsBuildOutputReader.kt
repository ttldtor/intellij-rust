/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.buildtool

import com.intellij.build.BuildProgressListener
import com.intellij.build.events.BuildEvent
import com.intellij.build.output.BuildOutputParser
import com.intellij.build.output.LineProcessor
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil.escapeToRegexp
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.future.future
import org.rust.cargo.runconfig.RsAnsiEscapeDecoder.Companion.ANSI_SGR_RE
import org.rust.cargo.runconfig.RsAnsiEscapeDecoder.Companion.CSI
import java.util.concurrent.CompletableFuture

@Suppress("UnstableApiUsage")
open class RsBuildOutputReader(
    private val buildId: Any,
    private val parentEventId: Any,
    buildProgressListener: BuildProgressListener,
    private val stdoutParser: BuildOutputParser,
    private val stderrParser: BuildOutputParser
) {
    private val readJob: Job = createReadJob(buildProgressListener)
    private val appendParentJob: Job = Job()

    private val outputLinesChannel: Channel<Line> = Channel(100)

    private val stdoutLineProcessor: LineProcessor
    private val stderrLineProcessor: LineProcessor

    init {
        val appendScope = CoroutineScope(Dispatchers.Default + appendParentJob)
        stdoutLineProcessor = MyLineProcessor(readJob, appendScope, outputLinesChannel, stdout = true)
        stderrLineProcessor = MyLineProcessor(readJob, appendScope, outputLinesChannel, stdout = false)
    }


    private fun createReadJob(buildProgressListener: BuildProgressListener): Job {
        return CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
            var lastMessage: BuildEvent? = null
            val messageConsumer = { event: BuildEvent ->
                if (event != lastMessage) {
                    buildProgressListener.onEvent(buildId, event)
                }
                lastMessage = event
            }

            while (true) {
                val line = readLine() ?: break
                if (line.text.isBlank()) continue

                val parser = if (line is Line.StdoutLine) stdoutParser else stderrParser
                val readerWrapper = DummyBuildOutputInstantReader(parentEventId)
                parser.parse(line.text, readerWrapper, messageConsumer)
            }
        }
    }

    fun append(csq: CharSequence, outputType: Key<*>): RsBuildOutputReader {
        if (ProcessOutputType.isStdout(outputType)) {
            stdoutLineProcessor.append(csq)
        } else {
            stderrLineProcessor.append(csq
                .replace(ERASE_LINES_RE, "\n")
                .replace(BUILD_PROGRESS_RE) { it.value.trimEnd(' ', '\r', '\n') + "\n" })
        }
        return this
    }

    fun closeAndGetFuture(): CompletableFuture<Unit> {
        stdoutLineProcessor.close()
        stderrLineProcessor.close()
        outputLinesChannel.close()
        return CoroutineScope(Dispatchers.Default).future {
            appendParentJob.children.forEach { it.join() }
            appendParentJob.cancelAndJoin()
            readJob.cancelAndJoin()
        }
    }

    private fun readLine(): Line? =
        outputLinesChannel.poll() ?: runBlocking {
            try {
                outputLinesChannel.receive()
            } catch (e: ClosedReceiveChannelException) {
                null
            }
        }

    private class MyLineProcessor(
        private val job: Job,
        private val scope: CoroutineScope,
        private val channel: Channel<Line>,
        private val stdout: Boolean
    ) : LineProcessor() {
        @ExperimentalCoroutinesApi
        override fun process(line: String) {
            if (job.isCompleted) {
                LOG.warn("Build output reader closed")
                return
            }
            if (!job.isActive) {
                job.start()
            }
            val taggedLine = if (stdout) Line.StdoutLine(line) else Line.StderrLine(line)
            scope.launch(start = CoroutineStart.UNDISPATCHED) { channel.send(taggedLine) }
        }
    }

    companion object {
        private val LOG: Logger = Logger.getInstance(RsBuildOutputReader::class.java)

        private val ERASE_LINES_RE: Regex = """${escapeToRegexp(CSI)}\d?K""".toRegex()
        private val BUILD_PROGRESS_RE: Regex = """($ANSI_SGR_RE)* *Building($ANSI_SGR_RE)* \[ *=*>? *] \d+/\d+: [\w\-(.)]+(, [\w\-(.)]+)*( *[\r\n])*""".toRegex()

        private sealed class Line {
            abstract val text: String

            data class StdoutLine(override val text: String) : Line()
            data class StderrLine(override val text: String) : Line()
        }
    }
}
