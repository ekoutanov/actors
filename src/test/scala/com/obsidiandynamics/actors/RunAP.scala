package com.obsidiandynamics.actors

import java.util.concurrent.Executors
import com.github.gist.viktorklang.Actor

object RunAP {
  def main(args: Array[String]) = {
    
    implicit val executor = Executors.newWorkStealingPool(1)
    
    val a = Actor(_ => {
      m: Any => {
        println(m)
        Thread.sleep(10)
        Actor.Stay
      }
    }, batch = 0)
    
    for (i <- 1 to 10) {
      a ! i
    }
    
    Thread.sleep(500)
  }
}