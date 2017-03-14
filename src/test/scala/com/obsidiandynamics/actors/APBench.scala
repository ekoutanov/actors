package com.obsidiandynamics.actors

import com.github.plokhotnyuk.actors.Message
import com.github.gist.viktorklang.Actor
import com.github.gist.viktorklang.Actor._
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors

object APBench {
  private def send(address: Address, messages: Int) = {
    val m = Message()
    var i = messages
    while (i > 0) {
      address ! m
      i -= 1
    }
  }
  
  private def countingActor(messages: Int, e: Executor, latch: CountDownLatch): Address = {
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
    val executor = Executors.newWorkStealingPool(threads)
    val n = 200000000
    
    val latch = new CountDownLatch(threads)
    var took = timed(() => {
      for (t <- 1 to threads) {
        new Thread() {
          override def run = {
            val a = countingActor(n, executor, latch)
            send(a, n)
          }
        }.start()
      }

      try {
        latch.await()
      } catch {case e: Exception => {}}
    })

    executor.shutdown()
    
    printf("%,d took %,d s, %,d ops/sec\n", threads * n, took / 1000, threads * n / took * 1000)
  }
  
  private def timed(r: () => Unit): Long = {
    val started = System.nanoTime()
    r()
    val took = System.nanoTime() - started
    took / 1000000l
  }
}