import { Meteor } from 'meteor/meteor';
import Timer from '/imports/api/timer';
import Auth from '/imports/ui/services/auth';
import { makeCall } from '/imports/ui/services/api';
import { Session } from 'meteor/session';
import Users from '/imports/api/users';
import Logger from '/imports/startup/client/logger';
import { ACTIONS, PANELS } from '../layout/enums';

const TIMER_CONFIG = Meteor.settings.public.timer;
const ROLE_MODERATOR = Meteor.settings.public.user.role_moderator;

const MILLI_IN_HOUR = 3600000;
const MILLI_IN_MINUTE = 60000;
const MILLI_IN_SECOND = 1000;

const MAX_HOURS = 23;

const MAX_TIME = 999
  + (59 * MILLI_IN_SECOND)
  + (59 * MILLI_IN_MINUTE)
  + (MAX_HOURS * MILLI_IN_HOUR);

const getMaxHours = () => MAX_HOURS;

isActive = () => {
  const timer = Timer.findOne(
    { meetingId: Auth.meetingID },
    { fields: { active: 1 } },
  );

  if (timer) return timer.active;
  return false;
};

const isEnabled = () => TIMER_CONFIG.enabled;

const getDefaultTime = () => TIMER_CONFIG.time * MILLI_IN_MINUTE;

const getInterval = () => TIMER_CONFIG.interval;

const getPreset = () => {
  const { preset } = TIMER_CONFIG;

  return preset.map(minutes => minutes * MILLI_IN_MINUTE);
};

const isRunning = () => {
  const timer = Timer.findOne(
    { meetingId: Auth.meetingID },
    { fields: { running: 1 } },
  );

  if (timer) return timer.running;
  return false;
};

const isStopwatch = () => {
  const timer = Timer.findOne(
    { meetingId: Auth.meetingID },
    { fields: { stopwatch: 1 } },
  );

  if (timer) return timer.stopwatch;
  return false;
};

const startTimer = () => makeCall('startTimer');

const stopTimer = () => makeCall('stopTimer');

const switchTimer = (stopwatch) => makeCall('switchTimer', stopwatch);

const setTimer = (time) => makeCall('setTimer', time);

const resetTimer = () => makeCall('resetTimer');

const activateTimer = () => makeCall('activateTimer');

const deactivateTimer = () => makeCall('deactivateTimer');


const getTimer = () => {
  const timer = Timer.findOne(
    { meetingId: Auth.meetingID },
    { fields:
      {
        stopwatch: 1,
        running: 1,
        time: 1,
        accumulated: 1,
        timestamp: 1,
      },
    },
  );

  if (timer) {
    const {
      stopwatch,
      running,
      time,
      accumulated,
      timestamp,
    } = timer;

    return {
      stopwatch,
      running,
      time,
      accumulated,
      timestamp,
    }
  }

  return {
    stopwatch: true,
    running: false,
    time: getDefaultTime(),
    accumulated: 0,
    timestamp: 0,
  }
};

const getTimerStatus = () => {
  const timerStatus = Timer.findOne(
    { meetingId: Auth.meetingID },
    { fields:
      {
        stopwatch: 1,
        running: 1,
        time: 1,
      },
    },
  );

  if (timerStatus) {
    const {
      stopwatch,
      running,
      time,
    } = timerStatus;

    return {
      stopwatch,
      running,
      time,
    }
  }

  return {
    stopwatch: true,
    running: false,
    time: getDefaultTime(),
  }
};

const getTimeAsString = (time, stopwatch) => {
  let milliseconds = time;

  let hours = Math.floor(milliseconds / MILLI_IN_HOUR);
  const mHours = hours * MILLI_IN_HOUR;

  let minutes = Math.floor((milliseconds - mHours) / MILLI_IN_MINUTE);
  const mMinutes = minutes * MILLI_IN_MINUTE;

  let seconds = Math.floor((milliseconds - mHours - mMinutes) / MILLI_IN_SECOND);
  const mSeconds = seconds * MILLI_IN_SECOND;

  milliseconds = milliseconds - mHours - mMinutes - mSeconds;

  let timeAsString = '';

  // Only add hour if it exists
  if (hours > 0) {
    if (hours < 10) {
      timeAsString += `0${hours}:`;
    } else {
      timeAsString += `${hours}:`;
    }
  }

  // Add minute if exists, has at least an hour
  // or is not stopwatch
  if (minutes > 0 || hours > 0 || !stopwatch) {
    if (minutes < 10) {
      timeAsString += `0${minutes}:`;
    } else {
      timeAsString += `${minutes}:`;
    }
  }

  // Always add seconds
  if (seconds < 10) {
    timeAsString += `0${seconds}`;
  } else {
    timeAsString += `${seconds}`;
  }

  // Only add milliseconds if it's a stopwatch
  if (stopwatch) {
    if (milliseconds < 10) {
      timeAsString += `:00${milliseconds}`;
    } else if (milliseconds < 100) {
      timeAsString += `:0${milliseconds}`;
    } else {
      timeAsString += `:${milliseconds}`;
    }
  }

  return timeAsString;
};

