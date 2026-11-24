import { test } from 'node:test';
import assert from 'node:assert';
import buildRedisMessage from './meetingStateSave';
import { ValidationError } from '../types/ValidationError';

const rejects = (fn: () => void, status: number) => {
  assert.throws(fn, (err: unknown) => {
    assert.ok(err instanceof ValidationError, `expected ValidationError, got ${err}`);
    assert.strictEqual(err.status, status);
    return true;
  });
};

const MODERATOR = {
  'x-hasura-meetingid': 'meeting-1',
  'x-hasura-userid': 'w_abcdefghijkl',
  'x-hasura-moderatorinmeeting': 'meeting-1',
};

const VIEWER = { ...MODERATOR, 'x-hasura-moderatorinmeeting': '' };

const JOB_ID = 'abcdefghij0123456789';

const input = (overrides: Record<string, unknown> = {}) => ({
  jobId: JOB_ID,
  clientState: { sidebarContentPanel: 'chat' },
  ...overrides,
});

test('meetingStateSave builds the expected redis message', () => {
  const msg = buildRedisMessage(MODERATOR, input());

  assert.strictEqual(msg.eventName, 'SaveMeetingStateReqMsg');
  assert.deepStrictEqual(msg.routing, { meetingId: 'meeting-1', userId: 'w_abcdefghijkl' });
  assert.deepStrictEqual(msg.body, { jobId: JOB_ID, clientState: { sidebarContentPanel: 'chat' }, download: false });
});

test('meetingStateSave is refused for a viewer', () => {
  rejects(() => buildRedisMessage(VIEWER, input()), 403);
});

test('meetingStateSave refuses a job id that is not plain letters and digits', () => {
  for (const jobId of ['short', '../../etc/passwd0000', 'abcdefghij012345678*', 'a'.repeat(65)]) {
    rejects(() => buildRedisMessage(MODERATOR, input({ jobId })), 400);
  }
});

test('meetingStateSave refuses an array or oversized client state', () => {
  rejects(() => buildRedisMessage(MODERATOR, input({ clientState: [] })), 400);
  rejects(() => buildRedisMessage(MODERATOR, input({ clientState: { filler: 'x'.repeat(5000) } })), 400);
});

test('meetingStateSave passes on a request for an archive to download', () => {
  const msg = buildRedisMessage(MODERATOR, input({ download: true }));

  assert.strictEqual(msg.body.download, true);
});

test('meetingStateSave refuses a download flag that is not a boolean', () => {
  rejects(() => buildRedisMessage(MODERATOR, input({ download: 'yes' })), 400);
});
