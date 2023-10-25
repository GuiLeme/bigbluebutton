package org.bigbluebutton.core.db

import PostgresProfile.api._
import spray.json.JsValue
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object Permission {
  val allowedRoles = List("MODERATOR","VIEWER","PRESENTER")
}

case class PluginDataChannelMessageDbModel(
    meetingId:          String,
    pluginName:         String,
    dataChannel:        String,
//    messageId:        Option[String] = None,
    payloadJson:        JsValue,
    fromUserId:         String,
    toRoles:            Option[List[String]],
    toUserIds:          Option[List[String]],
    createdAt:          java.sql.Timestamp,
)

class PluginDataChannelMessageDbTableDef(tag: Tag) extends Table[PluginDataChannelMessageDbModel](tag, None, "pluginDataChannelMessage") {
  val meetingId = column[String]("meetingId", O.PrimaryKey)
  val pluginName = column[String]("pluginName", O.PrimaryKey)
  val dataChannel = column[String]("dataChannel", O.PrimaryKey)
//  val messageId = column[Option[String]]("messageId", O.PrimaryKey) //// The messageId is generated by the database
  val payloadJson = column[JsValue]("payloadJson")
  val fromUserId = column[String]("fromUserId")
  val toRoles = column[Option[List[String]]]("toRoles")
  val toUserIds = column[Option[List[String]]]("toUserIds")
  val createdAt = column[java.sql.Timestamp]("createdAt")
  override def * = (meetingId, pluginName, dataChannel, payloadJson, fromUserId, toRoles, toUserIds, createdAt) <> (PluginDataChannelMessageDbModel.tupled, PluginDataChannelMessageDbModel.unapply)
}

object PluginDataChannelMessageDAO {
  def insert(meetingId: String, pluginName: String, dataChannel: String, senderUserId: String, payloadJson: String, toRoles: List[String], toUserIds: List[String]) = {
    DatabaseConnection.db.run(
      TableQuery[PluginDataChannelMessageDbTableDef].forceInsert(
        PluginDataChannelMessageDbModel(
          meetingId = meetingId,
          pluginName = pluginName,
          dataChannel = dataChannel,
          payloadJson = JsonUtils.stringToJson(payloadJson),
          fromUserId = senderUserId,
          toRoles = toRoles.filter(Permission.allowedRoles.contains) match {
            case Nil => None
            case filtered => Some(filtered)
          },
          toUserIds = if(toUserIds.isEmpty) None else Some(toUserIds),
          createdAt = new java.sql.Timestamp(System.currentTimeMillis())
        )
      )
    ).onComplete {
        case Success(rowsAffected) => DatabaseConnection.logger.debug(s"$rowsAffected row(s) inserted on PluginDataChannelMessage table!")
        case Failure(e)            => DatabaseConnection.logger.debug(s"Error inserting PluginDataChannelMessage: $e")
      }
  }
}