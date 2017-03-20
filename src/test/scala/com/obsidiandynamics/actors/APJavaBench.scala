package com.obsidiandynamics.actors

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool

import com.github.plokhotnyuk.actors.Message
import com.obsidiandynamics.indigo.benchmark
import com.obsidiandynamics.indigo.benchmark.APActor._
import com.obsidiandynamics.indigo.benchmark.APActor
import com.obsidiandynamics.indigo.benchmark.APActor.Func


object APJavaBench {
  private def send(address: Address, messages: Long) = {
    val m = Message()
    var i = messages
    while (i > 0) {
      address.tell(m)
      i -= 1
    }
  }
  
  private class Counter(var i: Long)
  
  private def countingActor(messages: Long, e: ForkJoinPool, latch: CountDownLatch): Address = {
    APActor.create(new Object() with Func[Address, Behavior] {
      override def apply(addr: Address): Behavior = {
        var c = new Counter(messages)
        new Object() with Behavior {
          override def apply(m: Object): Effect = {
            c.i -= 1
            if (c.i == 0) {
              latch.countDown()
            }
            APActor.stay
          }
        }
      }
    }, e, 1000);
  }
  
  private def benchmark() = {
    val threads = Runtime.getRuntime().availableProcessors()
    val actors = threads * 1;
    val executor = Executors.newWorkStealingPool(actors).asInstanceOf[ForkJoinPool]
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