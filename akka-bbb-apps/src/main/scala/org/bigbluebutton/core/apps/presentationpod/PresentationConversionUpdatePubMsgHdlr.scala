package org.bigbluebutton.core.apps.presentationpod

import org.bigbluebutton.common2.msgs._
import org.bigbluebutton.core.bus.MessageBus
import org.bigbluebutton.core.db.PresPresentationDAO
import org.bigbluebutton.core.domain.MeetingState2x
import org.bigbluebutton.core.models.PresentationInPod
import org.bigbluebutton.core.running.LiveMeeting

trait PresentationConversionUpdatePubMsgHdlr {
  this: PresentationPodHdlrs =>

  def handle(msg: PresentationConversionUpdateSysPubMsg, state: MeetingState2x,
             liveMeeting: LiveMeeting, bus: MessageBus): MeetingState2x = {

    def broadcastEvent(msg: PresentationConversionUpdateSysPubMsg): Unit = {
      val routing = Routing.addMsgToClientRouting(
        MessageTypes.BROADCAST_TO_MEETING,
        liveMeeting.props.meetingProp.intId, msg.header.userId
      )
      val envelope = BbbCoreEnvelope(PresentationConversionUpdateEvtMsg.NAME, routing)
      val header = BbbClientMsgHeader(
        PresentationConversionUpdateEvtMsg.NAME,
        liveMeeting.props.meetingProp.intId, msg.header.userId
      )

      val body = PresentationConversionUpdateEvtMsgBody(
        msg.body.podId,
        msg.body.messageKey,
        msg.body.code,
        msg.body.presentationId,
        msg.body.presName,
        msg.body.temporaryPresentationId
      )
      val event = PresentationConversionUpdateEvtMsg(header, body)
      val msgEvent = BbbCommonEnvCoreMsg(envelope, event)
      bus.outGW.send(msgEvent)
    }

    val pres = new PresentationInPod(msg.body.presentationId, msg.body.presName, false, Map.empty, false,
      false, uploadCompleted = false, numPages = -1, errorDetails = Map.empty)
    PresPresentationDAO.insertOrUpdate(msg.header.meetingId, pres)

    broadcastEvent(msg)
    state
  }
}
