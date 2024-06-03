import { User } from '/imports/ui/Types/user';
import {
  LockSettings,
  UsersPolicies,
} from '/imports/ui/Types/meeting';
import Auth from '/imports/ui/services/auth';
import { EMOJI_STATUSES } from '/imports/utils/statuses';
import logger from '/imports/startup/client/logger';
import { toggleMuteMicrophone } from '/imports/ui/components/audio/audio-graphql/audio-controls/input-stream-live-selector/service';

export const isVoiceOnlyUser = (userId: string) => userId.toString().startsWith('v_');

export const isMe = (userId: string) => userId === Auth.userID;

export const generateActionsPermissions = (
  subjectUser: User,
  currentUser: User,
  lockSettings: LockSettings,
  usersPolicies: UsersPolicies,
  isBreakout: boolean,
) => {
  const subjectUserVoice = subjectUser.voice;

  const USER_STATUS_ENABLED = window.meetingClientSettings.public.userStatus.enabled;

  const amIModerator = currentUser.isModerator;
  const isDialInUser = isVoiceOnlyUser(subjectUser.userId);
  const amISubjectUser = isMe(subjectUser.userId);
  const isSubjectUserModerator = subjectUser.isModerator;
  const isSubjectUserGuest = subjectUser.guest;
  const hasAuthority = currentUser.isModerator || amISubjectUser;
  const allowedToChatPrivately = !amISubjectUser && !isDialInUser;
  const allowedToMuteAudio = hasAuthority
    && subjectUserVoice?.joined
    && !subjectUserVoice?.muted
    && !subjectUserVoice?.listenOnly;

  const allowedToUnmuteAudio = hasAuthority
    && subjectUserVoice?.joined
    && !subjectUserVoice.listenOnly
    && subjectUserVoice.muted
    && (amISubjectUser || usersPolicies?.allowModsToUnmuteUsers);

  const allowedToResetStatus = hasAuthority
    && subjectUser.emoji !== EMOJI_STATUSES.none
    && !isDialInUser;

  // if currentUser is a moderator, allow removing other users
  const allowedToRemove = amIModerator
    && !amISubjectUser
    && !isBreakout;

  const allowedToPromote = amIModerator
    && !amISubjectUser
    && !isSubjectUserModerator
    && !isDialInUser
    && !isBreakout
    && !(isSubjectUserGuest && usersPolicies?.authenticatedGuest);

  const allowedToDemote = amIModerator
    && !amISubjectUser
    && isSubjectUserModerator
    && !isDialInUser
    && !isBreakout
    && !(isSubjectUserGuest && usersPolicies?.authenticatedGuest);

  const allowedToChangeStatus = amISubjectUser && USER_STATUS_ENABLED;

  const allowedToChangeUserLockStatus = amIModerator
    && !isSubjectUserModerator
    && lockSettings?.hasActiveLockSetting;

  const allowedToChangeWhiteboardAccess = currentUser.presenter
    && !amISubjectUser;

  const allowedToEjectCameras = amIModerator
    && !amISubjectUser
    && usersPolicies?.allowModsToEjectCameras;

  const allowedToSetPresenter = amIModerator
    && !subjectUser.presenter
    && !isDialInUser;
  // eslint-disable-next-line @typescript-eslint/ban-ts-comment
  // @ts-ignore - temporary, while meteor exists in the project
  const allowedToSetAway = amISubjectUser && !USER_STATUS_ENABLED;

  return {
    allowedToChatPrivately,
    allowedToMuteAudio,
    allowedToUnmuteAudio,
    allowedToResetStatus,
    allowedToRemove,
    allowedToSetPresenter,
    allowedToPromote,
    allowedToDemote,
    allowedToChangeStatus,
    allowedToChangeUserLockStatus,
    allowedToChangeWhiteboardAccess,
    allowedToEjectCameras,
    allowedToSetAway,
  };
};

export const isVideoPinEnabledForCurrentUser = (
  currentUser: User,
  isBreakout: boolean,
) => {
  const { isModerator } = currentUser;

  const PIN_WEBCAM = window.meetingClientSettings.public.kurento.enableVideoPin;
  const isPinEnabled = PIN_WEBCAM;

  return !!(isModerator
    && isPinEnabled
    && !isBreakout);
};

// actions
// disclaimer: For the first version of the userlist using graphql
// we decide keep using the same actions as the old userlist
// so this code is duplicated from the old userlist service
// session for chats the current user started

export const toggleVoice = (userId: string, muted: boolean, voiceToggle: (userId: string, muted: boolean) => void) => {
  if (userId === Auth.userID) {
    toggleMuteMicrophone(!muted, voiceToggle);
  } else {
    voiceToggle(userId, muted);
    logger.info({
      logCode: 'usermenu_option_mute_toggle_audio',
      extraInfo: { logType: 'moderator_action', userId },
    }, 'moderator muted user microphone');
  }
};
