package org.bigbluebutton.core.apps.meetingstate

import org.bigbluebutton.common2.msgs._
import org.bigbluebutton.core.apps.groupchats.GroupChatApp
import org.bigbluebutton.core.apps.{ ChatModel, PermissionCheck, RightsManagementTrait }
import org.bigbluebutton.core.apps.layout.LayoutHdlrHelpers
import org.bigbluebutton.core.bus.MessageBus
import org.bigbluebutton.core.db.{ ChatMessageDAO, LayoutDAO, NotificationDAO, PresPresentationDAO }
import org.bigbluebutton.core.domain.MeetingState2x
import org.bigbluebutton.core.models._
import org.bigbluebutton.core.running.{ LiveMeeting, LogHelper }
import org.bigbluebutton.core.util.MarkdownUtil
import org.bigbluebutton.core2.message.senders.MsgBuilder

/**
 * Applies a saved room state sent by bbb-web (see MeetingStateMsgs).
 *
 * These messages only come from bbb-web, which has already checked that the
 * requester is a moderator; the role is checked again here when the restore
 * begins, and the later messages are only accepted for the restore in progress.
 */
case class RestoreBegun(state: MeetingState2x, accepted: Boolean, layoutChanged: Boolean)

trait RestoreMeetingStateHdlrs extends LogHelper with RightsManagementTrait {

  protected var pendingRestore: Option[PendingRestore]

  // What users wrote. The other types (poll results, download links, plugin
  // messages...) point at things of the saved meeting that no longer exist.
  private val RestorableChatMessageTypes = Set(GroupChatMessageType.DEFAULT, GroupChatMessageType.API)

  private val KnownLayouts: Set[String] = LayoutsType.layoutsType.values.toSet

  private def restoreInProgress(loadId: String, userId: String): Option[PendingRestore] =
    pendingRestore.filter(p => p.loadId == loadId && p.requesterId == userId && !p.isExpired(System.currentTimeMillis()))

  private def broadcastToMeeting(name: String, meetingId: String, userId: String, event: BbbCoreMsg, bus: MessageBus): Unit = {
    val routing = Routing.addMsgToClientRouting(MessageTypes.BROADCAST_TO_MEETING, meetingId, userId)
    bus.outGW.send(BbbCommonEnvCoreMsg(BbbCoreEnvelope(name, routing), event))
  }

  private def removeAllPresentations(state: MeetingState2x, liveMeeting: LiveMeeting, userId: String, bus: MessageBus): MeetingState2x = {
    val meetingId = liveMeeting.props.meetingProp.intId
    val presentationIds = state.presentationPodManager.getDefaultPod()
      .map(_.presentations.keys.toVector).getOrElse(Vector())

    presentationIds.foldLeft(state) { (currentState, presentationId) =>
      PresPresentationDAO.delete(meetingId, presentationId)
      liveMeeting.droppedPresentations.addRemoved(presentationId)

      val header = BbbClientMsgHeader(RemovePresentationEvtMsg.NAME, meetingId, userId)
      val body = RemovePresentationEvtMsgBody(PresentationPod.DEFAULT_PRESENTATION_POD, presentationId)
      broadcastToMeeting(RemovePresentationEvtMsg.NAME, meetingId, userId, RemovePresentationEvtMsg(header, body), bus)

      currentState.update(currentState.presentationPodManager.removePresentationInPod(PresentationPod.DEFAULT_PRESENTATION_POD, presentationId))
    }
  }

