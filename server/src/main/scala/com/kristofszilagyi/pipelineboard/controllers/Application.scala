package com.kristofszilagyi.pipelineboard.controllers

import java.io.File
import javax.inject._

import com.kristofszilagyi.pipelineboard.actors.ResultCache
import com.kristofszilagyi.pipelineboard.db.BuildsDb
import com.kristofszilagyi.pipelineboard.fetchers._
import com.kristofszilagyi.pipelineboard.shared.CssSettings.settings._
import com.kristofszilagyi.pipelineboard.shared.JobType.{GitLabCi, Jenkins, TeamCity}
import com.kristofszilagyi.pipelineboard.shared.TypeSafeEqualsOps._
import com.kristofszilagyi.pipelineboard.shared.Wart.discard
import com.kristofszilagyi.pipelineboard.shared._
import com.kristofszilagyi.pipelineboard.utils.GitLabUrls
import com.netaporter.uri.{EmptyQueryString, PathPart}
import net.jcazevedo.moultingyaml.PimpedString
import play.api.Configuration
import play.api.libs.ws.WSClient
import play.api.mvc._
import slogging.{LazyLogging, LoggerConfig, PrintLoggerFactory}

import scala.collection.immutable.ListMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}
import scala.io.Source

object Application {
  val utf8 = "utf-8"
}

final class Application @Inject() (wsClient: WSClient)(val config: Configuration)
                                             (implicit ec: ExecutionContext) extends InjectedController with LazyLogging{

  LoggerConfig.factory = PrintLoggerFactory()

  @SuppressWarnings(Array(Wart.Throw))
  private val jobConfig = {
    //todo fix for other OS
    val home = System.getenv("HOME")
    val primaryConfigPath = new File("config")
    val secondaryConfigPath = new File(s"$home/.pipeline_board/config")
    val configPath = if (primaryConfigPath.isFile) primaryConfigPath
    else if (secondaryConfigPath.exists()) secondaryConfigPath
    else throw new RuntimeException(s"Neither $primaryConfigPath (in working dir) nor $secondaryConfigPath exists, aborting.")
    logger.info(s"Using config: $configPath")
    val config = Config.format.read(Source.fromFile(configPath).mkString.parseYaml)
    logger.info(s"Config is: $config")
    config
  }

  @SuppressWarnings(Array(Wart.Throw))
  private val autowireServer = {
    val groupedJobs = {
      val bindings = jobConfig.groups.map { group =>
        val jenkinsJobs = group.jenkins.map(_.jobs).getOrElse(Seq.empty).map { jobConfig =>
          val creds = (jobConfig.user, jobConfig.accessToken) match {
            case (Some(user), Some(token)) => Some(JenkinsCredentials(user, token))
            case (None, None) => None
            case other => throw new AssertionError(s"Either both access token and user has to be defined or neither: $other")
          }
          JenkinsJob(
            Job(jobConfig.name, Urls(userRoot = jobConfig.url, restRoot = RestRoot(jobConfig.url.u / "api/json")), Jenkins),
            creds
          )
        }

        val gitLabJobs = group.gitLabCi.map(_.jobs).getOrElse(Seq.empty).map { jobConfig =>
          GitLabCiJob(
            Job(jobConfig.name, Urls(jobConfig.url, GitLabUrls.restRoot(jobConfig.url)), GitLabCi),
            jobConfig.accessToken, jobConfig.jobNameOnGitLab
          )
        }

        val teamCityJobs = group.teamCity.map(_.jobs).getOrElse(Seq.empty).map { jobConfig =>
          val userRoot = jobConfig.url
          val jobId = userRoot.u.u.query.param("buildTypeId").getOrElse(throw new RuntimeException("Url for TeamCity has to have a buildTypeId parameter"))
          val restRoot = userRoot.u.u.copy(pathParts = Seq("guestAuth", "app", "rest", "buildTypes", jobId).map(PathPart.apply),
            query = EmptyQueryString)
          TeamCityJob(
            Job(jobConfig.name, Urls(userRoot = userRoot, restRoot = RestRoot(RawUrl(restRoot))), TeamCity)
          )
        }
        (group.groupName, (jenkinsJobs, gitLabJobs, teamCityJobs))
      }
      ListMap(bindings: _*)
    }

    def commonJobs(jenkinsJobs: Seq[JenkinsJob], gitlabJobs: Seq[GitLabCiJob], teamCityJobs: Seq[TeamCityJob]): Seq[Job] =
      jenkinsJobs.map(_.common) ++ gitlabJobs.map(_.common) ++ teamCityJobs.map(_.common)

    val jenkinsJobs = groupedJobs.toList.flatMap(_._2._1)
    val gitLabJobs = groupedJobs.toList.flatMap(_._2._2)
    val teamCityJobs = groupedJobs.toList.flatMap(_._2._3)
    val fetchers =
      jenkinsJobs.map(new JenkinsFetcher(wsClient, _)) ++
        gitLabJobs.map(new GitLabCiFetcher(wsClient, _, jobConfig.gitlabNumberOfBuildPagesToQuery.getOrElse(10))) ++
        teamCityJobs.map(new TeamCityFetcher(wsClient, _))

    def assertUniqueness(): Unit = {
      val jobs = commonJobs(jenkinsJobs, gitLabJobs, teamCityJobs)
      assert(jobs.size ==== jobs.toSet.size, "Jobs are not unique")
      val names = jobs.map(_.name)
      assert(names.size ==== names.toSet.size, "Jobs names are not unique")
    }
    assertUniqueness()

    // basically mapValues, but can't be because surprisingly that doesn't preserve ListMap which we need for ordering
    val jobsForCache = groupedJobs.map { case (name, (jenkins, gitlab, teamCity)) =>
      name -> commonJobs(jenkins, gitlab, teamCity)
    }

    import slick.jdbc.SQLiteProfile.api._
    val db = Database.forURL("jdbc:sqlite:./db.sqlite")
    val createTable =
      sqlu"""CREATE TABLE IF NOT EXISTS builds(
               name VARCHAR NOT NULL,
               buildNumber INTEGER NOT NULL,
               buildStatus VARCHAR NOT NULL,
               buildStart INTEGER NOT NULL,
               maybeBuildFinish INTEGER,
               PRIMARY KEY(name, buildNumber)
             )
        """
    val databaseTimeout = 10.seconds
    logger.info("Making sure database table exists")
    discard(Await.result(db.run(createTable), databaseTimeout))
    logger.info("Querying database table")
    val dataInDb = Await.result(db.run(BuildsDb.buildsQuery.result), databaseTimeout)
    logger.info("Finished startup database calls")
    val resultCache = new ResultCache(db, jobsForCache, jobConfig.fetchFrequency.getOrElse(Minutes(1)).toDuration, fetchers, dataInDb)
    new AutowireServer(new AutowireApiImpl(resultCache))
  }

  def root: Action[AnyContent] = Action {
    Ok(views.html.index(jobConfig.title)(config))
  }

  def css: Action[AnyContent] = Action {
    Ok(MyStyles.render).as(CSS)
  }

  def autowireApi(path: String): Action[AnyContent] = Action.async { implicit request =>
    // call Autowire route
    autowireServer.routes(
      autowire.Core.Request(path.split("/"), request.queryString.mapValues(_.mkString))
    ).map(Ok(_))
  }

}
