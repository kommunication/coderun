package cluster

import workers.Worker
import security.{Authentication, InputValidator, RateLimiter}
import monitoring.Metrics
import jobs.{Job, JobManager, JobJsonSupport}
import org.apache.pekko
import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.typed.Cluster
import pekko.actor.typed.{ActorSystem, Behavior}
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.server.Directives.*
import pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.util.Timeout
import pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
import pekko.actor.typed.scaladsl.AskPattern.Askable
import pekko.actor.typed.*
import pekko.actor.typed.scaladsl.*
import workers.Worker.*

import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.concurrent.duration.*
import scala.util.*

object ClusterSystem:

  def apply(): Behavior[Nothing] = Behaviors.setup[Nothing]: ctx =>

    import ctx.executionContext

    val cluster = Cluster(ctx.system)
    val node = cluster.selfMember
    val cfg = ctx.system.settings.config

    if node hasRole "worker" then
      val numberOfWorkers = Try(cfg.getInt("transformation.workers-per-node")).getOrElse(50)
      // actor that sends StartExecution message to local Worker actors in a round robin fashion
      val workerRouter = ctx.spawn(
        behavior = Routers
          .pool(numberOfWorkers) {
            Behaviors
              .supervise(Worker().narrow[StartExecution])
              .onFailure(SupervisorStrategy.restart)
          }
          .withRoundRobinRouting(),
        name = "worker-router"
      )
      // actors are registered to the ActorSystem receptionist using a special ServiceKey.
      // All remote worker-routers will be registered to ClusterBootstrap actor system receptionist.
      // When the "worker" node starts it registers the local worker-router to the Receptionist which is cluster-wide
      // As a result "master" node can have access to remote worker-router and receive any updates about workers through worker-router
      ctx.system.receptionist ! Receptionist.Register(Worker.WorkerRouterKey, workerRouter)

    if node hasRole "master" then
      given system: ActorSystem[Nothing] = ctx.system
      given ec: ExecutionContextExecutor = ctx.executionContext
      given timeout: Timeout = Timeout(3.seconds)

      val numberOfLoadBalancers = Try(cfg.getInt("transformation.load-balancer")).getOrElse(3)
      val numberOfWorkers = Try(cfg.getInt("transformation.workers-per-node")).getOrElse(32)

      // Initialize metrics with worker pool size
      Metrics.setWorkerPoolSize(numberOfWorkers * numberOfLoadBalancers)

      // pool of load balancers that forward StartExecution message to the remote worker-router actors in a round robin fashion
      val loadBalancers = (1 to numberOfLoadBalancers).map: n =>
        ctx.spawn(
          behavior =
            Routers
              .group(Worker.WorkerRouterKey)
              .withRoundRobinRouting(), // routes StartExecution message to the remote worker-router
          name = s"load-balancer-$n"
        )

      // Spawn rate limiter actor
      val maxRequestsPerHour = Try(cfg.getInt("security.rate-limit.max-requests")).getOrElse(100)
      val rateLimiter = ctx.spawn(
        RateLimiter(maxRequestsPerHour, 1.hour),
        "rate-limiter"
      )

      // Spawn job manager actor for async job execution
      val jobTTL = Try(cfg.getDuration("jobs.ttl").toMillis.milliseconds).getOrElse(1.hour)
      val jobManager = ctx.spawn(
        JobManager(jobTTL),
        "job-manager"
      )

      // Background job processor - polls for queued jobs and assigns them to workers
      ctx.system.scheduler.scheduleAtFixedRate(
        initialDelay = 1.second,
        interval = 100.milliseconds
      )(() => {
        // This is a simplified job processor - in production, use a more sophisticated queue
        // For now, jobs are processed via direct worker assignment when submitted
      })

      val route =
        concat(
          // Health check endpoint (no auth required)
          path("health"):
            get:
              complete(StatusCodes.OK -> "healthy")
          ,
          // Readiness check endpoint (no auth required)
          path("ready"):
            get:
              val clusterStatus = if cluster.state.members.nonEmpty then "ready" else "not ready"
              complete(StatusCodes.OK -> s"$clusterStatus (${cluster.state.members.size} members)")
          ,
          // Metrics endpoint (no auth required for monitoring systems)
          path("metrics"):
            get:
              complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, Metrics.getMetrics))
          ,
          // Async job submission endpoint
          path("jobs"):
            post:
              Authentication.authenticated: apiKey =>
                entity(as[String]): requestBody =>
                  // Parse simple JSON: {"code": "...", "language": "..."}
                  val codePattern = "\"code\"\\s*:\\s*\"([^\"]+)\"".r
                  val langPattern = "\"language\"\\s*:\\s*\"([^\"]+)\"".r

                  val code = codePattern.findFirstMatchIn(requestBody).map(_.group(1))
                  val lang = langPattern.findFirstMatchIn(requestBody).map(_.group(1))

                  (code, lang) match
                    case (Some(c), Some(l)) =>
                      // Validate input
                      InputValidator.validateRequest(c, l) match
                        case InputValidator.Valid =>
                          // Check rate limit
                          val rateLimitCheck = rateLimiter.ask[RateLimiter.Response](
                            RateLimiter.CheckLimit(apiKey, _)
                          )

                          onSuccess(rateLimitCheck):
                            case RateLimiter.Allowed(remaining) =>
                              // Submit job
                              val jobRequest = Job.JobRequest(c, l, apiKey)
                              val submitFuture = jobManager.ask[JobManager.JobSubmitted](
                                JobManager.SubmitJob(jobRequest, _)
                              )

                              onSuccess(submitFuture): submitted =>
                                // Immediately assign job to worker
                                val loadBalancer = Random.shuffle(loadBalancers).head
                                jobManager ! JobManager.StartJobExecution(submitted.id, loadBalancer)

                                val responseJson = JobJsonSupport.jobSubmittedToJson(submitted.id, submitted.status)
                                respondWithHeader(RawHeader("X-RateLimit-Remaining", remaining.toString)):
                                  complete(HttpEntity(ContentTypes.`application/json`, responseJson))

                            case RateLimiter.RateLimited(retryAfter) =>
                              respondWithHeaders(
                                RawHeader("X-RateLimit-Retry-After", retryAfter.toString),
                                RawHeader("Retry-After", retryAfter.toString)
                              ):
                                complete(StatusCodes.TooManyRequests -> s"Rate limit exceeded. Retry after $retryAfter seconds.")

                        case InputValidator.Invalid(reason) =>
                          complete(StatusCodes.BadRequest -> reason)

                    case _ =>
                      complete(StatusCodes.BadRequest -> "Invalid request body. Expected JSON with 'code' and 'language' fields.")
          ,
          // Get job status by ID
          pathPrefix("jobs" / Segment): jobIdStr =>
            get:
              Authentication.authenticated: _ =>
                val jobId = Job.JobId(jobIdStr)
                val jobFuture = jobManager.ask[JobManager.JobResponse](
                  JobManager.GetJob(jobId, _)
                )

                onSuccess(jobFuture):
                  case JobManager.JobFound(job) =>
                    val responseJson = JobJsonSupport.jobInfoToJson(job)
                    complete(HttpEntity(ContentTypes.`application/json`, responseJson))
                  case JobManager.JobNotFound(id) =>
                    val responseJson = JobJsonSupport.jobNotFoundToJson(id)
                    complete(StatusCodes.NotFound, HttpEntity(ContentTypes.`application/json`, responseJson))
          ,
          // List all jobs (with pagination)
          path("jobs"):
            get:
              Authentication.authenticated: _ =>
                parameters("limit".as[Int].?(20), "offset".as[Int].?(0)): (limit, offset) =>
                  val listFuture = jobManager.ask[JobManager.JobListResponse](
                    JobManager.ListJobs(limit, offset, _)
                  )

                  onSuccess(listFuture): response =>
                    val responseJson = JobJsonSupport.jobListToJson(
                      response.jobs,
                      response.total,
                      response.limit,
                      response.offset
                    )
                    complete(HttpEntity(ContentTypes.`application/json`, responseJson))
          ,
          // Code execution endpoint (requires auth and rate limiting) - SYNCHRONOUS for backwards compatibility
          pathPrefix("lang" / Segment): lang =>
            post:
              Authentication.authenticated: apiKey =>
                entity(as[String]): code =>
                  // Input validation
                  InputValidator.validateRequest(code, lang) match
                    case InputValidator.Valid =>
                      // Check rate limit
                      val rateLimitCheck = rateLimiter.ask[RateLimiter.Response](
                        RateLimiter.CheckLimit(apiKey, _)
                      )

                      onSuccess(rateLimitCheck):
                        case RateLimiter.Allowed(remaining) =>
                          val startTime = System.nanoTime()
                          Metrics.incrementActiveExecutions(lang)

                          val loadBalancer = Random.shuffle(loadBalancers).head
                          val asyncResponse = loadBalancer
                            .ask[ExecutionResult](StartExecution(code, lang, _))
                            .map: result =>
                              val durationSeconds = (System.nanoTime() - startTime) / 1e9
                              Metrics.decrementActiveExecutions(lang)
                              Metrics.recordExecutionTime(lang, durationSeconds)

                              result match
                                case _: ExecutionSucceeded =>
                                  Metrics.recordRequest(lang, "success")
                                case _: ExecutionFailed =>
                                  Metrics.recordRequest(lang, "failure")

                              result.value
                            .recover: _ =>
                              val durationSeconds = (System.nanoTime() - startTime) / 1e9
                              Metrics.decrementActiveExecutions(lang)
                              Metrics.recordExecutionTime(lang, durationSeconds)
                              Metrics.recordRequest(lang, "error")
                              "something went wrong"

                          respondWithHeader(RawHeader("X-RateLimit-Remaining", remaining.toString)):
                            complete(asyncResponse)

                        case RateLimiter.RateLimited(retryAfter) =>
                          respondWithHeaders(
                            RawHeader("X-RateLimit-Retry-After", retryAfter.toString),
                            RawHeader("Retry-After", retryAfter.toString)
                          ):
                            complete(StatusCodes.TooManyRequests -> s"Rate limit exceeded. Retry after $retryAfter seconds.")

                    case InputValidator.Invalid(reason) =>
                      complete(StatusCodes.BadRequest -> reason)
        )

      val host = Try(cfg.getString("http.host")).getOrElse("0.0.0.0")
      val port = Try(cfg.getInt("http.port")).getOrElse(8080)

      Http()
        .newServerAt(host, port)
        .bind(route)

      ctx.log.info("Server is listening on {}:{}", host, port)
      ctx.log.info("Metrics available at http://{}:{}/metrics", host, port)
      ctx.log.info("Health check at http://{}:{}/health", host, port)
      ctx.log.info("Async job submission at POST http://{}:{}/jobs", host, port)
      ctx.log.info("Job status retrieval at GET http://{}:{}/jobs/<job-id>", host, port)
      ctx.log.info("Synchronous execution at POST http://{}:{}/lang/<language>", host, port)

    Behaviors.empty[Nothing]
