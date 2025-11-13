package jobs

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import workers.Worker
import monitoring.Metrics
import jobs.Job.*

import scala.collection.mutable
import scala.concurrent.duration.*

object JobManager:

  sealed trait Command
  final case class SubmitJob(
      request: JobRequest,
      replyTo: ActorRef[JobSubmitted]
  ) extends Command
  final case class GetJob(id: JobId, replyTo: ActorRef[JobResponse]) extends Command
  final case class ListJobs(
      limit: Int,
      offset: Int,
      replyTo: ActorRef[JobListResponse]
  ) extends Command
  final case class StartJobExecution(id: JobId, worker: ActorRef[Worker.In]) extends Command
  final case class JobExecutionResult(id: JobId, result: Worker.ExecutionResult) extends Command
  private case object CleanupExpiredJobs extends Command

  sealed trait Response
  final case class JobSubmitted(id: JobId, status: Status) extends Response
  sealed trait JobResponse extends Response
  final case class JobFound(job: JobInfo) extends JobResponse
  final case class JobNotFound(id: JobId) extends JobResponse
  final case class JobListResponse(
      jobs: List[JobSummary],
      total: Int,
      limit: Int,
      offset: Int
  ) extends Response

  def apply(
      jobTTL: FiniteDuration = 1.hour,
      cleanupInterval: FiniteDuration = 5.minutes
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        timers.startTimerWithFixedDelay(CleanupExpiredJobs, cleanupInterval)
        active(mutable.Map.empty, jobTTL, timers)
      }
    }

  private def active(
      jobs: mutable.Map[JobId, JobInfo],
      jobTTL: FiniteDuration,
      timers: TimerScheduler[Command]
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match
        case SubmitJob(request, replyTo) =>
          val jobId = JobId.generate()
          val job = JobInfo(
            id = jobId,
            request = request,
            status = Status.Queued
          )
          jobs.update(jobId, job)

          ctx.log.info("Job {} submitted: language={}", jobId, request.language)

          // Update metrics
          Metrics.incrementQueuedJobs(request.language)

          replyTo ! JobSubmitted(jobId, Status.Queued)
          Behaviors.same

        case GetJob(id, replyTo) =>
          jobs.get(id) match
            case Some(job) =>
              replyTo ! JobFound(job)
            case None =>
              replyTo ! JobNotFound(id)
          Behaviors.same

        case ListJobs(limit, offset, replyTo) =>
          val allJobs = jobs.values.toList
            .sortBy(_.createdAt.toEpochMilli)(Ordering[Long].reverse)

          val page = allJobs.slice(offset, offset + limit)
          val summaries = page.map(JobSummary.from)

          replyTo ! JobListResponse(
            jobs = summaries,
            total = allJobs.size,
            limit = limit,
            offset = offset
          )
          Behaviors.same

        case StartJobExecution(id, worker) =>
          jobs.get(id).foreach { job =>
            val updatedJob = job.withStarted()
            jobs.update(id, updatedJob)

            ctx.log.info("Job {} started execution", id)

            // Update metrics
            Metrics.decrementQueuedJobs(job.request.language)

            // Send execution request to worker
            worker ! Worker.StartExecution(
              code = job.request.code,
              language = job.request.language,
              replyTo = ctx.messageAdapter[Worker.ExecutionResult](result =>
                JobExecutionResult(id, result)
              )
            )
          }
          Behaviors.same

        case JobExecutionResult(id, result) =>
          jobs.get(id).foreach { job =>
            val updatedJob = result match
              case Worker.ExecutionSucceeded(output) =>
                ctx.log.info("Job {} completed successfully", id)
                job.withSuccess(output)
              case Worker.ExecutionFailed(error) =>
                ctx.log.warn("Job {} failed: {}", id, error)
                job.withFailure(error)

            jobs.update(id, updatedJob)

            // Update metrics
            updatedJob.executionDurationMs.foreach { durationMs =>
              Metrics.recordExecutionTime(job.request.language, durationMs / 1000.0)
            }

            updatedJob.status match
              case Status.Completed =>
                Metrics.recordRequest(job.request.language, "success")
              case Status.Failed =>
                Metrics.recordRequest(job.request.language, "failure")
              case _ => // ignore
          }
          Behaviors.same

        case CleanupExpiredJobs =>
          val now = java.time.Instant.now()
          val cutoff = now.minusMillis(jobTTL.toMillis)

          val expiredJobs = jobs.filter { case (_, job) =>
            job.isTerminal && job.completedAt.exists(_.isBefore(cutoff))
          }.keys.toList

          expiredJobs.foreach { id =>
            jobs.remove(id)
            ctx.log.debug("Cleaned up expired job: {}", id)
          }

          if expiredJobs.nonEmpty then
            ctx.log.info("Cleaned up {} expired jobs", expiredJobs.size)

          // Update queue depth metrics
          val queuedByLang = jobs.values
            .filter(_.status == Status.Queued)
            .groupBy(_.request.language)
            .view.mapValues(_.size)
            .toMap

          queuedByLang.foreach { case (lang, count) =>
            Metrics.setQueueDepth(lang, count)
          }

          Behaviors.same
    }
