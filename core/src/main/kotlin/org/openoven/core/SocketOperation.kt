package org.openoven.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class SocketDeadline(timeoutMillis:Long,private val clock:()->Long=System::nanoTime) {
    private val started=clock()
    private val budget=timeoutMillis*1_000_000
    fun remainingMillis():Int {
        val remaining=budget-(clock()-started)
        if(remaining<=0)throw SocketTimeoutException("Przekroczono czas odpowiedzi")
        return ((remaining+999999)/1_000_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    fun expired()=clock()-started>=budget
}

/** Whole request budget includes the mutex and write. Cancellation closes only its captured socket. */
internal class SocketOperation(private val workers:CoroutineScope) {
    suspend fun <T> run(timeoutMillis:Long,mutex:Mutex,socket:()->Socket,abort:(Socket)->Unit,
                        operation:(Socket,SocketDeadline)->T):T {
        val deadline=SocketDeadline(timeoutMillis)
        try {
            return withTimeout(timeoutMillis) {
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    val owned=socket()
                    suspendCancellableCoroutine { continuation ->
                        val worker=workers.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
                            try {
                                val result=operation(owned,deadline)
                                if(continuation.isActive)continuation.resume(result)
                            } catch(e:Exception) {
                                if(continuation.isActive)continuation.resumeWithException(e)
                            }
                        }
                        continuation.invokeOnCancellation { worker.cancel();abort(owned) }
                        worker.invokeOnCompletion { cause ->
                            if(cause is CancellationException && continuation.isActive)continuation.cancel(cause)
                        }
                        worker.start()
                    }
                }
            }
        } catch(e:TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw SocketTimeoutException("Przekroczono czas całej operacji")
        }
    }
}
