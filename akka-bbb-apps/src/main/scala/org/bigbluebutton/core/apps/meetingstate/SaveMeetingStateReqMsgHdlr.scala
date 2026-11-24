package org.bigbluebutton.core.apps.meetingstate

import org.bigbluebutton.common2.msgs._
import org.bigbluebutton.common2.util.JsonUtil
import org.bigbluebutton.core.apps.groupchats.GroupChatApp
import org.bigbluebutton.core.apps.{ PermissionCheck, RightsManagementTrait }
import org.bigbluebutton.core.bus.MessageBus
import org.bigbluebutton.core.domain.MeetingState2x
import org.bigbluebutton.core.models.{ GroupChatMessage, Layouts, PresentationInPod }
import org.bigbluebutton.core.running.{ LiveMeeting, LogHelper }

import java.io.File

trait SaveMeetingStateReqMsgHdlr extends LogHelper with RightsManagementTrait {

  // The same message id appears more than once after an edit (GroupChat.update
  // appends), so keep the last version of each message in its original position.
  private def collectChat(state: MeetingState2x): Vector[Map[String, Any]] = {
    val msgs: Vector[GroupChatMessage] = state.groupChats.find(GroupChatApp.MAIN_PUBLIC_CHAT).map(_.msgs).getOrElse(Vector())
    val latestById = msgs.map(m => m.id -> m).toMap
    val orderedIds = msgs.map(_.id).distinct

    orderedIds.flatMap(latestById.get).sortBy(_.timestamp).map { m =>
      Map(
        "id" -> m.id,
        "timestamp" -> m.timestamp,
        "senderId" -> m.sender.id,
        "senderName" -> m.sender.name,
        "senderRole" -> m.sender.role,
        "chatEmphasizedText" -> m.chatEmphasizedText,
        "message" -> m.message,
        "replyToMessageId" -> m.replyToMessageId,
        "messageType" -> m.messageType,
        "metadata" -> m.metadata
      )
    }
  }

  // A presentation still converting, or whose conversion failed, has nothing to
  // save. Its pages are added as they are converted, so it is complete once they
  // are all there (the uploadCompleted flag is not kept up to date in memory).
  private def isConverted(pres: PresentationInPod): Boolean =
    pres.errorMsgKey.isEmpty && pres.numPages > 0 && pres.pages.size == pres.numPages && pres.pages.values.forall(_.converted)

  private def collectPresentation(pres: PresentationInPod, index: Int, liveMeeting: LiveMeeting): Map[String, Any] = {
    val meetingId = liveMeeting.props.meetingProp.intId
    val presLocation = List("var", "bigbluebutton", meetingId, meetingId, pres.id).mkString(File.separator, File.separator, "")
    val pages = pres.pages.values.toVector.sortBy(_.num)

    Map(
      "index" -> index,
      "sourceId" -> pres.id,
      "name" -> pres.name,
      "presLocation" -> presLocation,
      "current" -> pres.current,
      "currentPage" -> pages.find(_.current).map(_.num).getOrElse(1),
      "pageCount" -> pages.size,
      "downloadable" -> pres.downloadable,
      "removable" -> pres.removable,
      "default" -> pres.default,
      "pages" -> pages.map { page =>
        Map(
          "num" -> page.num,
          "infiniteWhiteboard" -> page.infiniteWhiteboard,
          "annotations" -> liveMeeting.wbModel.getHistory(page.id).toVector
        )
      }
    )
  }

  private def collectLayout(liveMeeting: LiveMeeting): Map[String, Any] = {
    val layouts = liveMeeting.layouts
    Map(
      "currentLayout" -> Layouts.getCurrentLayout(layouts),
      "pushLayout" -> Layouts.getPushLayout(layouts),
      "presentationIsOpen" -> Layouts.getPresentationIsOpen(layouts),
      "cameraPosition" -> Layouts.getCameraPosition(layouts),
      "focusedCamera" -> Layouts.getFocusedCamera(layouts),
      "presentationVideoRate" -> Layouts.getPresentationVideoRate(layouts),
      "screenshareAsContent" -> Layouts.getScreenshareAsContent(layouts)
    )
  }

  def handle(msg: SaveMeetingStateReqMsg, state: MeetingState2x, liveMeeting: LiveMeeting, bus: MessageBus): Unit = {
    val meetingId = liveMeeting.props.meetingProp.intId
    val userId = msg.header.userId
    val jobId = msg.body.jobId

    if (liveMeeting.props.meetingProp.disabledFeatures.contains(MeetingStateApp.FEATURE_NAME)) {
      val reason = "Saving the meeting state is disabled for this meeting."
      PermissionCheck.ejectUserForFailedPermission(meetingId, userId, reason, bus.outGW, liveMeeting)
    } else if (permissionFailed(PermissionCheck.MOD_LEVEL, PermissionCheck.VIEWER_LEVEL, liveMeeting.users2x, userId)) {
      val reason = "No permission to save the meeting state."
      PermissionCheck.ejectUserForFailedPermission(meetingId, userId, reason, bus.outGW, liveMeeting)
    } else if (!MeetingStateApp.isValidJobId(jobId)) {
      log.error("Ignoring SaveMeetingStateReqMsg with invalid jobId in meeting {}", meetingId)
    } else {
      val presentations = state.presentationPodManager.getDefaultPod()
        .map(_.presentations.values.toVector).getOrElse(Vector())
        .filter(isConverted)
        .sortBy(p => (!p.current, p.name))
        .zipWithIndex
        .map { case (pres, idx) => collectPresentation(pres, idx + 1, liveMeeting) }

      val stateJson = JsonUtil.toJson(Map(
        "version" -> 1,
        "savedAt" -> System.currentTimeMillis(),
        "source" -> Map(
          "meetingName" -> liveMeeting.props.meetingProp.name,
          "extMeetingId" -> liveMeeting.props.meetingProp.extId
        ),
        "chat" -> collectChat(state),
        "presentations" -> presentations,
        "layout" -> Map(
          "server" -> collectLayout(liveMeeting),
          "client" -> msg.body.clientState
        )
      ))

      val jobJson = JsonUtil.toJson(Map(
        "jobId" -> jobId,
        "jobType" -> MeetingStateSaveJobTypes.SAVE,
        "meetingId" -> meetingId,
        "requesterId" -> userId,
        "download" -> msg.body.download
      ))

      log.info("Queueing meeting state save job {} for meeting {} ({} presentations, download={})", jobId, meetingId, presentations.size, msg.body.download)

      val routing = collection.immutable.HashMap("sender" -> "bbb-apps-akka")
      val envelope = BbbCoreEnvelope(StoreMeetingStateSaveJobInRedisSysMsg.NAME, routing)
      val header = BbbCoreHeaderWithMeetingId(StoreMeetingStateSaveJobInRedisSysMsg.NAME, meetingId)
      val body = StoreMeetingStateSaveJobInRedisSysMsgBody(jobId, jobJson, stateJson)
      bus.outGW.send(BbbCommonEnvCoreMsg(envelope, StoreMeetingStateSaveJobInRedisSysMsg(header, body)))
    }
  }
}
