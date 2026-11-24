import { gql } from '@apollo/client';

export const MEETING_STATE_SAVE = gql`
  mutation MeetingStateSave($jobId: String!, $clientState: json!, $download: Boolean) {
    meetingStateSave(jobId: $jobId, clientState: $clientState, download: $download)
  }
`;

export default { MEETING_STATE_SAVE };
