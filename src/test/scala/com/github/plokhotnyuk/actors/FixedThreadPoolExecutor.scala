package com.github.plokhotnyuk.actors

import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicReference, AtomicInteger}
import java.lang.InterruptedException
import scala.annotation.tailrec
import java.util.concurrent.locks.AbstractQueuedSynchronizer

/**
 * A high performance implementation of thread pool with fixed number of threads.
 *
 * Implementation of task queue based on structure of non-intrusive MPSC node-based queue, described by Dmitriy Vyukov:
 * http://www.1024cores.net/home/lock-free-algorithms/queues/non-intrusive-mpsc-node-based-queue
 *
 * Idea of using of semaphores to control of queue access borrowed from implementation of ThreadManager of JActor2:
 * https://github.com/laforge49/JActor2/blob/master/jactor-impl/src/main/java/org/agilewiki/jactor/impl/ThreadManagerImpl.java
 *
 * @param threadCount a number of worker threads in pool
 * @param threadFactory a factory to be used to build worker threads
 * @param handler the handler for internal worker threads that will be called
 *                in case of unrecoverable errors encountered while executing tasks.
 */
class FixedThreadPoolExecutor(threadCount: Int = Runtime.getRuntime.availableProcessors(),
                              threadFactory: ThreadFactory = new ThreadFactory() {
                                def newThread(r: Runnable): Thread = new Thread(r)
                              },
                              handler: Thread.UncaughtExceptionHandler = new Thread.UncaughtExceptionHandler() {
                                def uncaughtException(t: Thread, e: Throwable): Unit = e.printStackTrace()
                              }) extends AbstractExecutorService {
  private val tail = new AtomicReference[TaskNode](new TaskNode())
  private val terminations = new CountDownLatch(threadCount)
  private val requests = new CountingSemaphore()
  private val closing = new AtomicInteger(0)
  private val head = new AtomicReference[TaskNode](tail.get)
  private val threads = {
    val tf = threadFactory // to avoid creating of field for the threadFactory constructor param
    (1 to threadCount).map(_ => tf.newThread(new Runnable() {
      def run(): Unit = doWork()
    }))
  }

  threads.foreach(_.start())

  def shutdown() {
    shutdownNow()
    awaitTermination(0, TimeUnit.MILLISECONDS)
  }

  def shutdownNow(): java.util.List[Runnable] = {
    closing.lazySet(1)
    threads.filter(_ ne Thread.currentThread()).foreach(_.interrupt()) // don't interrupt worker thread due call in task
    drainTo(new java.util.LinkedList[Runnable](), tail.get.getAndSet(null))
  }

  def isShutdown: Boolean = closing.get != 0

  def isTerminated: Boolean = terminations.getCount == 0

  def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = {
    if (threads.exists(_ eq Thread.currentThread())) terminations.countDown() // don't hang up due call in task
    terminations.await(timeout, unit)
  }

  def execute(task: Runnable) {
    if (isShutdown) throw new IllegalStateException("Cannot execute in terminating/shutdown state")
    enqueue(task)
    requests.releaseShared(1)
  }

  private def enqueue(task: Runnable) {
    if (task eq null) throw new NullPointerException
    val n = new TaskNode(task)
    head.getAndSet(n).lazySet(n)
  }

  @tailrec
  private def drainTo(ts: java.util.List[Runnable], n: TaskNode): java.util.List[Runnable] =
    if (n eq null) ts
    else {
      ts.add(n.task)
      drainTo(ts, n.get)
    }

  private def doWork() {
    try {
      while (closing.get == 0) {
        try {
          requests.acquireSharedInterruptibly(1)
          dequeueAndRun()
        } catch {
          case ex: InterruptedException => return
          case ex: Throwable => onError(ex)
        }
      }
    } finally {
      terminations.countDown()
    }
  }

  @tailrec
  private def dequeueAndRun() {
    val tn = tail.get
    val n = tn.get
    if ((n ne null) && tail.compareAndSet(tn, n)) {
      val t = n.task
      n.task = null
      t.run()
    } else dequeueAndRun()
  }

  private def onError(ex: Throwable) {
    handler.uncaughtException(Thread.currentThread(), ex)
  }
}

private class CountingSemaphore extends AbstractQueuedSynchronizer() {
  private val count = new AtomicInteger()

  override protected final def tryReleaseShared(releases: Int): Boolean = {
    count.getAndAdd(releases)
    true
  }

  @tailrec
  override protected final def tryAcquireShared(acquires: Int): Int = {
    val available = count.get
    val remaining = available - acquires
    if (remaining < 0 || count.compareAndSet(available, remaining)) remaining
    else tryAcquireShared(acquires)
  }
}

private class TaskNode(var task: Runnable = null) extends AtomicReference[TaskNode]