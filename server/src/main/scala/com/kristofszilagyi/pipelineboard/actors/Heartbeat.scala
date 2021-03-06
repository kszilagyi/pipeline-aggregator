package com.kristofszilagyi.pipelineboard.actors

import akka.typed.{ActorRef, Behavior}
import akka.typed.scaladsl.Actor
import com.kristofszilagyi.pipelineboard.FetcherResult
import com.kristofszilagyi.pipelineboard.fetchers.JenkinsFetcher.Fetch
import com.kristofszilagyi.pipelineboard.shared.Wart.discard
import slogging.LazyLogging

import scala.concurrent.duration.FiniteDuration

class Heartbeat(fetcher: ActorRef[Fetch], cache: ActorRef[OneFetchFinished], fetchFrequency: FiniteDuration) extends LazyLogging {

  val behaviour: Behavior[FetcherResult] = Actor.deferred[FetcherResult] { ctx =>
    fetcher ! Fetch(ctx.self)
    ongoingBehaviour
  }

  private def ongoingBehaviour: Actor.Immutable[FetcherResult] =
    Actor.immutable[FetcherResult] { case (ctx, msg) =>
      cache ! OneFetchFinished(msg.desc, msg.builds)
      discard(ctx.schedule(fetchFrequency, fetcher, Fetch(ctx.self)))
      Actor.same
    }
}
