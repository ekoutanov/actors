package com.obsidiandynamics.actors

import com.github.plokhotnyuk.actors.Message
import com.github.gist.viktorklang.Actor
import com.github.gist.viktorklang.Actor._
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors

object APBench {
  private def send(address: Address, messages: Long) = {
    val m = Message()
    var i = messages
    while (i > 0) {
      address ! m
      i -= 1
    }
  }
  
  private def countingActor(messages: Long, e: Executor, latch: CountDownLatch): Address = {
    Actor(_ => {
      var i = messages
      _: Any =>
        i -= 1
        if (i == 0) {
          latch.countDown()
        }
        Stay
    }, batch = 1024)(e)
  }
  
  def main(args: Array[String]) = {
    println("bench started")
    
    val threads = Runtime.getRuntime().availableProcessors()
    val actors = threads * 1;
    val executor = Executors.newWorkStealingPool(actors)
    val n: Long = 400000000
    
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