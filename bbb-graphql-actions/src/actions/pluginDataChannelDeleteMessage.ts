import { RedisMessage } from '../types';
import { ValidationError } from '../types/ValidationError';

export default function buildRedisMessage(sessionVariables: Record<string, unknown>, input: Record<string, unknown>): RedisMessage {
  const eventName = `PluginDataChannelDeleteMessageMsg`;

  const routing = {
    meetingId: sessionVariables['x-hasura-meetingid'] as String,
    userId: sessionVariables['x-hasura-userid'] as String
  };

  const header = {
    name: eventName,
    meetingId: routing.meetingId,
    userId: routing.userId
  };

  const body = {
    pluginName: input.pluginName,
    dataChannel: input.dataChannel,
    subChannelName: input.subChannelName,
    messageId: input.messageId
  };

  return { eventName, routing, header, body };
}
