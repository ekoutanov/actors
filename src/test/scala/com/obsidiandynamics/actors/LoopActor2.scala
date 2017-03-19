package com.obsidiandynamics.actors

/*
   Copyright 2012 Viktor Klang

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

// Initial version from Viktor Klang: https://gist.github.com/viktorklang/2362563
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent._
import scala.annotation.tailrec
import scala.concurrent.{forkjoin => scfj}
import scala.concurrent.{forkjoin => scfj}

object LoopActor2 {
  type Behavior = Any => Effect
  
  sealed trait Effect extends (Behavior => Behavior)
  
  case object Stay extends Effect { def apply(old: Behavior): Behavior = old }
  
  case class Become(like: Behavior) extends Effect { def apply(old: Behavior): Behavior = like }
  
  case object Die extends Effect { def apply(old: Behavior): Behavior = msg => sys.error("Dropping of message due to severe case of death: " + msg) }
  
  trait Address { def !(a: Any): Unit } // The notion of an Address to where you can post messages to
  
  def apply(initial: Address => Behavior, batch: Int = 5)(implicit e: Executor): Address = // Seeded by the self-reference that yields the initial behavior
    new AtomicReference[AnyRef]((self: Address) => Become(initial(self))) with Address { // Memory visibility of behavior is guarded by volatile piggybacking or provided by executor
      this ! this // Make the actor self aware by seeding its address to the initial behavior
  
      def !(a: Any): Unit = {
        val n = new Node(a)
        val h = getAndSet(n) 
        h match { 
          case h: Node => {
            h.lazySet(n)
          }
          case b => {
            async(b.asInstanceOf[Behavior], n, true) 
          }
        } 
      } // Enqueue the message onto the mailbox and schedule for execution if the actor was suspended
      
      private def async(b: Behavior, n: Node, x: Boolean): Unit = e match {
        case p: scfj.ForkJoinPool => p.execute(new scfj.ForkJoinTask[Unit] {
          def exec(): Boolean = { tryAct(b, n, x); false }
          def getRawResult: Unit = ()
          def setRawResult(unit: Unit): Unit = ()
        })
        case p: ForkJoinPool => p.execute(new ForkJoinTask[Unit] {
          def exec(): Boolean = { tryAct(b, n, x); false }
          def getRawResult: Unit = ()
          def setRawResult(unit: Unit): Unit = ()
        })
        case p => p.execute(new Runnable { override def run(): Unit = tryAct(b, n, x) })
      }
      
      // called from the FJ task
      private def tryAct(b: Behavior, n: Node, x: Boolean): Unit = {
        if (x) {
          act(b, n, batch) 
        } else if (/*(n ne get) || */!compareAndSet(n, b)) {
          actOrAsync(b, n, 0) // Act or suspend or stop
        }
      }
      
      private def actOrAsync(b: Behavior, n: Node, i: Int): Unit = { 
        var _b = b
        var _n = n
        var _i = i
        
        while (true) {
          val n1 = _n.get
          if (n1 ne null) {
            act(_b, n1, batch) 
            return
          } else if (_i != 9999) {
            _i += 1
            // continue actOrAsync(b, n, i + 1) 
          } else { 
            Thread.`yield`()
            async(_b, _n, false) 
            return
          } 
        }
      } // Spin for submit completion then act or suspend
      
      private def act(b: Behavior, n: Node, i: Int): Unit = { 
        var _b = b
        var _n = n
        var _i = i
        while (true) {
          val b1 = try {
            _b(_n.a)(_b) 
          } catch { 
            case t: Throwable => asyncAndRethrow(_b, _n, t) 
          }
          
          val n1 = _n.get
          if (n1 ne null) { 
            if (_i > 0) {
              _b = b1; _n = n1; _i = _i - 1
              // continue act(b1, n1, i - 1) 
            } else { 
              //_n.lazySet(null) // for GC (orig)
              async(b1, n1, true)
              return
            } 
          } else { // no more elements observed... wrap up
            async(b1, n, false) //<- original (other lines in this block added)
//            if (!compareAndSet(_n, b1)) {
//              //actOrAsync(b1, _n, 0) // Act or suspend or stop
//              async(b1, n, false) 
//            }
            return
          }
        }
      } // Reduce messages to behaviour in batch loop then suspend
      
      private def asyncAndRethrow(b: Behavior, n: Node, t: Throwable): Nothing = { 
        async(b, n, false)
        val ct = Thread.currentThread()
        if (t.isInstanceOf[InterruptedException]) 
          ct.interrupt()
        ct.getUncaughtExceptionHandler.uncaughtException(ct, t)
        throw t 
      }
    }
  
  private class Node(val a: Any) extends AtomicReference[Node]
}

//Usage example that creates an actor that will, after it's first message is received, Die
//import Actor._
//implicit val e: java.util.concurrent.Executor = java.util.concurrent.Executors.newCachedThreadPool
//val actor = Actor(self => msg => { println("self: " + self + " got msg " + msg); Die })
//actor ! "foo"
//actor ! "foo"