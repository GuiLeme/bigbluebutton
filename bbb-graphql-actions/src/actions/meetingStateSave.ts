import { RedisMessage } from '../types';
import { ValidationError } from '../types/ValidationError';
import { throwErrorIfInvalidInput, throwErrorIfNotModerator } from '../imports/validation';

// The job id ends up in redis keys and file paths on the server.
const JOB_ID_PATTERN = /^[A-Za-z0-9]{16,64}$/;
// The client state only describes which panels are open; anything bigger is not ours.
const MAX_CLIENT_STATE_LENGTH = 4096;

export default function buildRedisMessage(sessionVariables: Record<string, unknown>, input: Record<string, unknown>): RedisMessage {
  throwErrorIfNotModerator(sessionVariables);
  throwErrorIfInvalidInput(input,
    [
      { name: 'jobId', type: 'string', required: true },
      { name: 'clientState', type: 'json', required: true },
      // The archive is for the moderator to download: it does not replace the state kept in the meeting.
      { name: 'download', type: 'boolean', required: false },
    ]
  );

  if (!JOB_ID_PATTERN.test(input.jobId as string)) {
    throw new ValidationError("Parameter 'jobId' must be 16 to 64 letters or digits", 400);
  }

  if (Array.isArray(input.clientState)) {
    throw new ValidationError("Parameter 'clientState' should be an object", 400);
  }

  if (JSON.stringify(input.clientState).length > MAX_CLIENT_STATE_LENGTH) {
    throw new ValidationError(`Parameter 'clientState' exceeds the maximum length of ${MAX_CLIENT_STATE_LENGTH}`, 400);
  }

  const eventName = `SaveMeetingStateReqMsg`;

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
    jobId: input.jobId,
    clientState: input.clientState,
    download: input.download === true,
  };

  return { eventName, routing, header, body };
}
