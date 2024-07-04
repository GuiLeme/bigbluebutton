import { RedisMessage } from '../types';
import {throwErrorIfInvalidInput} from "../imports/validation";

export default function buildRedisMessage(sessionVariables: Record<string, unknown>, input: Record<string, unknown>): RedisMessage {
  throwErrorIfInvalidInput(input,
    [
      {name: 'pluginName', type: 'string', required: true},
      {name: 'dataAnalyticsObject', type: 'json', required: true},
    ]
  )

  const eventName = `PluginDataAnalyticsSendObjectMsg`;

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
    dataAnalyticsObject: input.dataAnalyticsObject,
  };

  return { eventName, routing, header, body };
}
