package org.openoven.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SocketOperationTest {
    @Test fun budgetIncludesMutexWaitWithoutExecutingQueuedIo()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val mutex=Mutex(locked=true);val ran=AtomicBoolean(false);val closed=AtomicBoolean(false)
        try {
            val result=runCatching { SocketOperation(scope).run(80,mutex,{ Socket() },{ closed.set(true) }) { _,_->ran.set(true) } }
            assertTrue(result.exceptionOrNull() is SocketTimeoutException)
            assertFalse(ran.get());assertFalse(closed.get())
        } finally { mutex.unlock();scope.cancel() }
    }
    @Test fun blockedIoIsReleasedByTimeoutClosingItsOwnSocket()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val released=CountDownLatch(1);val socket=Socket();val closed=AtomicBoolean(false)
        try {
            val start=System.nanoTime()
            val result=runCatching {
                SocketOperation(scope).run(100,Mutex(),{socket},{ owned->assertSame(socket,owned);owned.close();closed.set(true);released.countDown() }) { _,_->
                    check(released.await(3,TimeUnit.SECONDS));0
                }
            }
            assertTrue(result.exceptionOrNull() is SocketTimeoutException);assertTrue(closed.get())
            assertTrue((System.nanoTime()-start)/1_000_000<1500)
        } finally { released.countDown();socket.close();scope.cancel() }
    }
    @Test fun cancellationUnblocksOldIoWithoutTouchingReplacementResources()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val entered=CompletableDeferred<Unit>();val released=CountDownLatch(1)
        val old=Socket();val replacement=Socket()
        try {
            val operation=launch {
                SocketOperation(scope).run(5000,Mutex(),{old},{ owned->owned.close();released.countDown() }) { _,_->
                    entered.complete(Unit);released.await(3,TimeUnit.SECONDS)
                }
            }
            withTimeout(2000) { entered.await() }
            withTimeout(1000) { operation.cancelAndJoin() }
            assertTrue(old.isClosed);assertFalse(replacement.isClosed)
        } finally { released.countDown();old.close();replacement.close();scope.cancel() }
    }
    @Test fun monotonicBudgetUsesElapsedTimeAndRejectsExpiry() {
        var clock=0L
        val deadline=SocketDeadline(100) { clock }
        clock=50_000_000;assertEquals(50,deadline.remainingMillis())
        clock=100_000_000;assertTrue(deadline.expired())
        assertThrows(SocketTimeoutException::class.java) { deadline.remainingMillis() }
    }
}
