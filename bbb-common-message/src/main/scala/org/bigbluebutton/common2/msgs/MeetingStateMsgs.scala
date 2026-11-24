package org.bigbluebutton.common2.msgs

/**
 * Messages for saving a meeting's state (chat, presentations, annotations,
 * layout) to an archive and restoring it into another meeting.
 */

object MeetingStateSaveJobTypes {
  val SAVE = "MeetingStateSaveJob"
}

// Sent by a moderator's client (through graphql-actions) to request a save.
// With download, the archive is built for the moderator to download and the
// state kept in the meeting is left as it is.
object SaveMeetingStateReqMsg { val NAME = "SaveMeetingStateReqMsg" }
case class SaveMeetingStateReqMsg(header: BbbClientMsgHeader, body: SaveMeetingStateReqMsgBody) extends StandardMsg
case class SaveMeetingStateReqMsgBody(jobId: String, clientState: Map[String, Any], download: Boolean = false)

// Sent by akka-apps to its own redis storage actor: stores the collected state
// and queues the job for bbb-export-annotations.
object StoreMeetingStateSaveJobInRedisSysMsg { val NAME = "StoreMeetingStateSaveJobInRedisSysMsg" }
case class StoreMeetingStateSaveJobInRedisSysMsg(header: BbbCoreHeaderWithMeetingId, body: StoreMeetingStateSaveJobInRedisSysMsgBody) extends BbbCoreMsg
case class StoreMeetingStateSaveJobInRedisSysMsgBody(jobId: String, jobJson: String, stateJson: String)

/**
 * Restoring a saved state. bbb-web validates the archive, gives every
 * presentation a new id (rewriting the annotations accordingly) and then sends,
 * in this order: Begin, the chat, the annotations, the notes, End. The
 * presentations themselves go through the usual conversion.
 * The header's userId is the moderator who asked for the restore.
 */
case class RestorePresentationVO(presentationId: String, current: Boolean, currentPage: Int)

case class RestoreLayoutVO(
    currentLayout:         String,
    pushLayout:            Boolean,
    presentationIsOpen:    Boolean,
    cameraPosition:        String,
    focusedCamera:         String,
    presentationVideoRate: Double,
    screenshareAsContent:  Boolean
)

case class RestoredChatMessageVO(
    id:                 String,
    timestamp:          Long,
    senderId:           String,
    senderName:         String,
    senderRole:         String,
    chatEmphasizedText: Boolean,
    message:            String,
    replyToMessageId:   String,
    messageType:        String,
    metadata:           Map[String, Any]
)

object RestoreMeetingStateBeginSysMsg { val NAME = "RestoreMeetingStateBeginSysMsg" }
case class RestoreMeetingStateBeginSysMsg(header: BbbClientMsgHeader, body: RestoreMeetingStateBeginSysMsgBody) extends StandardMsg
case class RestoreMeetingStateBeginSysMsgBody(
    loadId:        String,
    presentations: Vector[RestorePresentationVO],
    layout:        RestoreLayoutVO,
    notesPinned:   Boolean
)

object RestoreGroupChatMessagesSysMsg { val NAME = "RestoreGroupChatMessagesSysMsg" }
case class RestoreGroupChatMessagesSysMsg(header: BbbClientMsgHeader, body: RestoreGroupChatMessagesSysMsgBody) extends StandardMsg
case class RestoreGroupChatMessagesSysMsgBody(loadId: String, messages: Vector[RestoredChatMessageVO])

object RestoreWhiteboardAnnotationsSysMsg { val NAME = "RestoreWhiteboardAnnotationsSysMsg" }
case class RestoreWhiteboardAnnotationsSysMsg(header: BbbClientMsgHeader, body: RestoreWhiteboardAnnotationsSysMsgBody) extends StandardMsg
case class RestoreWhiteboardAnnotationsSysMsgBody(loadId: String, presentationId: String, whiteboardId: String, annotations: Array[AnnotationVO])

object RestoreSharedNotesSysMsg { val NAME = "RestoreSharedNotesSysMsg" }
case class RestoreSharedNotesSysMsg(header: BbbClientMsgHeader, body: RestoreSharedNotesSysMsgBody) extends StandardMsg
case class RestoreSharedNotesSysMsgBody(loadId: String, contentJson: Vector[AnyRef])

object RestoreMeetingStateEndSysMsg { val NAME = "RestoreMeetingStateEndSysMsg" }
case class RestoreMeetingStateEndSysMsg(header: BbbClientMsgHeader, body: RestoreMeetingStateEndSysMsgBody) extends StandardMsg
case class RestoreMeetingStateEndSysMsgBody(loadId: String)

// apps -> shared-notes-server: replace the whole content of the shared notes
object BNSharedNotesReplaceContentCmdMsg { val NAME = "BNSharedNotesReplaceContentCmdMsg" }
case class BNSharedNotesReplaceContentCmdMsg(header: BbbCoreHeaderWithMeetingId, body: BNSharedNotesReplaceContentCmdMsgBody) extends BbbCoreMsg
case class BNSharedNotesReplaceContentCmdMsgBody(externalId: String, userId: String, contentJson: Vector[AnyRef])
