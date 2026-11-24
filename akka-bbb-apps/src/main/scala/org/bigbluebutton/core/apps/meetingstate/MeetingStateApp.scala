package org.bigbluebutton.core.apps.meetingstate

import org.apache.pekko.actor.ActorContext
import org.bigbluebutton.common2.msgs.{ AnnotationVO, RestorePresentationVO }

object MeetingStateApp {
  val FEATURE_NAME = "roomState"

  // A restore whose presentations never finish converting is forgotten after this long.
  val RESTORE_TIMEOUT_MS: Long = 15 * 60 * 1000

  // Ids are generated outside of akka-apps and end up in file paths and redis keys.
  private val JobIdPattern = "^[A-Za-z0-9]{16,64}$".r

  def isValidJobId(jobId: String): Boolean = jobId != null && JobIdPattern.matches(jobId)
}

/**
 * A restore in progress: the presentations of the archive are still converting
 * and their annotations wait here until the pages exist.
 */
case class PendingRestore(
    loadId:        String,
    requesterId:   String,
    startedAt:     Long,
    presentations: Map[String, RestorePresentationVO],
    annotations:   Map[String, Vector[(String, Array[AnnotationVO])]] = Map.empty,
    converted:     Set[String]                                        = Set.empty,
    dispatched:    Boolean                                            = false
) {
  def isExpired(now: Long): Boolean = now - startedAt > MeetingStateApp.RESTORE_TIMEOUT_MS
  def isComplete: Boolean = dispatched && presentations.keySet.subsetOf(converted)
}

class MeetingStateApp(implicit val context: ActorContext)
  extends SaveMeetingStateReqMsgHdlr
  with RestoreMeetingStateHdlrs {

  // Only touched from the meeting actor's message loop.
  protected var pendingRestore: Option[PendingRestore] = None
}
