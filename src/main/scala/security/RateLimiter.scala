package security

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import monitoring.Metrics

import scala.concurrent.duration.*
import scala.collection.mutable

object RateLimiter:

  sealed trait Command
  final case class CheckLimit(apiKey: String, replyTo: ActorRef[Response]) extends Command
  private case object CleanupExpired extends Command

  sealed trait Response
  final case class Allowed(remainingRequests: Int) extends Response
  final case class RateLimited(retryAfterSeconds: Int) extends Response

  private case class RateLimitEntry(
      count: Int,
      windowStart: Long
  )

  def apply(
      maxRequestsPerWindow: Int = 100,
      windowDuration: FiniteDuration = 1.hour
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        timers.startTimerWithFixedDelay(CleanupExpired, 5.minutes)
        active(maxRequestsPerWindow, windowDuration, mutable.Map.empty, timers)
      }
    }

  private def active(
      maxRequestsPerWindow: Int,
      windowDuration: FiniteDuration,
      rateLimits: mutable.Map[String, RateLimitEntry],
      timers: TimerScheduler[Command]
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match
        case CheckLimit(apiKey, replyTo) =>
          val now = System.currentTimeMillis()
          val windowStartMs = now - windowDuration.toMillis

          // Get or create entry for this API key
          val entry = rateLimits.get(apiKey) match
            case Some(e) if e.windowStart >= windowStartMs =>
              // Within current window
              e
            case _ =>
              // New window or expired entry
              RateLimitEntry(0, now)

          if entry.count >= maxRequestsPerWindow then
            // Rate limited
            val retryAfter = ((entry.windowStart + windowDuration.toMillis - now) / 1000).toInt
            ctx.log.warn(
              "Rate limit exceeded for API key: {} ({})",
              apiKey.take(8) + "...",
              entry.count
            )
            Metrics.recordRateLimitHit(apiKey.take(8))
            replyTo ! RateLimited(retryAfter.max(1))
          else
            // Allowed - increment counter
            val newEntry = entry.copy(count = entry.count + 1)
            rateLimits.update(apiKey, newEntry)
            val remaining = maxRequestsPerWindow - newEntry.count
            replyTo ! Allowed(remaining)

          Behaviors.same

        case CleanupExpired =>
          val now = System.currentTimeMillis()
          val windowStartMs = now - windowDuration.toMillis
          val expiredKeys = rateLimits.filter(_._2.windowStart < windowStartMs).keys.toList
          expiredKeys.foreach(rateLimits.remove)
          if expiredKeys.nonEmpty then
            ctx.log.debug("Cleaned up {} expired rate limit entries", expiredKeys.size)
          Behaviors.same
    }
