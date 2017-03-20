package com.obsidiandynamics.actors

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors

import com.obsidiandynamics.actors.{LoopActor => LA}
import LA.Address
import com.github.plokhotnyuk.actors.Message



object LoopActorBench {
  private def send(address: Address, messages: Long) = {
    val m = Message()
    var i = messages
    while (i > 0) {
      //if (i % 1000 == 0) println(s"Told $i")
      if (i % 100000000 == 0) Thread.`yield`()
      address ! m
      i -= 1
    }
  }
  
  private class Counter(var i: Long)
  
  private def countingActor(messages: Long, e: Executor, latch: CountDownLatch): Address = {
    LA(_ => {
      var c = new Counter(messages)
      _: Any =>
        c.i -= 1
        //if (c.i % 1000 == 0) println(s"Actor ${System.identityHashCode(this)} value ${c.i} thread=${Thread.currentThread().getName()}")
        if (c.i == 0) {
          latch.countDown()
        }
        LA.Stay
    }, batch = 1000)(e)
  }
  
  private def benchmark() = {
    val threads = Runtime.getRuntime().availableProcessors()
    val actors = threads * 1;
    val executor = Executors.newWorkStealingPool(actors)
//    val executor = Executors.newFixedThreadPool(actors)
    val n: Long = 200000000
    
    val latch = new CountDownLatch(actors)
    var took = timed(() => {
      for (t <- 1 to actors) {
        new Thread() {
          override def run = {
            val a = countingActor(n, executor, latch)
            send(a, n)
          }
        }.start()
      }

      latch.await()
    })

    
    printf("%,d took %,d s, %,d ops/sec\n", actors * n, took / 1000, actors * n / took * 1000)
    executor.shutdown()
  }
  
  def main(args: Array[String]) = {
    println("bench started")
    for (i <- 1 to 5) {
      System.gc()
      benchmark()
    }
  }
  
  private def timed(r: () => Unit): Long = {
    val started = System.nanoTime()
    r()
    val took = System.nanoTime() - started
    took / 1000000l
  }
}