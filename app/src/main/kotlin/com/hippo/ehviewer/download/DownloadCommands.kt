package com.hippo.ehviewer.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 串行命令队列拥有者：统一承接下载控制指令与当前画廊任务回调，避免并发竞态。 */
internal class DownloadCommands(scope: CoroutineScope, private val reportFailure: (Throwable) -> Unit) {
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (command in commands) {
                try {
                    command()
                } catch (_: CancellationException) {
                    // 仅当所属作用域本身被取消时退出消费者循环；单条指令取消不误报失败且不中断全局队列。
                    coroutineContext.ensureActive()
                } catch (t: Throwable) {
                    reportFailure(t)
                }
            }
        }
    }

    /** 投递无需同步等待结果的命令。 */
    fun post(command: suspend () -> Unit) {
        commands.trySend(command).getOrThrow()
    }

    /** 入队并返回可等待的结果句柄。 */
    fun <T> enqueue(command: suspend () -> T): Deferred<Result<T>> {
        val result = CompletableDeferred<Result<T>>()
        post {
            try {
                result.complete(Result.success(command()))
            } catch (ce: CancellationException) {
                result.cancel(ce)
                throw ce
            } catch (t: Throwable) {
                result.complete(Result.failure(t))
            }
        }
        return result
    }

    /** 顺序执行命令并等待返回，异常透传给调用方。 */
    suspend fun <T> execute(command: suspend () -> T): T = enqueue(command).await().getOrThrow()
}
