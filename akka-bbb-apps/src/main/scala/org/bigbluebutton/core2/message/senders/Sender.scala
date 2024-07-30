package org.bigbluebutton.core2.message.senders

import org.bigbluebutton.core.running.OutMsgRouter

object Sender {

  def sendForceUserGraphqlReconnectionSysMsg(meetingId: String, userId: String, sessionToken: String, reason: String, outGW: OutMsgRouter): Unit = {
    val ForceUserGraphqlReconnectionSysMsg = MsgBuilder.buildForceUserGraphqlReconnectionSysMsg(meetingId, userId, sessionToken, reason)
    outGW.send(ForceUserGraphqlReconnectionSysMsg)
  }

  def sendUserInactivityInspectMsg(meetingId: String, userId: String, responseDelay: Long, outGW: OutMsgRouter): Unit = {
    val userInactivityInspectMsg = MsgBuilder.buildUserInactivityInspectMsg(meetingId, userId, responseDelay)
    outGW.send(userInactivityInspectMsg)
  }

}