  // Returns true when the layout was changed and has to be announced.
  private def applyLayout(layout: RestoreLayoutVO, liveMeeting: LiveMeeting, userId: String): Boolean = {
    if (!KnownLayouts.contains(layout.currentLayout)) {
      false
    } else {
      val layouts = liveMeeting.layouts
      Layouts.setCurrentLayout(layouts, layout.currentLayout)
      Layouts.setPushLayout(layouts, layout.pushLayout)
      Layouts.setPresentationIsOpen(layouts, layout.presentationIsOpen)
      Layouts.setCameraDockIsResizing(layouts, false)
      Layouts.setCameraPosition(layouts, layout.cameraPosition)
      // The focused camera belongs to a stream of the saved meeting, which is gone.
      Layouts.setFocusedCamera(layouts, "none")
      Layouts.setPresentationVideoRate(layouts, LayoutHdlrHelpers.clampPresentationVideoRate(layout.presentationVideoRate))
      Layouts.setScreenshareAsContent(layouts, layout.screenshareAsContent)
      // Clients ignore a layout set by "system": attribute it to the moderator who loaded it.
      Layouts.setRequestedBy(layouts, userId)
      LayoutDAO.insertOrUpdate(liveMeeting.props.meetingProp.intId, layouts)
      true
    }
  }

  /**
   * Starts a restore: removes the presentations of the meeting and applies the
   * saved layout.
   */
  def handle(msg: RestoreMeetingStateBeginSysMsg, state: MeetingState2x, liveMeeting: LiveMeeting, bus: MessageBus): RestoreBegun = {
    val meetingId = liveMeeting.props.meetingProp.intId
    val userId = msg.header.userId

    if (liveMeeting.props.meetingProp.disabledFeatures.contains(MeetingStateApp.FEATURE_NAME)) {
      log.warning("Ignoring room state restore in meeting {}: the feature is disabled", meetingId)
      RestoreBegun(state, accepted = false, layoutChanged = false)
    } else if (permissionFailed(PermissionCheck.MOD_LEVEL, PermissionCheck.VIEWER_LEVEL, liveMeeting.users2x, userId)) {
      log.warning("Ignoring room state restore in meeting {}: user {} is not a moderator", meetingId, userId)
      RestoreBegun(state, accepted = false, layoutChanged = false)
    } else if (!MeetingStateApp.isValidJobId(msg.body.loadId)) {
      log.warning("Ignoring room state restore with an invalid id in meeting {}", meetingId)
      RestoreBegun(state, accepted = false, layoutChanged = false)
    } else {
      log.info("Restoring room state {} in meeting {} ({} presentations)", msg.body.loadId, meetingId, msg.body.presentations.size)

      pendingRestore = Some(PendingRestore(
        loadId = msg.body.loadId,
        requesterId = userId,
        startedAt = System.currentTimeMillis(),
        presentations = msg.body.presentations.map(p => p.presentationId -> p).toMap
      ))

      val newState = removeAllPresentations(state, liveMeeting, userId, bus)
      val layoutChanged = applyLayout(msg.body.layout, liveMeeting, userId)
      RestoreBegun(newState, accepted = true, layoutChanged = layoutChanged)
    }
  }

