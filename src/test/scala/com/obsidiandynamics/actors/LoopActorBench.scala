package com.obsidiandynamics.actors

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors

import com.obsidiandynamics.actors.LoopActor._
import com.github.plokhotnyuk.actors.Message



object LoopActorBench {
  private def send(address: Address, messages: Long) = {
    val m = Message()
    var i = messages
    while (i > 0) {
      address ! m
      i -= 1
    }
  }
  
  private class Counter(var i: Long)
  
  private def countingActor(messages: Long, e: Executor, latch: CountDownLatch): Address = {
    LoopActor(_ => {
      var c = new Counter(messages)
      _: Any =>
        c.i -= 1
        if (c.i == 0) {
          latch.countDown()
        }
        LoopActor.Stay
    }, batch = 1000)(e)
  }
  
  def main(args: Array[String]) = {
    println("bench started")
    
    val threads = Runtime.getRuntime().availableProcessors()
    val actors = threads * 1;
    val executor = Executors.newWorkStealingPool(actors)
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

    executor.shutdown()
    
    printf("%,d took %,d s, %,d ops/sec\n", actors * n, took / 1000, actors * n / took * 1000)
  }
  
  private def timed(r: () => Unit): Long = {
    val started = System.nanoTime()
    r()
    val took = System.nanoTime() - started
    took / 1000000l
  }
}