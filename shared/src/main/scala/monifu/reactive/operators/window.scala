/*
 * Copyright (c) 2014-2015 by its authors. Some rights reserved.
 * See the project homepage at: http://www.monifu.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monifu.reactive.operators

import monifu.reactive.Ack.{Continue, Cancel}
import monifu.reactive.internals._
import monifu.reactive.subjects.ReplaySubject
import monifu.reactive.{Ack, Observable, Observer}
import scala.collection.mutable
import scala.concurrent.Future


object window {
  /** Implementation for [[Observable.window]] */
  def sized[T](source: Observable[T], count: Int, skip: Int): Observable[Observable[T]] = {
    require(count > 0, "count must be strictly positive")
    require(skip > 0, "skip must be strictly positive")

    if (count == skip)
      sizedFixed(source, count)
    else if (skip < count)
      sizedOverlap(source, count, skip)
    else // skip > count
      sizedDrop(source, count, skip)
  }

  private def sizedFixed[T](source: Observable[T], count: Int) = {
    require(count > 0, "count must be strictly positive")

    Observable.create[Observable[T]] { subscriber =>
      implicit val s = subscriber.scheduler
      val observer = subscriber.observer

      source.unsafeSubscribe(new Observer[T] {
        private[this] var isDone = false
        private[this] var buffer = ReplaySubject[T]()
        private[this] var ack = observer.onNext(buffer)
        private[this] var leftToPush = count

        def onNext(elem: T): Future[Ack] =
          if (isDone) Cancel
          else {
            if (leftToPush > 0) {
              leftToPush -= 1
              buffer.onNext(elem)
            }
            else {
              buffer.onComplete()
              buffer = ReplaySubject(elem)
              leftToPush = count - 1

              val previousAck = ack
              ack = ack.onContinueStreamOnNext(observer, buffer)
              previousAck
            }
          }

        def onError(ex: Throwable): Unit = {
          if (!isDone) {
            isDone = true
            buffer.onComplete()
            ack.onContinueSignalError(observer, ex)
            buffer = null
          }
        }

        def onComplete(): Unit = {
          if (!isDone) {
            isDone = true
            buffer.onComplete()
            ack.onContinueSignalComplete(observer)
            buffer = null
          }
        }
      })
    }
  }

  def sizedOverlap[T](source: Observable[T], count: Int, skip: Int): Observable[Observable[T]] = {
    require(count > 0, "count must be strictly positive")
    require(skip > 0, "skip must be strictly positive")
    assert(skip < count, "skip < count")

    Observable.create { subscriber =>
      implicit val s = subscriber.scheduler
      val observer = subscriber.observer

      source.unsafeSubscribe(new Observer[T] {
        private[this] var isDone = false
        private[this] var buffer = ReplaySubject[T]()
        private[this] var ack = observer.onNext(buffer)
        private[this] var leftToPush = count

        private[this] val overlap = count - skip
        private[this] val queue = mutable.ArrayBuffer.empty[T]

        def onNext(elem: T): Future[Ack] =
          if (isDone) Cancel else {
            if (leftToPush > 0) {
              if (leftToPush <= overlap) queue += elem
              leftToPush -= 1
              buffer.onNext(elem)
            }
            else {
              buffer.onComplete()
              queue += elem
              buffer = ReplaySubject(queue:_*)
              queue.clear()
              leftToPush = count - (overlap + 1)
              if (leftToPush <= overlap) queue += elem

              val previousAck = ack
              ack = ack.onContinueStreamOnNext(observer, buffer)
              previousAck
            }
          }

        def onError(ex: Throwable): Unit = {
          if (!isDone) {
            isDone = true
            buffer.onComplete()
            ack.onContinueSignalError(observer, ex)
            buffer = null
          }
        }

        def onComplete(): Unit = {
          if (!isDone) {
            isDone = true
            buffer.onComplete()
            ack.onContinueSignalComplete(observer)
            buffer = null
          }
        }
      })
    }
  }

  private def sizedDrop[T](source: Observable[T], count: Int, skip: Int): Observable[Observable[T]] = {
    require(count > 0, "count must be strictly positive")
    require(skip > 0, "skip must be strictly positive")
    assert(skip > count, "skip > drop")

    Observable.create { subscriber =>
      implicit val s = subscriber.scheduler
      val observer = subscriber.observer

      source.unsafeSubscribe(new Observer[T] {
        private[this] var isDone = false
        private[this] var buffer = ReplaySubject[T]()
        private[this] var ack = observer.onNext(buffer)
        private[this] var leftToPush = count
        private[this] var leftToDrop = 0

        def onNext(elem: T): Future[Ack] =
          if (isDone) Cancel else {
            if (leftToDrop > 0) {
              leftToDrop -= 1
              Continue
            }
            else if (leftToPush > 0) {
              leftToPush -= 1
              buffer.onNext(elem)
            }
            else {
              buffer.onComplete()
              leftToDrop = skip - count - 1
              leftToPush = count
              buffer = ReplaySubject()

              val previousAck = ack
              ack = ack.onContinueStreamOnNext(observer, buffer)
              previousAck
            }
          }

        def onError(ex: Throwable): Unit = {
          if (!isDone) {
            isDone = true
            buffer.onComplete()
            ack.onContinueSignalError(observer, ex)
            buffer = null
          }
        }

        def onComplete(): Unit = {
          if (!isDone) {
            isDone = true
            buffer.onComplete()
            ack.onContinueSignalComplete(observer)
            buffer = null
          }
        }
      })
    }
  }
}