  /** Replaces the public chat with the saved messages, keeping their senders and times. */
  def handle(msg: RestoreGroupChatMessagesSysMsg, state: MeetingState2x, liveMeeting: LiveMeeting, bus: MessageBus): MeetingState2x = {
    val meetingId = liveMeeting.props.meetingProp.intId
    val userId = msg.header.userId
    val chatId = GroupChatApp.MAIN_PUBLIC_CHAT

    val newState = for {
      _ <- restoreInProgress(msg.body.loadId, userId)
      if !liveMeeting.props.meetingProp.disabledFeatures.contains("chat")
      gc <- state.groupChats.find(chatId)
    } yield {
      ChatModel.clearPublicChatHistory(liveMeeting.chatModel)
      ChatMessageDAO.deleteAllFromChat(meetingId, chatId)

      val clearHeader = BbbClientMsgHeader(ClearPublicChatHistoryEvtMsg.NAME, meetingId, userId)
      val clearEvent = ClearPublicChatHistoryEvtMsg(clearHeader, ClearPublicChatHistoryEvtMsgBody(chatId))
      broadcastToMeeting(ClearPublicChatHistoryEvtMsg.NAME, meetingId, userId, clearEvent, bus)

      // Message ids are unique across meetings in the database, and the saved
      // meeting may still be there: every message gets a new id.
      val saved = msg.body.messages.filter(m => RestorableChatMessageTypes.contains(m.messageType)).sortBy(_.timestamp)
      val newIds = saved.map(m => m.id -> GroupChatFactory.genId()).toMap

      // The database numbers the messages of a chat by creation time, so two
      // messages must not share one.
      var lastTimestamp = 0L
      val restored = saved.map { m =>
        val timestamp = math.max(m.timestamp, lastTimestamp + 1)
        lastTimestamp = timestamp
        GroupChatMessage(
          id = newIds(m.id),
          timestamp = timestamp,
          correlationId = "",
          createdOn = timestamp,
          updatedOn = timestamp,
          sender = GroupChatUser(m.senderId, m.senderName, m.senderRole),
          chatEmphasizedText = m.chatEmphasizedText,
          message = m.message,
          // Never trust saved HTML: render it again from the markdown source.
          messageAsHtml = MarkdownUtil.markdownToSafeHtml(m.message),
          replyToMessageId = newIds.getOrElse(m.replyToMessageId, ""),
          messageType = m.messageType,
          metadata = m.metadata
        )
      }

      restored.foreach(m => ChatMessageDAO.insertRestored(meetingId, chatId, m))
      log.info("Restored {} public chat messages in meeting {}", restored.size, meetingId)

      val restoredChat = restored.foldLeft(gc.clearMessages())((chat, m) => chat.add(m))
      state.update(state.groupChats.update(restoredChat))
    }

    newState.getOrElse(state)
  }

  /** Keeps the annotations of a presentation until its pages exist. */
  def handle(msg: RestoreWhiteboardAnnotationsSysMsg, state: MeetingState2x, liveMeeting: LiveMeeting, bus: MessageBus): Unit = {
    for {
      pending <- restoreInProgress(msg.body.loadId, msg.header.userId)
      if pending.presentations.contains(msg.body.presentationId)
      if msg.body.whiteboardId.startsWith(msg.body.presentationId + "/")
    } yield {
      if (pending.converted.contains(msg.body.presentationId)) {
        addAnnotations(msg.body.whiteboardId, msg.body.annotations, pending.requesterId, liveMeeting, bus)
      } else {
        val buffered = pending.annotations.getOrElse(msg.body.presentationId, Vector()) :+ (msg.body.whiteboardId -> msg.body.annotations)
        pendingRestore = Some(pending.copy(annotations = pending.annotations + (msg.body.presentationId -> buffered)))
      }
    }
  }

  /** Asks the shared notes server to replace the notes with the saved ones. */
  def handle(msg: RestoreSharedNotesSysMsg, liveMeeting: LiveMeeting, bus: MessageBus): Unit = {
    val meetingId = liveMeeting.props.meetingProp.intId

    for {
      _ <- restoreInProgress(msg.body.loadId, msg.header.userId)
      if !liveMeeting.props.meetingProp.disabledFeatures.contains("sharedNotes")
    } yield {
      val routing = collection.immutable.HashMap("sender" -> "bbb-apps-akka")
      val envelope = BbbCoreEnvelope(BNSharedNotesReplaceContentCmdMsg.NAME, routing)
      val header = BbbCoreHeaderWithMeetingId(BNSharedNotesReplaceContentCmdMsg.NAME, meetingId)
      val body = BNSharedNotesReplaceContentCmdMsgBody("notes", msg.header.userId, msg.body.contentJson)
      bus.outGW.send(BbbCommonEnvCoreMsg(envelope, BNSharedNotesReplaceContentCmdMsg(header, body)))
    }
  }

  /** bbb-web has sent everything: the restore ends once the presentations are converted. */
  def handle(msg: RestoreMeetingStateEndSysMsg, liveMeeting: LiveMeeting, bus: MessageBus): Unit = {
    restoreInProgress(msg.body.loadId, msg.header.userId).foreach { pending =>
      pendingRestore = Some(pending.copy(dispatched = true))
      finishIfComplete(liveMeeting, bus)
    }
  }