const isPanelOpen = () => Session.get('openPanel') === 'timer';

const closePanel = (layoutContextDispatch) => {
  layoutContextDispatch({
    type: ACTIONS.SET_SIDEBAR_CONTENT_IS_OPEN,
    value: false,
  });
  layoutContextDispatch({
    type: ACTIONS.SET_SIDEBAR_CONTENT_PANEL,
    value: PANELS.NONE,
  });
};

const togglePanel = (sidebarContentPanel, layoutContextDispatch) => {
  layoutContextDispatch({
    type: ACTIONS.SET_SIDEBAR_CONTENT_IS_OPEN,
    value: sidebarContentPanel !== PANELS.TIMER,
  });
  layoutContextDispatch({
    type: ACTIONS.SET_SIDEBAR_CONTENT_PANEL,
    value: sidebarContentPanel === PANELS.TIMER
      ? PANELS.NONE
      : PANELS.TIMER,
  });
};

const isModerator = () => {
  return Users.findOne(
    { userId: Auth.userID },
    { fields: { role: 1 } },
  ).role === ROLE_MODERATOR;
};

const setHours = (hours, time) => {
  if (!isNaN(hours) && hours >= 0 && hours <= MAX_HOURS) {
    const currentHours = Math.floor(time / MILLI_IN_HOUR);

    const diff = (hours - currentHours) * MILLI_IN_HOUR;
    setTimer(time + diff);
  } else {
    Logger.warn('Invalid time');
  }
};

const setMinutes = (minutes, time) => {
  if (!isNaN(minutes) && minutes >= 0 && minutes <= 59) {
    const currentHours = Math.floor(time / MILLI_IN_HOUR);
    const mHours = currentHours * MILLI_IN_HOUR;

    const currentMinutes = Math.floor((time - mHours) / MILLI_IN_MINUTE);

    const diff = (minutes - currentMinutes) * MILLI_IN_MINUTE;
    setTimer(time + diff);
  } else {
    Logger.warn('Invalid time');
  }
};

const setSeconds = (seconds, time) => {
  if (!isNaN(seconds) && seconds >= 0 && seconds <= 59) {
    const currentHours = Math.floor(time / MILLI_IN_HOUR);
    const mHours = currentHours * MILLI_IN_HOUR;

    const currentMinutes = Math.floor((time - mHours) / MILLI_IN_MINUTE);
    const mMinutes = currentMinutes * MILLI_IN_MINUTE;

    const currentSeconds = Math.floor((time - mHours - mMinutes) / MILLI_IN_SECOND);

    const diff = (seconds - currentSeconds) * MILLI_IN_SECOND;
    setTimer(time + diff);
  } else {
    Logger.warn('Invalid time');
  }
};

const subtractTime = (preset, time) => {
  if (!isNaN(preset)) {
    const min = 0;
    setTimer(Math.max(time - preset, min));
  } else {
    Logger.warn('Invalid time');
  }
};

const setTime = (preset) => {
  if (!isNaN(preset)) {
    setTimer(preset);
  } else {
    Logger.warn('Invalid time');
  }
};

const addTime = (preset, time) => {
  if (!isNaN(preset)) {
    const max = MAX_TIME;
    setTimer(Math.min(time + preset, max));
  } else {
    Logger.warn('Invalid time');
  }
};

const buildPresetLabel = (preset) => {
  const minutes = preset / MILLI_IN_MINUTE;

  if (minutes < 10) {
    return `0${minutes}"00'`;
  }

  return `${minutes}"00'`;
};

export default {
  isActive,
  isEnabled,
  isRunning,
  isStopwatch,
  startTimer,
  stopTimer,
  switchTimer,
  setHours,
  setMinutes,
  setSeconds,
  setTime,
  subtractTime,
  addTime,
  resetTimer,
  activateTimer,
  deactivateTimer,
  getInterval,
  getPreset,
  getMaxHours,
  getTimer,
  getTimerStatus,
  getTimeAsString,
  closePanel,
  togglePanel,
  isModerator,
  buildPresetLabel,
};
