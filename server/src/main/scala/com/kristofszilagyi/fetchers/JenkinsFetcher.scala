package com.kristofszilagyi.fetchers

import javax.inject.Inject

import akka.typed._
import akka.typed.scaladsl.Actor
import com.kristofszilagyi.Wart
import com.kristofszilagyi.fetchers.JenkinsFetcher._
import com.kristofszilagyi.fetchers.JenkinsJson.{PartialDetailedBuildInfo, PartialJenkinsJobInfo}
import com.kristofszilagyi.utils.FutureUtils.RichFuture
import com.netaporter.uri.Uri
import com.netaporter.uri.dsl._
import play.api.libs.ws._
import com.kristofszilagyi.utils.TypeSafeEqualsOps._
import io.circe._
import io.circe.generic.JsonCodec
import io.circe.parser.decode

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import enumeratum._

import scala.collection.immutable


sealed abstract class BuildStatus(override val entryName: String) extends EnumEntry


object BuildStatus extends Enum[BuildStatus] with CirceEnum[BuildStatus] {
  val values: immutable.IndexedSeq[BuildStatus] = findValues

  case object Building extends BuildStatus("BUILDING")
  case object Failed extends BuildStatus("FAILED")
  case object Successful extends BuildStatus("SUCCESS")
  case object Aborted extends BuildStatus("ABORTED")
}

@SuppressWarnings(Array(Wart.Public))
object JenkinsJson { //this object is only here because @JsonCodec has the public wart :(
  @JsonCodec final case class PartialDetailedBuildInfo(result: BuildStatus)

  @JsonCodec final case class PartialBuildInfo(number: Int)
  @JsonCodec final case class PartialJenkinsJobInfo(builds: Seq[PartialBuildInfo])
}

final case class JenkinsJobUrl(url: Uri) {
  def buildInfo(buildNumber: BuildNumber): Uri = url / buildNumber.i.toString
}

final case class BuildNumber(i: Int)

object JenkinsFetcher {
  final case class FetchResult(r: Try[Either[ErrorAndRequest, Seq[Try[scala.Either[ErrorAndRequest, BuildStatus]]]]])
  sealed trait Incoming
  final case class Fetch(job: JenkinsJobUrl, replyTo: ActorRef[FetchResult]) extends Incoming
  private final case class FirstSuccessful(job: JenkinsJobUrl, jobNumbers: Seq[BuildNumber],
                                        replyTo: ActorRef[FetchResult]) extends Incoming

  private def restify(u: Uri) = u / "api/json" ? "pretty=true"

  @SuppressWarnings(Array(Wart.AsInstanceOf))
  private def safeRead[T : Decoder](body: WSResponse, destination: Uri): Either[ErrorAndRequest, T] = {
    if (body.status !=== 200) Left(ErrorAndRequest(destination, InvalidResponseCode(body)))
    else decode[T](body.body).left.map(err => ErrorAndRequest(destination, JsonError(err)))
  }
}

final case class ErrorAndRequest(request: Uri, responseError: ResponseError)

sealed trait ResponseError
final case class JsonError(invalid: io.circe.Error) extends ResponseError
final case class InvalidResponseCode(body: WSResponse) extends ResponseError

class JenkinsFetcher @Inject() (ws: WSClient)(implicit ec: ExecutionContext) {

  @SuppressWarnings(Array(Wart.Null, Wart.Public)) //I think these are false positive
  val behaviour: Actor.Immutable[Incoming] = Actor.immutable[Incoming] { (ctx, msg) =>
    msg match {
      case Fetch(job, replyTo) =>
        val destination = restify(job.url)
        val future = ws.url(destination).get.map(response => safeRead[PartialJenkinsJobInfo](response, destination))

        future onComplete {
          case Failure(ex) => replyTo ! FetchResult(Failure(ex))
          case Success(Left(error)) => replyTo ! FetchResult(Success(Left(error)))
          case Success(Right(jenkinsJobInfo)) => ctx.self ! FirstSuccessful(
            job,
            jenkinsJobInfo.builds.map(partialBuildInfo => BuildNumber(partialBuildInfo.number)),
            replyTo
          )
        }
        Actor.same
      case FirstSuccessful(job, buildNumbers, replyTo) =>
        val liftedFutures = buildNumbers.map{ buildNumber =>
          val destination = restify(job.buildInfo(buildNumber))
            ws.url(destination).get.map(result => safeRead[PartialDetailedBuildInfo](result, destination).map(_.result)).lift
          }
        Future.sequence(liftedFutures) foreach { buildStatuses => //this future can't fail because all the futures are lifted#
          replyTo ! FetchResult(Success(Right(buildStatuses)))
        }
        Actor.same
    }
  }
}