  /**
   * Called once a presentation finished converting: when it belongs to the
   * restore in progress, adds its annotations and shows the saved page.
   */
  def handlePresentationConverted(presentationId: String, state: MeetingState2x, liveMeeting: LiveMeeting, bus: MessageBus): MeetingState2x = {
    val now = System.currentTimeMillis()
    if (pendingRestore.exists(_.isExpired(now))) pendingRestore = None

    pendingRestore.filter(_.presentations.contains(presentationId)) match {
      case None => state
      case Some(pending) =>
        pending.annotations.getOrElse(presentationId, Vector()).foreach {
          case (whiteboardId, annotations) => addAnnotations(whiteboardId, annotations, pending.requesterId, liveMeeting, bus)
        }

        pendingRestore = Some(pending.copy(
          annotations = pending.annotations - presentationId,
          converted = pending.converted + presentationId
        ))

        val newState = showSavedPage(pending.presentations(presentationId), pending.requesterId, state, liveMeeting, bus)
        finishIfComplete(liveMeeting, bus)
        newState
    }
  }

  private def addAnnotations(whiteboardId: String, annotations: Array[AnnotationVO], userId: String, liveMeeting: LiveMeeting, bus: MessageBus): Unit = {
    val meetingId = liveMeeting.props.meetingProp.intId
    // Goes through the same checks as annotations drawn by a user (allowed
    // shape types, safe urls); the moderator who loaded the state owns them.
    val added = liveMeeting.wbModel.addAnnotations(whiteboardId, meetingId, userId, annotations, isPresenter = true, isModerator = true)

    if (added.nonEmpty) {
      val header = BbbClientMsgHeader(SendWhiteboardAnnotationsEvtMsg.NAME, meetingId, userId)
      val body = SendWhiteboardAnnotationsEvtMsgBody(whiteboardId, added)
      broadcastToMeeting(SendWhiteboardAnnotationsEvtMsg.NAME, meetingId, userId, SendWhiteboardAnnotationsEvtMsg(header, body), bus)
    }
  }

  private def showSavedPage(saved: RestorePresentationVO, userId: String, state: MeetingState2x, liveMeeting: LiveMeeting, bus: MessageBus): MeetingState2x = {
    val meetingId = liveMeeting.props.meetingProp.intId
    val pageId = s"${saved.presentationId}/${saved.currentPage}"

    val newState = for {
      pod <- state.presentationPodManager.getDefaultPod()
      pres <- pod.getPresentation(saved.presentationId)
      if saved.currentPage > 1 && pres.pages.contains(pageId)
      updatedPod <- pod.setCurrentPage(saved.presentationId, pageId)
    } yield {
      val header = BbbClientMsgHeader(SetCurrentPageEvtMsg.NAME, meetingId, userId)
      val body = SetCurrentPageEvtMsgBody(pod.id, saved.presentationId, pageId)
      broadcastToMeeting(SetCurrentPageEvtMsg.NAME, meetingId, userId, SetCurrentPageEvtMsg(header, body), bus)
      state.update(state.presentationPodManager.addPod(updatedPod))
    }

    newState.getOrElse(state)
  }

  private def finishIfComplete(liveMeeting: LiveMeeting, bus: MessageBus): Unit = {
    pendingRestore.filter(_.isComplete).foreach { pending =>
      log.info("Room state {} restored in meeting {}", pending.loadId, liveMeeting.props.meetingProp.intId)
      pendingRestore = None

      val notifyEvent = MsgBuilder.buildNotifyAllInMeetingEvtMsg(
        liveMeeting.props.meetingProp.intId,
        "success",
        "upload",
        "app.roomState.load.completed",
        "Notification shown to everyone when a saved room state has been loaded",
        Map()
      )
      bus.outGW.send(notifyEvent)
      NotificationDAO.insert(notifyEvent)
    }
  }
}
