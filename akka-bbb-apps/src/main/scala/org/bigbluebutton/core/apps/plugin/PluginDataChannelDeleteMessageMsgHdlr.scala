package org.bigbluebutton.core.apps.plugin

import org.bigbluebutton.ClientSettings
import org.bigbluebutton.common2.msgs.PluginDataChannelDeleteMessageMsg
import org.bigbluebutton.core.db.PluginDataChannelMessageDAO
import org.bigbluebutton.core.domain.MeetingState2x
import org.bigbluebutton.core.models.{ Roles, Users2x }
import org.bigbluebutton.core.running.{ HandlerHelpers, LiveMeeting }

trait PluginDataChannelDeleteMessageMsgHdlr extends HandlerHelpers {

  def handle(msg: PluginDataChannelDeleteMessageMsg, state: MeetingState2x, liveMeeting: LiveMeeting): Unit = {
    val pluginsDisabled: Boolean = liveMeeting.props.meetingProp.disabledFeatures.contains("plugins")
    val meetingId = liveMeeting.props.meetingProp.intId

    for {
      _ <- if (!pluginsDisabled) Some(()) else None
      user <- Users2x.findWithIntId(liveMeeting.users2x, msg.header.userId)
    } yield {
      val pluginsConfig = ClientSettings.getPluginsFromConfig(ClientSettings.clientSettingsFromFile)

      if (!pluginsConfig.contains(msg.body.pluginName)) {
        println(s"Plugin '${msg.body.pluginName}' not found.")
      } else if (!pluginsConfig(msg.body.pluginName).dataChannels.contains(msg.body.dataChannel)) {
        println(s"Data channel '${msg.body.dataChannel}' not found in plugin '${msg.body.pluginName}'.")
      } else {
        val hasPermission = for {
          deletePermission <- pluginsConfig(msg.body.pluginName).dataChannels(msg.body.dataChannel).deletePermission
        } yield {
          deletePermission.toLowerCase match {
            case "all"       => true
            case "moderator" => user.role == Roles.MODERATOR_ROLE
            case "presenter" => user.presenter
            case "sender" => {
              val senderUserId = PluginDataChannelMessageDAO.getMessageSender(
                meetingId,
                msg.body.pluginName,
                msg.body.dataChannel,
                msg.body.subChannelName,
                msg.body.messageId
              )
              senderUserId == msg.header.userId
            }
            case _ => false
          }
        }

        if (!hasPermission.contains(true)) {
          println(s"No permission to delete in plugin: '${msg.body.pluginName}', data channel: '${msg.body.dataChannel}'.")
        } else {
          PluginDataChannelMessageDAO.delete(
            meetingId,
            msg.body.pluginName,
            msg.body.dataChannel,
            msg.body.subChannelName,
            msg.body.messageId
          )
        }
      }
    }
  }
}
