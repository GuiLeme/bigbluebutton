import Auth from '/imports/ui/services/auth';

const BASE_PATH = '/bigbluebutton/meetingState';
const STATUS_POLL_INTERVAL_MS = 1500;
const SAVE_TIMEOUT_MS = 120000;
const ID_ALPHABET = 'abcdefghijklmnopqrstuvwxyz0123456789';

/**
 * What the client alone knows about how the room looks: these values are not
 * kept on the server, so they are sent along with the save request.
 */
export interface ClientRoomState {
  selectedLayout: string;
  sidebarNavigationIsOpen: boolean;
  sidebarContentIsOpen: boolean;
  sidebarContentPanel: string;
  presentationIsOpen: boolean;
  cameraDockPosition: string;
  notesPinned: boolean;
}

// The server only accepts ids made of letters and digits.
export const generateJobId = (): string => {
  const values = new Uint8Array(32);
  window.crypto.getRandomValues(values);
  return Array.from(values, (value) => ID_ALPHABET[value % ID_ALPHABET.length]).join('');
};

const buildUrl = (path: string): string => {
  const sessionToken = encodeURIComponent(String(Auth.sessionToken ?? ''));
  return `${BASE_PATH}/${path}?sessionToken=${sessionToken}`;
};

const wait = (ms: number) => new Promise((resolve) => { setTimeout(resolve, ms); });

/**
 * Resolves once the server has stored the state of a save job, and rejects
 * when it reports a failure or does not finish in time.
 */
export const waitForSavedState = async (jobId: string): Promise<void> => {
  const deadline = Date.now() + SAVE_TIMEOUT_MS;

  while (Date.now() < deadline) {
    // eslint-disable-next-line no-await-in-loop
    await wait(STATUS_POLL_INTERVAL_MS);
    // eslint-disable-next-line no-await-in-loop
    const response = await fetch(buildUrl(`save/${jobId}/status`), { credentials: 'include' });
    if (!response.ok) throw new Error(`status request failed with ${response.status}`);
    // eslint-disable-next-line no-await-in-loop
    const { status } = await response.json();
    if (status === 'ready') return;
    if (status === 'error') throw new Error('the server could not save the room state');
  }

  throw new Error('timed out waiting for the room state to be saved');
};

/**
 * Where the archive of a finished save job made for a download is fetched
 * from. The server sends it once.
 */
export const savedStateDownloadUrl = (jobId: string): string => buildUrl(`save/${jobId}/download`);

// Hands the archive to the browser as a file to save.
export const downloadSavedState = (jobId: string): void => {
  const link = document.createElement('a');
  link.href = savedStateDownloadUrl(jobId);
  link.download = '';
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  link.remove();
};

/**
 * A state the server keeps and can load: the one saved in this session
 * (id 'current'), or one saved in an earlier session of the room.
 */
export interface SavedRoomState {
  id: string;
  name: string;
  savedAt: number;
}

export const CURRENT_STATE_ID = 'current';

export const fetchSavedStates = async (): Promise<SavedRoomState[]> => {
  const response = await fetch(buildUrl('states'), { credentials: 'include' });
  if (!response.ok) throw new Error(`saved states request failed with ${response.status}`);
  const { states } = await response.json();
  return Array.isArray(states) ? states : [];
};

/** Thrown when the server refuses an archive; `messageKey` says why. */
export class RoomStateLoadError extends Error {
  messageKey: string;

  constructor(messageKey: string) {
    super(`room state not loaded: ${messageKey}`);
    this.messageKey = messageKey;
  }
}

const readLoadResponse = async (response: Response): Promise<Partial<ClientRoomState>> => {
  let body: { status?: string; messageKey?: string; client?: Partial<ClientRoomState> } = {};
  try {
    body = await response.json();
  } catch (error) {
    // An error page of a proxy: handled below through the status code.
  }

  if (!response.ok || body.status !== 'loaded') {
    if (response.status === 413) throw new RoomStateLoadError('tooLarge');
    throw new RoomStateLoadError(body.messageKey ?? 'failed');
  }

  return body.client ?? {};
};

/**
 * Sends an archive to the server, which replaces the content of the room with
 * it. Resolves with the part of the state that only this client can apply.
 */
export const loadStateFromFile = async (file: File): Promise<Partial<ClientRoomState>> => {
  const body = new FormData();
  body.append('file', file);
  const response = await fetch(buildUrl(`load/${generateJobId()}/upload`), {
    method: 'POST',
    credentials: 'include',
    body,
  });
  return readLoadResponse(response);
};

/** Same as loadStateFromFile, for an archive the server downloads itself. */
export const loadStateFromUrl = async (url: string): Promise<Partial<ClientRoomState>> => {
  const response = await fetch(buildUrl(`load/${generateJobId()}/url`), {
    method: 'POST',
    credentials: 'include',
    body: new URLSearchParams({ url }),
  });
  return readLoadResponse(response);
};

/** Same as loadStateFromFile, for a state the server keeps. */
export const loadSavedState = async (id: string): Promise<Partial<ClientRoomState>> => {
  const response = await fetch(buildUrl(`load/${generateJobId()}/saved`), {
    method: 'POST',
    credentials: 'include',
    body: new URLSearchParams({ state: id }),
  });
  return readLoadResponse(response);
};

export default {
  generateJobId,
  waitForSavedState,
  savedStateDownloadUrl,
  downloadSavedState,
  fetchSavedStates,
  loadStateFromFile,
  loadStateFromUrl,
  loadSavedState,
};
